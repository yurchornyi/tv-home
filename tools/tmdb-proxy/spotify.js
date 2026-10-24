// Spotify's top shelf (launcher ShelfFeed): the songs most played on Spotify in Ukraine today, from the daily chart
// kworb.net keeps of Spotify's own (spotify.com publishes it only behind a sign-in), each with its album cover
// (Spotify's oEmbed) and a link the Spotify TV app opens. Russian-language songs are left out: a Cyrillic title or
// artist stays only with a letter Russian lacks (і, ї, є, ґ). The global chart tops the list up. Cached six hours.

const UA = { 'User-Agent': 'Mozilla/5.0 (Linux; Android 12; TV) tv-launcher' };
const TTL = 6 * 60 * 60;
const CHARTS = ['https://kworb.net/spotify/country/ua_daily.html', 'https://kworb.net/spotify/country/global_daily.html'];
const MAX = 20;

/** Ukrainian, Latin or other script: true; Cyrillic without a letter only Ukrainian has: false. */
export function notRussian(text) {
  if (!/[Ѐ-ӿ]/.test(text)) return true;
  return /[іїєґІЇЄҐ]/.test(text) && !/[ыэъёЫЭЪЁ]/.test(text);
}

const unescape = (s) => s.replace(/&amp;/g, '&').replace(/&#39;|&apos;/g, "'").replace(/&quot;/g, '"')
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>');

/** The chart's rows: the first artist's name, the song's title and its Spotify track id. */
function rows(html) {
  const out = [];
  for (const row of html.matchAll(/<tr>([\s\S]*?)<\/tr>/g)) {
    const cell = row[1];
    const artist = /href="\.\.\/artist\/[^"]+">([^<]+)<\/a>/.exec(cell);
    const track = /href="\.\.\/track\/([A-Za-z0-9]{22})\.html">([^<]+)<\/a>/.exec(cell);
    if (artist && track) out.push({ artist: unescape(artist[1]).trim(), title: unescape(track[2]).trim(), id: track[1] });
  }
  return out;
}

/** The album cover at 640 px (oEmbed gives the 300 px one; Spotify's image ids differ only in the size part). */
async function cover(id) {
  const r = await fetch(`https://open.spotify.com/oembed?url=https://open.spotify.com/track/${id}`, { headers: UA });
  if (!r.ok) return '';
  const d = await r.json();
  return String(d.thumbnail_url || '').replace('ab67616d00001e02', 'ab67616d0000b273');
}

export async function popular(request, ctx) {
  const key = new Request(new URL(request.url).toString(), { method: 'GET' });
  const hit = await caches.default.match(key);
  if (hit) return hit;
  const picked = [];
  const seen = new Set();
  for (const chart of CHARTS) {
    if (picked.length >= MAX) break;
    try {
      const r = await fetch(chart, { headers: UA });
      if (!r.ok) continue;
      for (const row of rows(await r.text())) {
        if (picked.length >= MAX) break;
        if (seen.has(row.id) || !notRussian(row.artist) || !notRussian(row.title)) continue;
        seen.add(row.id);
        picked.push(row);
      }
    } catch (e) {
      console.warn('chart', chart, String(e));
    }
  }
  const covers = await Promise.all(picked.map((p) => cover(p.id).catch(() => '')));
  const items = picked.map((p, i) => ({
    title: `${p.title} · ${p.artist}`,
    backdrop: covers[i],
    open: `https://open.spotify.com/track/${p.id}`,
  })).filter((it) => it.backdrop);
  const response = new Response(JSON.stringify({ items }), {
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'public, max-age=' + TTL },
  });
  if (items.length) ctx.waitUntil(caches.default.put(key, response.clone()));
  return response;
}
