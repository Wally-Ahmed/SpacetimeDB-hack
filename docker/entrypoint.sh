#!/usr/bin/env bash
# Boot the three services in order: STDB -> publish module -> worker -> Paper (foreground).
set -euo pipefail
export HOME=/root
export PATH="/usr/local/bin:$PATH"

echo "[entry] starting SpacetimeDB on 127.0.0.1:3050…"
spacetime start --listen-addr 127.0.0.1:3050 &

echo "[entry] waiting for STDB to answer /v1/ping…"
for i in $(seq 1 90); do
  if curl -sf http://127.0.0.1:3050/v1/ping >/dev/null 2>&1; then echo "[entry] STDB up after ${i}s"; break; fi
  sleep 1
  if [ "$i" -eq 90 ]; then echo "[entry] FATAL: STDB never came up"; exit 1; fi
done

echo "[entry] registering :3050 server (nickname 'prod') + publishing builders-rpg…"
# NB: the CLI ships a built-in 'local' alias pointing at :3000 — use a fresh name
# so publish targets our :3050 standalone, not the default :3000.
spacetime server add --url http://127.0.0.1:3050 prod --default --no-fingerprint || true
spacetime publish builders-rpg --server prod --anonymous -y -p /app/stdb/spacetimedb/spacetimedb

echo "[entry] starting worker (the brain)…"
( cd /app/worker \
  && STDB_URI="ws://127.0.0.1:3050" STDB_DB="builders-rpg" npm start 2>&1 | sed 's/^/[worker] /' ) &

echo "[entry] configuring Paper for public online-mode play on :25565…"
cd /app/server
sed -i -E 's/^online-mode=.*/online-mode=true/'   server.properties || true
sed -i -E 's/^server-port=.*/server-port=25565/'  server.properties || true
sed -i -E 's/^server-ip=.*/server-ip=/'           server.properties || true
sed -i -E 's/^gamemode=.*/gamemode=survival/'     server.properties || true
grep -q '^online-mode=' server.properties || echo 'online-mode=true' >> server.properties
echo 'eula=true' > eula.txt

echo "[entry] launching Paper (foreground)…"
exec java -Xms1G -Xmx2G -jar paper-1.21.10-129.jar --nogui
