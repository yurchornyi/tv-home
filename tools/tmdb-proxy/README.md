# tv-launcher-tmdb

A Cloudflare Worker (account "Basic") that asks TMDB for the launcher's top-shelf posters, so the TMDB key lives
only in the Worker's `TMDB_KEY` secret and never in the APK or on GitHub. The launcher reaches it at
`config.xml` `tmdb_proxy` (https://tv-launcher-tmdb.basic-175.workers.dev). Only the read-only paths the shelf uses are
passed on; anything else is a 404.

Deploy or change the key: put it in `launcher/local.properties` as `tmdb.key=...`, then run
`tools/tmdb-proxy/deploy.sh` (wrangler, logged in to Cloudflare once with `npx wrangler login`). TVs need no update
for a new key.

## AI (ai.js)

`POST /ai/command` turns a spoken request into one action on the TV, `POST /ai/picks` suggests films for a mood
(found on TMDB for their backdrops), `POST /ai/about` writes two lines on a film. They use Claude (claude-opus-5-5,
low effort, server-side fallbacks) through Sentra, the owner's AI centre (`SENTRA_URL` in `wrangler.toml`, the `SENTRA_TOKEN` secret that Sentra writes
into this Worker when the project is connected; the Anthropic key, its limits and spend live there). Each address gets 12 AI requests a
minute (the `AI_LIMIT` rate limit), and all TVs together 60 (`AI_GLOBAL`); limits and spend are set in Sentra as well,
since the URL is public.
Without `SENTRA_TOKEN` the routes answer 503 and the TV quietly uses plain search.

## Kyivstar TV (kyivstar.js)

`GET /kyivstar/popular` gives Kyivstar TV's own posters for its shelf: the weekly top ten, then its most popular films
and series. `GET /kyivstar/channel?id=<channel id>&name=<name>` gives the programmes popular on one channel (Kyivstar's
"Popular on TV" list for it, then the shows of its programme guide with the catalogue's pictures). Both come from the
public catalogue behind tv.kyivstar.ua with an anonymous session, cached three hours, and never list "Svaty" or
"Sluha narodu". Each item carries the app's deep link (`com.kyivstar.tv.tvapp://movie<id>` or `://series<id>`).

## Accounts (account.js)

`POST /account/signin {email, pin}` signs in or makes the account (PIN of 4 to 6 digits, kept only as PBKDF2);
`GET /account/state` and `PUT /account/state` carry one TV's Home (Backup's snapshot and its apps) to the others;
`POST /account/signout` ends one TV's sign-in. Data lives in the `ACCOUNTS` KV namespace (tv-launcher-accounts); a
sign-in token is kept only as its SHA-256 and lasts a year. Tries are counted exactly by a Durable Object (guard.js):
ten sign-in tries a minute per address, ten wrong PINs lock the e-mail for 15 minutes, five new accounts an hour per
address. Writes are limited per account (`WRITE_LIMIT`).

The owner's account (the `ADMIN_EMAIL` secret) sees and deletes the others. Signing in never makes it, so nobody can
claim that e-mail first: run `tools/tmdb-proxy/make-admin.sh` in a terminal once, which asks the e-mail and the PIN,
writes the account and sets the secret. Run it again to change the PIN.

## Spotify (spotify.js)

`GET /spotify/popular` gives the songs most played on Spotify in Ukraine today (kworb.net's copy of Spotify's daily
chart, the global one to top it up), Russian-language ones left out, each with its 640 px album cover and an
`open.spotify.com/track` link the Spotify TV app plays. YouTube Music's shelf shows the same songs, opening its search.

`GET`/`PUT /account/app/<package>` keep one app's own settings backup for the account (today SmartTube's, from
`Android/media/<its package>/data`). The TV encrypts it with AES-GCM under a key made from the PIN (PBKDF2), so
the Worker holds bytes it cannot read; up to 4 MB per app, and only SmartTube's (`org.smarttube.stable`).
