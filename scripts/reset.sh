#!/usr/bin/env bash
# Wipe all runtime data and re-seed (re-publish with --delete-data).
set -euo pipefail
export PATH="$HOME/.local/share/spacetime/bin/current:$PATH"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
spacetime publish builders-rpg --server dev -p "$ROOT/stdb/spacetimedb/spacetimedb" --delete-data=always -y
echo "Wiped + re-seeded builders-rpg."
