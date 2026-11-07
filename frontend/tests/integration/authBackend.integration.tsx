import { act, useEffect } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { getCurrentUser, readStoredSession, type Role } from '../../src/api/client';
import { ActiveBranchProvider } from '../../src/features/auth/ActiveBranchProvider';
import { AppRoutes } from '../../src/features/auth/AppRoutes';
import { AuthProvider } from '../../src/features/auth/AuthProvider';
import { useAuth } from '../../src/features/auth/useAuth';

// Explicit opt-in command; no mock API and no credentials committed to the repo.
const password = process.env.GREENMART_DEMO_PASSWORD;
if (!password) throw new Error('Set GREENMART_DEMO_PASSWORD for the local demo database.');
const base = 'http://127.0.0.1:8080';
const backendFetch = globalThis.fetch;
let root: Root;
let container: HTMLDivElement;
let auth: ReturnType<typeof useAuth>;
let calls: Array<{ path: string; status: number }>;

export function ObserveAuth() {
  const current = useAuth();
  useEffect(() => {
    auth = current;
  }, [current]);
  return null;
}

beforeEach(() => {
  calls = [];
  sessionStorage.clear();
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  vi.stubGlobal('fetch', async (path: string, init?: RequestInit) => {
    const response = await backendFetch(new URL(path, base), init);
    calls.push({ path, status: response.status });
    return response;
  });
  container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
});

afterEach(async () => {
  await act(async () => {
    if (readStoredSession()) await auth.signOut();
  });
  await act(async () => root.unmount());
  container.remove();
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

async function render(path: string, reload = false) {
  if (reload) {
    await act(async () => root.unmount());
    root = createRoot(container);
  }
  window.history.replaceState(null, '', path);
  await act(async () => {
    root.render(
      <BrowserRouter>
        <AuthProvider>
          <ObserveAuth />
          <ActiveBranchProvider>
            <AppRoutes />
          </ActiveBranchProvider>
        </AuthProvider>
      </BrowserRouter>,
    );
  });
  await settle(() => auth.state.status !== 'checking');
}

async function settle(condition: () => boolean) {
  const until = Date.now() + 10_000;
  while (!condition() && Date.now() < until) {
    await act(async () => new Promise((resolve) => setTimeout(resolve, 20)));
  }
  expect(condition()).toBe(true);
}

async function fill(id: string, value: string) {
  await act(async () => {
    const element = container.querySelector<HTMLInputElement>(`#login-${id}`)!;
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

async function submit() {
  await act(async () => {
    container
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  });
}

const accounts: Array<{
  username: string;
  role: Role;
  branches: number;
  workspace: string;
  forbidden: string;
}> = [
  {
    username: 'thungan.gm001',
    role: 'CASHIER',
    branches: 1,
    workspace: '/pos',
    forbidden: '/operations/stock-documents',
  },
  {
    username: 'quanly.gm001',
    role: 'BRANCH_MANAGER',
    branches: 1,
    workspace: '/operations',
    forbidden: '/admin',
  },
  {
    username: 'thukho.gm001',
    role: 'STOREKEEPER',
    branches: 1,
    workspace: '/operations',
    forbidden: '/pos',
  },
  { username: 'owner', role: 'OWNER', branches: 3, workspace: '/operations', forbidden: '/admin' },
  {
    username: 'admin',
    role: 'ADMIN',
    branches: 3,
    workspace: '/admin',
    forbidden: '/operations/stock-documents',
  },
];

it.each(accounts)(
  'authenticates $role with the real backend: branch, workspace, reload, RBAC, refresh and logout',
  async (account) => {
    await render('/login');
    await fill('username', account.username);
    await fill('password', password);
    await act(async () => {
      container
        .querySelector('form')!
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    });
    await settle(() => window.location.pathname !== '/login');
    expect(readStoredSession()?.user.role).toBe(account.role);
    expect(readStoredSession()?.user.branches).toHaveLength(account.branches);
    if (account.role === 'OWNER') {
      expect(window.location.pathname).toBe('/select-branch');
      await act(async () =>
        container.querySelector<HTMLButtonElement>('.branch-page__row-select')!.click(),
      );
      await act(async () =>
        container.querySelector<HTMLButtonElement>('.branch-page__actions button')!.click(),
      );
    }
    expect(window.location.pathname).toBe(account.workspace);
    await render(account.workspace, true);
    expect(window.location.pathname).toBe(account.workspace);
    expect(calls).toContainEqual({ path: '/api/v1/auth/me', status: 200 });

    // Only invalidate our local access token; refresh stays genuine and server-owned.
    const previous = readStoredSession()!;
    sessionStorage.setItem(
      'greenmart.session',
      JSON.stringify({ ...previous, accessToken: 'invalid-test-access' }),
    );
    await act(async () => {
      expect((await getCurrentUser()).role).toBe(account.role);
    });
    expect(calls).toContainEqual({ path: '/api/v1/auth/refresh', status: 200 });
    expect(readStoredSession()?.refreshToken).not.toBe(previous.refreshToken);

    await render(account.forbidden, true);
    expect(window.location.pathname).toBe('/403');
    const current = readStoredSession()!;
    sessionStorage.setItem(
      'greenmart.session',
      JSON.stringify({ ...current, accessToken: 'invalid-test-access' }),
    );
    await act(async () => auth.signOut());
    expect(readStoredSession()).toBeNull();
    expect(sessionStorage.getItem('greenmart.active-branch')).toBeNull();
    expect(calls.slice(-3)).toEqual([
      { path: '/api/v1/auth/logout', status: 401 },
      { path: '/api/v1/auth/refresh', status: 200 },
      { path: '/api/v1/auth/logout', status: 204 },
    ]);
    await render(account.workspace, true);
    expect(window.location.pathname).toBe('/login');
  },
);

it('returns to the complete protected URL after the real backend rejects a revoked refresh token', async () => {
  const destination = '/pos/receipts?range=today#receipt-42';
  await render(destination);
  await fill('username', 'thungan.gm001');
  await fill('password', password);
  await submit();
  await settle(() => window.location.pathname === '/pos/receipts');
  const previous = readStoredSession()!;
  const revoked = await backendFetch(`${base}/api/v1/auth/logout`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${previous.accessToken}`,
    },
    body: JSON.stringify({ refreshToken: previous.refreshToken }),
  });
  expect(revoked.status).toBe(204);
  sessionStorage.setItem(
    'greenmart.session',
    JSON.stringify({ ...previous, accessToken: 'invalid-test-access' }),
  );
  await act(async () => {
    await expect(getCurrentUser()).rejects.toThrow();
  });
  expect(window.location.pathname).toBe('/login');
  expect(container.textContent).toContain('Phiên đăng nhập đã hết hạn');
  expect(calls).toContainEqual({ path: '/api/v1/auth/refresh', status: 401 });
  await fill('username', 'thungan.gm001');
  await fill('password', password);
  await submit();
  await settle(() => window.location.pathname === '/pos/receipts');
  expect(`${window.location.pathname}${window.location.search}${window.location.hash}`).toBe(
    destination,
  );
});

it('renders real invalid-credential feedback and allows a successful retry', async () => {
  await render('/login');
  await fill('username', 'thungan.gm002');
  await fill('password', 'deliberately-incorrect-test-value');
  await submit();
  await settle(() => container.textContent?.includes('Bạn còn 4 lần thử') === true);
  expect(calls).toContainEqual({ path: '/api/v1/auth/login', status: 401 });
  await fill('password', password);
  await submit();
  await settle(() => window.location.pathname === '/pos');
});
