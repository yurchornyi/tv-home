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

/** Kyivstar's clients API as it answers an anonymous visitor: `sizes` gives each group's number of assets. */
function fakeKyivstar(sizes) {
  const asset = (group, i) => ({
    assetId: `${group}-${i}`, name: `${group} ${i}`, assetType: i % 2 ? 'SERIES' : 'MOVIE',
    imagesSource: [{ url: 'https://img.test/' + group + i, imgFiles: [{ fileName: 'IMAGE_16_9_XL.jpg' }] }],
  });
  const ids = { top: '68f251f535b4956d90011a23', films: '661cec7dc7b45a1690959607', series: '660a7dc314f4b3f94bbe7777',
    fresh: '67efcd0583d4a7114f86703c', ukrainian: '66e024925bd49f112008988a' };
  return async (u) => {
    const url = String(u);
    if (url.includes('/authentication/login')) return Response.json({ sessionId: 'anon' });
    if (url.includes('/contentareas/')) return Response.json([]);
    const m = /contentgroups\/([0-9a-f]{24})/.exec(url);
    const name = m && Object.keys(ids).find((k) => ids[k] === m[1]);
    if (!name) return new Response('not found', { status: 404 });
    return Response.json({ assets: Array.from({ length: sizes[name] || 0 }, (_, i) => asset(name, i)) });
  };
}

test('/kyivstar/popular: empty popular films and series are topped up with new and Ukrainian ones', async () => {
  globalThis.fetch = fakeKyivstar({ top: 10, films: 0, series: 0, fresh: 20, ukrainian: 20 });
  const r = await worker.fetch(new Request(BASE + '/kyivstar/popular?t=fallback'), {}, ctx);
  const { items } = await r.json();
  assert.equal(items.length, 30);
  assert.deepEqual(items.slice(0, 10).map((i) => i.title), Array.from({ length: 10 }, (_, i) => `top ${i}`));
  assert.deepEqual(items.slice(10, 14).map((i) => i.title), ['fresh 0', 'ukrainian 0', 'fresh 1', 'ukrainian 1']);
  assert.ok(errors.some((e) => e.includes('kyivstar group empty') && e.includes('Найпопулярніші фільми')));
  assert.ok(errors.some((e) => e.includes('Найпопулярніші серіали')));
});

test('/kyivstar/popular: with the popular groups full, the fallbacks are not asked', async () => {
  const fake = fakeKyivstar({ top: 10, films: 20, series: 20, fresh: 20, ukrainian: 20 });
  const asked = [];
  globalThis.fetch = (u, o) => {
    asked.push(String(u));
    return fake(u, o);
  };
  const { items } = await (await worker.fetch(new Request(BASE + '/kyivstar/popular?t=full'), {}, ctx)).json();
  assert.equal(items.length, 30);
  assert.ok(!asked.some((u) => u.includes('67efcd0583d4a7114f86703c')));
  assert.equal(errors.length, 0);
});
