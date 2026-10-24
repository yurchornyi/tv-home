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
minute (the `AI_LIMIT` rate limit); limits and spend are set in Sentra as well, since the URL is public.
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
`POST /account/signout` ends one TV's sign-in. Data lives in the `ACCOUNTS` KV namespace (tv-launcher-accounts); PIN
tries are limited per address (`ACCOUNT_LIMIT`) and ten wrong ones lock the e-mail for 15 minutes.
