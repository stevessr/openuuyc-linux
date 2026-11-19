"""WebRTC STUN / coturn REST shared-secret ICE credential generation."""
from __future__ import annotations
import base64
import hashlib
import hmac
import secrets
import time


def ice_servers(urls, *, shared_secret="", static_user="", static_password="", ttl=3600):
    """Build one native-compatible ICE server object for each configured URL.

    TURN REST (coturn use-auth-secret) credentials are generated for each
    session. A static user/password can be configured for other TURN servers.
    STUN servers receive no credentials.
    """
    result = []
    for url in urls:
        server = {"urls": url}
        if url.startswith(("turn:", "turns:")):
            if shared_secret:
                username = f"{int(time.time()) + ttl}:{secrets.token_hex(6)}"
                digest = hmac.new(shared_secret.encode("utf-8"),
                                  username.encode("utf-8"), hashlib.sha1).digest()
                server.update(username=username,
                              credential=base64.b64encode(digest).decode("ascii"))
            elif static_user and static_password:
                server.update(username=static_user, credential=static_password)
        result.append(server)
    return result
