#!/usr/bin/env bash
# Packages a bot host needs (Debian/Ubuntu): Node 22+, a virtual display for the headless Minecraft
# clients, and the libraries LWJGL loads. Java isn't needed: the launcher downloads Mojang's runtime.
# Run as root (or with sudo). Ollama is separate: https://ollama.com/download
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates xvfb xauth libgl1 libgl1-mesa-dri libegl1 \
  libx11-6 libxext6 libxrender1 libxtst6 libxi6 libxrandr2 libxcursor1 libxxf86vm1 libxinerama1 \
  libfreetype6 libasound2t64 2>/dev/null || apt-get install -y -qq curl ca-certificates xvfb xauth libgl1 \
  libgl1-mesa-dri libegl1 libx11-6 libxext6 libxrender1 libxtst6 libxi6 libxrandr2 libxcursor1 libxxf86vm1 \
  libxinerama1 libfreetype6 libasound2
if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 22 ]; then
  apt-get install -y -qq nodejs npm
fi
node -v
echo "Done. Next: cd ai-bot/manager && npm ci && cp manager.example.json manager.json (then edit it)"
