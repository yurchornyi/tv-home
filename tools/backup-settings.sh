#!/bin/sh
# Copies the launcher's choices (tile order, Home additions and removals, shelf posters, channels) off a TV into
# backups/<name>.json, ready to commit to your repository, and restore-settings.sh puts them back on any TV.
# Usage: TV_HOST=<ip> tools/backup-settings.sh <name>          e.g. tools/backup-settings.sh xgimi
set -e
cd "$(dirname "$0")/.."
NAME=${1:?usage: backup-settings.sh <name>}
HOST=${TV_HOST:?set TV_HOST=<tv ip>}
adb connect "$HOST:5555" >/dev/null
mkdir -p backups
adb -s "$HOST:5555" pull /sdcard/Android/data/com.gathin.tvhome/files/backup.json "backups/$NAME.json" >/dev/null
echo "Saved backups/$NAME.json ($(wc -c < "backups/$NAME.json") bytes). Commit it to keep it."
