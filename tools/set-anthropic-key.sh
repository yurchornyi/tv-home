#!/bin/sh
# Asks for an Anthropic API key without showing it and writes it to launcher/local.properties (anthropic.key=...),
# which git ignores. Then tools/tmdb-proxy/deploy.sh hands it to the Worker. Run it in a real terminal: it reads the keyboard.
set -e
cd "$(dirname "$0")/.."
FILE=launcher/local.properties
printf 'Anthropic API key (input hidden): '
stty -echo
read -r KEY
stty echo
printf '\n'
KEY=$(printf '%s' "$KEY" | tr -d '[:space:]')
[ -n "$KEY" ] || { echo "nothing entered, file unchanged" >&2; exit 1; }
touch "$FILE"
grep -v '^anthropic\.key=' "$FILE" > "$FILE.tmp" || true
printf 'anthropic.key=%s\n' "$KEY" >> "$FILE.tmp"
mv "$FILE.tmp" "$FILE"
echo "saved (${#KEY} characters). Tell Claude: done."
