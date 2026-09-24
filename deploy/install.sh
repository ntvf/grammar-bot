#!/bin/bash
# One-time setup of the grammar bot (@RewrytBot) on the production host. Safe to re-run.
#   sudo bash install.sh
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "Run with sudo"; exit 1; }

HERE=$(cd "$(dirname "$0")" && pwd)
APP=/opt/grammar
ENV_FILE=$APP/env
REMINDER_ENV=/opt/reminder/env

echo "== User and directories"
id grammar &>/dev/null || useradd --system --no-create-home --shell /usr/sbin/nologin grammar
install -d -m 755 "$APP" /var/log/grammar
install -m 755 "$HERE/run.sh" "$HERE/update.sh" "$HERE/set-secret.sh" "$APP/"

reminder_value() { (set -a; . "$REMINDER_ENV"; set +a; printf '%s' "${!1:-}"); }

if [ ! -f "$ENV_FILE" ]; then
  echo "== Database"
  DB_PASSWORD=$(openssl rand -hex 24)
  sudo -u postgres psql -q -v ON_ERROR_STOP=1 <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'grammar') THEN
    CREATE ROLE grammar LOGIN PASSWORD '$DB_PASSWORD';
  ELSE
    ALTER ROLE grammar PASSWORD '$DB_PASSWORD';
  END IF;
END \$\$;
SQL
  sudo -u postgres psql -Atc "SELECT 1 FROM pg_database WHERE datname = 'grammar'" | grep -q 1 \
    || sudo -u postgres createdb -O grammar grammar

  echo "== Environment (OpenAI key copied from the reminder bot)"
  install -m 600 -o grammar -g grammar /dev/null "$ENV_FILE"
  cat > "$ENV_FILE" <<ENV
DB_HOST=$(reminder_value DB_HOST)
DB_PORT=$(reminder_value DB_PORT)
DB_NAME=grammar
DB_USER=grammar
DB_PASSWORD=$DB_PASSWORD
OPENAI_API_KEY=$(reminder_value OPENAI_API_KEY)
TELEGRAM_BOT_TOKEN=
TELEGRAM_BOT_USERNAME=
ADMIN_CHAT_IDS=$(reminder_value ADMIN_CHAT_IDS)
ENV
fi

if ! grep -q '^TELEGRAM_BOT_TOKEN=.\+' "$ENV_FILE"; then
  echo "== Telegram token for @RewrytBot (from @BotFather; input is hidden)"
  "$APP/set-secret.sh" TELEGRAM_BOT_TOKEN
fi
if ! grep -q '^ADMIN_CHAT_IDS=.\+' "$ENV_FILE"; then
  read -rp "Your Telegram user id for /stats (from @userinfobot, Enter to skip): " ADMIN
  [ -n "$ADMIN" ] && "$APP/set-secret.sh" ADMIN_CHAT_IDS "$ADMIN" >/dev/null
fi

echo "== Download latest release"
"$APP/update.sh"
cat "$APP/version"

echo "== Auto-update timer"
install -m 644 "$HERE/grammar-update.service" "$HERE/grammar-update.timer" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now grammar-update.timer

echo "== Start under immortal"
install -m 644 "$HERE/grammar.yml" /etc/immortal/grammar.yml

for _ in $(seq 1 45); do
  if grep -q "Started GrammarApplication" /var/log/grammar/app.log 2>/dev/null; then
    echo "✅ @RewrytBot is running. Logs: /var/log/grammar/app.log"
    exit 0
  fi
  sleep 2
done
echo "⚠️  Not started yet — check: tail -50 /var/log/grammar/app.log"
