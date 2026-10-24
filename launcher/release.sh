#!/bin/sh
# Publishes a launcher release every TV updates itself to (Updater, config.xml update_url):
#   launcher/release.sh 1.8 "What changed"      (no version: the next one)
# The build happens on GitHub (.github/workflows/release.yml), from what is pushed to main, signed with the release
# key kept in the repository's secrets: no particular computer is needed, and nothing uncommitted here goes in.
# It bumps the version, builds, checks the signature, tags vX.Y and publishes tv-launcher.apk with update.json
# (the APK's SHA-256, which the TVs check). Needs the GitHub CLI signed in (gh auth login).
set -e
cd "$(dirname "$0")/.."
REPO=${RELEASE_REPO:-yurchornyi/tv-launcher}
VERSION=${1:-}
NOTES=${2:-}
git fetch -q origin main
if [ -n "$(git rev-list origin/main..HEAD 2>/dev/null)" ]; then
  echo "This checkout has commits not on GitHub yet: push them first (git push), only main is released." >&2; exit 1
fi
[ -z "$(git status --porcelain --untracked-files=no)" ] || echo "Note: uncommitted changes here are not part of the release."
BEFORE=$(gh run list --repo "$REPO" --workflow release.yml --limit 1 --json databaseId -q '.[0].databaseId' 2>/dev/null || true)
gh workflow run release.yml --repo "$REPO" -f version="$VERSION" -f notes="$NOTES"
echo "Started the release on GitHub; waiting for it..."
RUN=""
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  sleep 3
  RUN=$(gh run list --repo "$REPO" --workflow release.yml --limit 1 --json databaseId -q '.[0].databaseId')
  [ -n "$RUN" ] && [ "$RUN" != "$BEFORE" ] && break
done
[ -n "$RUN" ] && [ "$RUN" != "$BEFORE" ] || { echo "The release run did not show up; see the Actions tab." >&2; exit 1; }
gh run watch "$RUN" --repo "$REPO" --exit-status
git pull -q --ff-only origin main || echo "Pull the version bump later: git pull"
echo "Released. TVs pick it up within an hour, or when their screen turns on or off."
