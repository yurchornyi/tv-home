// Kick's live streams for the launcher's top shelf (launcher KickFeed): the streams Kick features right now, and a
// fresh playback link for one when the TV opens it (the links carry a token that expires). Kick's TV app opens no
// particular channel, so the TV plays the link in a video player. Lists are cached a couple of minutes.

const UA = { 'User-Agent': 'Mozilla/5.0 (Linux; Android 12; TV) tv-launcher', Accept: 'application/json' };

export async function featured(url, ctx) {
  const lang = /^[a-z]{2}$/.test(url.searchParams.get('lang') || '') ? url.searchParams.get('lang') : 'en';
  const r = await fetch(`https://kick.com/stream/featured-livestreams/${lang}`, { headers: UA, cf: { cacheTtl: 120 } });
  if (!r.ok) return json({ error: 'kick ' + r.status }, 502);
  const d = await r.json();
  const items = (d.data || []).filter((s) => s.is_live && !s.is_mature).slice(0, 30).map((s) => ({
    slug: s.channel && s.channel.slug,
    streamer: (s.channel && s.channel.user && s.channel.user.username) || '',
    title: s.session_title || '',
    viewers: s.viewer_count || 0,
    category: (s.categories && s.categories[0] && s.categories[0].name) || '',
    thumbnail: thumb(s.thumbnail),
  })).filter((s) => s.slug && s.thumbnail);
  return json({ items }, 200, 120);
}

export async function play(url) {
  const slug = url.searchParams.get('slug') || '';
  if (!/^[A-Za-z0-9_-]{1,40}$/.test(slug)) return json({ error: 'bad slug' }, 400);
  const r = await fetch(`https://kick.com/api/v2/channels/${slug}`, { headers: UA });
  if (!r.ok) return json({ error: 'kick ' + r.status }, 502);
  const c = await r.json();
  if (!c.livestream || !c.playback_url) return json({ error: 'offline' }, 404);
  return json({ url: c.playback_url, title: c.livestream.session_title || '' });
}

function thumb(t) {
  if (!t) return '';
  if (typeof t === 'string') return t;
  if (t.src) return t.src;
  if (t.srcset) return String(t.srcset).split(',')[0].trim().split(' ')[0];
  if (t.url) return t.url;
  return '';
}

function json(o, status = 200, maxAge = 0) {
  const headers = { 'Content-Type': 'application/json; charset=utf-8' };
  if (maxAge) headers['Cache-Control'] = 'public, max-age=' + maxAge;
  return new Response(JSON.stringify(o), { status, headers });
}
