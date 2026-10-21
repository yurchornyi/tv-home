# tv-home-tmdb

A Cloudflare Worker (account "Basic") that asks TMDB for the launcher's top-shelf posters, so the TMDB key lives
only in the Worker's `TMDB_KEY` secret and never in the APK or on GitHub. The launcher reaches it at
`config.xml` `tmdb_proxy` (https://tv-home-tmdb.basic-175.workers.dev). Only the read-only paths the shelf uses are
passed on; anything else is a 404.

Deploy or change the key: put it in `launcher/local.properties` as `tmdb.key=...`, then run
`tools/tmdb-proxy/deploy.sh` (wrangler, logged in to Cloudflare once with `npx wrangler login`). TVs need no update
for a new key.

## AI (ai.js)

`POST /ai/command` turns a spoken request into one action on the TV, `POST /ai/picks` suggests films for a mood
(found on TMDB for their backdrops), `POST /ai/about` writes two lines on a film. They use Claude (claude-opus-5-5,
low effort, server-side fallbacks) with the `ANTHROPIC_KEY` secret: put `anthropic.key=...` in
`launcher/local.properties` with `tools/set-anthropic-key.sh` and run `deploy.sh`. Each address gets 12 AI requests a
minute (the `AI_LIMIT` rate limit); set a monthly spend limit in the Anthropic Console as well, since the URL is public.
Without a key the routes answer 503 and the TV quietly uses plain search.
