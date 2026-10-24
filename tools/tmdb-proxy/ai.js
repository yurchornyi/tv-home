// The launcher's AI features, answered through Sentra, the owner's AI centre (SENTRA_URL in vars, SENTRA_TOKEN secret,
// which Sentra writes itself): a spoken command turned into one action on the TV (/ai/command), a few films for a mood
// or company with a line on why (/ai/picks), and two sentences on a film from the shelf (/ai/about). Each goes to
// Sentra's chat route with a role, not a model: Sentra picks the model, keeps limits and spend, caches, and learns
// each feature apart (x-sentra-skill). Each answer is a JSON object checked against a fixed shape, so the TV never
// parses prose.

/** The schema as a check: true when `value` has the shape (types, enums, required keys) the TV relies on. */
function fits(schema, value) {
  if (schema.type === 'object') {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
    return (schema.required || []).every((k) => k in value && fits(schema.properties[k], value[k]));
  }
  if (schema.type === 'array') return Array.isArray(value) && value.every((v) => fits(schema.items, v));
  if (schema.type === 'integer') return Number.isInteger(value);
  if (schema.type === 'string') return typeof value === 'string' && (!schema.enum || schema.enum.includes(value));
  return true;
}

/**
 * One request to Sentra for a JSON object in `schema`; null when the answer is unusable. `skill` names the feature
 * for Sentra's learning and stats, `role` is fast (a remote waits) or quality, `cache` is how many seconds Sentra may
 * answer the same request from its cache.
 */
async function ask(env, { skill, role, system, user, schema, maxTokens, cache = 0, deadlineMs = 15000 }) {
  const headers = {
    'Content-Type': 'application/json',
    Authorization: 'Bearer ' + env.SENTRA_TOKEN.trim(),
    'x-sentra-skill': skill,
    'x-sentra-deadline-ms': String(deadlineMs),
  };
  if (cache > 0) headers['x-sentra-cache'] = String(cache);
  const response = await fetch(env.SENTRA_URL.replace(/\/+$/, '') + '/v1/chat/completions', {
    method: 'POST',
    headers,
    body: JSON.stringify({
      model: 'role:' + role,
      max_tokens: maxTokens,
      response_format: { type: 'json_object' },
      messages: [
        { role: 'system', content: `${system}\nAnswer with one JSON object only, matching this JSON Schema:\n${JSON.stringify(schema)}` },
        { role: 'user', content: user },
      ],
    }),
  });
  if (response.status === 429) throw new HttpError(429, 'busy');
  if (!response.ok) throw new HttpError(502, 'ai error ' + response.status);
  const data = await response.json();
  const text = String(data.choices?.[0]?.message?.content ?? '').replace(/^```(?:json)?\s*|\s*```$/g, '');
  try {
    const out = JSON.parse(text);
    return fits(schema, out) ? out : null;
  } catch {
    return null;
  }
}

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

const str = (v, max) => (typeof v === 'string' ? v.slice(0, max) : '');
const list = (v, max) => (Array.isArray(v) ? v.slice(0, max) : []);

// ---- /ai/command ------------------------------------------------------------------------------------------------

const COMMAND_SCHEMA = {
  type: 'object',
  properties: {
    action: {
      type: 'string',
      enum: ['open_channel', 'open_app', 'open_title', 'youtube_search', 'spotify', 'picks', 'search', 'none'],
    },
    app: { type: 'string', description: 'package of the app to use, from the lists given; empty if none' },
    channel_id: { type: 'string', description: 'id of the channel for open_channel; empty otherwise' },
    title: { type: 'string', description: 'exact shelf title for open_title; empty otherwise' },
    query: {
      type: 'string',
      description: 'search words for youtube_search, spotify or search; the mood or request for picks',
    },
    say: { type: 'string', description: 'a few words for the TV to show, in the user language' },
  },
  required: ['action', 'app', 'channel_id', 'title', 'query', 'say'],
  additionalProperties: false,
};

const COMMAND_SYSTEM = `You turn one spoken request on a living-room TV (Android TV, Ukrainian household) into exactly one action.
The request may be in Ukrainian, Russian or English. The user message lists what is on this TV: apps (package and name),
live TV channels (id, name, app), and titles shown on the home screen right now (title, app, live flag).
Choose the action that best does what was asked:
- open_channel: a TV channel by name or by topic ("новини" -> a news channel, "футбол" -> a sports channel); channel_id and app from the list.
- open_title: one of the listed home-screen titles is clearly what was asked (for example a live match that is on now); title exactly as listed, its app.
- youtube_search: a YouTube video, stream, music video or topic, when no listed title fits; query is the search; app is the YouTube app from the list.
- spotify: music, a song, an artist, a playlist or a mood for listening; query is what to play; app is the music app from the list.
- picks: the user wants suggestions of films or series to watch ("щось смішне на вечір", "що подивитися вдвох"); query restates the request.
- open_app: an app by name; app is its package.
- search: none of the above fits but a name was said; query is that name.
- none: nothing on this TV can do it; say explains briefly.
Prefer what is live right now for "наживо"/"live". Use only packages and ids that appear in the lists. say is at most six words.`;

export async function command(env, body) {
  const text = str(body.text, 300).trim();
  if (!text) throw new HttpError(400, 'no text');
  const apps = list(body.apps, 80).map((a) => `${str(a.pkg, 80)} | ${str(a.label, 60)}`).join('\n');
  const channels = list(body.channels, 400).map((c) => `${str(c.id, 40)} | ${str(c.name, 60)} | ${str(c.app, 80)}`).join('\n');
  const titles = list(body.titles, 80)
    .map((t) => `${str(t.title, 120)} | ${str(t.app, 80)}${t.live ? ' | LIVE' : ''}`).join('\n');
  const user = `Request: ${text}\n\nApps:\n${apps}\n\nChannels:\n${channels}\n\nHome-screen titles:\n${titles}`;
  const out = await ask(env, { skill: 'tv-command', role: 'fast', system: COMMAND_SYSTEM, user, schema: COMMAND_SCHEMA, maxTokens: 400, deadlineMs: 8000 });
  return out || { action: 'search', app: '', channel_id: '', title: '', query: text, say: '' };
}

// ---- /ai/picks --------------------------------------------------------------------------------------------------

const PICKS_SCHEMA = {
  type: 'object',
  properties: {
    picks: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          title: { type: 'string', description: 'the original title, as TMDB lists it' },
          year: { type: 'integer' },
          kind: { type: 'string', enum: ['movie', 'tv'] },
          why: { type: 'string', description: 'one short sentence in Ukrainian on why it fits the request' },
        },
        required: ['title', 'year', 'kind', 'why'],
        additionalProperties: false,
      },
    },
  },
  required: ['picks'],
  additionalProperties: false,
};

const PICKS_SYSTEM = `You suggest films and series for a household in Ukraine to watch tonight on their TV.
Give six well-known, well-rated picks that fit the request, mixing recent and classic, available widely on streaming.
Use the original title and release year so they can be found on TMDB. Each "why" is one short Ukrainian sentence,
at most twelve words, about this pick and this request.`;

export async function picks(env, body) {
  const request = str(body.request, 300).trim();
  if (!request) throw new HttpError(400, 'no request');
  const lang = str(body.lang, 10) || 'uk-UA';
  const out = await ask(env, { skill: 'tv-picks', role: 'quality', system: PICKS_SYSTEM, user: `Request: ${request}`, schema: PICKS_SCHEMA, maxTokens: 1200, cache: 3600 });
  const found = [];
  for (const p of list(out && out.picks, 8)) {
    const hit = await tmdbFind(env, str(p.title, 120), p.year, p.kind === 'tv' ? 'tv' : 'movie', lang);
    if (hit) found.push({ ...hit, why: str(p.why, 160) });
  }
  return { picks: found };
}

/** The best TMDB match for a title and year: its localized title, a backdrop, kind and id; null when none. */
async function tmdbFind(env, title, year, kind, lang) {
  if (!title) return null;
  const u = new URL(`https://api.themoviedb.org/3/search/${kind}`);
  u.searchParams.set('query', title);
  u.searchParams.set('language', lang);
  if (Number.isInteger(year)) u.searchParams.set(kind === 'tv' ? 'first_air_date_year' : 'year', String(year));
  const r = await fetch(tmdbUrl(env, u), { headers: tmdbHeaders(env), cache: 'no-store' });
  if (!r.ok) return null;
  const data = await r.json();
  const best = (data.results || []).find((x) => x.backdrop_path) || null;
  if (!best) return null;
  return {
    title: best.title || best.name || title,
    type: kind,
    id: best.id,
    backdrop: 'https://image.tmdb.org/t/p/w1280' + best.backdrop_path,
  };
}

function tmdbUrl(env, u) {
  const key = env.TMDB_KEY.trim();
  if (key.length <= 40) u.searchParams.set('api_key', key);
  return u.toString();
}

function tmdbHeaders(env) {
  const key = env.TMDB_KEY.trim();
  return key.length > 40 ? { Authorization: 'Bearer ' + key, Accept: 'application/json' } : { Accept: 'application/json' };
}

// ---- /ai/about --------------------------------------------------------------------------------------------------

const ABOUT_SCHEMA = {
  type: 'object',
  properties: {
    about: { type: 'string', description: 'what it is about, one Ukrainian sentence, at most 18 words, no spoilers' },
    verdict: { type: 'string', description: 'whether it is worth watching and for whom, one Ukrainian sentence, at most 14 words' },
  },
  required: ['about', 'verdict'],
  additionalProperties: false,
};

const ABOUT_SYSTEM = `You write two short lines in Ukrainian for a film or series shown on a TV home screen: what it is
about (no spoilers) and whether it is worth watching and for whom. Base them on the TMDB facts given; be honest when
the rating is mediocre. Plain sentences, no quotes, no emoji.`;

export async function about(env, body) {
  const kind = body.type === 'tv' ? 'tv' : 'movie';
  const id = Number.parseInt(body.id, 10);
  if (!Number.isInteger(id) || id <= 0) throw new HttpError(400, 'no id');
  const lang = str(body.lang, 10) || 'uk-UA';
  const u = new URL(`https://api.themoviedb.org/3/${kind}/${id}`);
  u.searchParams.set('language', lang);
  const r = await fetch(tmdbUrl(env, u), { headers: tmdbHeaders(env), cache: 'no-store' });
  if (!r.ok) throw new HttpError(404, 'unknown title');
  const d = await r.json();
  const facts = [
    `Title: ${d.title || d.name}`,
    `Original: ${d.original_title || d.original_name}`,
    `Year: ${(d.release_date || d.first_air_date || '').slice(0, 4)}`,
    `Genres: ${(d.genres || []).map((g) => g.name).join(', ')}`,
    `TMDB rating: ${d.vote_average} from ${d.vote_count} votes`,
    `Overview: ${(d.overview || '').slice(0, 900)}`,
  ].join('\n');
  const out = await ask(env, { skill: 'tv-about', role: 'quality', system: ABOUT_SYSTEM, user: facts, schema: ABOUT_SCHEMA, maxTokens: 400, cache: 7 * 24 * 3600 });
  if (!out) return { about: str(d.overview, 200), verdict: '' };
  return { about: str(out.about, 220), verdict: str(out.verdict, 160) };
}

export { HttpError };
