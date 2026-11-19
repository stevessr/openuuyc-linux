"""Live, host-authorized remote-assistance challenge/response protocol.

This mirrors the protocol consumed by src/features/host/assist.rs:
  get_control_mode -> upload_control_mode,
  remote_control(salt) -> upload_sign(SHA256(salt||password)),
  remote_control_by_confirmation -> confirmation.

The server never stores plaintext assistance passwords. Every challenge belongs
to an online host room, a specific viewer and an expiring nonce.
"""
from __future__ import annotations

import asyncio
from dataclasses import dataclass
import hashlib
import hmac
import secrets
import time

from .server_utils import envelope


@dataclass
class Challenge:
    host: str
    controller: str
    stage: str
    expires: float
    future: asyncio.Future


class LiveAssistance:
    def init_live_assist(self):
        self.assist_challenges = {}
        self.assist_verified = {}

    def _host_for_assistance(self, row):
        if row is None or self.device(row["device_id"]) is None:
            return None
        host = self.hosts.get(row["device_id"])
        return host if host is not None and host.active and not host.socket.closed else None

    async def _challenge(self, row, caller, kind, stage, fields=None, timeout=15, request_id=None):
        host = self._host_for_assistance(row)
        if host is None:
            return None
        if len(self.assist_challenges) >= 256:
            now = time.monotonic()
            self.assist_challenges = {
                k: v for k, v in self.assist_challenges.items()
                if v.expires > now and not v.future.done()
            }
            if len(self.assist_challenges) >= 256:
                return None
        control_id = request_id or secrets.token_hex(16)
        future = asyncio.get_running_loop().create_future()
        challenge = Challenge(row["device_id"], caller["device_id"], stage,
                              time.monotonic() + timeout, future)
        if control_id in self.assist_challenges:
            return None
        self.assist_challenges[control_id] = challenge
        payload = {"control_id": control_id}
        payload.update(fields or {})
        try:
            await host.event("bmsg_push", {"type": kind, "data": payload})
            result = await asyncio.wait_for(future, timeout)
            return control_id, result
        except (asyncio.TimeoutError, ConnectionError):
            return None
        finally:
            if self.assist_challenges.get(control_id) is challenge:
                self.assist_challenges.pop(control_id, None)

    async def _upload_challenge(self, req, expected_stage, fields):
        caller = self.host_caller(req)
        body = await self.body(req)
        control_id = body.get("control_id")
        if not isinstance(control_id, str) or len(control_id) > 128:
            return envelope(code=400, msg="invalid control identifier")
        pending = self.assist_challenges.get(control_id)
        if (not pending or pending.host != caller["device_id"] or
            pending.stage != expected_stage or pending.expires < time.monotonic() or
            pending.future.done() or not all(isinstance(body.get(key), klass) for key, klass in fields.items())):
            return envelope(code=403, msg="no matching remote-assistance challenge")
        pending.future.set_result(body)
        return envelope({})

    async def live_upload_mode(self, req):
        return await self._upload_challenge(
            req, "mode", {"allow_control": bool, "control_mode": str})

    async def live_upload_sign(self, req):
        return await self._upload_challenge(
            req, "verify", {"can_remote_control": bool, "sign": str,
                             "control_mode": str, "need_confirmation": bool})

    async def live_upload_confirmation(self, req):
        return await self._upload_challenge(
            req, "confirm", {"allow_control": bool, "need_password": bool})

    async def live_control_mode(self, req):
        caller = self.caller(req)
        body = await self.body(req)
        row = self._assist_by_connect(body.get("connect_id"))
        if not row or not self._host_for_assistance(row):
            return envelope(code=404, msg="assistance host not found")
        challenge = await self._challenge(row, caller, "get_control_mode", "mode",
                                          timeout=12)
        if not challenge:
            return envelope(code=504, msg="host did not answer assistance mode")
        _, response = challenge
        enabled = response["allow_control"]
        mode = response["control_mode"]
        if mode not in ("by_password", "by_confirmation", "password_confirmation"):
            return envelope(code=400, msg="invalid host assistance mode")
        # Persist only public policy, never password verifiers.
        self.db.execute(
            "UPDATE assistance SET allow_control=?,control_mode=?,need_confirmation=?,need_password=?"
            " WHERE device_id=?",
            (int(enabled), mode, int(mode != "by_password"),
             int(mode != "by_confirmation"), row["device_id"]))
        return envelope({"can_remote_control": enabled, "control_mode": mode})

    def _assistance_rate(self, caller, host_id, success):
        key = (caller["device_id"], host_id)
        now = time.monotonic()
        failures = [t for t in self.assist_failures.get(key, ()) if now - t < 300]
        if success:
            self.assist_failures.pop(key, None)
            return True
        failures.append(now)
        self.assist_failures[key] = failures
        return len(failures) <= 6

    def _assistance_locked(self, caller, host_id):
        key = (caller["device_id"], host_id)
        now = time.monotonic()
        return sum(now - t < 300 for t in self.assist_failures.get(key, ())) >= 6

    def _issue_assistance_room(self, caller, row, token):
        self._save_recent(caller["device_id"], row["device_id"], row["connect_id"])
        room = self.new_ticket("controller", caller["device_id"], row["device_id"],
                               lifetime=600, session_token=token)
        device = self.device(row["device_id"])
        room.update({
            "share_id": row["connect_id"],
            "device_name": device["alias"],
            "publisher_platform": device["platform"],
            "publisher_version_name": self.metadata(row["device_id"])["version_name"],
            "publisher_device_id": row["device_id"],
            "control_id": row["connect_id"]
        })
        return room

    async def live_join_code(self, req):
        caller = self.caller(req)
        body = await self.body(req)
        row = self._assist_by_connect(body.get("connect_id"))
        code = body.get("connect_code")
        if not row or not self._host_for_assistance(row):
            return envelope(code=404, msg="assistance host unavailable")
        if caller["device_id"] == row["device_id"]:
            return envelope(code=400, msg="cannot control the same device")
        if not isinstance(code, str) or not (1 <= len(code) <= 256):
            return envelope(code=1133, msg="code required")
        if self._assistance_locked(caller, row["device_id"]):
            return envelope(code=429, msg="assistance temporarily rate limited")
        salt = secrets.token_hex(16)
        challenge = await self._challenge(row, caller, "remote_control", "verify",
                                          {"salt": salt}, timeout=15)
        if not challenge:
            return envelope(code=504, msg="host did not answer assistance verification")
        control_id, response = challenge
        if not response["can_remote_control"]:
            return envelope(code=403, msg="host assistance disabled")
        mode = response["control_mode"]
        if mode not in ("by_password", "password_confirmation"):
            return envelope(code=1133, msg="host does not allow password verification")
        sign = hashlib.sha256((salt + code).encode("utf-8")).hexdigest()
        valid_signs = [s for s in (response.get("sign"), response.get("backup_sign"))
                       if isinstance(s, str) and len(s) == 64]
        if not any(hmac.compare_digest(sign, s) for s in valid_signs):
            self._assistance_rate(caller, row["device_id"], False)
            return envelope(code=1131, msg="invalid assistance code")
        self._assistance_rate(caller, row["device_id"], True)
        if mode == "password_confirmation" or response["need_confirmation"]:
            # Preserve the validated password only for a single subsequent
            # explicit host-confirmation POST and at most 60 seconds.
            self.assist_verified[control_id] = (
                row["device_id"], caller["device_id"], time.monotonic() + 60)
            return envelope({"control_id": control_id}, code=1136,
                            msg="waiting for local host confirmation")
        token = req.headers["Authorization"][7:]
        return envelope(self._issue_assistance_room(caller, row, token))

    async def live_join_confirmation(self, req):
        caller = self.caller(req)
        body = await self.body(req)
        row = self._assist_by_connect(body.get("connect_id"))
        if not row or not self._host_for_assistance(row):
            return envelope(code=404, msg="assistance host unavailable")
        if caller["device_id"] == row["device_id"]:
            return envelope(code=400, msg="cannot control the same device")
        requested = body.get("control_id", "")
        if not isinstance(requested, str) or len(requested) > 128:
            return envelope(code=400, msg="invalid control ID")
        if requested:
            pending = self.assist_verified.pop(requested, None)
            if not pending or pending[0] != row["device_id"] or pending[1] != caller["device_id"] or pending[2] < time.monotonic():
                return envelope(code=403, msg="confirmation not authorized by verified code")
            control_id = requested
        else:
            # No-code confirmation is valid only in the currently published
            # mode. Ask the live host again before opening its UI prompt.
            current = await self._challenge(row, caller, "get_control_mode", "mode",
                                            timeout=12)
            if not current or not current[1]["allow_control"] or current[1]["control_mode"] != "by_confirmation":
                return envelope(code=403, msg="host requires a password")
            control_id = secrets.token_hex(16)
        alias = caller["alias"][:100]
        response = await self._challenge(
            row, caller, "remote_control_by_confirmation", "confirm",
            {"name": alias}, timeout=65, request_id=control_id)
        if not response or not response[1]["allow_control"]:
            return envelope(code=403, msg="host did not approve remote assistance")
        token = req.headers["Authorization"][7:]
        return envelope(self._issue_assistance_room(caller, row, token))

    async def live_cancel(self, req):
        caller = self.caller(req)
        body = await self.body(req)
        row = self._assist_by_connect(body.get("connect_id"))
        if row:
            target = row["device_id"]
            active = [key for key, challenge in self.assist_challenges.items()
                      if challenge.host == target and challenge.controller == caller["device_id"]]
            host = self._host_for_assistance(row)
            for key in active:
                challenge = self.assist_challenges.pop(key, None)
                if challenge is not None and not challenge.future.done():
                    challenge.future.cancel()
                if host:
                    await host.event("bmsg_push",
                                     {"type": "remote_control_by_confirmation_cancel",
                                      "data": {"control_id": key}})
            for key, pending in list(self.assist_verified.items()):
                if pending[0] == target and pending[1] == caller["device_id"]:
                    self.assist_verified.pop(key, None)
        return envelope({})
