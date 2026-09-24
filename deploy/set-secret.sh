#!/bin/bash
# Sets one value in /opt/grammar/env and restarts the bot.
#   sudo /opt/grammar/set-secret.sh TELEGRAM_BOT_TOKEN      (prompts, input hidden; also sets the username)
#   sudo /opt/grammar/set-secret.sh ADMIN_CHAT_IDS 12345
set -euo pipefail
ENV_FILE=/opt/grammar/env
[ "$(id -u)" -eq 0 ] || { echo "Run with sudo"; exit 1; }
KEY=${1:?Usage: set-secret.sh KEY [VALUE]}
VALUE=${2:-}
if [ -z "$VALUE" ]; then
  read -rsp "$KEY: " VALUE; echo
fi
[ -n "$VALUE" ] || { echo "Empty value, nothing changed"; exit 1; }

put() {
  local k=$1 v=$2 tmp
  tmp=$(mktemp)
  grep -v "^$k=" "$ENV_FILE" > "$tmp" || true
  printf '%s=%s\n' "$k" "$v" >> "$tmp"
  install -m 600 -o grammar -g grammar "$tmp" "$ENV_FILE"
  rm -f "$tmp"
}

if [ "$KEY" = "TELEGRAM_BOT_TOKEN" ]; then
  ME=$(curl -s "https://api.telegram.org/bot$VALUE/getMe")
  USERNAME=$(python3 -c "import sys, json; d=json.load(sys.stdin); print(d['result']['username'] if d.get('ok') else '')" <<<"$ME")
  [ -n "$USERNAME" ] || { echo "Telegram rejected this token: $ME"; exit 1; }
  put TELEGRAM_BOT_USERNAME "$USERNAME"
  echo "Token belongs to @$USERNAME"
fi
put "$KEY" "$VALUE"
echo "$KEY updated"

if pkill -f -- '-jar grammar\.jar$'; then echo "Bot restarting…"; fi
