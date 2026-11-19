"""Self-hosted wallpaper grant, upload, publication, and static retrieval.

File URL tokens are random and unguessable. Upload grants are bound to their
enrolled owner and may be used only once. Server stores images locally.
"""
from __future__ import annotations

import hashlib
import hmac
import os
from pathlib import Path
import secrets
import time
from aiohttp import web

from .server_utils import envelope

MAX_WALLPAPER = 10 * 1024 * 1024


def image_mime(image: bytes) -> str | None:
    if image.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if image.startswith(b"\xff\xd8\xff") and image.endswith(b"\xff\xd9"):
        return "image/jpeg"
    if image.startswith(b"RIFF") and image[8:12] == b"WEBP":
        return "image/webp"
    return None


class LocalMedia:
    def init_media(self, database):
        self.media_path = Path(database).resolve().parent / "wallpaper"
        self.media_path.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.media_path, 0o700)
        self.upload_grants = {}
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS wallpaper_files(
              file_id TEXT PRIMARY KEY, device_id TEXT NOT NULL,
              size INTEGER NOT NULL, mime TEXT NOT NULL, md5 TEXT NOT NULL,
              sha256 TEXT NOT NULL, uploaded_at INTEGER NOT NULL
            );
        """)

    def media_routes(self):
        return [
            web.get("/api/v1/tool/fp/token", self.wallpaper_grant),
            web.post("/api/v1/device/wallpaper", self.bind_wallpaper),
            web.post("/filepicker/upload/{grant}", self.upload_wallpaper),
            web.get("/media/wallpaper/{file_id}", self.download_wallpaper),
        ]

    async def wallpaper_grant(self, req):
        caller = self.caller(req)
        if req.query.get("filetype") != "1":
            return envelope(code=400, msg="unsupported file type")
        now = time.monotonic()
        self.upload_grants = {
            key: entry for key, entry in self.upload_grants.items()
            if entry[2] > now
        }
        if len(self.upload_grants) > 2048:
            raise web.HTTPServiceUnavailable(text="file grant capacity exceeded")
        ticket = secrets.token_urlsafe(32)
        bearer = secrets.token_urlsafe(40)
        self.upload_grants[ticket] = (caller["device_id"], bearer, now + 120)
        return envelope({
            "token": bearer, "req_url": self.public_url + "/filepicker/upload/" + ticket,
            "ttl": 120, "max_size": MAX_WALLPAPER + 1
        })

    async def upload_wallpaper(self, req):
        ticket = req.match_info["grant"]
        entry = self.upload_grants.get(ticket)
        supplied = req.headers.get("Authorization", "")
        if not entry or entry[2] <= time.monotonic() or not hmac.compare_digest(supplied, entry[1]):
            raise web.HTTPUnauthorized(text="invalid file grant")
        if req.content_length and req.content_length > MAX_WALLPAPER:
            raise web.HTTPRequestEntityTooLarge(max_size=MAX_WALLPAPER,
                                                 actual_size=req.content_length)
        image = await req.content.read(MAX_WALLPAPER + 1)
        if not image or len(image) > MAX_WALLPAPER:
            raise web.HTTPRequestEntityTooLarge(max_size=MAX_WALLPAPER,
                                                 actual_size=len(image))
        mime = image_mime(image)
        if not mime:
            raise web.HTTPUnsupportedMediaType(text="unsupported wallpaper encoding")
        # Claim before writing; concurrent replay cannot duplicate a successful grant.
        self.upload_grants.pop(ticket, None)
        file_id = secrets.token_urlsafe(28)
        path = self.media_path / file_id
        try:
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as output:
                output.write(image)
            md5 = hashlib.md5(image, usedforsecurity=False).hexdigest()
            sha256 = hashlib.sha256(image).hexdigest()
            self.db.execute(
                """INSERT INTO wallpaper_files(file_id,device_id,size,mime,md5,sha256,uploaded_at)
                   VALUES(?,?,?,?,?,?,?)""",
                (file_id, entry[0], len(image), mime, md5, sha256, int(time.time()))
            )
        except Exception:
            path.unlink(missing_ok=True)
            raise
        return web.json_response({
            "url": self.public_url + "/media/wallpaper/" + file_id,
            "fsize": len(image), "md5": md5, "mime": mime,
        }, headers={"Cache-Control": "no-store"})

    async def download_wallpaper(self, req):
        file_id = req.match_info["file_id"]
        row = self.db.execute("SELECT * FROM wallpaper_files WHERE file_id=?",
                              (file_id,)).fetchone()
        if row is None:
            raise web.HTTPNotFound()
        return web.FileResponse(self.media_path / file_id,
                                headers={"Content-Type": row["mime"],
                                         "Cache-Control": "no-store",
                                         "X-Content-Type-Options": "nosniff"})

    async def bind_wallpaper(self, req):
        owner = self.caller(req)
        body = await self.body(req)
        url = body.get("wallpaper_url")
        prefix = self.public_url + "/media/wallpaper/"
        if not isinstance(url, str) or not url.startswith(prefix):
            return envelope(code=403, msg="wallpaper must belong to this server")
        file_id = url[len(prefix):]
        row = self.db.execute(
            "SELECT device_id FROM wallpaper_files WHERE file_id=?", (file_id,)
        ).fetchone()
        if not row or row["device_id"] != owner["device_id"]:
            return envelope(code=403, msg="wallpaper upload owner mismatch")
        self.update_registered(owner["device_id"])
        self.db.execute(
            "UPDATE device_properties SET wallpaper_url=? WHERE device_id=?",
            (url, owner["device_id"]))
        return envelope({})
