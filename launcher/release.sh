#!/bin/sh
# Publishes a launcher release every TV can update itself to (Updater, config.xml update_url):
#   launcher/release.sh 2.5 "What changed"
# Bumps versionName to 2.5 and versionCode by one in app/build.gradle, commits and tags v2.5, builds the release
# APK, and creates a GitHub release on $RELEASE_REPO (default yurchornyi/tv-home) with tv-home.apk and
# update.json; .../releases/latest/download/update.json is what the TVs read. The APK is signed with this
# machine's debug key (app/build.gradle): release from the same machine, or copy ~/.android/debug.keystore, or
# the TVs refuse the update as foreign.
set -e
cd "$(dirname "$0")"
VERSION=$1; NOTES=${2:-"Launcher $1"}
[ -n "$VERSION" ] || { echo "usage: release.sh <versionName> [notes]" >&2; exit 1; }
REPO=${RELEASE_REPO:-yurchornyi/tv-home}
CODE=$(($(sed -n 's/^ *versionCode \([0-9]*\)$/\1/p' app/build.gradle) + 1))
sed -i '' "s/^\( *versionCode \)[0-9]*$/\1$CODE/; s/^\( *versionName \)'[^']*'$/\1'$VERSION'/" app/build.gradle
grep -q "versionCode $CODE" app/build.gradle || { echo "could not bump the version" >&2; exit 1; }
./gradlew assembleRelease -q --console=plain
APK=app/build/outputs/apk/release/app-release.apk
OUT=$(mktemp -d)
cp "$APK" "$OUT/tv-home.apk"
cat > "$OUT/update.json" <<JSON
{"versionCode": $CODE, "versionName": "$VERSION", "apk": "https://github.com/$REPO/releases/download/v$VERSION/tv-home.apk", "notes": $(printf '%s' "$NOTES" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')}
JSON
git add app/build.gradle
git commit -q -m "launcher $VERSION (versionCode $CODE)"
git tag "v$VERSION"
git push -q "${RELEASE_REMOTE:-mine}" HEAD "v$VERSION"
gh release create "v$VERSION" --repo "$REPO" --title "Launcher $VERSION" --notes "$NOTES" "$OUT/tv-home.apk" "$OUT/update.json"
rm -rf "$OUT"
echo "Released $VERSION ($CODE). TVs pick it up within six hours of being on Home."
