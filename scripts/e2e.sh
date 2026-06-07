#!/usr/bin/env bash
# Headless full-loop test (no Minecraft client): spawn -> recruit -> accept -> complete,
# asserting each step in SpacetimeDB. Requires the Paper server (:25566) and worker running.
set -uo pipefail
BASE=http://127.0.0.1:3050; DB=builders-rpg
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
sqlq(){ curl -s -X POST "$BASE/v1/database/$DB/sql" -H "Content-Type: text/plain" --data "$1"; }
callr(){ curl -s -X POST "$BASE/v1/database/$DB/call/$1" -H "Content-Type: application/json" -d "$2" -o /dev/null -w "%{http_code}"; }

echo "spawn 4 builders:"; node "$ROOT/server/rcon.mjs" "builders spawn 4"
sleep 2
echo "register TestHero -> $(callr register_player '["e2e","TestHero","med"]')"
callr update_player_pos '["e2e","TestHero",5.0,64.0,5.0,"world","med"]' >/dev/null

echo "waiting for planner offer..."
q="[]"
for i in $(seq 1 20); do
  q=$(sqlq "SELECT id, builder_id, advancement_id FROM quest WHERE player_uuid='e2e' AND state='offered'" | jq -c '.[0].rows')
  { [ -n "$q" ] && [ "$q" != "[]" ]; } && break; sleep 2
done
echo "offered: $q"
bid=$(echo "$q" | jq -r '.[0][1]'); adv=$(echo "$q" | jq -r '.[0][2]')
echo "approach_player consumed: $(sqlq "SELECT consumed FROM story_directive WHERE kind='approach_player'" | jq -c '.[0].rows')"

echo "accept -> $(callr join_party "[\"e2e\",$bid]")"
sleep 1; echo "quest after accept: $(sqlq "SELECT state FROM quest WHERE player_uuid='e2e'" | jq -c '.[0].rows')  parties: $(sqlq 'SELECT COUNT(*) AS n FROM party' | jq -c '.[0].rows')"
echo "complete -> $(callr set_advancement "[\"e2e\",\"$adv\",true]")"
sleep 1
echo "quest after complete: $(sqlq "SELECT state FROM quest WHERE player_uuid='e2e'" | jq -c '.[0].rows')  parties: $(sqlq 'SELECT COUNT(*) AS n FROM party' | jq -c '.[0].rows')"
echo "memory: $(sqlq 'SELECT text FROM builder_memory' | jq -c '.[0].rows')"
