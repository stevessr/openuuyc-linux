"""Self-hosted OpenUUYC NRD-compatible REST and Engine.IO v4 / Socket.IO v4 signaling.

An experimental control plane for OpenUUYC clients. WebRTC media stays peer-to-peer.
Requires an HTTPS reverse proxy and explicit administrator-approved enrollment.
"""
from __future__ import annotations

import argparse
import asyncio
import hmac
import json
import os
import re
import secrets
import sqlite3
import time
from dataclasses import dataclass, field
from urllib.parse import quote
from aiohttp import WSMsgType, web
from .compat import Compatibility
from .ice import ice_servers
from .media import LocalMedia
from .assistance_live import LiveAssistance

MAX_BODY = 1024 * 1024
MAX_SIGNAL = 1024 * 1024
DEVICE_RE = re.compile(r"^[a-z0-9]{16}$")
EVENT_RE = re.compile(r"^42(\d{0,16})(\[.*)$", re.S)
BINARY_RE = re.compile(r"^45(\d+)-(\d{0,16})(\[.*)$", re.S)


def envelope(data=None, code=0, msg=""):
    return web.json_response({"code": code, "msg": msg, "data": data})


@dataclass
class Ticket:
    kind: str
    device: str
    target: str
    expires: float
    force: bool = False
    session_token: str = ""
    guest: bool = False
    client_id: str = field(default_factory=lambda: secrets.token_hex(12))


@dataclass(eq=False)
class Peer:
    socket: web.WebSocketResponse
    ticket: Ticket
    client_id: str = field(default_factory=lambda: secrets.token_hex(12))
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    namespace: bool = False
    pending: tuple[str, str, dict] | None = None
    active: bool = True
    last_pong: float = field(default_factory=time.monotonic)

    async def send(self, *frames: str | bytes):
        if self.active and not self.socket.closed:
            async with self.lock:
                for frame in frames:
                    if isinstance(frame, str):
                        await self.socket.send_str(frame)
                    else:
                        await self.socket.send_bytes(frame)

    async def event(self, name: str, data: dict | None = None, attachment: bytes | None = None):
        args = [name] + ([data] if data is not None else [])
        payload = json.dumps(args, separators=(",", ":"))
        if attachment is None:
            await self.send("42" + payload)
        else:
            await self.send("451-" + payload, b"\x04" + attachment)


class ControlPlane(Compatibility, LocalMedia, LiveAssistance):
    def __init__(self, database: str, public_url: str, admin_key: str, ice_urls: list[str],
                 turn_secret: str = "", turn_user: str = "", turn_password: str = "",
                 reconnect_grace: float = 20.0):
        if not public_url.startswith("https://"):
            raise ValueError("OPENUUYC_PUBLIC_URL must be an HTTPS origin")
        if len(admin_key) < 24:
            raise ValueError("OPENUUYC_ADMIN_KEY must contain at least 24 characters")
        if not ice_urls or not all(u.startswith(("stun:", "turn:", "turns:")) for u in ice_urls):
            raise ValueError("configure at least one STUN/TURN URL")
        self.public_url = public_url.rstrip("/")
        self.signal_endpoint = self.public_url.removeprefix("https://")
        self.admin_key = admin_key
        self.ice_urls = ice_urls
        self.turn_secret = turn_secret
        self.turn_user = turn_user
        self.turn_password = turn_password
        self.reconnect_grace = max(0.05, min(120.0, reconnect_grace))
        self.db = sqlite3.connect(database, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS devices(
              device_id TEXT PRIMARY KEY, client_id TEXT UNIQUE NOT NULL,
              alias TEXT NOT NULL, platform INTEGER NOT NULL,
              controllable INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS sessions(
              token TEXT PRIMARY KEY, device_id TEXT NOT NULL, created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS qrs(
              ticket TEXT PRIMARY KEY, client_id TEXT NOT NULL, expires INTEGER NOT NULL,
              approved INTEGER NOT NULL DEFAULT 0, consumed INTEGER NOT NULL DEFAULT 0
            );
        """)
        self.init_compat()
        self.init_media(database)
        self.init_live_assist()
        self.tickets: dict[str, Ticket] = {}
        self.peers: set[Peer] = set()
        self.hosts: dict[str, Peer] = {}
        self.controllers: dict[str, Peer] = {}
        self.disconnect_tasks: set[asyncio.Task] = set()
        self.controls: dict[str, tuple[Peer, Peer, str, str]] = {}
        self.guard = asyncio.Lock()

    def device(self, did):
        return self.db.execute("SELECT * FROM devices WHERE device_id=?", (did,)).fetchone()

    def caller(self, req):
        authorization = req.headers.get("Authorization", "")
        token = authorization.removeprefix("Bearer ") if authorization.startswith("Bearer ") else ""
        row = self.db.execute("SELECT device_id FROM sessions WHERE token=?", (token,)).fetchone() if token else None
        if row is None:
            raise web.HTTPUnauthorized(text="invalid session")
        device = self.device(row["device_id"])
        if (not device or device["device_id"] != req.headers.get("X-Param-device-id")
                or device["client_id"] != req.headers.get("X-Param-client-id")):
            raise web.HTTPUnauthorized(text="device mismatch")
        return device

    async def body(self, req):
        if req.content_length and req.content_length > MAX_BODY:
            raise web.HTTPRequestEntityTooLarge(max_size=MAX_BODY, actual_size=req.content_length)
        if not req.can_read_body:
            return {}
        try:
            value = await req.json()
        except (ValueError, UnicodeError):
            raise web.HTTPBadRequest(text="expected JSON") from None
        if not isinstance(value, dict):
            raise web.HTTPBadRequest(text="expected object")
        return value

    def new_ticket(self, kind, device, target, lifetime=3600, force=False, session_token="", guest=False):
        if len(self.tickets) >= 8192:
            now = time.monotonic()
            self.tickets = {k: t for k, t in self.tickets.items() if t.expires > now}
            if len(self.tickets) >= 8192:
                raise web.HTTPServiceUnavailable(text="room ticket capacity exceeded")
        key = secrets.token_urlsafe(32)
        if not session_token:
            raise ValueError("room tickets require an authenticated session")
        self.tickets[key] = Ticket(kind, device, target, time.monotonic() + lifetime,
                                   force, session_token, guest)
        return {"token": key, "ws_connect_timeout_ms": 10000,
                "streamer_retry_delta_ms": 1000, "max_reconnect_delta": 120000,
                "signaling_list": [self.signal_endpoint],
                "signaling_server": self.signal_endpoint}

    def status(self, row):
        did = row["device_id"]
        metadata = self.metadata(did)
        return {"device_id": did, "alias": row["alias"], "platform": row["platform"],
                "version_name": metadata["version_name"],
                "wallpaper_url": metadata["wallpaper_url"],
                "support_wol": bool(metadata["support_wol"]),
                "status": "CONNECTED" if did in self.hosts else "DISCONNECTED",
                "controllable": bool(row["controllable"]),
                "controlled_support": True,
                "participants_info": ([{"device_id": self.controls[did][0].ticket.device}]
                                      if did in self.controls else [])}

    async def init(self, req):
        body = await self.body(req)
        cid = req.headers.get("X-Param-client-id", "")
        if not cid or len(cid) > 128 or cid != body.get("client_id"):
            return envelope(code=400, msg="client-id mismatch")
        name = str(body.get("name", "OpenUUYC"))[:100]
        platform = 2 if "android" in req.path else 1
        existing = self.db.execute("SELECT * FROM devices WHERE client_id=?", (cid,)).fetchone()
        if existing:
            self.update_registered(existing["device_id"],
                                   req.headers.get("X-Param-VN", ""))
            return envelope({"device_id": existing["device_id"], "alias": existing["alias"]})
        did = secrets.token_hex(8)
        count = self.db.execute("SELECT COUNT(*) FROM devices").fetchone()[0]
        if count >= 1024:
            raise web.HTTPServiceUnavailable(text="device capacity exceeded")
        self.db.execute("INSERT INTO devices(device_id,client_id,alias,platform) VALUES(?,?,?,?)",
                        (did, cid, name, platform))
        self.update_registered(did, req.headers.get("X-Param-VN", ""))
        return envelope({"device_id": did, "alias": name})

    async def generate_qr(self, req):
        cid = req.headers.get("X-Param-client-id", "")
        if not cid or not self.db.execute("SELECT 1 FROM devices WHERE client_id=?", (cid,)).fetchone():
            return envelope(code=401, msg="device not registered")
        # Bound QR storage and ensure expired grants cannot accumulate.
        self.db.execute("DELETE FROM qrs WHERE expires<? OR consumed=1",
                        (int(time.time()) - 300,))
        pending = self.db.execute("SELECT COUNT(*) FROM qrs WHERE client_id=? AND consumed=0 AND expires>?",
                                  (cid, int(time.time()))).fetchone()[0]
        if pending >= 8:
            return envelope(code=429, msg="too many pending QR requests")
        ticket = secrets.token_urlsafe(24)
        self.db.execute("INSERT INTO qrs(ticket,client_id,expires) VALUES(?,?,?)",
                        (ticket, cid, int(time.time()) + 300))
        return envelope({"qrcode_jump_url": f"{self.public_url}/enroll/{quote(ticket)}",
                         "token": ticket, "status_query_ticket": ticket})

    async def qr_status(self, req):
        body = await self.body(req)
        row = self.db.execute("SELECT * FROM qrs WHERE ticket=?", (body.get("token", ""),)).fetchone()
        if not row or row["expires"] <= time.time() or row["consumed"]:
            return envelope({"status": 3, "login_status": 3})
        if row["client_id"] != req.headers.get("X-Param-client-id"):
            raise web.HTTPForbidden(text="QR bound to another device")
        status = 4 if row["approved"] else 1
        if not row["approved"]:
            await asyncio.sleep(0.5)
        return envelope({"status": status, "login_status": status})

    async def approve_get(self, req):
        row = self.db.execute("SELECT expires FROM qrs WHERE ticket=?",
                              (req.match_info["ticket"],)).fetchone()
        if not row or row["expires"] <= time.time():
            raise web.HTTPNotFound()
        html = ('<!doctype html><meta charset="utf-8">'
                '<meta name="referrer" content="no-referrer">'
                '<title>OpenUUYC self-host login</title><h1>Approve OpenUUYC device</h1>'
                '<p>Approve only a device you control.</p>'
                '<form method="post"><label>Administrator key '
                '<input type="password" name="admin_key" required autocomplete="off"></label>'
                '<button type="submit">Approve</button></form>')
        return web.Response(text=html, content_type="text/html",
                            headers={"Cache-Control": "no-store"})

    async def approve_post(self, req):
        form = await req.post()
        if not hmac.compare_digest(str(form.get("admin_key", "")), self.admin_key):
            raise web.HTTPForbidden(text="invalid administrator key")
        cursor = self.db.execute(
            "UPDATE qrs SET approved=1 WHERE ticket=? AND expires>? AND consumed=0",
            (req.match_info["ticket"], int(time.time())))
        if cursor.rowcount == 0:
            raise web.HTTPNotFound()
        return web.Response(text="Approved. Return to OpenUUYC.",
                            headers={"Cache-Control": "no-store"})

    async def login_qr(self, req):
        body = await self.body(req)
        ticket = body.get("token", "")
        row = self.db.execute("SELECT * FROM qrs WHERE ticket=?", (ticket,)).fetchone()
        if not row or not row["approved"] or row["consumed"] or row["expires"] <= time.time():
            return envelope(code=403, msg="QR not authorized")
        url = f"{self.public_url}/enroll/{quote(ticket)}"
        if body.get("qrcode_jump_url") != url or row["client_id"] != req.headers.get("X-Param-client-id"):
            raise web.HTTPForbidden(text="QR identity mismatch")
        device = self.db.execute("SELECT device_id FROM devices WHERE client_id=?",
                                 (row["client_id"],)).fetchone()
        token = secrets.token_urlsafe(48)
        self.db.execute("UPDATE qrs SET consumed=1 WHERE ticket=?", (ticket,))
        self.db.execute("INSERT INTO sessions(token,device_id,created_at) VALUES(?,?,?)",
                        (token, device["device_id"], int(time.time())))
        return envelope({"token": token, "user_id": "selfhost", "nickname": "OpenUUYC selfhost"})

    async def user_info(self, req):
        self.caller(req)
        return envelope({"user_id": "selfhost", "nickname": "OpenUUYC selfhost"})

    async def logout(self, req):
        self.caller(req)
        token = req.headers["Authorization"][7:]
        self.db.execute("DELETE FROM sessions WHERE token=?", (token,))
        async with self.guard:
            invalid = [p for p in self.peers if p.ticket.session_token == token]
            affected = [target for target, (controller, host, _, _) in self.controls.items()
                        if controller.ticket.session_token == token
                        or host.ticket.session_token == token]
            for target in affected:
                await self.release(target)
            for key, ticket in list(self.tickets.items()):
                if ticket.session_token == token:
                    self.tickets.pop(key, None)
        for peer in invalid:
            await peer.socket.close(code=1008, message=b"session revoked")
        return envelope({})

    async def list_devices(self, req):
        own = self.caller(req)
        rows = self.db.execute("SELECT * FROM devices ORDER BY alias,device_id").fetchall()
        return envelope({"current_device": self.status(own),
                         "my_binded_devices": [self.status(r) for r in rows
                                               if r["device_id"] != own["device_id"]]})

    async def device_groups(self, req):
        own = self.caller(req)
        rows = self.db.execute("SELECT * FROM devices").fetchall()
        return envelope({"current_device_id": own["device_id"],
                         "desktop_devices": [self.status(r) for r in rows if r["platform"] == 1],
                         "mobile_devices": [self.status(r) for r in rows if r["platform"] == 2],
                         "tv_devices": []})

    async def controllable(self, req):
        own = self.host_caller(req)
        body = await self.body(req)
        if not isinstance(body.get("controllable"), bool):
            raise web.HTTPBadRequest(text="controllable must be boolean")
        self.db.execute("UPDATE devices SET controllable=? WHERE device_id=?",
                        (int(body["controllable"]), own["device_id"]))
        if not body["controllable"]:
            async with self.guard:
                await self.release(own["device_id"])
        return envelope({})

    async def create_room(self, req):
        own = self.caller(req)
        return envelope(self.new_ticket("host", own["device_id"], own["device_id"],
                                        session_token=req.headers["Authorization"][7:]))

    async def join(self, req):
        own = self.caller(req)
        target = req.match_info["device"]
        body = await self.body(req)
        if not DEVICE_RE.fullmatch(target) or target == own["device_id"]:
            return envelope(code=400, msg="invalid target")
        target_row = self.device(target)
        host = self.hosts.get(target)
        if not target_row or not host or not host.active or not target_row["controllable"]:
            return envelope(code=404, msg="target offline or disabled")
        force = body.get("force_join") is True
        if target in self.controls and not force:
            return envelope(code=1136, msg="device busy")
        return envelope(self.new_ticket("controller", own["device_id"], target, 600, force,
                                        session_token=req.headers["Authorization"][7:]))

    async def device_detail(self, req):
        self.caller(req)
        if not self.device(req.match_info["device"]):
            return envelope(code=404, msg="device not found")
        info = self.device(req.match_info["device"])
        return envelope({"details": [["Device ID", info["device_id"]],
                                     ["Platform", str(info["platform"])],
                                     ["Version", self.metadata(info["device_id"])["version_name"]]]})

    async def configures(self, req):
        self.caller(req)
        return envelope({})

    async def unsupported(self, req):
        self.caller(req)
        return envelope(code=501, msg="unsupported on self-host")

    async def release(self, target):
        session = self.controls.pop(target, None)
        if session:
            controller, host, ice, app = session
            await controller.event("released", {"ice_id": ice, "app_control_id": app,
                                                "client_id": host.client_id})
            await host.event("left", {"ice_id": ice, "app_control_id": app,
                                      "client_id": controller.client_id})

    async def enter(self, peer):
        async with self.guard:
            if peer.ticket.kind == "host":
                previous = self.hosts.get(peer.ticket.device)
                if previous and previous is not peer:
                    await previous.socket.close(code=1008, message=b"host superseded")
                self.hosts[peer.ticket.device] = peer
            else:
                previous = self.controllers.get(peer.client_id)
                if previous and previous is not peer:
                    await previous.socket.close(code=1008, message=b"controller superseded")
                self.controllers[peer.client_id] = peer
            # Physical WebSocket generations may change without changing the
            # logical UU room, ICE id, app_control_id or controller client_id.
            for target, (controller, host, ice, app) in list(self.controls.items()):
                if controller.ticket is peer.ticket:
                    controller = peer
                if host.ticket is peer.ticket:
                    host = peer
                self.controls[target] = (controller, host, ice, app)
            self.peers.add(peer)

    async def expire_disconnected(self, peer):
        await asyncio.sleep(self.reconnect_grace)
        async with self.guard:
            for target, (controller, host, _, _) in list(self.controls.items()):
                if peer is controller or peer is host:
                    await self.release(target)

    async def leave(self, peer):
        peer.active = False
        async with self.guard:
            self.peers.discard(peer)
            if self.hosts.get(peer.ticket.device) is peer:
                self.hosts.pop(peer.ticket.device, None)
            if self.controllers.get(peer.client_id) is peer:
                self.controllers.pop(peer.client_id, None)
            if any(peer is c or peer is h for c, h, _, _ in self.controls.values()):
                # Delay release only for unexpected physical disconnects. Explicit
                # leave, logout and host clear_out already release immediately.
                task = asyncio.create_task(self.expire_disconnected(peer))
                self.disconnect_tasks.add(task)
                task.add_done_callback(self.disconnect_tasks.discard)

    async def handle_event(self, peer, event, args, ack, attachment):
        if event == "room_info":
            payload = {"room_id": peer.ticket.target, "client_id": peer.client_id}
            await peer.send("43" + ack + json.dumps([payload], separators=(",", ":")))
            return
        if event == "refresh_reconnect_key":
            await peer.send("43" + ack + json.dumps(
                ["success", {"reconnect_key": secrets.token_urlsafe(16)}]))
            return
        if event in ("leave", "clear_out"):
            async with self.guard:
                active = self.controls.get(peer.ticket.target)
                # A stale controller's LEAVE must not release another
                # controller's live session after a takeover.
                if active and (peer is active[0] or peer is active[1]):
                    await self.release(peer.ticket.target)
            if ack:
                await peer.send("43" + ack + "[]")
            return
        if event == "control":
            if peer.ticket.kind != "controller" or attachment is None or not args or not isinstance(args[0], dict):
                await peer.send("43" + ack + '["rejected",{"code":403}]')
                return
            target = peer.ticket.target
            host = self.hosts.get(target)
            row = self.device(target)
            if not host or not host.active or not row or not row["controllable"]:
                await peer.send("43" + ack + '["rejected",{"code":404}]')
                return
            request = args[0]
            app = request.get("app_control_id")
            app_data = request.get("app_data")
            if not isinstance(app, str) or not app or len(app) > 128 or not isinstance(app_data, dict) or app_data.get("_placeholder") is not True:
                await peer.send("43" + ack + '["rejected",{"code":400}]')
                return
            async with self.guard:
                if target in self.controls:
                    if not peer.ticket.force:
                        await peer.send("43" + ack + '["rejected",{"code":1136}]')
                        return
                    await self.release(target)
                ice = secrets.token_hex(12)
                self.controls[target] = (peer, host, ice, app)
                forwarded = {"client_id": peer.client_id, "ice_id": ice,
                             "app_control_id": app,
                             "app_data": {"_placeholder": True, "num": 0},
                             "streamer_data": request.get("streamer_data", "{}")}
                await host.event("be-controlled", forwarded, attachment)
                reply = {"code": 0, "client_id": host.client_id, "ice_id": ice,
                         "iceServers": ice_servers(self.ice_urls,
                                                   shared_secret=self.turn_secret,
                                                   static_user=self.turn_user,
                                                   static_password=self.turn_password),
                         "force_relay": False}
                await peer.send("43" + ack + json.dumps(["success", reply], separators=(",", ":")))
            return
        if event in ("soac", "forward_setting"):
            if not args or not isinstance(args[0], dict) or not isinstance(args[0].get("data"), dict):
                return
            async with self.guard:
                active = self.controls.get(peer.ticket.target)
                if not active:
                    return
                controller, host, ice, app = active
                if peer not in (controller, host):
                    return
                other = host if peer is controller else controller
                item = args[0]
                data = item["data"]
                # UU forward_setting envelopes carry their ICE identity
                # inside the typed payload. Signal app-data has no ICE field,
                # but is still restricted to the authenticated active peer pair.
                declared_ice = data.get("ice_id")
                if event == "forward_setting":
                    kind = data.get("type")
                    nested = data.get(kind) if isinstance(kind, str) else None
                    if kind == "signal_app_data":
                        declared_ice = ice
                    elif isinstance(nested, dict):
                        declared_ice = nested.get("ice_id")
                if (item.get("client_id") != other.client_id or
                    declared_ice != ice or
                    data.get("app_control_id", app) != app):
                    return
                await other.event(event, item, attachment)
            return
        if ack:
            await peer.send("43" + ack + "[]")

    async def signal(self, req):
        if req.query.get("EIO") != "4" or req.query.get("transport") != "websocket":
            raise web.HTTPBadRequest(text="Engine.IO v4 websocket required")
        token = req.headers.get("X-NRD-AUTH", "")
        ticket = self.tickets.get(token)
        if ticket is None or ticket.expires <= time.monotonic():
            raise web.HTTPUnauthorized(text="invalid room token")
        if ticket.guest:
            session = self.db.execute(
                "SELECT device_id FROM guest_sessions WHERE token=? AND expires>?",
                (ticket.session_token, int(time.time()))).fetchone()
        else:
            session = self.db.execute("SELECT device_id FROM sessions WHERE token=?",
                                      (ticket.session_token,)).fetchone()
        if not session or session["device_id"] != ticket.device or not self.device(ticket.device):
            raise web.HTTPUnauthorized(text="revoked room session")
        ws = web.WebSocketResponse(heartbeat=None, max_msg_size=MAX_SIGNAL)
        await ws.prepare(req)
        peer = Peer(ws, ticket, client_id=ticket.client_id)
        await self.enter(peer)
        await peer.send("0" + json.dumps(
            {"sid": peer.client_id, "upgrades": [], "pingInterval": 25000,
             "pingTimeout": 60000, "maxPayload": MAX_SIGNAL}))

        async def heartbeat():
            while peer.active and not ws.closed:
                await asyncio.sleep(25)
                if time.monotonic() - peer.last_pong > 85:
                    await ws.close(code=1008, message=b"ping timeout")
                    return
                await peer.send("2")

        job = asyncio.create_task(heartbeat())
        try:
            async for message in ws:
                if message.type == WSMsgType.TEXT:
                    frame = message.data
                    if frame == "40":
                        peer.namespace = True
                        await peer.send("40" + json.dumps({"sid": peer.client_id}))
                    elif frame.startswith("3"):
                        peer.last_pong = time.monotonic()
                    elif frame.startswith("2"):
                        await peer.send("3" + frame[1:])
                    elif frame in ("41", "1"):
                        break
                    elif not peer.namespace:
                        await ws.close(code=1008, message=b"namespace required")
                        break
                    elif frame.startswith("45"):
                        match = BINARY_RE.fullmatch(frame)
                        if not match or match.group(1) != "1" or peer.pending:
                            await ws.close(code=1008, message=b"invalid binary event")
                            break
                        try:
                            payload = json.loads(match.group(3))
                            if not isinstance(payload, list) or not payload or not isinstance(payload[0], str):
                                raise ValueError()
                            peer.pending = (payload[0], match.group(2),
                                            payload[1] if len(payload) > 1 else {})
                        except (ValueError, TypeError):
                            await ws.close(code=1008, message=b"invalid JSON")
                            break
                    elif frame.startswith("42"):
                        match = EVENT_RE.fullmatch(frame)
                        if not match:
                            await ws.close(code=1008, message=b"invalid event")
                            break
                        try:
                            payload = json.loads(match.group(2))
                            if not isinstance(payload, list) or not payload or not isinstance(payload[0], str):
                                raise ValueError()
                            await self.handle_event(peer, payload[0], payload[1:], match.group(1), None)
                        except (ValueError, TypeError):
                            await ws.close(code=1008, message=b"invalid JSON")
                            break
                elif message.type == WSMsgType.BINARY:
                    if not peer.pending or not message.data.startswith(b"\x04") or len(message.data) < 2:
                        await ws.close(code=1008, message=b"orphan binary payload")
                        break
                    event, ack, data = peer.pending
                    peer.pending = None
                    await self.handle_event(peer, event, [data], ack, message.data[1:])
                elif message.type in (WSMsgType.ERROR, WSMsgType.CLOSE, WSMsgType.CLOSED):
                    break
        finally:
            job.cancel()
            await self.leave(peer)
        return ws

    def application(self):
        app = web.Application(client_max_size=MAX_BODY)

        async def health(_):
            return web.json_response({"status": "ok"})

        app.add_routes([
            web.post("/api/v1/device/windows/init", self.init),
            web.post("/api/v1/device/android/init", self.init),
            web.post("/api/v1/qrcode/gen/login", self.generate_qr),
            web.post("/api/v1/qrcode/login/status", self.qr_status),
            web.post("/api/v1/login/by_qrcode", self.login_qr),
            web.get("/enroll/{ticket}", self.approve_get),
            web.post("/enroll/{ticket}", self.approve_post),
            web.get("/api/v1/user/info", self.user_info),
            web.post("/api/v1/user/logout", self.logout),
            web.get("/api/v1/device/list", self.list_devices),
            web.get("/api/v1/device/groups/of/my", self.device_groups),
            web.get("/api/v1/device/{device}/detail", self.device_detail),
            web.post("/api/v1/device/controllable", self.controllable),
            web.post("/api/v1/room/create", self.create_room),
            web.post("/api/v1/room/join/by_device/{device}", self.join),
            web.post("/api/v1/tool/configures_with_version", self.configures),
            web.post("/api/v1/transport/{device}/rpc/request", self.transport_rpc),
            web.get("/socket.io/", self.signal),
            web.get("/healthz", health),
        ] + self.compat_routes() + self.media_routes())

        async def close(_):
            for peer in list(self.peers):
                await peer.socket.close()
            for task in list(self.disconnect_tasks):
                task.cancel()
            await asyncio.gather(*self.disconnect_tasks, return_exceptions=True)
            self.db.close()
        app.on_cleanup.append(close)
        return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8844)
    parser.add_argument("--database", default="./selfhost.sqlite3")
    args = parser.parse_args()
    public_url = os.environ.get("OPENUUYC_PUBLIC_URL", "")
    admin_key = os.environ.get("OPENUUYC_ADMIN_KEY", "")
    ice = [x.strip() for x in os.environ.get("OPENUUYC_ICE_SERVERS", "").split(",") if x.strip()]
    plane = ControlPlane(args.database, public_url, admin_key, ice,
                         turn_secret=os.environ.get("OPENUUYC_TURN_SECRET", ""),
                         turn_user=os.environ.get("OPENUUYC_TURN_USER", ""),
                         turn_password=os.environ.get("OPENUUYC_TURN_PASSWORD", ""))
    web.run_app(plane.application(), host=args.bind, port=args.port, access_log=None)


if __name__ == "__main__":
    main()
