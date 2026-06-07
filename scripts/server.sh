#!/usr/bin/env bash
# Launch the Paper 1.21.10 server (Java 21).
set -euo pipefail
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
cd "$(dirname "$0")/../server"
JAR="$(ls paper-*.jar 2>/dev/null | head -1)"
[ -z "$JAR" ] && { echo "No paper jar — run scripts/fetch-deps.sh first."; exit 1; }
exec "$JAVA_HOME/bin/java" -Xms1G -Xmx2G -jar "$JAR" --nogui
