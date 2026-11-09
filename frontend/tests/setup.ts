import { beforeEach } from 'vitest';

class MemoryStorage {
  private store = new Map<string, string>();

  get length() {
    return this.store.size;
  }

  clear() {
    this.store.clear();
  }

  getItem(key: string) {
    return this.store.get(key) ?? null;
  }

  setItem(key: string, value: string) {
    this.store.set(key, String(value));
  }

  removeItem(key: string) {
    this.store.delete(key);
  }

  key(index: number) {
    return Array.from(this.store.keys())[index] ?? null;
  }
}

Object.defineProperty(globalThis, 'sessionStorage', {
  configurable: true,
  writable: true,
  value: new MemoryStorage(),
});

console.log('[Vitest setup] sessionStorage initialized');

beforeEach(() => {
  globalThis.sessionStorage.clear();
});
