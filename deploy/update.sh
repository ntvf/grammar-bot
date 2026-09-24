#!/bin/bash
# Installs the latest GitHub release if it is newer than the deployed one. Run as root by grammar-update.timer.
# Library layer (~130 MB) is only downloaded when its checksum changes; otherwise just the app layer (~0.4 MB).
set -euo pipefail

REPO="ntvf/grammar-bot"
INSTALL_DIR="/opt/grammar"
EXTRACTED_DIR="$INSTALL_DIR/extracted"
VERSION_FILE="$INSTALL_DIR/version"
LIBS_CHECKSUM_FILE="$INSTALL_DIR/libs-checksum"
JAVA=/opt/java/current/bin/java
LOG="/var/log/grammar/update.log"

log() { echo "$(date -Is) $*" >> "$LOG"; }

asset_url() {
  python3 -c "
import sys, json
for a in json.load(sys.stdin)['assets']:
    if a['name'] == '$1':
        print(a['browser_download_url'])
" <<<"$RELEASE_JSON"
}

RELEASE_JSON=$(curl -sf "https://api.github.com/repos/$REPO/releases/latest") || { log "ERROR: GitHub API unreachable"; exit 1; }
LATEST_TAG=$(python3 -c "import sys, json; print(json.load(sys.stdin)['tag_name'])" <<<"$RELEASE_JSON")
CURRENT=$(cat "$VERSION_FILE" 2>/dev/null || echo "none")
[ "$LATEST_TAG" = "$CURRENT" ] && exit 0

log "New release: $LATEST_TAG (was $CURRENT)"
LATEST_LIBS=$(curl -sfL "$(asset_url grammar-libs-checksum.txt)" || echo "")
CURRENT_LIBS=$(cat "$LIBS_CHECKSUM_FILE" 2>/dev/null || echo "")

if [ "$LATEST_LIBS" != "$CURRENT_LIBS" ] || [ ! -d "$EXTRACTED_DIR/lib" ]; then
  log "Libraries changed or first deploy — full extraction"
  curl -sfL "$(asset_url grammar.jar)" -o "$INSTALL_DIR/grammar.jar"
  rm -rf "$EXTRACTED_DIR.new"
  "$JAVA" -Djarmode=tools -jar "$INSTALL_DIR/grammar.jar" extract --destination "$EXTRACTED_DIR.new"
  rm -f "$INSTALL_DIR/grammar.jar"
  rm -rf "$EXTRACTED_DIR.previous"
  [ -d "$EXTRACTED_DIR" ] && mv "$EXTRACTED_DIR" "$EXTRACTED_DIR.previous"
  mv "$EXTRACTED_DIR.new" "$EXTRACTED_DIR"
  echo "$LATEST_LIBS" > "$LIBS_CHECKSUM_FILE"
else
  log "Libraries unchanged — app layer only"
  curl -sfL "$(asset_url grammar-app.jar)" -o "$EXTRACTED_DIR/grammar.jar.new"
  cp "$EXTRACTED_DIR/grammar.jar" "$EXTRACTED_DIR/grammar.jar.previous"
  mv "$EXTRACTED_DIR/grammar.jar.new" "$EXTRACTED_DIR/grammar.jar"
fi

echo "$LATEST_TAG" > "$VERSION_FILE"

# Only this bot's JVM — other bots on the host must not be touched. immortal restarts it.
if pkill -f -- '-jar grammar\.jar$'; then
  log "Restarted with $LATEST_TAG"
else
  log "Deployed $LATEST_TAG (process was not running)"
fi
