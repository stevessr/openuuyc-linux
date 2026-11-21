"""Helpers shared by the self-host core and optional protocol extensions."""
from aiohttp import web
import re


def envelope(data=None, code=0, msg=""):
    return web.json_response({"code": code, "msg": msg, "data": data})


def validated_id(value):
    return isinstance(value, str) and re.fullmatch(r"[a-z0-9]{16}", value) is not None
