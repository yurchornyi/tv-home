// Kyivstar TV's own posters for the launcher's top shelf (launcher KyivstarFeed): what is popular on Kyivstar TV
// right now (its weekly top ten, its most popular films and series), and for one live channel the programmes that
// are popular on it. Everything comes from the public catalogue behind tv.kyivstar.ua (Vidmind's clients API), as an
// anonymous visitor sees it. Answers are cached at the edge for three hours. When Kyivstar is down or changes its API,
// the shelf comes back empty (the TV keeps what it has) and console.error tells Sentra's monitoring.

const API = 'https://clients.production.vidmind.com/vidmind-stb-ws';
const HEADERS = { 'x-vidmind-device-type': 'WEB', 'User-Agent': 'Mozilla/5.0 (Linux; Android 12; TV) tv-launcher' };
const TTL = 3 * 60 * 60;
const APP = 'com.kyivstar.tv.tvapp';

// The groups on Kyivstar TV's main page, by name (their ids are the fallback if a name changes).
const TOP_WEEK = { name: 'Топ-10 тижня', id: '68f251f535b4956d90011a23' };
const POPULAR_FILMS = { name: 'Найпопулярніші фільми', id: '661cec7dc7b45a1690959607' };
const POPULAR_SERIES = { name: 'Найпопулярніші серіали', id: '660a7dc314f4b3f94bbe7777' };
const POPULAR_PROGRAMS = { name: 'Популярне на ТБ: Програми', id: '5f0851018d54597041cba621' };

/** Titles the owner never wants on the shelf (ShelfFeed.NEVER on the TV says the same). */
const NEVER = /(^|[^\p{L}])(свати|сваты|svaty|слуга народу|слуга народа)([^\p{L}]|$)/iu;

let session = null;   // { id, at }: one anonymous session per Worker instance, renewed after an hour

async function sid(fresh) {
  if (!fresh && session && Date.now() - session.at < 60 * 60 * 1000) return session.id;
  const body = new URLSearchParams({ username: '557455cfe4b04ad886a6ae41\\anonymous', password: 'anonymous' });
  const r = await fetch(API + '/api/v1/authentication/login', {
    method: 'POST', headers: { ...HEADERS, 'Content-Type': 'application/x-www-form-urlencoded' }, body,
  });
  if (!r.ok) throw new Error('kyivstar login ' + r.status);
  const u = await r.json();
  session = { id: u.sessionId, at: Date.now() };
  return session.id;
}

/** GET on the clients API; "{s}" in the path is where the session goes. One retry with a new session on 401/403. */
async function get(path) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const s = await sid(attempt > 0);
    const r = await fetch(API + path.replace('{s}', ';jsessionid=' + s), { headers: HEADERS });
    if (r.ok) return r.json();
    if (r.status !== 401 && r.status !== 403) throw new Error('kyivstar ' + r.status + ' ' + path);
  }
  throw new Error('kyivstar refused ' + path);
}

async function group(id, limit) {
  const g = await get(`/api/v1/gallery/v3/contentgroups/${id}{s}?offset=0&limit=${limit}`);
  return g.assets || [];
}

/** The main page's group ids by name; the weekly top is a compilation whose first element is the group. */
async function groupIds() {
  const out = {};
  try {
    const areas = await get('/api/v2/contentareas/MY_TV_Anonymous{s}?includeRestricted=true');
    for (const a of areas) out[a.name] = a.assetId;
    const topArea = areas.find((a) => a.name && a.name.startsWith(TOP_WEEK.name));
    if (topArea) {
      const elements = await get(`/api/v1/compilations/${topArea.assetId}{s}`);
      const first = elements.find((e) => e.assetType === 'CONTENT_GROUP');
      if (first) out[TOP_WEEK.name] = first.assetId;
    }
  } catch (e) {
    console.warn('content areas', String(e));
  }
  return out;
}

function image(asset) {
  for (const source of asset.imagesSource || []) {
    const files = (source.imgFiles || []).map((f) => f.fileName);
    for (const want of ['IMAGE_16_9_XXL.jpg', 'IMAGE_16_9_XL.jpg', 'IMAGE_16_9_L.jpg']) {
      if (files.includes(want)) return source.url.replace(/\/+$/, '') + '/' + want;
    }
  }
  for (const i of asset.images || []) {
    if (/16_9_(XXL|XL)/.test(i.url || '')) return i.url;
  }
  return '';
}

/** The app's deep link for a film, series or recorded programme (checked on Kyivstar TV 1.30). */
function open(asset) {
  const kind = asset.assetType === 'SERIES' ? 'series' : 'movie';
  return `${APP}://${kind}${asset.assetId}`;
}

/** "Супермама, 12 сезон, 20 еп. Ділила..." -> "Супермама"; "Прем'єра. Дизель шоу, 11 сезон" -> "Дизель шоу". */
export function showName(title) {
  let t = String(title || '').replace(/^\s*Прем['’]?єра\.?\s*/i, '').replace(/["«»“”]/g, '').trim();
  // \b is ASCII-only in JS, so the ends of the Cyrillic words are a look-ahead for a non-letter.
  t = t.replace(/,?\s*\d+\s*(сезон|с\.?)(?=[^\p{L}]|$).*$/iu, '').replace(/,?\s*\d+\s*(еп|серія|серії|ep)(?=[^\p{L}]|$).*$/iu, '');
  t = t.replace(/\.\s*(Вихідний\.\s*)?\d{1,2}\s+\p{L}+$/u, '');   // "Сніданок з 1+1. 22 жовтня"
  return t.replace(/[\s.,:;-]+$/, '').trim();
}

function item(asset, title) {
  const backdrop = image(asset);
  if (!backdrop || !asset.assetId) return null;
  const name = title || asset.name || '';
  if (!name || NEVER.test(name) || NEVER.test(asset.name || '')) return null;
  return { title: name, backdrop, open: open(asset) };
}

function json(o, maxAge) {
  const headers = { 'Content-Type': 'application/json; charset=utf-8' };
  headers['Cache-Control'] = maxAge ? 'public, max-age=' + maxAge : 'no-store';
  return new Response(JSON.stringify(o), { status: 200, headers });
}

/** `build` gives { items }; only a list with something in it is cached. An empty one is logged when `needed`. */
async function cached(request, ctx, build, needed = true) {
  const key = new Request(new URL(request.url).toString(), { method: 'GET' });
  const hit = await caches.default.match(key);
  if (hit) return hit;
  let body;
  try {
    body = await build();
  } catch (e) {
    console.error('kyivstar shelf empty', new URL(request.url).pathname, String(e));
    return json({ items: [] }, 0);
  }
  const items = body && Array.isArray(body.items) ? body.items : [];
  if (!items.length) {
    if (needed) console.error('kyivstar shelf empty', new URL(request.url).pathname, 'nothing usable');
    return json({ items: [] }, 0);
  }
  const response = json({ items }, TTL);
  ctx.waitUntil(caches.default.put(key, response.clone()));
  return response;
}

/** GET /kyivstar/popular: the weekly top ten first, then the most popular films and series, interleaved. */
export function popular(request, ctx) {
  return cached(request, ctx, async () => {
    const ids = await groupIds();
    const lists = await Promise.all([TOP_WEEK, POPULAR_FILMS, POPULAR_SERIES].map((g) =>
      group(ids[g.name] || g.id, 20).catch((e) => {
        console.error('kyivstar group', g.name, String(e));
        return [];
      })));
    const items = [];
    const seen = new Set();
    const add = (a) => {
      const it = item(a);
      if (!it || seen.has(a.assetId) || seen.has(it.title)) return;
      seen.add(a.assetId);
      seen.add(it.title);
      items.push(it);
    };
    lists[0].slice(0, 10).forEach(add);
    for (let i = 0; i < 20; i++) {
      if (lists[1][i]) add(lists[1][i]);
      if (lists[2][i]) add(lists[2][i]);
    }
    return { items: items.slice(0, 30) };
  });
}

/**
 * GET /kyivstar/channel?id=<channel id>&name=<its name>: the programmes popular on that channel. First the ones
 * Kyivstar itself lists as popular on TV for it; then the shows of its programme guide for the next three days, those
 * on most often in the evening first, each with the picture Kyivstar's catalogue has for it.
 */
export function channel(request, ctx, url) {
  const id = url.searchParams.get('id') || '';
  const name = (url.searchParams.get('name') || '').trim();
  if (!/^[0-9a-f]{24}$/.test(id)) return new Response('bad id', { status: 400 });
  return cached(request, ctx, async () => {
    const items = [];
    const names = new Set();
    const add = (asset, title) => {
      const show = showName(title || asset.name);
      const key = show.toLowerCase();
      if (!show || names.has(key)) return false;
      const it = item(asset, show);
      if (!it) return false;
      names.add(key);
      items.push(it);
      return true;
    };
    const same = (a, b) => a.toLowerCase().replace(/\s+hd$/, '') === b.toLowerCase().replace(/\s+hd$/, '');
    if (name) {
      const programs = await group(POPULAR_PROGRAMS.id, 100).catch(() => []);
      for (const p of programs) if (p.channelName && same(p.channelName, name)) add(p);
    }
    // The guide: each show weighed by how often it is on, an evening (18:00-23:00 Kyiv) showing counting three times.
    const day = (offset) => new Date(Date.now() + offset * 86400000).toISOString().slice(0, 10).replace(/-/g, '');
    let guide = [];
    try {
      guide = await get(`/livechannels/${id}/epg{s}?from=${day(0)}&to=${day(2)}`);
    } catch (e) {
      console.warn('epg', String(e));
    }
    const weight = new Map();
    const now = Date.now();
    for (const d of guide || []) {
      for (const p of d.programList || []) {
        if (p.finish < now) continue;
        const show = showName(p.title);
        if (!show || show.length < 3 || /новини|news|телепродаж|реклама|марафон/i.test(show)) continue;
        const hour = (new Date(p.start).getUTCHours() + 3) % 24;
        weight.set(show, (weight.get(show) || 0) + (hour >= 18 && hour < 23 ? 3 : 1));
      }
    }
    const ranked = [...weight.entries()].sort((a, b) => b[1] - a[1]).map(([show]) => show)
      .filter((show) => !names.has(show.toLowerCase()) && !NEVER.test(show));
    for (const show of ranked.slice(0, 10)) {
      if (items.length >= 12) break;
      try {
        const hits = await get(`/api/v1/search/predictive{s}?q=${encodeURIComponent(show)}&limit=10&includeLiveChannels=false`);
        const want = show.toLowerCase();
        const hit = (hits || []).find((h) => (h.assetType === 'MOVIE' || h.assetType === 'SERIES')
          && showName(h.name).toLowerCase() === want && image(h));
        if (hit) add(hit, show);
      } catch (e) {
        console.warn('search', show, String(e));
      }
    }
    return { items };
  }, false);   // a channel with nothing popular is possible: its shelf keeps the app's posters
}
