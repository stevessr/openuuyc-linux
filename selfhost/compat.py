"""Additional self-hosted NRD-compatible device, assistance and push services.

These routes are deliberately account-scoped to one administrator's approved
devices. A remote RPC is delivered only to an online, explicitly controllable
target; never report success without an accepted signaling write.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import re
import secrets
import time
from aiohttp import web

from .server_utils import envelope, validated_id

ASSIST_ID = re.compile(r"^[0-9]{9}$")
MAX_ALIAS = 100


class Compatibility:
    def init_compat(self):
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS device_properties(
              device_id TEXT PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
              version_name TEXT NOT NULL DEFAULT '',
              support_wol INTEGER NOT NULL DEFAULT 0,
              lan_info TEXT NOT NULL DEFAULT '{}',
              wallpaper_url TEXT NOT NULL DEFAULT ''
            );
            CREATE TABLE IF NOT EXISTS assistance(
              device_id TEXT PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
              connect_id TEXT NOT NULL UNIQUE,
              control_mode TEXT NOT NULL DEFAULT 'password',
              allow_control INTEGER NOT NULL DEFAULT 0,
              need_confirmation INTEGER NOT NULL DEFAULT 0,
              need_password INTEGER NOT NULL DEFAULT 1,
              sign_hash TEXT NOT NULL DEFAULT '',
              backup_hash TEXT NOT NULL DEFAULT ''
            );
            CREATE TABLE IF NOT EXISTS saved_assistance(
              owner_id TEXT NOT NULL,
              publisher_device_id TEXT NOT NULL,
              connect_id TEXT NOT NULL,
              remark TEXT NOT NULL DEFAULT '',
              is_favorite INTEGER NOT NULL DEFAULT 0,
              last_connected_at INTEGER,
              favorited_at INTEGER,
              PRIMARY KEY(owner_id,publisher_device_id)
            );
        """)
        self.pending_android = {}
        self.assist_failures = {}

    def compat_routes(self):
        return [
            web.put("/api/v1/device/{device}", self.rename_device),
            web.post("/api/v1/device/{device}/unbind", self.unbind_device),
            web.post("/api/v1/device/set_support_wol", self.set_wol),
            web.post("/api/v1/device/wake_on_lan/info", self.wol_info),
            web.post("/api/v1/device/{device}/wake_on_lan/req", self.wake),
            web.post("/api/v1/device/{device}/reboot", self.reboot),
            web.post("/api/v1/transport/rpc/response", self.rpc_response),
            web.post("/api/v1/device/share/info/refresh", self.assist_identity),
            web.post("/api/v2/room/share/upload_control_mode", self.assist_upload_mode),
            web.post("/api/v2/room/share/upload_sign", self.assist_upload_sign),
            web.post("/api/v2/room/share/confirmation", self.assist_upload_confirmation),
            web.post("/api/v2/room/share/control_mode", self.assist_control_mode),
            web.post("/api/v2/room/join/share/by_code", self.assist_join_by_code),
            web.post("/api/v2/room/join/share/by_confirmation", self.assist_join_by_confirmation),
            web.post("/api/v2/room/share/cancel_remote_assist", self.assist_cancel),
            web.post("/api/v1/room/join/refetch", self.assist_refetch),
            web.get("/api/v1/device/share/saved/{kind}", self.saved_list),
            web.post("/api/v1/device/share/saved/favorites", self.save_favorites),
            web.delete("/api/v1/device/share/saved/{kind}/{device}", self.delete_saved),
            web.delete("/api/v1/device/share/saved/recent", self.clear_recent),
        ]

    def metadata(self, device_id: str):
        row = self.db.execute(
            "SELECT * FROM device_properties WHERE device_id=?", (device_id,)
        ).fetchone()
        return dict(row) if row else {
            "support_wol": 0, "version_name": "", "wallpaper_url": "", "lan_info": "{}"
        }

    def update_registered(self, device_id, version=""):
        self.db.execute("INSERT OR IGNORE INTO device_properties(device_id) VALUES(?)", (device_id,))
        if version and len(version) <= 80:
            self.db.execute(
                "UPDATE device_properties SET version_name=? WHERE device_id=?",
                (version, device_id),
            )

    def _assist_hash(self, device_id, value):
        return hmac.new(self.admin_key.encode(),
                        (device_id + "\x00" + value).encode(), hashlib.sha256).hexdigest()

    def _assist_by_device(self, device_id):
        return self.db.execute(
            "SELECT * FROM assistance WHERE device_id=?", (device_id,)
        ).fetchone()

    def _assist_by_connect(self, connect_id):
        if not isinstance(connect_id, str) or not ASSIST_ID.fullmatch(connect_id):
            return None
        return self.db.execute(
            "SELECT * FROM assistance WHERE connect_id=?", (connect_id,)
        ).fetchone()

    def _assist_enabled(self, row):
        return (row is not None and bool(row["allow_control"])
                and self.device(row["device_id"]) is not None
                and row["device_id"] in self.hosts
                and self.hosts[row["device_id"]].active)

    def _save_recent(self, caller_id, target, connect_id):
        self.db.execute("""
            INSERT INTO saved_assistance(owner_id,publisher_device_id,connect_id,last_connected_at)
            VALUES(?,?,?,?)
            ON CONFLICT(owner_id,publisher_device_id) DO UPDATE SET
              last_connected_at=excluded.last_connected_at,connect_id=excluded.connect_id
        """, (caller_id, target, connect_id, int(time.time())))

    async def rename_device(self, req):
        caller = self.caller(req)
        target = req.match_info["device"]
        if not validated_id(target) or not self.device(target):
            return envelope(code=404, msg="device not found")
        body = await self.body(req)
        alias = body.get("alias")
        if (not isinstance(alias, str) or not alias.strip()
                or len(alias) > MAX_ALIAS or any(ord(c) < 32 or ord(c) == 127 for c in alias)):
            return envelope(code=400, msg="invalid alias")
        self.db.execute("UPDATE devices SET alias=? WHERE device_id=?", (alias, target))
        return envelope({"device_id": target, "alias": alias})

    async def unbind_device(self, req):
        caller = self.caller(req)
        target = req.match_info["device"]
        if not validated_id(target) or not self.device(target) or target == caller["device_id"]:
            return envelope(code=400, msg="invalid unbind target")
        async with self.guard:
            await self.release(target)
            peers = [p for p in self.peers if p.ticket.device == target]
            for token, ticket in list(self.tickets.items()):
                if ticket.device == target or ticket.target == target:
                    self.tickets.pop(token, None)
            self.db.execute("DELETE FROM sessions WHERE device_id=?", (target,))
            self.db.execute("DELETE FROM saved_assistance WHERE publisher_device_id=?", (target,))
            self.db.execute("DELETE FROM device_properties WHERE device_id=?", (target,))
            self.db.execute("DELETE FROM assistance WHERE device_id=?", (target,))
            self.db.execute("DELETE FROM devices WHERE device_id=?", (target,))
        for peer in peers:
            await peer.socket.close(code=1008, message=b"device unbound")
        return envelope({})

    async def set_wol(self, req):
        caller = self.caller(req)
        body = await self.body(req)
        if not isinstance(body.get("support_wol"), bool):
            return envelope(code=400, msg="invalid WoL state")
        self.update_registered(caller["device_id"])
        self.db.execute(
            "UPDATE device_properties SET support_wol=? WHERE device_id=?",
            (int(body["support_wol"]), caller["device_id"])
        )
        return envelope({})

    async def wol_info(self, req):
        caller = self.caller(req)
        body = await self.body(req)
        # LAN information is private and never served through device lists.
        if len(json.dumps(body)) > 32768:
            return envelope(code=400, msg="LAN data too large")
        self.update_registered(caller["device_id"])
        self.db.execute(
            "UPDATE device_properties SET lan_info=? WHERE device_id=?",
            (json.dumps(body, separators=(",", ":")), caller["device_id"])
        )
        return envelope({"support_wol": bool(self.metadata(caller["device_id"])["support_wol"])})

    async def wake(self, req):
        self.caller(req)
        # The server is not on the host's LAN and has no authenticated wake
        # helper protocol; acknowledging an unsent magic packet is unsafe.
        return envelope(code=501, msg="no configured local wake helper")

    async def send_to_host(self, target, body, require_control=True):
        host = self.hosts.get(target)
        row = self.device(target)
        if not host or not host.active or not row:
            return False
        if require_control and not row["controllable"]:
            return False
        await host.event("bmsg_push", body)
        return host.active and not host.socket.closed

    async def reboot(self, req):
        caller = self.caller(req)
        target = req.match_info["device"]
        if not validated_id(target) or target == caller["device_id"]:
            return envelope(code=400, msg="invalid target")
        delivered = await self.send_to_host(target, {"type": "device_reboot", "data": {}})
        return envelope({} if delivered else None,
                        code=0 if delivered else 404,
                        msg="" if delivered else "host is unavailable")

    async def transport_rpc(self, req):
        caller = self.caller(req)
        target = req.match_info["device"]
        if not validated_id(target) or target == caller["device_id"]:
            return envelope(code=400, msg="invalid RPC target")
        target_row = self.device(target)
        if target_row is None:
            return envelope(code=404, msg="RPC target does not exist")
        body = await self.body(req)
        action = body.get("p")
        if action not in {"shutdown", "android_control_request", "android_control_result"}:
            return envelope(code=501, msg="unsupported RPC")
        if action == "shutdown":
            if target_row["platform"] not in (1, 4):
                return envelope(code=400, msg="not a desktop")
        elif action == "android_control_request":
            details = body.get("data", {})
            info = details.get("device_info", {}) if isinstance(details, dict) else {}
            if (not isinstance(info, dict) or
                info.get("device_id") != caller["device_id"] or
                details.get("action") not in ("request_control", "cancel_request")):
                return envelope(code=400, msg="invalid Android approval request")
            if target_row["platform"] != 2:
                return envelope(code=400, msg="target is not Android")
            key = (target, caller["device_id"])
            if details["action"] == "cancel_request":
                self.pending_android.pop(key, None)
            else:
                self.pending_android[key] = time.monotonic() + 60
        elif action == "android_control_result":
            if caller["platform"] != 2:
                return envelope(code=403, msg="only Android host can grant approval")
            details = body.get("data", {})
            info = details.get("device_info", {}) if isinstance(details, dict) else {}
            key = (caller["device_id"], target)
            if (not isinstance(info, dict) or
                info.get("device_id") != caller["device_id"] or
                details.get("action") not in
                    ("accept_control", "reject_request", "during_control") or
                self.pending_android.get(key, 0) < time.monotonic()):
                return envelope(code=403, msg="no matching active approval challenge")
            self.pending_android.pop(key, None)
        delivered = await self.send_to_host(target, {"type": "transport_rpc", "data": body},
                                            require_control=(action == "shutdown"))
        return envelope({} if delivered else None, code=0 if delivered else 404,
                        msg="" if delivered else "target unavailable")

    async def rpc_response(self, req):
        self.caller(req)
        await self.body(req)
        return envelope({})

    async def assist_identity(self, req):
        caller = self.caller(req)
        device_id = caller["device_id"]
        existing = self._assist_by_device(device_id)
        if not existing:
            for _ in range(20):
                connect_id = f"{secrets.randbelow(1_000_000_000):09d}"
                if self._assist_by_connect(connect_id) is None:
                    break
            else:
                raise web.HTTPServiceUnavailable(text="unable to allocate connect ID")
            self.db.execute(
                "INSERT INTO assistance(device_id,connect_id) VALUES(?,?)",
                (device_id, connect_id)
            )
            return envelope({"connect_id": connect_id})
        return envelope({"connect_id": existing["connect_id"]})

    async def assist_upload_mode(self, req):
        caller = self.caller(req)
        body = await self.body(req)
        row = self._assist_by_device(caller["device_id"])
        if not row or not isinstance(body.get("allow_control"), bool):
            return envelope(code=400, msg="invalid assist configuration")
        if body.get("control_id") != row["connect_id"]:
            return envelope(code=403, msg="assist identity mismatch")
        mode = body.get("control_mode")
        if not isinstance(mode, str) or not (1 <= len(mode) <= 64):
            return envelope(code=400, msg="invalid assist mode")
        self.db.execute(
            "UPDATE assistance SET control_mode=?,allow_control=? WHERE device_id=?",
            (mode, int(body["allow_control"]), caller["device_id"])
        )
        return envelope({})

    async def assist_upload_sign(self, req):
        caller = self.caller(req)
        body = await self.body(req)
        row = self._assist_by_device(caller["device_id"])
        if not row or body.get("control_id") != row["connect_id"]:
            return envelope(code=403, msg="assist identity mismatch")
        sign = body.get("sign", "")
        backup = body.get("backup_sign", "")
        if (not isinstance(sign, str) or not isinstance(backup, str)
                or len(sign) > 256 or len(backup) > 256):
            return envelope(code=400, msg="invalid assist credential")
        allowed = body.get("can_remote_control")
        confirmation = body.get("need_confirmation")
        if not isinstance(allowed, bool) or not isinstance(confirmation, bool):
            return envelope(code=400, msg="invalid assist policy")
        mode = body.get("control_mode")
        if not isinstance(mode, str) or not (1 <= len(mode) <= 64):
            return envelope(code=400, msg="invalid assist mode")
        self.db.execute("""
            UPDATE assistance SET
              sign_hash=?,backup_hash=?,control_mode=?,allow_control=?,need_confirmation=?
            WHERE device_id=?
        """, (self._assist_hash(caller["device_id"], sign) if sign else "",
              self._assist_hash(caller["device_id"], backup) if backup else "",
              mode, int(allowed), int(confirmation), caller["device_id"]))
        return envelope({})

    async def assist_upload_confirmation(self, req):
        caller = self.caller(req)
        body = await self.body(req)
        row = self._assist_by_device(caller["device_id"])
        if not row or body.get("control_id") != row["connect_id"]:
            return envelope(code=403, msg="assist identity mismatch")
        if not isinstance(body.get("allow_control"), bool) or not isinstance(body.get("need_password"), bool):
            return envelope(code=400, msg="invalid assist policy")
        self.db.execute(
            "UPDATE assistance SET allow_control=?,need_password=? WHERE device_id=?",
            (int(body["allow_control"]), int(body["need_password"]), caller["device_id"])
        )
        return envelope({})

    async def assist_control_mode(self, req):
        self.caller(req)
        body = await self.body(req)
        row = self._assist_by_connect(body.get("connect_id"))
        if not self._assist_enabled(row):
            return envelope(code=404, msg="assist device offline or not allowed")
        return envelope({"can_remote_control": True, "control_mode": row["control_mode"]})

    def _assist_room(self, caller, row, supplied_code, session_token):
        if not self._assist_enabled(row):
            return None, 404
        if caller["device_id"] == row["device_id"]:
            return None, 400
        # Live confirmation requires push challenge/response and is deliberately
        # not represented by the static password check.
        if row["need_confirmation"]:
            return None, 1136
        if row["need_password"] or row["sign_hash"]:
            key = (caller["device_id"], row["device_id"])
            now = time.monotonic()
            attempts = [t for t in self.assist_failures.get(key, ()) if now - t < 300]
            if len(attempts) >= 6:
                return None, 429
            candidate = self._assist_hash(row["device_id"], supplied_code)
            if not ((row["sign_hash"] and hmac.compare_digest(candidate, row["sign_hash"])) or
                    (row["backup_hash"] and hmac.compare_digest(candidate, row["backup_hash"]))):
                attempts.append(now)
                self.assist_failures[key] = attempts
                return None, 403
            self.assist_failures.pop(key, None)
        self._save_recent(caller["device_id"], row["device_id"], row["connect_id"])
        room = self.new_ticket("controller", caller["device_id"], row["device_id"],
                               lifetime=600, session_token=session_token)
        room.update({
            "share_id": row["connect_id"],
            "device_name": self.device(row["device_id"])["alias"],
            "publisher_platform": self.device(row["device_id"])["platform"],
            "publisher_version_name": self.metadata(row["device_id"])["version_name"],
            "publisher_device_id": row["device_id"],
            "control_id": row["connect_id"],
        })
        return room, 0

    async def assist_join_by_code(self, req):
        caller = self.caller(req)
        body = await self.body(req)
        row = self._assist_by_connect(body.get("connect_id"))
        code = body.get("connect_code", "")
        if not isinstance(code, str) or len(code) > 256:
            return envelope(code=400, msg="invalid assist code")
        token = req.headers.get("Authorization", "")[7:]
        room, code_result = self._assist_room(caller, row, code, token)
        return envelope(room, code_result, "" if code_result == 0 else "assist join rejected")

    async def assist_join_by_confirmation(self, req):
        self.caller(req)
        await self.body(req)
        return envelope(code=501, msg="live host confirmation not implemented")

    async def assist_cancel(self, req):
        caller = self.caller(req)
        body = await self.body(req)
        row = self._assist_by_connect(body.get("connect_id"))
        if not row or row["device_id"] != caller["device_id"]:
            return envelope(code=403, msg="assist owner mismatch")
        async with self.guard:
            await self.release(row["device_id"])
        return envelope({})

    async def assist_refetch(self, req):
        caller = self.caller(req)
        body = await self.body(req)
        ticket = self.tickets.get(body.get("token", ""))
        if (ticket is None or ticket.kind != "controller"
                or ticket.device != caller["device_id"]
                or ticket.expires <= time.monotonic()):
            return envelope(code=403, msg="invalid refetch ticket")
        row = self._assist_by_device(ticket.target)
        if not row:
            return envelope(code=404, msg="assist target missing")
        # Refetch does not mint a new authorization grant.
        return envelope({
            "token": body["token"],
            "signaling_list": [self.signal_endpoint],
            "share_id": row["connect_id"],
            "publisher_platform": self.device(row["device_id"])["platform"],
            "publisher_device_id": row["device_id"],
            "publisher_version_name": self.metadata(row["device_id"])["version_name"],
            "device_name": self.device(row["device_id"])["alias"],
            "ws_connect_timeout_ms": 10000,
            "streamer_retry_delta_ms": 1000,
        })

    def _saved_rows(self, owner, kind):
        if kind == "recent":
            where = "last_connected_at IS NOT NULL"
        elif kind == "favorites":
            where = "is_favorite=1"
        else:
            return None
        rows = self.db.execute(
            "SELECT * FROM saved_assistance WHERE owner_id=? AND " + where +
            " ORDER BY COALESCE(favorited_at,last_connected_at,0) DESC LIMIT 100",
            (owner,)
        ).fetchall()
        return [dict(row) for row in rows]

    async def saved_list(self, req):
        caller = self.caller(req)
        rows = self._saved_rows(caller["device_id"], req.match_info["kind"])
        if rows is None:
            return envelope(code=400, msg="invalid saved list")
        return envelope({"saved": [{k: r[k] for k in (
            "publisher_device_id", "connect_id", "remark", "is_favorite",
            "last_connected_at", "favorited_at")} for r in rows]})

    async def save_favorites(self, req):
        caller = self.caller(req)
        body = await self.body(req)
        items = body.get("favorites")
        if not isinstance(items, list) or len(items) > 100:
            return envelope(code=400, msg="invalid favorites")
        for item in items:
            if not isinstance(item, dict):
                return envelope(code=400, msg="invalid favorite")
            row = self._assist_by_connect(item.get("connect_id"))
            remark = item.get("remark", "")
            if not row or not isinstance(remark, str) or len(remark) > 100:
                return envelope(code=400, msg="invalid favorite")
            self.db.execute("""
                INSERT INTO saved_assistance(owner_id,publisher_device_id,connect_id,remark,is_favorite,favorited_at)
                VALUES(?,?,?,?,1,?)
                ON CONFLICT(owner_id,publisher_device_id) DO UPDATE SET
                  remark=excluded.remark,is_favorite=1,favorited_at=excluded.favorited_at
            """, (caller["device_id"], row["device_id"], row["connect_id"],
                  remark, int(time.time())))
        rows = self._saved_rows(caller["device_id"], "favorites")
        return envelope({"saved": rows})

    async def delete_saved(self, req):
        caller = self.caller(req)
        kind, target = req.match_info["kind"], req.match_info["device"]
        if kind not in ("recent", "favorites") or not validated_id(target):
            return envelope(code=400, msg="invalid saved device")
        if kind == "favorites":
            self.db.execute("""
                UPDATE saved_assistance SET is_favorite=0,favorited_at=NULL
                WHERE owner_id=? AND publisher_device_id=?
            """, (caller["device_id"], target))
        else:
            self.db.execute("""
                UPDATE saved_assistance SET last_connected_at=NULL
                WHERE owner_id=? AND publisher_device_id=?
            """, (caller["device_id"], target))
        return envelope({})

    async def clear_recent(self, req):
        caller = self.caller(req)
        self.db.execute(
            "UPDATE saved_assistance SET last_connected_at=NULL WHERE owner_id=?",
            (caller["device_id"],))
        return envelope({})
