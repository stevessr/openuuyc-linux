"""Self-host optional route contract tests."""
import json
import tempfile
import unittest
from pathlib import Path
from aiohttp import WSMsgType
from aiohttp.test_utils import TestClient, TestServer
from selfhost.server import ControlPlane


class CompatibilityTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.control = ControlPlane(str(Path(self.tmp.name) / "db.sqlite"),
                                    "https://uu.test", "a" * 32,
                                    ["stun:stun.test:3478"])
        self.client = TestClient(TestServer(self.control.application()))
        await self.client.start_server()
        self.sockets = []

    async def asyncTearDown(self):
        for sock in self.sockets:
            await sock.close()
        await self.client.close()
        self.tmp.cleanup()

    def headers(self, client, device="", token=""):
        h = {"X-Param-client-id": client, "X-Param-device-id": device}
        if token:
            h["Authorization"] = "Bearer " + token
        return h

    async def request(self, method, path, identity, device="", token="", data=None):
        async with self.client.request(method, path, json=data,
                headers=self.headers(identity, device, token)) as response:
            try:
                return response.status, await response.json()
            except Exception:
                return response.status, {}

    async def reg(self, alias):
        status, reply = await self.request("POST", "/api/v1/device/windows/init",
                                    alias, data={"client_id": alias, "name": alias})
        self.assertEqual(status, 200)
        return reply["data"]["device_id"]

    async def login(self, alias, device):
        _, qr = await self.request("POST", "/api/v1/qrcode/gen/login", alias, device)
        ticket = qr["data"]["token"]
        async with self.client.post("/enroll/" + ticket,
                          data={"admin_key": "a" * 32}) as r:
            self.assertEqual(r.status, 200)
        _, result = await self.request("POST", "/api/v1/login/by_qrcode", alias, device,
                        data={"token": ticket, "qrcode_jump_url": qr["data"]["qrcode_jump_url"]})
        self.assertEqual(result["code"], 0)
        return result["data"]["token"]

    async def room(self, alias, device, token):
        _, r = await self.request("POST", "/api/v1/room/create", alias, device, token, {})
        ws = await self.client.ws_connect(
            "/socket.io/?EIO=4&transport=websocket",
            headers={"X-NRD-AUTH": r["data"]["token"]})
        self.sockets.append(ws)
        await ws.receive()
        await ws.send_str("40")
        await ws.receive()
        await ws.send_str('421["room_info"]')
        await ws.receive()
        return ws

    async def users(self):
        hd, cd = await self.reg("host"), await self.reg("controller")
        ht, ct = await self.login("host", hd), await self.login("controller", cd)
        return hd, cd, ht, ct

    async def test_device_rename_unbind_and_revoke(self):
        hd, cd, ht, ct = await self.users()
        status, rename = await self.request("PUT", "/api/v1/device/" + hd,
                                        "controller", cd, ct, {"alias": "Renamed"})
        self.assertEqual(status, 200)
        self.assertEqual(rename["data"]["alias"], "Renamed")
        _, groups = await self.request("GET", "/api/v1/device/groups/of/my",
                                    "controller", cd, ct)
        self.assertIn("Renamed", [x["alias"] for x in groups["data"]["desktop_devices"]])
        host = await self.room("host", hd, ht)
        _, unbound = await self.request("POST", "/api/v1/device/" + hd + "/unbind",
                                    "controller", cd, ct)
        self.assertEqual(unbound["code"], 0)
        self.assertIn((await host.receive()).type,
                      (WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED))
        status, _ = await self.request("GET", "/api/v1/device/list", "host", hd, ht)
        self.assertEqual(status, 401)

    async def test_wol_state_persists_without_faking_magic_packet(self):
        hd, cd, ht, ct = await self.users()
        _, saved = await self.request("POST", "/api/v1/device/set_support_wol",
                                   "host", hd, ht, {"support_wol": True})
        self.assertEqual(saved["code"], 0)
        _, data = await self.request("POST", "/api/v1/device/wake_on_lan/info",
                                   "host", hd, ht, {"ip": "192.0.2.1"})
        self.assertTrue(data["data"]["support_wol"])
        _, listed = await self.request("GET", "/api/v1/device/list",
                                      "controller", cd, ct)
        self.assertTrue(listed["data"]["my_binded_devices"][0]["support_wol"])
        _, result = await self.request("POST", "/api/v1/device/" + hd + "/wake_on_lan/req",
                                       "controller", cd, ct)
        self.assertEqual(result["code"], 501)

    async def test_power_requests_are_delivered_only_to_live_enabled_host(self):
        hd, cd, ht, ct = await self.users()
        _, denied = await self.request("POST", "/api/v1/device/" + hd + "/reboot",
                                       "controller", cd, ct)
        self.assertNotEqual(denied["code"], 0)
        host = await self.room("host", hd, ht)
        await self.request("POST", "/api/v1/device/controllable", "host", hd, ht,
                           {"controllable": True})
        _, reboot = await self.request("POST", "/api/v1/device/" + hd + "/reboot",
                                       "controller", cd, ct)
        self.assertEqual(reboot["code"], 0)
        frame = (await host.receive()).data
        self.assertTrue(frame.startswith("42"))
        self.assertEqual(json.loads(frame[2:])[0], "bmsg_push")
        self.assertEqual(json.loads(frame[2:])[1]["type"], "device_reboot")

    async def test_assistance_code_and_saved_devices(self):
        hd, cd, ht, ct = await self.users()
        host = await self.room("host", hd, ht)
        await self.request("POST", "/api/v1/device/controllable", "host", hd, ht,
                           {"controllable": True})
        _, identity = await self.request("POST", "/api/v1/device/share/info/refresh",
                                        "host", hd, ht)
        assist_id = identity["data"]["connect_id"]
        self.assertRegex(assist_id, r"^\d{9}$")
        _, upload = await self.request("POST", "/api/v2/room/share/upload_sign",
                                       "host", hd, ht,
                                       {"control_id": assist_id, "sign": "123456",
                                        "backup_sign": "", "control_mode": "password",
                                        "can_remote_control": True, "need_confirmation": False})
        self.assertEqual(upload["code"], 0)
        _, confirm = await self.request("POST", "/api/v2/room/share/confirmation",
                                         "host", hd, ht, {
                                          "control_id": assist_id, "allow_control": True,
                                          "need_password": True})
        self.assertEqual(confirm["code"], 0)
        _, mode = await self.request("POST", "/api/v2/room/share/control_mode",
                                      "controller", cd, ct, {"connect_id": assist_id})
        self.assertEqual(mode["data"]["control_mode"], "password")
        _, bad = await self.request("POST", "/api/v2/room/join/share/by_code",
                                     "controller", cd, ct,
                                     {"connect_id": assist_id, "connect_code": "bad"})
        self.assertEqual(bad["code"], 403)
        _, ok = await self.request("POST", "/api/v2/room/join/share/by_code",
                                     "controller", cd, ct,
                                     {"connect_id": assist_id, "connect_code": "123456"})
        self.assertEqual(ok["code"], 0)
        self.assertEqual(ok["data"]["publisher_device_id"], hd)
        _, refetched = await self.request("POST", "/api/v1/room/join/refetch",
                                     "controller", cd, ct, {"token": ok["data"]["token"]})
        self.assertEqual(refetched["data"]["publisher_device_id"], hd)
        _, fav = await self.request("POST", "/api/v1/device/share/saved/favorites",
                                    "controller", cd, ct,
                                    {"favorites": [{"connect_id": assist_id, "remark": "Home", "favorited_at": 1}]})
        self.assertEqual(fav["code"], 0)
        _, saved = await self.request("GET", "/api/v1/device/share/saved/favorites",
                                      "controller", cd, ct)
        self.assertEqual(saved["data"]["saved"][0]["remark"], "Home")

    async def test_android_share_approval_requires_matching_challenge(self):
        hd, cd, ht, ct = await self.users()
        host = await self.room("host", hd, ht)
        # Registered Android targets exist only after a distinct android init.
        status, reply = await self.request("POST", "/api/v1/device/android/init", "phone",
                                data={"name": "Phone", "client_id": "phone"})
        self.assertEqual(status, 200)
        phone = reply["data"]["device_id"]
        pt = await self.login("phone", phone)
        online = await self.room("phone", phone, pt)
        await self.request("POST", "/api/v1/device/controllable", "phone", phone, pt,
                           {"controllable": True})
        _, unsolicited = await self.request("POST", "/api/v1/transport/" + hd + "/rpc/request",
                                    "phone", phone, pt, {"p": "android_control_result",
                                        "data": {"action": "accept_control",
                                                 "device_info": {"device_id": phone}}})
        self.assertEqual(unsolicited["code"], 403)
        _, sent = await self.request("POST", "/api/v1/transport/" + phone + "/rpc/request",
                                "host", hd, ht,
                                {"p": "android_control_request", "data": {
                                    "action": "request_control",
                                    "device_info": {"device_id": hd}}})
        self.assertEqual(sent["code"], 0)
        self.assertIn("android_control_request", (await online.receive()).data)
        _, response = await self.request("POST", "/api/v1/transport/" + hd + "/rpc/request",
                                   "phone", phone, pt,
                                   {"p": "android_control_result", "data": {
                                       "action": "accept_control",
                                       "device_info": {"device_id": phone}}})
        self.assertEqual(response["code"], 0)
        self.assertIn("android_control_result", (await host.receive()).data)


    async def test_guest_host_isolated_from_account_management(self):
        hd, cd, ht, ct = await self.users()
        # Independent, device-scoped guest registration. No UU account login.
        status, reply = await self.request("POST", "/api/v1/guest/create",
                                           "host", hd, data={})
        self.assertEqual(status, 200)
        self.assertEqual(reply["code"], 0)
        guest = reply["data"]
        headers = self.headers("host", hd, guest["token"])
        headers["X-Param-Guest-ID"] = guest["guest_id"]
        async with self.client.get("/api/v1/device/list", headers=headers) as r:
            self.assertEqual(r.status, 401)
        async with self.client.post("/api/v1/guest/room/create",
                                    headers=headers, json={}) as r:
            self.assertEqual(r.status, 200)
            room = (await r.json())["data"]
        socket = await self.client.ws_connect("/socket.io/?EIO=4&transport=websocket",
                                headers={"X-NRD-AUTH": room["token"]})
        self.sockets.append(socket)
        self.assertTrue((await socket.receive()).data.startswith("0"))
        await socket.send_str("40")
        await socket.receive()
        await socket.send_str('421["room_info"]')
        await socket.receive()
        async with self.client.post("/api/v1/device/controllable", headers=headers,
                                    json={"controllable": True}) as r:
            self.assertEqual((await r.json())["code"], 0)
        async with self.client.post("/api/v1/guest/share/info", headers=headers, json={}) as r:
            connect_id = (await r.json())["data"]["connect_id"]
        async with self.client.post("/api/v1/guest/room/share/upload/sign", headers=headers,
                                    json={"control_id": connect_id, "can_remote_control": True,
                                          "sign": "guestpass", "backup_sign": "",
                                          "control_mode": "password",
                                          "need_confirmation": False}) as r:
            self.assertEqual((await r.json())["code"], 0)
        async with self.client.post("/api/v1/guest/room/share/confirmation", headers=headers,
                                    json={"control_id": connect_id,
                                          "allow_control": True, "need_password": True}) as r:
            self.assertEqual((await r.json())["code"], 0)
        _, join = await self.request("POST", "/api/v2/room/join/share/by_code",
                                     "controller", cd, ct,
                                     {"connect_id": connect_id, "connect_code": "guestpass"})
        self.assertEqual(join["code"], 0)
        self.assertEqual(join["data"]["publisher_device_id"], hd)

    async def test_assistance_password_locks_after_repeated_failures(self):
        hd, cd, ht, ct = await self.users()
        await self.room("host", hd, ht)
        await self.request("POST", "/api/v1/device/controllable", "host", hd, ht,
                           {"controllable": True})
        _, aid = await self.request("POST", "/api/v1/device/share/info/refresh",
                                    "host", hd, ht)
        code = aid["data"]["connect_id"]
        await self.request("POST", "/api/v2/room/share/upload_sign", "host", hd, ht,
                           {"control_id": code, "sign": "secret",
                            "backup_sign": "", "control_mode": "password",
                            "can_remote_control": True, "need_confirmation": False})
        for _ in range(6):
            _, denied = await self.request("POST", "/api/v2/room/join/share/by_code",
                                "controller", cd, ct,
                                {"connect_id": code, "connect_code": "wrong"})
            self.assertEqual(denied["code"], 403)
        _, locked = await self.request("POST", "/api/v2/room/join/share/by_code",
                                "controller", cd, ct,
                                {"connect_id": code, "connect_code": "secret"})
        self.assertEqual(locked["code"], 429)

if __name__ == "__main__":
    unittest.main()
