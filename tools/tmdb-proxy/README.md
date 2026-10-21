# tv-home-tmdb

A Cloudflare Worker (account "Basic") that asks TMDB for the launcher's top-shelf posters, so the TMDB key lives
only in the Worker's `TMDB_KEY` secret and never in the APK or on GitHub. The launcher reaches it at
`config.xml` `tmdb_proxy` (https://tv-home-tmdb.basic-175.workers.dev). Only the read-only paths the shelf uses are
passed on; anything else is a 404.

Deploy or change the key: put it in `launcher/local.properties` as `tmdb.key=...`, then run
`tools/tmdb-proxy/deploy.sh` (wrangler, logged in to Cloudflare once with `npx wrangler login`). TVs need no update
for a new key.
