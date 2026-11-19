"""Protocol-level REST + Socket.IO regression tests, without a live UU account."""
import json
import tempfile
import unittest
from pathlib import Path

from aiohttp import WSMsgType
from aiohttp.test_utils import TestClient, TestServer
from selfhost.server import ControlPlane


class SelfHostTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.plane = ControlPlane(str(Path(self.tmp.name) / "test.sqlite"),
                                  "https://uu.example.test", "z" * 32,
                                  ["stun:stun.example.test:3478"])
        self.http = TestClient(TestServer(self.plane.application()))
        await self.http.start_server()
        self.sockets = []

    async def asyncTearDown(self):
        for socket in self.sockets:
            await socket.close()
        await self.http.close()
        self.tmp.cleanup()

    def headers(self, client, device="", token=""):
        h = {"X-Param-client-id": client, "X-Param-device-id": device}
        if token:
            h["Authorization"] = "Bearer " + token
        return h

    async def post(self, path, client, device="", token="", data=None):
        async with self.http.post(path, json=data or {},
                                  headers=self.headers(client, device, token)) as response:
            if response.content_type == "application/json":
                return response.status, await response.json()
            return response.status, {"error": await response.text()}

    async def register(self, client):
        _, result = await self.post("/api/v1/device/windows/init", client,
                                    data={"client_id": client, "name": client})
        self.assertEqual(result["code"], 0)
        device = result["data"]["device_id"]
        self.assertRegex(device, r"^[a-z0-9]{16}$")
        return device

    async def login(self, client, device):
        _, qr = await self.post("/api/v1/qrcode/gen/login", client, device)
        ticket = qr["data"]["token"]
        _, pending = await self.post("/api/v1/qrcode/login/status", client, device,
                                     data={"token": ticket})
        self.assertEqual(pending["data"]["status"], 1)
        async with self.http.post("/enroll/" + ticket,
                                  data={"admin_key": "z" * 32}) as reply:
            self.assertEqual(reply.status, 200)
        _, confirmed = await self.post("/api/v1/qrcode/login/status", client, device,
                                       data={"token": ticket})
        self.assertEqual(confirmed["data"]["status"], 4)
        _, result = await self.post("/api/v1/login/by_qrcode", client, device,
                                    data={"token": ticket,
                                          "qrcode_jump_url": qr["data"]["qrcode_jump_url"]})
        self.assertEqual(result["code"], 0)
        return result["data"]["token"], ticket

    async def socket(self, ticket):
        socket = await self.http.ws_connect("/socket.io/?EIO=4&transport=websocket",
                                            headers={"X-NRD-AUTH": ticket})
        self.sockets.append(socket)
        self.assertTrue((await socket.receive()).data.startswith("0{"))
        await socket.send_str("40")
        self.assertTrue((await socket.receive()).data.startswith("40{"))
        await socket.send_str('421["room_info"]')
        self.assertTrue((await socket.receive()).data.startswith("431["))
        return socket

    async def pair(self):
        hd = await self.register("host")
        cd = await self.register("controller")
        ht, _ = await self.login("host", hd)
        ct, _ = await self.login("controller", cd)
        _, room = await self.post("/api/v1/room/create", "host", hd, ht)
        host = await self.socket(room["data"]["token"])
        _, access = await self.post("/api/v1/device/controllable", "host", hd, ht,
                                    {"controllable": True})
        self.assertEqual(access["code"], 0)
        _, joined = await self.post("/api/v1/room/join/by_device/" + hd,
                                    "controller", cd, ct)
        self.assertEqual(joined["code"], 0)
        controller = await self.socket(joined["data"]["token"])
        return hd, cd, ht, ct, host, controller

    async def test_stable_identity_and_bearer_auth(self):
        device = await self.register("machine")
        self.assertEqual(device, await self.register("machine"))
        async with self.http.get("/api/v1/device/list",
                                 headers=self.headers("machine", device, "invalid")) as response:
            self.assertEqual(response.status, 401)
        token, ticket = await self.login("machine", device)
        async with self.http.get("/api/v1/device/list",
                                 headers=self.headers("machine", device, token)) as response:
            self.assertEqual(response.status, 200)
            result = await response.json()
            self.assertEqual(result["data"]["current_device"]["device_id"], device)
        _, replay = await self.post("/api/v1/login/by_qrcode", "machine", device,
                                    data={"token": ticket})
        self.assertNotEqual(replay["code"], 0)

    async def test_qr_requires_administrator_and_owner(self):
        a = await self.register("a")
        b = await self.register("b")
        _, qr = await self.post("/api/v1/qrcode/gen/login", "a", a)
        ticket = qr["data"]["token"]
        async with self.http.post("/enroll/" + ticket,
                                  data={"admin_key": "wrong"}) as response:
            self.assertEqual(response.status, 403)
        status, _ = await self.post("/api/v1/qrcode/login/status", "b", b,
                                    data={"token": ticket})
        self.assertEqual(status, 403)
        _, denied = await self.post("/api/v1/login/by_qrcode", "a", a,
                                    data={"token": ticket})
        self.assertEqual(denied["code"], 403)

    async def test_signal_control_ack_and_binary_forwarding(self):
        hd, cd, ht, ct, host, controller = await self.pair()
        app = "application-1"
        request = {"app_control_id": app,
                   "app_data": {"_placeholder": True, "num": 0},
                   "streamer_data": "{}"}
        await controller.send_str("451-2" + json.dumps(["control", request]))
        await controller.send_bytes(b"\x04\x08\x01")
        started = await host.receive()
        self.assertIn("be-controlled", started.data)
        forwarded = json.loads(started.data.split("-", 1)[1])[1]
        self.assertEqual(forwarded["app_control_id"], app)
        self.assertEqual((await host.receive()).data, b"\x04\x08\x01")
        ack = (await controller.receive()).data
        self.assertTrue(ack.startswith('432["success",'))
        info = json.loads(ack[3:])[1]
        self.assertEqual(info["iceServers"][0]["urls"],
                         "stun:stun.example.test:3478")
        offer = {"client_id": info["client_id"],
                 "data": {"ice_id": info["ice_id"], "app_control_id": app,
                          "type": "offer", "gzip_sdp": {"_placeholder": True, "num": 0}}}
        await controller.send_str("451-" + json.dumps(["soac", offer]))
        await controller.send_bytes(b"\x04\x1f\x8b")
        self.assertIn("soac", (await host.receive()).data)
        self.assertEqual((await host.receive()).data, b"\x04\x1f\x8b")
        capability = {"client_id": forwarded["client_id"],
                      "data": {"type": "device_capability",
                               "device_capability": {"ice_id": info["ice_id"],
                                                     "display_info": [],
                                                     "video_codec_capability": []}}}
        await host.send_str("42" + json.dumps(["forward_setting", capability]))
        self.assertIn("forward_setting", (await controller.receive()).data)
        sender = {"client_id": forwarded["client_id"],
                  "data": {"type": "sender_para_info",
                           "sender_para_info": {"ice_id": info["ice_id"],
                                                "sender_media_infos": []}}}
        await host.send_str("42" + json.dumps(["forward_setting", sender]))
        self.assertIn("sender_para_info", (await controller.receive()).data)
        app_data = {"client_id": info["client_id"],
                    "data": {"type": "signal_app_data",
                             "signal_app_data": {"binary_data": {"_placeholder": True, "num": 0}}}}
        await controller.send_str("451-" + json.dumps(["forward_setting", app_data]))
        await controller.send_bytes(b"\x04protobuf-payload")
        self.assertIn("signal_app_data", (await host.receive()).data)
        self.assertEqual((await host.receive()).data, b"\x04protobuf-payload")
        await host.send_str('42["clear_out"]')
        self.assertIn("released", (await controller.receive()).data)

    async def test_busy_join_and_explicit_takeover(self):
        hd, cd, ht, ct, host, controller = await self.pair()
        request = {"app_control_id": "application", "app_data": {"_placeholder": True, "num": 0}}
        await controller.send_str("451-2" + json.dumps(["control", request]))
        await controller.send_bytes(b"\x04\x01")
        await host.receive()
        await host.receive()
        await controller.receive()
        _, busy = await self.post("/api/v1/room/join/by_device/" + hd,
                                  "controller", cd, ct)
        self.assertEqual(busy["code"], 1136)
        _, room = await self.post("/api/v1/room/join/by_device/" + hd,
                                  "controller", cd, ct, {"force_join": True})
        self.assertEqual(room["code"], 0)
        new = await self.socket(room["data"]["token"])
        await new.send_str("451-2" + json.dumps(["control", request]))
        await new.send_bytes(b"\x04\x01")
        self.assertIn("released", (await controller.receive()).data)
        self.assertIn("left", (await host.receive()).data)
        self.assertIn("be-controlled", (await host.receive()).data)
        await host.receive()
        self.assertIn("success", (await new.receive()).data)

    async def test_orphan_binary_is_rejected(self):
        hd, cd, ht, ct, host, controller = await self.pair()
        await controller.send_bytes(b"\x04orphan")
        packet = await controller.receive()
        self.assertIn(packet.type, (WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED))
        self.assertFalse(host.closed)


    async def test_logical_control_survives_websocket_reconnect(self):
        import asyncio
        hd, cd, ht, ct, host, controller = await self.pair()
        request = {"app_control_id": "app-reconnect",
                   "app_data": {"_placeholder": True, "num": 0},
                   "streamer_data": "{}"}
        await controller.send_str("451-2" + json.dumps(["control", request]))
        await controller.send_bytes(b"\x04\x08\x01")
        await host.receive()
        await host.receive()
        ack = json.loads((await controller.receive()).data[3:])[1]
        controller_ticket = next(token for token, t in self.plane.tickets.items()
                                 if t.kind == "controller" and t.device == cd)
        before = self.plane.controls[hd][0].client_id
        self.plane.reconnect_grace = 0.15
        await controller.close()
        reconnected = await self.socket(controller_ticket)
        self.assertEqual(self.plane.controls[hd][0].client_id, before)
        await asyncio.sleep(0.25)
        self.assertIn(hd, self.plane.controls)
        # The old connection must no longer own the forwarding slot.
        capability = {"client_id": before, "data": {
            "type": "device_capability",
            "device_capability": {"ice_id": ack["ice_id"], "display_info": [],
                                  "video_codec_capability": []}}}
        await host.send_str("42" + json.dumps(["forward_setting", capability]))
        frame = await asyncio.wait_for(reconnected.receive(), timeout=2)
        self.assertIn("forward_setting", frame.data)

    async def test_disconnected_control_expires_after_reconnect_grace(self):
        import asyncio
        hd, cd, ht, ct, host, controller = await self.pair()
        request = {"app_control_id": "app-expiry",
                   "app_data": {"_placeholder": True, "num": 0}}
        await controller.send_str("451-2" + json.dumps(["control", request]))
        await controller.send_bytes(b"\x04\x01")
        await host.receive()
        await host.receive()
        await controller.receive()
        self.plane.reconnect_grace = 0.1
        await controller.close()
        await asyncio.sleep(0.25)
        self.assertNotIn(hd, self.plane.controls)
        self.assertIn("left", (await host.receive()).data)


if __name__ == "__main__":
    unittest.main()
