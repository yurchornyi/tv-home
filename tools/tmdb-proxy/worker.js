import { command, picks, about, HttpError } from './ai.js';
import { featured as kickFeatured, play as kickPlay } from './kick.js';
import { popular as kyivstarPopular, channel as kyivstarChannel } from './kyivstar.js';
import { account, isAccountRoute } from './account.js';
import { popular as spotifyPopular } from './spotify.js';

export { Guard } from './guard.js';

// The launcher's TMDB requests go through here (ShelfFeed, config.xml tmdb_proxy), so the TMDB key lives only in
// this Worker's secret (TMDB_KEY) and never in the APK or on GitHub. Only the few read-only paths the top shelf
// uses are passed on; answers are cached at Cloudflare's edge for six hours, so every TV asking the same list
// costs TMDB one request. Deploy: tools/tmdb-proxy/deploy.sh.

const ALLOWED = [
  /^\/3\/trending\/all\/week$/,
  /^\/3\/movie\/popular$/,
  /^\/3\/discover\/(movie|tv)$/,
  /^\/3\/(movie|tv)\/\d{1,9}\/(images|external_ids)$/,
];
const PASSED = ['language', 'region', 'with_networks', 'with_watch_providers', 'watch_region', 'with_origin_country',
  'sort_by', 'include_image_language', 'page'];
const TTL = 6 * 60 * 60;

/**
 * What the TV gets when TMDB is down, refuses the key or answers in a shape it no longer reads: the same shape, empty.
 * ShelfFeed then keeps the posters it has and tries again later; console.error is what Sentra's monitoring sees.
 */
function tmdbEmpty(path, why) {
  if (why) console.error('tmdb shelf empty', path, why);
  const body = path.endsWith('/images') ? { backdrops: [] }
    : path.endsWith('/external_ids') ? {}
      : { page: 1, results: [], total_pages: 0, total_results: 0 };
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

const ABOUT_PAGE = `TV-Launcher: the backend of an Android TV home screen for a Ukrainian household.
- TMDB posters for the top shelf (/3/...), Kyivstar TV, Kick and Spotify shelves.
- AI through Sentra: a spoken command turned into one TV action, film picks for a mood, two lines on a film.
- Accounts that carry one TV's Home to the others.
`;

const AI_ROUTES = { '/ai/command': command, '/ai/picks': picks, '/ai/about': about };

/** POST /ai/*: JSON in, JSON out; each TV (by address) gets a few a minute, so a stranger cannot run up the bill. */
async function ai(request, env, path) {
  const json = (o, status = 200) => new Response(JSON.stringify(o), {
    status, headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });
  if (!env.SENTRA_TOKEN || !env.SENTRA_URL) return json({ error: 'not configured' }, 503);
  const who = request.headers.get('cf-connecting-ip') || 'unknown';
  if (env.AI_LIMIT) {
    const { success } = await env.AI_LIMIT.limit({ key: who });
    if (!success) return json({ error: 'slow down' }, 429);
  }
  // And a ceiling for everyone together, so many addresses at once cannot run up the bill either.
  if (env.AI_GLOBAL) {
    const { success } = await env.AI_GLOBAL.limit({ key: 'all' });
    if (!success) return json({ error: 'busy' }, 429);
  }
  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: 'bad json' }, 400);
  }
  try {
    return json(await AI_ROUTES[path](env, body || {}));
  } catch (e) {
    if (e instanceof HttpError) return json({ error: e.message }, e.status);
    console.error('AI route failed', path, e instanceof Error ? `${e.name}: ${e.message}` : String(e));
    return json({ error: 'failed' }, 500);
  }
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (request.method === 'POST' && AI_ROUTES[url.pathname]) return ai(request, env, url.pathname);
    if (isAccountRoute(request, url)) return account(request, env, url);
    if (request.method !== 'GET') return new Response('GET only', { status: 405 });
    // The address Sentra watches and reads (its «site» of this project): what this Worker is, in a few lines.
    if (url.pathname === '/') return new Response(ABOUT_PAGE, { headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
    if (url.pathname === '/kick/featured') return kickFeatured(url, ctx);
    if (url.pathname === '/kick/play') return kickPlay(url);
    if (url.pathname === '/kyivstar/popular') return kyivstarPopular(request, ctx);
    if (url.pathname === '/kyivstar/channel') return kyivstarChannel(request, ctx, url);
    if (url.pathname === '/spotify/popular') return spotifyPopular(request, ctx);
    if (!ALLOWED.some((re) => re.test(url.pathname))) return new Response('Not found', { status: 404 });
    if (!env.TMDB_KEY) return tmdbEmpty(url.pathname, 'TMDB_KEY not set');

    // The upstream request carries only known parameters, in a fixed order, so the cache key is stable.
    const upstream = new URL('https://api.themoviedb.org' + url.pathname);
    for (const name of PASSED) {
      const v = url.searchParams.get(name);
      if (v !== null && v.length <= 64) upstream.searchParams.set(name, v);
    }
    const cacheKey = new Request(upstream.toString(), { method: 'GET' });
    const cache = caches.default;
    let response = await cache.match(cacheKey);
    if (response) return response;
    // Only requests that reach TMDB count: a TV's few a day pass easily, while someone using the public URL as a free
    // TMDB proxy runs into the limit long before the token could get flagged.
    if (env.TMDB_LIMIT) {
      const { success } = await env.TMDB_LIMIT.limit({ key: request.headers.get('cf-connecting-ip') || 'unknown' });
      if (!success) return new Response('Slow down', { status: 429 });
    }

    const key = env.TMDB_KEY.trim();
    const headers = { Accept: 'application/json' };
    if (key.length > 40) headers.Authorization = 'Bearer ' + key;   // a v4 read token
    else upstream.searchParams.set('api_key', key);                 // a v3 key
    // no-store: Cloudflare must not keep TMDB's own answer by URL. A v4 token travels in a header the URL does not
    // show, so a 401 cached under an old token kept coming back, now and then, after the token changed.
    let data;
    try {
      const answer = await fetch(upstream.toString(), { headers, cache: 'no-store' });
      // An unknown title's images or ids: nothing to show, and nothing broken either.
      if (answer.status === 404) return tmdbEmpty(url.pathname, '');
      if (!answer.ok) return tmdbEmpty(url.pathname, 'status ' + answer.status);
      data = await answer.json();
    } catch (e) {
      return tmdbEmpty(url.pathname, e instanceof Error ? `${e.name}: ${e.message}` : String(e));
    }
    const list = !/\/(images|external_ids)$/.test(url.pathname);
    if (!data || typeof data !== 'object' || (list ? !Array.isArray(data.results) : url.pathname.endsWith('/images')
      && !Array.isArray(data.backdrops))) return tmdbEmpty(url.pathname, 'unexpected shape');
    // Only the JSON travels on: TMDB's own headers (its CDN's ids, cookies) stay behind.
    response = new Response(JSON.stringify(data), {
      headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'public, max-age=' + TTL },
    });
    ctx.waitUntil(cache.put(cacheKey, response.clone()));
    return response;
  },
};
