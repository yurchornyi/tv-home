#!/bin/sh
# Puts a backups/<name>.json made by backup-settings.sh onto a TV; the launcher takes it in when it restarts.
# Usage: TV_HOST=<ip> tools/restore-settings.sh <name>
set -e
cd "$(dirname "$0")/.."
NAME=${1:?usage: restore-settings.sh <name>}
HOST=${TV_HOST:?set TV_HOST=<tv ip>}
[ -f "backups/$NAME.json" ] || { echo "no backups/$NAME.json" >&2; exit 1; }
adb connect "$HOST:5555" >/dev/null
adb -s "$HOST:5555" push "backups/$NAME.json" /sdcard/Android/data/com.gathin.tvhome/files/backup.json >/dev/null
adb -s "$HOST:5555" shell "am force-stop com.gathin.tvhome; sleep 1; am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; echo restored"
