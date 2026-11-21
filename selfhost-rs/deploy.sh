#!/usr/bin/env bash
# One-command deployment for the distributed self-host server.
#   ./deploy.sh            minimal (redis + 2 nodes, TLS handled upstream)
#   ./deploy.sh keycloak   + Keycloak IdP (for OIDC enrollment login)
#   ./deploy.sh proxy      + Caddy automatic HTTPS
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
    echo "[deploy] creating .env from .env.example"
    cp .env.example .env
    KEY=$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))' 2>/dev/null || openssl rand -base64 36 | tr '+/' '-_' | tr -d '=')
    sed -i "s|REPLACE_WITH_32PLUS_CHARS|$KEY|" .env
    echo "[deploy] generated OPENUUYC_ADMIN_KEY into .env"
    echo "[deploy] edit .env: set OPENUUYC_PUBLIC_URL (and ICE/TURN/Keycloak as needed)"
fi

set -a; . ./.env; set +a
PROFILE_ARGS=()
for p in "$@"; do PROFILE_ARGS+=("--profile" "$p"); done

echo "[deploy] building + starting (profiles: ${*:-minimal})"
docker compose "${PROFILE_ARGS[@]}" up --build -d

echo "[deploy] waiting for health..."
for i in $(seq 1 30); do
    if curl -sf "http://127.0.0.1:8844/healthz" >/dev/null 2>&1 && \
       curl -sf "http://127.0.0.1:8845/healthz" >/dev/null 2>&1; then
        echo "[deploy] node-a: http://127.0.0.1:8844/healthz OK"
        echo "[deploy] node-b: http://127.0.0.1:8845/healthz OK"
        echo "[deploy] done. Point OPENUUYC_NRD_BASE_URL=$OPENUUYC_PUBLIC_URL at your clients."
        exit 0
    fi
    sleep 2
done
echo "[deploy] nodes did not become healthy in 60s; check: docker compose logs"
exit 1
