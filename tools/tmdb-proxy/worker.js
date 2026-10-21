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

export default {
  async fetch(request, env, ctx) {
    if (request.method !== 'GET') return new Response('GET only', { status: 405 });
    const url = new URL(request.url);
    if (!ALLOWED.some((re) => re.test(url.pathname))) return new Response('Not found', { status: 404 });
    if (!env.TMDB_KEY) return new Response('Not configured', { status: 503 });

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

    const key = env.TMDB_KEY.trim();
    const headers = { Accept: 'application/json' };
    if (key.length > 40) headers.Authorization = 'Bearer ' + key;   // a v4 read token
    else upstream.searchParams.set('api_key', key);                 // a v3 key
    const answer = await fetch(upstream.toString(), { headers });
    response = new Response(answer.body, answer);
    response.headers.delete('set-cookie');
    if (answer.ok) {
      response.headers.set('Cache-Control', 'public, max-age=' + TTL);
      ctx.waitUntil(cache.put(cacheKey, response.clone()));
    }
    return response;
  },
};
