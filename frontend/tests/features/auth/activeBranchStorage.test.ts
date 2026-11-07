import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearStoredActiveBranch,
  readStoredActiveBranchId,
  writeStoredActiveBranch,
} from '../../../src/features/auth/activeBranchStorage';

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();
  readonly length = 0;

  clear(): void {
    this.values.clear();
  }

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  key(index: number): string | null {
    return [...this.values.keys()][index] ?? null;
  }

  removeItem(key: string): void {
    this.values.delete(key);
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }
}

const storage = new MemoryStorage();

beforeEach(() => {
  storage.clear();
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: storage });
});

describe('active branch session storage', () => {
  it('stores only the selected branch and owning user identifiers', () => {
    writeStoredActiveBranch(7, 12);

    expect(readStoredActiveBranchId(7)).toBe(12);
    expect(storage.getItem('greenmart.active-branch')).toBe('{"userId":7,"branchId":12}');
  });

  it('does not reuse a branch selection from another user', () => {
    writeStoredActiveBranch(7, 12);

    expect(readStoredActiveBranchId(8)).toBeUndefined();
    expect(storage.getItem('greenmart.active-branch')).toBeNull();
  });

  it('discards malformed or non-positive branch identifiers', () => {
    storage.setItem('greenmart.active-branch', '{bad json');

    expect(readStoredActiveBranchId(7)).toBeUndefined();
    expect(storage.getItem('greenmart.active-branch')).toBeNull();

    storage.setItem('greenmart.active-branch', '{"userId":7,"branchId":0}');
    expect(readStoredActiveBranchId(7)).toBeUndefined();
  });

  it('clears active branch state on logout without touching the API session key', () => {
    writeStoredActiveBranch(7, 12);
    storage.setItem('greenmart.session', 'opaque-session-value');

    clearStoredActiveBranch();

    expect(storage.getItem('greenmart.active-branch')).toBeNull();
    expect(storage.getItem('greenmart.session')).toBe('opaque-session-value');
  });
});
