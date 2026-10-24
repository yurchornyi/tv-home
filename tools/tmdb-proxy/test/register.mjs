// Lets Node load worker.js: `cloudflare:workers` (guard.js) becomes a stub, and caches.default an empty cache.
import { register } from 'node:module';

register('data:text/javascript,' + encodeURIComponent(`
export async function resolve(specifier, context, next) {
  if (specifier === 'cloudflare:workers') {
    return { url: 'data:text/javascript,export class DurableObject {}', shortCircuit: true };
  }
  return next(specifier, context);
}`));

globalThis.caches = { default: { match: async () => undefined, put: async () => {} } };
