# OpenUUYC self-hosted compatibility server (experimental)

A standalone, opt-in **NRD-style REST control plane and Engine.IO v4 / Socket.IO v4 WebSocket signaling server** for OpenUUYC Linux hosts and PICO Android controllers. The existing official UU path remains the default. Self-hosted enrollment is completely separate from official UU accounts.

## Implemented

- Device registration and stable 16-character device IDs, local administrator-approved QR login, persistent SQLite device/session records.
- Token-authenticated device list, controllable state, create host room and join a specific host (with explicit force takeover).
- Engine.IO v4 / Socket.IO v4 namespace and heartbeat, room_info and control ACK, binary `be-controlled` forwarding, gzip SDP / ICE candidate `soac` relay, `forward_setting`, release/leave, reconnect-key ACK.
- Direct peer-to-peer WebRTC: this server does not see or relay video/audio/input payloads.

The wire-level Python tests exercise this subset, but **physical-host/PICO WebRTC interoperability has not yet been validated**. No production compatibility claim is made.

## Deploy

```sh
cd selfhost
cp .env.example .env
# Set OPENUUYC_PUBLIC_URL to a real HTTPS hostname.
# Generate a strong OPENUUYC_ADMIN_KEY and set ICE/STUN/TURN URLs.
docker compose up --build -d
```

The container listens on localhost port 8844 at the host. Place Caddy/nginx in front of it for trusted TLS and WebSocket upgrade:

```caddyfile
uu.example.com {
    reverse_proxy 127.0.0.1:8844
}
```

No insecure HTTP fallback is supported in the clients. A self-signed certificate must be trusted by the host and PICO if using internal PKI. Configure a reachable STUN server for direct ICE. The minimal `OPENUUYC_ICE_SERVERS` setting accepts comma-separated STUN/TURN URIs **without username/password**; authenticated TURN relay is not implemented, so direct ICE may fail behind restrictive NATs.

## Connect Linux host

Use the environment variable **for every OpenUUYC process** that should connect to the self-host service:

```sh
OPENUUYC_NRD_BASE_URL=https://uu.example.com OpenUUYC login
OPENUUYC_NRD_BASE_URL=https://uu.example.com OpenUUYC
```

Open the QR's `https://uu.example.com/enroll/...` link in a browser and enter the server administrator key. The URL alone does not authorize login. Every machine enrolls separately.

**Account isolation:** the application credential storage is shared between official and self-host modes. Do not change the backend for an existing logged-in instance. Use a fresh OS user/profile for testing or clear the app's saved credentials and identity first. An official UU token will not work on the self-host backend.

## Build PICO APK

```sh
cd pico-android
./gradlew assembleDebug -PopenuuycNrdBase=https://uu.example.com
```

Install the resulting APK on PICO, enroll using the self-host QR approval page, and select a Linux host enrolled against the *same* server. If the Gradle property is absent, the APK retains the official NRD endpoint. Build with a separate app data profile/application ID when switching backends, to avoid reusing cached official identity or login data.

## Test

From repository root:

```sh
python3 -m pip install -r selfhost/requirements.txt
python3 -m unittest discover -s selfhost -p 'test_*.py' -v
```

The tests use local HTTP/WebSocket connections and do not contact UU, STUN, or physical devices.

## Limits and security

- Experimental single-owner model: **every enrolled device belongs to one trusted administrator account**; an explicitly authorized controller may request forced takeover. Do not treat it as multi-tenant authorization.
- No official UU cloud account/SMS, guest sharing, remote mobile share-consent RPC, proprietary cloud transport, TURN allocation/credentials, or replay of sessions across server restarts. Room tickets are in-memory and expire; hosts must reconnect/re-create rooms after restart.
- SQLite holds bearer sessions: restrict filesystem permissions and backups. QR tickets expire after five minutes and are single-use.
- The client-side NRD request-signing constant is public and is **not** used as access control. REST bearer tokens and room tickets govern access.
- Bind the server to loopback behind an HTTPS reverse proxy. Enable reverse-proxy rate limits and access controls. No service-side rate limiting or comprehensive audit logging is implemented.
- The `/healthz` path checks process health only, not WebRTC readiness.

**Acceptance still required:** one physical Linux host and PICO 4 Ultra, keyboard/mouse, audio, screen capture, ICE connectivity across networks, reconnect and security checks.