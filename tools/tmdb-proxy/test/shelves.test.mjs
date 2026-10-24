// The shelves (worker.js TMDB, kick.js, kyivstar.js, spotify.js): when the service behind one is down or answers in a
// shape the Worker no longer reads, the TV gets an empty shelf with 200, never a 500, and console.error says why.
import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../worker.js';

const BASE = 'https://tv-launcher-tmdb.test';
const ctx = { waitUntil() {} };
const realFetch = globalThis.fetch;
const realError = console.error;
const realWarn = console.warn;
let errors;

beforeEach(() => {
  errors = [];
  console.error = (...a) => errors.push(a.join(' '));
  console.warn = () => {};
});
afterEach(() => {
  globalThis.fetch = realFetch;
  console.error = realError;
  console.warn = realWarn;
});

const FAILURES = {
  'network down': async () => { throw new TypeError('fetch failed'); },
  'server error': async () => new Response('oops', { status: 500 }),
  'not JSON any more': async () => new Response('<html>moved</html>', { status: 200 }),
  'a new JSON shape': async () => Response.json({ something: 'else' }),
};

const SHELVES = [
  ['/3/trending/all/week?language=uk-UA', (j) => assert.deepEqual(j.results, [])],
  ['/3/discover/movie?with_networks=213', (j) => assert.deepEqual(j.results, [])],
  ['/3/movie/603/images', (j) => assert.deepEqual(j.backdrops, [])],
  ['/kick/featured?lang=en', (j) => assert.deepEqual(j.items, [])],
  ['/kyivstar/popular', (j) => assert.deepEqual(j.items, [])],
  ['/spotify/popular', (j) => assert.deepEqual(j.items, [])],
];

for (const [failure, fake] of Object.entries(FAILURES)) {
  for (const [path, empty] of SHELVES) {
    test(`${path}: ${failure} gives an empty shelf and console.error`, async () => {
      globalThis.fetch = fake;
      const r = await worker.fetch(new Request(BASE + path), { TMDB_KEY: 'k'.repeat(32) }, ctx);
      assert.equal(r.status, 200);
      assert.notEqual(r.headers.get('Cache-Control') || '', 'public, max-age=21600');
      empty(await r.json());
      assert.ok(errors.length > 0, 'no console.error');
    });
  }
}

test('a TMDB list that works is passed on and cached', async () => {
  globalThis.fetch = async () => Response.json({ page: 1, results: [{ id: 1, title: 'X' }] });
  const r = await worker.fetch(new Request(BASE + '/3/movie/popular'), { TMDB_KEY: 'k'.repeat(32) }, ctx);
  assert.equal(r.status, 200);
  assert.equal((await r.json()).results.length, 1);
  assert.match(r.headers.get('Cache-Control'), /max-age=21600/);
  assert.equal(errors.length, 0);
});

test('an unknown title\'s images are empty without an alarm', async () => {
  globalThis.fetch = async () => new Response('{}', { status: 404 });
  const r = await worker.fetch(new Request(BASE + '/3/movie/999999999/images'), { TMDB_KEY: 'k'.repeat(32) }, ctx);
  assert.equal(r.status, 200);
  assert.deepEqual((await r.json()).backdrops, []);
  assert.equal(errors.length, 0);
});

test('without TMDB_KEY the TMDB shelf is empty and says so', async () => {
  const r = await worker.fetch(new Request(BASE + '/3/movie/popular'), {}, ctx);
  assert.equal(r.status, 200);
  assert.deepEqual((await r.json()).results, []);
  assert.ok(errors.some((e) => e.includes('TMDB_KEY')));
});
