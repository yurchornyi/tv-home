// In-memory stand-ins for the Worker's bindings: the ACCOUNTS KV namespace and the GUARD Durable Object.

export function fakeKv() {
  const data = new Map();
  return {
    data,
    async get(key, type) {
      if (!data.has(key)) return null;
      const { value } = data.get(key);
      return type === 'json' ? JSON.parse(value) : value;
    },
    async getWithMetadata(key) {
      if (!data.has(key)) return { value: null, metadata: null };
      return data.get(key);
    },
    async put(key, value, opts = {}) {
      data.set(key, { value, metadata: opts.metadata || null });
    },
    async delete(key) {
      data.delete(key);
    },
    async list({ prefix = '' } = {}) {
      return { keys: [...data.keys()].filter((k) => k.startsWith(prefix)).map((name) => ({ name })), list_complete: true };
    },
  };
}

/** Counts per name, as guard.js does, without the time window (a test runs well inside one). */
export function fakeGuard() {
  const counts = new Map();
  return {
    idFromName: (name) => name,
    get: (name) => ({
      async hit(limit) {
        const n = counts.get(name) || 0;
        if (n >= limit) return false;
        counts.set(name, n + 1);
        return true;
      },
      async reached(limit) {
        return (counts.get(name) || 0) >= limit;
      },
      async clear() {
        counts.delete(name);
      },
    }),
  };
}
