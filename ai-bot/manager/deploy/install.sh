#!/usr/bin/env bash
# One-time VPS setup (Debian/Ubuntu). Run from the bot folder as the user that will run the bot:
#   bash deploy/install.sh
set -euo pipefail
cd "$(dirname "$0")/.."
BOT_DIR="$(pwd)"

need_node=1
if command -v node >/dev/null; then
  major=$(node -p 'process.versions.node.split(".")[0]')
  [ "$major" -ge 20 ] && need_node=0
fi
if [ $need_node = 1 ]; then
  echo "==> Installing Node.js 22"
  curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
  sudo apt-get install -y nodejs
fi

echo "==> Installing npm packages"
npm ci --omit=dev || npm install --omit=dev

# Pick a model the VPS can actually run. CPU-only boxes are slow, so stay small.
ram_gb=$(awk '/MemTotal/ {printf "%d", $2/1024/1024}' /proc/meminfo)
cores=$(nproc)
if command -v nvidia-smi >/dev/null && [ "$ram_gb" -ge 16 ]; then model="qwen2.5:14b"
elif [ "$ram_gb" -ge 15 ] && [ "$cores" -ge 8 ]; then model="qwen2.5:7b"
elif [ "$ram_gb" -ge 6 ]; then model="qwen2.5:3b"
else model=""; fi
echo "==> ${ram_gb} GB RAM, ${cores} cores -> model: ${model:-none (rule-based brain only)}"

if [ -n "$model" ]; then
  if ! command -v ollama >/dev/null; then
    echo "==> Installing Ollama"
    curl -fsSL https://ollama.com/install.sh | sh
  fi
  sudo systemctl enable --now ollama 2>/dev/null || true
  ollama pull "$model"
fi

if [ ! -f config.json ]; then
  cp config.example.json config.json
  if [ -n "$model" ]; then
    sed -i "s/\"model\": \"[^\"]*\"/\"model\": \"$model\"/" config.json
  else
    sed -i 's/"enabled": true/"enabled": false/' config.json
  fi
  echo "==> Wrote config.json (edit it to add a Discord webhook, friends, etc.)"
fi
mkdir -p auth-cache data logs
chmod 700 auth-cache

echo "==> Installing systemd service 2b2t-bot"
sudo tee /etc/systemd/system/2b2t-bot.service >/dev/null <<EOF
[Unit]
Description=2b2t AI survival bot
After=network-online.target ollama.service
Wants=network-online.target

[Service]
User=$(id -un)
WorkingDirectory=$BOT_DIR
ExecStart=$(command -v node) src/index.js
Restart=always
RestartSec=30
Environment=NODE_ENV=production

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload

cat <<EOF

Done. Next steps:
  1. npm run login                 # sign in to the Microsoft account that owns TheDrVillager
  2. sudo systemctl enable --now 2b2t-bot
  3. journalctl -fu 2b2t-bot       # live log      |   npm run status   # summary
EOF
