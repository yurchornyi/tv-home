// Launcher accounts (launcher Account, AccountActivity): an e-mail and a PIN of four to six digits, so one owner's
// TVs keep the same Home. A TV signed in sends its state (the tile order, what is on Home, the shelf and channel
// choices, the apps installed) whenever it changes, and takes the newest from here; a new TV signed in to the same
// account gets that Home and the list of apps to install. Kept in the ACCOUNTS KV namespace:
//   u:<email>  { salt, hash, created }   the PIN only as PBKDF2-SHA256, never itself
//   t:<token>  <email>                   a TV's sign-in, until it signs out
//   s:<email>  { state, updated, device } the newest state
//   f:<email>  failed PIN tries in the last 15 minutes; ten lock the account until they expire
//   b:<email>  blocked by the owner: no sign-in, no sync, until unblocked
// Each address also gets few tries a minute (ACCOUNT_LIMIT), so six digits cannot be guessed from one place.
// The owner's own account (ADMIN_EMAIL in wrangler.toml) also sees every account and can delete one (/account/admin).

const enc = new TextEncoder();
const MAX_STATE = 512 * 1024;

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function json(o, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { 'Content-Type': 'application/json; charset=utf-8' } });
}

const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');

async function pinHash(pin, salt) {
  const key = await crypto.subtle.importKey('raw', enc.encode(pin), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: enc.encode(salt), iterations: 100000 }, key, 256);
  return hex(bits);
}

function same(a, b) {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

function email(v) {
  const e = typeof v === 'string' ? v.trim().toLowerCase() : '';
  if (e.length > 120 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(e)) throw new HttpError(400, 'bad email');
  return e;
}

function pin(v) {
  const p = typeof v === 'string' ? v.trim() : '';
  if (!/^\d{4,6}$/.test(p)) throw new HttpError(400, 'bad pin');
  return p;
}

async function newToken(env, who) {
  const token = hex(crypto.getRandomValues(new Uint8Array(24)));
  await env.ACCOUNTS.put('t:' + token, who);
  return token;
}

async function signedIn(request, env) {
  const auth = request.headers.get('Authorization') || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  if (!/^[0-9a-f]{48}$/.test(token)) throw new HttpError(401, 'signed out');
  const who = await env.ACCOUNTS.get('t:' + token);
  if (!who) throw new HttpError(401, 'signed out');
  if (await env.ACCOUNTS.get('b:' + who)) throw new HttpError(403, 'blocked');
  return { who, token };
}

/** POST /account/signin {email, pin}: signs in, or makes the account when there is none for that e-mail yet. */
async function signin(request, env) {
  const body = await request.json().catch(() => ({}));
  const who = email(body.email);
  const p = pin(body.pin);
  if (await env.ACCOUNTS.get('b:' + who)) throw new HttpError(403, 'blocked');
  const user = await env.ACCOUNTS.get('u:' + who, 'json');
  if (!user) {
    const salt = hex(crypto.getRandomValues(new Uint8Array(16)));
    await env.ACCOUNTS.put('u:' + who, JSON.stringify({ salt, hash: await pinHash(p, salt), created: Date.now() }));
    return json({ token: await newToken(env, who), created: true, email: who, admin: isOwner(env, who) });
  }
  const failed = Number.parseInt((await env.ACCOUNTS.get('f:' + who)) || '0', 10);
  if (failed >= 10) throw new HttpError(429, 'locked');
  if (!same(await pinHash(p, user.salt), user.hash)) {
    await env.ACCOUNTS.put('f:' + who, String(failed + 1), { expirationTtl: 15 * 60 });
    throw new HttpError(403, 'wrong pin');
  }
  if (failed) await env.ACCOUNTS.delete('f:' + who);
  const state = await env.ACCOUNTS.get('s:' + who, 'json');
  return json({ token: await newToken(env, who), created: false, email: who, updated: state ? state.updated : 0,
    admin: isOwner(env, who) });
}

/** GET /account/state: the newest state, or {updated: 0} when no TV has sent one yet. */
async function getState(request, env) {
  const { who } = await signedIn(request, env);
  const state = await env.ACCOUNTS.get('s:' + who, 'json');
  return json(state || { updated: 0 });
}

/** PUT /account/state {state, device}: keeps it as the newest; answers its time. */
async function putState(request, env) {
  const { who } = await signedIn(request, env);
  const text = await request.text();
  if (text.length > MAX_STATE) throw new HttpError(413, 'too large');
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    throw new HttpError(400, 'bad json');
  }
  if (!body || typeof body.state !== 'object') throw new HttpError(400, 'no state');
  const updated = Date.now();
  const device = typeof body.device === 'string' ? body.device.slice(0, 80) : '';
  await env.ACCOUNTS.put('s:' + who, JSON.stringify({ state: body.state, updated, device }));
  return json({ updated });
}

/** POST /account/signout: this TV's sign-in ends; the account and its state stay. */
async function signout(request, env) {
  const { token } = await signedIn(request, env);
  await env.ACCOUNTS.delete('t:' + token);
  return json({ ok: true });
}

function isOwner(env, who) {
  const owner = String(env.ADMIN_EMAIL || '').trim().toLowerCase();
  return Boolean(owner) && who === owner;
}

async function admin(request, env) {
  const { who } = await signedIn(request, env);
  if (!isOwner(env, who)) throw new HttpError(403, 'not admin');
  return who;
}

/** Every key with a prefix (KV lists a thousand at a time). */
async function keys(env, prefix) {
  const out = [];
  let cursor;
  do {
    const page = await env.ACCOUNTS.list({ prefix, cursor });
    out.push(...page.keys.map((k) => k.name));
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  return out;
}

/** GET /account/admin/accounts: each account with when it was made, its last sync, the TV that sent it, its apps. */
async function adminList(request, env) {
  await admin(request, env);
  const emails = (await keys(env, 'u:')).map((k) => k.slice(2));
  const accounts = await Promise.all(emails.map(async (e) => {
    const [user, state, blocked] = await Promise.all([env.ACCOUNTS.get('u:' + e, 'json'),
      env.ACCOUNTS.get('s:' + e, 'json'), env.ACCOUNTS.get('b:' + e)]);
    return {
      email: e,
      created: (user && user.created) || 0,
      updated: (state && state.updated) || 0,
      device: (state && state.device) || '',
      apps: state && state.state && Array.isArray(state.state.apps) ? state.state.apps.length : 0,
      blocked: Boolean(blocked),
    };
  }));
  accounts.sort((a, b) => (b.updated || b.created) - (a.updated || a.created));
  return json({ accounts });
}

/** POST /account/admin/delete {email}: the account, its state and every TV's sign-in to it go. Not the owner's own. */
async function adminDelete(request, env) {
  const owner = await admin(request, env);
  const body = await request.json().catch(() => ({}));
  const who = email(body.email);
  if (who === owner) throw new HttpError(400, 'own account');
  const tokens = await keys(env, 't:');
  const theirs = (await Promise.all(tokens.map(async (t) => ((await env.ACCOUNTS.get(t)) === who ? t : null))))
    .filter(Boolean);
  await Promise.all([...theirs, 'u:' + who, 's:' + who, 'f:' + who, 'b:' + who].map((k) => env.ACCOUNTS.delete(k)));
  return json({ ok: true, signedOut: theirs.length });
}

/** POST /account/admin/block {email, blocked}: a blocked account can neither sign in nor sync; its Home is kept. */
async function adminBlock(request, env) {
  const owner = await admin(request, env);
  const body = await request.json().catch(() => ({}));
  const who = email(body.email);
  if (who === owner) throw new HttpError(400, 'own account');
  if (body.blocked) await env.ACCOUNTS.put('b:' + who, String(Date.now()));
  else await env.ACCOUNTS.delete('b:' + who);
  return json({ ok: true, blocked: Boolean(body.blocked) });
}

const ROUTES = {
  'GET /account/admin/accounts': adminList,
  'POST /account/admin/delete': adminDelete,
  'POST /account/admin/block': adminBlock,
  'POST /account/signin': signin,
  'GET /account/state': getState,
  'PUT /account/state': putState,
  'POST /account/signout': signout,
};

export function isAccountRoute(request, url) {
  return Boolean(ROUTES[request.method + ' ' + url.pathname]);
}

export async function account(request, env, url) {
  if (!env.ACCOUNTS) return json({ error: 'not configured' }, 503);
  if (env.ACCOUNT_LIMIT && url.pathname === '/account/signin') {
    const { success } = await env.ACCOUNT_LIMIT.limit({ key: request.headers.get('cf-connecting-ip') || 'unknown' });
    if (!success) return json({ error: 'slow down' }, 429);
  }
  try {
    return await ROUTES[request.method + ' ' + url.pathname](request, env);
  } catch (e) {
    if (e instanceof HttpError) return json({ error: e.message }, e.status);
    console.error('account route failed', url.pathname, e instanceof Error ? `${e.name}: ${e.message}` : String(e));
    return json({ error: 'failed' }, 500);
  }
}
