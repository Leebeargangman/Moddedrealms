#!/usr/bin/env bash
# For hosts without systemd (e.g. a Docker container): keeps Ollama and the bot manager running in the
# background, each restarted if it dies. Safe to run again; skips what's already running.
#   bash deploy/start-container.sh          start
#   bash deploy/start-container.sh stop     stop the manager and its bots (Ollama keeps running)
cd "$(dirname "$0")/.."
BOT_DIR="$(pwd)"
mkdir -p logs
# Set OLLAMA_MODELS before running to keep models somewhere else (e.g. /workspace/ollama-models).
export OLLAMA_HOST=127.0.0.1:11434 OLLAMA_KEEP_ALIVE=24h

if [ "$1" = stop ]; then
  pkill -f "ai-bot-supervisor" && echo "supervisor stopped"
  pkill -TERM -xf "node src/manager.js" && echo "manager stopping (its bots log off)"
  exit 0
fi

if command -v ollama >/dev/null && ! pgrep -x ollama >/dev/null; then
  setsid nohup ollama serve >> logs/ollama.log 2>&1 < /dev/null &
  echo "ollama started"
fi

if pgrep -f "ai-bot-supervisor" >/dev/null; then
  echo "manager already running"
else
  setsid nohup bash -c 'exec -a ai-bot-supervisor bash -c "while true; do node src/manager.js; echo \"[supervisor] manager exited, restarting in 30s\"; sleep 30; done"' \
    >> logs/console.log 2>&1 < /dev/null &
  echo "manager started (log: $BOT_DIR/logs/manager.log)"
fi
