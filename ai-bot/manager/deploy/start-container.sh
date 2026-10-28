#!/usr/bin/env bash
# For machines without systemd (e.g. a Docker container): starts Ollama and the bot in the
# background, each restarting itself if it dies. Safe to run again; skips what's already running.
#   bash deploy/start-container.sh          start
#   bash deploy/start-container.sh stop     stop the bot (Ollama keeps running)
#   pkill -xf "node src/index.js"           restart just the bot (the supervisor brings it back in 30s)
cd "$(dirname "$0")/.."
BOT_DIR="$(pwd)"
mkdir -p logs
export OLLAMA_MODELS="${OLLAMA_MODELS:-$BOT_DIR/../ollama-models}" OLLAMA_HOST=127.0.0.1:11434 OLLAMA_KEEP_ALIVE=24h

if [ "$1" = stop ]; then
  pkill -f "2b2t-bot-supervisor" && echo "bot supervisor stopped"
  pkill -xf "node src/index.js" && echo "bot stopped"
  exit 0
fi

if ! pgrep -x ollama >/dev/null; then
  setsid nohup ollama serve >> logs/ollama.log 2>&1 < /dev/null &
  echo "ollama started"
fi

if pgrep -f "2b2t-bot-supervisor" >/dev/null; then
  echo "bot already running"
else
  setsid nohup bash -c 'exec -a 2b2t-bot-supervisor bash -c "while true; do node src/index.js; echo \"[supervisor] bot exited, restarting in 30s\"; sleep 30; done"' \
    >> logs/console.log 2>&1 < /dev/null &
  echo "bot started (log: $BOT_DIR/logs/bot.log)"
fi
