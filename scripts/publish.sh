#!/usr/bin/env bash
# Publish the SpacetimeDB module (schema + reducers) to the local dev server.
set -euo pipefail
export PATH="$HOME/.local/share/spacetime/bin/current:$PATH"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
spacetime publish builders-rpg --server dev -p "$ROOT/stdb/spacetimedb/spacetimedb" -y
