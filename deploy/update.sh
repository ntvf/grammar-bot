#!/usr/bin/env bash
# Installs the latest GitHub release if it is newer than the running one.
# The 130 MB of libraries are only downloaded when their checksum changes; otherwise just the ~100 KB
# application layer is swapped. Run from cron, e.g.:  */5 * * * * /opt/grammar-bot/update.sh
set -euo pipefail

REPO="${REPO:-ntvf/grammar-bot}"
BASE=/opt/grammar-bot
CURRENT="$BASE/current"
API="https://api.github.com/repos/$REPO/releases/latest"
AUTH=()
[[ -n "${GITHUB_TOKEN:-}" ]] && AUTH=(-H "Authorization: Bearer $GITHUB_TOKEN")

release=$(curl -fsSL "${AUTH[@]}" "$API")
tag=$(grep -m1 '"tag_name"' <<<"$release" | cut -d'"' -f4)
[[ -z "$tag" ]] && { echo "No release found"; exit 1; }
[[ "$(cat "$BASE/version" 2>/dev/null)" == "$tag" ]] && exit 0

asset() { echo "https://github.com/$REPO/releases/download/$tag/$1"; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

curl -fsSL "${AUTH[@]}" -o "$work/libs.txt" "$(asset grammar-libs-checksum.txt)"
if [[ ! -d "$CURRENT/lib" || "$(cat "$BASE/libs-checksum" 2>/dev/null)" != "$(cat "$work/libs.txt")" ]]; then
  echo "Libraries changed — downloading full JAR for $tag"
  curl -fsSL "${AUTH[@]}" -o "$work/grammar.jar" "$(asset grammar.jar)"
  java -Djarmode=tools -jar "$work/grammar.jar" extract --destination "$work/extracted"
  rm -rf "$BASE/next" && mv "$work/extracted" "$BASE/next"
else
  echo "Only application changed — downloading app layer for $tag"
  rm -rf "$BASE/next" && cp -a "$CURRENT" "$BASE/next"
  curl -fsSL "${AUTH[@]}" -o "$BASE/next/grammar.jar" "$(asset grammar-app.jar)"
fi

rm -rf "$BASE/previous"
[[ -d "$CURRENT" ]] && mv "$CURRENT" "$BASE/previous"
mv "$BASE/next" "$CURRENT"
cp "$work/libs.txt" "$BASE/libs-checksum"
echo "$tag" > "$BASE/version"

sudo systemctl restart grammar-bot
echo "Deployed $tag (previous version kept in $BASE/previous)"
