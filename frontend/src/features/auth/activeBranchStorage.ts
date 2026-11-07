const ACTIVE_BRANCH_STORAGE_KEY = 'greenmart.active-branch';

interface StoredActiveBranch {
  userId: number;
  branchId: number;
}

function getSessionStorage(): Storage | undefined {
  try {
    return typeof globalThis.sessionStorage === 'undefined' ? undefined : globalThis.sessionStorage;
  } catch {
    return undefined;
  }
}

export function readStoredActiveBranchId(userId: number): number | undefined {
  const storage = getSessionStorage();
  if (!storage) return undefined;

  let value: string | null;
  try {
    value = storage.getItem(ACTIVE_BRANCH_STORAGE_KEY);
  } catch {
    return undefined;
  }
  if (!value) return undefined;

  try {
    const parsed: unknown = JSON.parse(value);
    if (
      typeof parsed === 'object' &&
      parsed !== null &&
      'userId' in parsed &&
      'branchId' in parsed &&
      typeof parsed.userId === 'number' &&
      typeof parsed.branchId === 'number' &&
      Number.isInteger(parsed.userId) &&
      Number.isInteger(parsed.branchId) &&
      parsed.userId === userId &&
      parsed.branchId > 0
    ) {
      return parsed.branchId;
    }
  } catch {
    // Invalid local branch state is discarded; the backend branch list is authoritative.
  }

  try {
    storage.removeItem(ACTIVE_BRANCH_STORAGE_KEY);
  } catch {
    // Storage can be unavailable in restricted browser contexts.
  }
  return undefined;
}

export function writeStoredActiveBranch(userId: number, branchId: number): void {
  try {
    getSessionStorage()?.setItem(
      ACTIVE_BRANCH_STORAGE_KEY,
      JSON.stringify({ userId, branchId } satisfies StoredActiveBranch),
    );
  } catch {
    // In-memory selection remains usable for the current page session.
  }
}

export function clearStoredActiveBranch(): void {
  try {
    getSessionStorage()?.removeItem(ACTIVE_BRANCH_STORAGE_KEY);
  } catch {
    // Clearing local branch state is best effort when browser storage is unavailable.
  }
}
