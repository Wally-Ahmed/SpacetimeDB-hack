#!/usr/bin/env bash
# Convenience launcher: publish the module, then start worker + server + dashboard.
# Ctrl+C stops them all. (Assumes the local SpacetimeDB `dev` server is running:
#   spacetime start   — if it isn't.)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "[1/4] publishing module..."; bash "$ROOT/scripts/publish.sh"
echo "[2/4] starting worker..."; ( cd "$ROOT/worker" && npm start ) &
echo "[3/4] starting Minecraft server (:25566)..."; ( bash "$ROOT/scripts/server.sh" ) &
echo "[4/4] starting dashboard (:5173)..."; ( cd "$ROOT/dashboard" && npm run dev ) &

echo
echo "  Dashboard : http://localhost:5173"
echo "  Minecraft : localhost:25566  (Java 1.21.10 client, offline mode)"
echo "  Ctrl+C to stop everything."
trap 'kill 0' INT TERM
wait
