// Accounts (account.js): one account never reads or changes another's Home or app backup, the PIN is kept only as a
// hash, wrong PINs lock the e-mail, and the owner's e-mail cannot be claimed by signing in.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { account } from '../account.js';
import { fakeKv, fakeGuard } from './fakes.mjs';

const BASE = 'https://tv-launcher-tmdb.test';

function env(extra = {}) {
  return { ACCOUNTS: fakeKv(), GUARD: fakeGuard(), ...extra };
}

async function call(e, method, path, { token, body, raw, ip = '198.51.100.1' } = {}) {
  const headers = { 'cf-connecting-ip': ip };
  if (token) headers.Authorization = 'Bearer ' + token;
  const init = { method, headers };
  if (raw !== undefined) init.body = raw;
  else if (body !== undefined) init.body = JSON.stringify(body);
  const url = new URL(BASE + path);
  const r = await account(new Request(url, init), e, url);
  const text = await r.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {}
  return { status: r.status, json, text };
}

async function signin(e, email, pin, ip) {
  const r = await call(e, 'POST', '/account/signin', { body: { email, pin }, ip });
  return r;
}

test('one account neither reads nor overwrites another account\'s Home', async () => {
  const e = env();
  const a = (await signin(e, 'a@example.com', '1111')).json.token;
  const b = (await signin(e, 'b@example.com', '2222')).json.token;
  assert.equal((await call(e, 'PUT', '/account/state', { token: a, body: { state: { tiles: ['a'] }, device: 'TV A' } })).status, 200);

  // B sees no Home of its own yet, never A's.
  const seen = await call(e, 'GET', '/account/state', { token: b });
  assert.equal(seen.status, 200);
  assert.deepEqual(seen.json, { updated: 0 });

  // B naming A's e-mail in the body still writes only B's own Home.
  await call(e, 'PUT', '/account/state', { token: b, body: { email: 'a@example.com', state: { tiles: ['b'] } } });
  assert.deepEqual((await call(e, 'GET', '/account/state', { token: a })).json.state, { tiles: ['a'] });
  assert.deepEqual((await call(e, 'GET', '/account/state', { token: b })).json.state, { tiles: ['b'] });
});

test('one account cannot read another account\'s app backup', async () => {
  const e = env();
  const a = (await signin(e, 'a@example.com', '1111')).json.token;
  const b = (await signin(e, 'b@example.com', '2222')).json.token;
  assert.equal((await call(e, 'PUT', '/account/app/org.smarttube.stable', { token: a, raw: 'secret-bytes' })).status, 200);
  assert.equal((await call(e, 'GET', '/account/app/org.smarttube.stable', { token: b })).status, 404);
  assert.equal((await call(e, 'GET', '/account/app/org.smarttube.stable', { token: a })).text, 'secret-bytes');
});

test('no token, a made-up token or a signed-out token reads nothing', async () => {
  const e = env();
  const a = (await signin(e, 'a@example.com', '1111')).json.token;
  await call(e, 'PUT', '/account/state', { token: a, body: { state: { tiles: ['a'] } } });
  assert.equal((await call(e, 'GET', '/account/state')).status, 401);
  assert.equal((await call(e, 'GET', '/account/state', { token: 'f'.repeat(48) })).status, 401);
  await call(e, 'POST', '/account/signout', { token: a });
  assert.equal((await call(e, 'GET', '/account/state', { token: a })).status, 401);
});

test('an ordinary account cannot list, block or delete accounts', async () => {
  const e = env({ ADMIN_EMAIL: 'owner@example.com' });
  const a = (await signin(e, 'a@example.com', '1111')).json.token;
  await signin(e, 'b@example.com', '2222');
  assert.equal((await call(e, 'GET', '/account/admin/accounts', { token: a })).status, 403);
  assert.equal((await call(e, 'POST', '/account/admin/delete', { token: a, body: { email: 'b@example.com' } })).status, 403);
  assert.equal((await call(e, 'POST', '/account/admin/block', { token: a, body: { email: 'b@example.com', blocked: true } })).status, 403);
  assert.ok(e.ACCOUNTS.data.has('u:b@example.com'));
});

test('the PIN is kept only as a salted hash', async () => {
  const e = env();
  await signin(e, 'a@example.com', '482913');
  const user = JSON.parse(e.ACCOUNTS.data.get('u:a@example.com').value);
  assert.deepEqual(Object.keys(user).sort(), ['created', 'hash', 'salt']);
  assert.match(user.hash, /^[0-9a-f]{64}$/);
  for (const [key, { value }] of e.ACCOUNTS.data) {
    assert.ok(!String(value).includes('482913'), 'PIN in plain text under ' + key);
  }
});

test('ten wrong PINs lock the e-mail, even for the right PIN', async () => {
  const e = env();
  await signin(e, 'a@example.com', '1111');
  for (let i = 0; i < 10; i++) {
    // Different addresses, so the per-address limit is not what stops them.
    assert.equal((await signin(e, 'a@example.com', '9999', `203.0.113.${i}`)).status, 403);
  }
  const r = await signin(e, 'a@example.com', '1111', '203.0.113.200');
  assert.equal(r.status, 429);
  assert.equal(r.json.error, 'locked');
});

test('one address gets ten sign-in tries a minute', async () => {
  const e = env();
  for (let i = 0; i < 10; i++) await signin(e, `u${i}@example.com`, '1111', '192.0.2.7');
  assert.equal((await signin(e, 'late@example.com', '1111', '192.0.2.7')).status, 429);
});

test('the owner\'s e-mail cannot be claimed by signing in first', async () => {
  const e = env({ ADMIN_EMAIL: 'owner@example.com' });
  const r = await signin(e, 'Owner@Example.com', '1111');
  assert.equal(r.status, 403);
  assert.ok(!e.ACCOUNTS.data.has('u:owner@example.com'));
});
