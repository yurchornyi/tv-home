#!/bin/sh
# Installs the channels stub (channels-stub/) and removes the stock Google TV launcher for the user, data kept.
# Why: SmartTube publishes its home-screen channels (what the top shelf shows for it) only while a launcher it
# knows is installed; the stub carries one of those names, so Google's launcher no longer has to stay, disabled,
# where something re-enabled it once (docs/TROUBLESHOOTING.md). Undo: tools/tv.py 'pm install-existing --user 0
# com.google.android.apps.tv.launcherx' and 'pm uninstall com.tv.leanback.launcher'.
# Usage: TV_HOST=<ip> launcher/install-channels-stub.sh
set -e
cd "$(dirname "$0")"
PY=../tools/venv/bin/python
[ -x "$PY" ] || PY=python3
./gradlew :channels-stub:assembleRelease -q --console=plain
APK=channels-stub/build/outputs/apk/release/channels-stub-release.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/channels-stub.apk
"$PY" -I ../tools/tv.py 'pm install -r /data/local/tmp/channels-stub.apk; rm -f /data/local/tmp/channels-stub.apk'
"$PY" -I ../tools/tv.py 'pm uninstall -k --user 0 com.google.android.apps.tv.launcherx; cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1'
echo "Stub installed, stock launcher removed for the user. SmartTube refreshes its channels within 15 minutes."
