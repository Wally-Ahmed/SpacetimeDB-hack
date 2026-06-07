#!/usr/bin/env bash
# Download the Minecraft server binaries (Paper + Citizens) into server/.
set -euo pipefail
cd "$(dirname "$0")/../server"
echo "Fetching Paper 1.21.10 #129..."
curl -fsSL -o paper-1.21.10-129.jar \
  "https://api.papermc.io/v2/projects/paper/versions/1.21.10/builds/129/downloads/paper-1.21.10-129.jar"
mkdir -p plugins
echo "Fetching Citizens 2.0.42 b4187..."
curl -fsSL -o plugins/Citizens-2.0.42-b4187.jar \
  "https://ci.citizensnpcs.co/job/Citizens2/lastSuccessfulBuild/artifact/dist/target/Citizens-2.0.42-b4187.jar"
echo "Done. (eula.txt + server.properties are already in server/.)"
