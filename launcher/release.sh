#!/bin/sh
# Publishes a launcher release every TV can update itself to (Updater, config.xml update_url):
#   launcher/release.sh 2.5 "What changed"
# Bumps versionName to 2.5 and versionCode by one in app/build.gradle, commits and tags v2.5, builds the release
# APK, and creates a GitHub release on $RELEASE_REPO (default yurchornyi/tv-launcher) with tv-launcher.apk and
# update.json; .../releases/latest/download/update.json is what the TVs read. The APK is signed with this
# machine's debug key (app/build.gradle): release from the same machine, or copy ~/.android/debug.keystore, or
# the TVs refuse the update as foreign.
set -e
cd "$(dirname "$0")"
# The JDK and SDK Gradle needs, where Homebrew puts them, unless the shell already says.
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
[ -x "$JAVA_HOME/bin/java" ] || { echo "No JDK at $JAVA_HOME (brew install openjdk@17, or set JAVA_HOME)" >&2; exit 1; }
VERSION=$1; NOTES=${2:-"Launcher $1"}
[ -n "$VERSION" ] || { echo "usage: release.sh <versionName> [notes]" >&2; exit 1; }
REPO=${RELEASE_REPO:-yurchornyi/tv-launcher}
if git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null; then
  echo "v$VERSION is already released; pick the next number, e.g. $(git tag -l 'v*' | sort -V | tail -1 | sed 's/^v//' | awk -F. '{print $1"."$2+1}')" >&2; exit 1
fi
[ -z "$(git status --porcelain app/build.gradle)" ] || { echo "app/build.gradle has uncommitted changes; commit or revert them first" >&2; exit 1; }
CODE=$(($(sed -n 's/^ *versionCode \([0-9]*\)$/\1/p' app/build.gradle) + 1))
sed -i '' "s/^\( *versionCode \)[0-9]*$/\1$CODE/; s/^\( *versionName \)'[^']*'$/\1'$VERSION'/" app/build.gradle
grep -q "versionCode $CODE" app/build.gradle || { echo "could not bump the version" >&2; exit 1; }
# New TVs start from the owner's latest saved settings (backups/xgimi.json, tools/backup-settings.sh).
python3 -I ../tools/make-defaults.py
./gradlew assembleRelease -q --console=plain || { git checkout -- app/build.gradle; echo "build failed; version left as it was" >&2; exit 1; }
APK=app/build/outputs/apk/release/app-release.apk
OUT=$(mktemp -d)
cp "$APK" "$OUT/tv-launcher.apk"
cat > "$OUT/update.json" <<JSON
{"versionCode": $CODE, "versionName": "$VERSION", "apk": "https://github.com/$REPO/releases/download/v$VERSION/tv-launcher.apk", "notes": $(printf '%s' "$NOTES" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')}
JSON
git add app/build.gradle
git commit -q -m "launcher $VERSION (versionCode $CODE)"
git tag "v$VERSION"
git push -q "${RELEASE_REMOTE:-mine}" HEAD "v$VERSION"
gh release create "v$VERSION" --repo "$REPO" --title "Launcher $VERSION" --notes "$NOTES" "$OUT/tv-launcher.apk" "$OUT/update.json"
rm -rf "$OUT"
echo "Released $VERSION ($CODE). TVs pick it up within six hours of being on Home."
