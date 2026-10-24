// Counting that must not miss a beat (account.js): failed PIN tries per e-mail, sign-in tries and new accounts per
// address. KV lost counts (a burst of fourteen wrong PINs left it at nine, so the lock never came) and Cloudflare's
// rate-limit bindings are kept per location; one Durable Object per key counts every request, one at a time, from
// anywhere. Each forgets itself when its window ends.
import { DurableObject } from 'cloudflare:workers';

export class Guard extends DurableObject {
  async window(windowMs) {
    const now = Date.now();
    let w = await this.ctx.storage.get('w');
    if (!w || now - w.start >= windowMs) {
      w = { start: now, n: 0 };
      await this.ctx.storage.setAlarm(now + windowMs);
    }
    return w;
  }

  /** Counts one more; false when `limit` were already counted in this window. */
  async hit(limit, windowMs) {
    const w = await this.window(windowMs);
    if (w.n >= limit) return false;
    w.n++;
    await this.ctx.storage.put('w', w);
    return true;
  }

  /** Whether `limit` were counted in this window, counting nothing. */
  async reached(limit, windowMs) {
    return (await this.window(windowMs)).n >= limit;
  }

  async clear() {
    await this.ctx.storage.deleteAll();
  }

  async alarm() {
    await this.ctx.storage.deleteAll();
  }
}
