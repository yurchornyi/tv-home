#!/bin/sh
# Deploys the TMDB proxy and AI Worker (worker.js, ai.js) to the Cloudflare account in wrangler.toml and sets its TMDB_KEY secret
# from launcher/local.properties (tmdb.key=...), read from the file and piped in, never printed.
# Usage: tools/tmdb-proxy/deploy.sh   (needs `npx wrangler login` once on this Mac)
set -e
cd "$(dirname "$0")"
KEY=$(sed -n 's/^tmdb\.key=//p' ../../launcher/local.properties | tr -d '[:space:]')
[ -n "$KEY" ] || { echo "no tmdb.key in launcher/local.properties" >&2; exit 1; }
npm install --silent
npx -y wrangler@4 deploy
printf '%s' "$KEY" | npx -y wrangler@4 secret put TMDB_KEY
# The AI features (ai.js) go through Sentra: it writes SENTRA_TOKEN into this Worker when the project is connected
# on its dashboard (SENTRA_URL is in wrangler.toml). Without it the AI routes answer "not configured".
