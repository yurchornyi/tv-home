#!/bin/sh
# Deploys the TMDB proxy Worker (worker.js) to the Cloudflare account in wrangler.toml and sets its TMDB_KEY secret
# from launcher/local.properties (tmdb.key=...), read from the file and piped in, never printed.
# Usage: tools/tmdb-proxy/deploy.sh   (needs `npx wrangler login` once on this Mac)
set -e
cd "$(dirname "$0")"
KEY=$(sed -n 's/^tmdb\.key=//p' ../../launcher/local.properties | tr -d '[:space:]')
[ -n "$KEY" ] || { echo "no tmdb.key in launcher/local.properties" >&2; exit 1; }
npx -y wrangler@4 deploy
printf '%s' "$KEY" | npx -y wrangler@4 secret put TMDB_KEY
