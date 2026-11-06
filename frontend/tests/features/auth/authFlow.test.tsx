// @vitest-environment jsdom
import { act, useEffect } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getCurrentUser, type AuthSession, type Role } from '../../../src/api/client';
import { ActiveBranchProvider } from '../../../src/features/auth/ActiveBranchProvider';
import { AppRoutes } from '../../../src/features/auth/AppRoutes';
import { AuthProvider } from '../../../src/features/auth/AuthProvider';

const branches = [1, 2, 3].map((id) => ({ id, code: `GM-00${id}`, name: `Chi nhánh ${id}` }));
function session(role: Role = 'CASHIER', count = 1): AuthSession {
  return {
    accessToken: 'test-access',
    refreshToken: 'test-refresh',
    tokenType: 'Bearer',
    expiresIn: 900,
    user: {
      id: 7,
      username: 'test-user',
      fullName: 'Test User',
      role,
      branches: branches.slice(0, count),
    },
  };
}
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });

let container: HTMLDivElement;
let root: Root;
let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;
let currentPath: string;

function LocationObserver() {
  const location = useLocation();
  useEffect(() => {
    currentPath = `${location.pathname}${location.search}${location.hash}`;
  }, [location]);
  return null;
}

beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  fetchMock = vi.fn<typeof fetch>();
  vi.stubGlobal('fetch', fetchMock);
  sessionStorage.clear();
  container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
});

afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
  sessionStorage.clear();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

async function render(path = '/login') {
  await act(async () => {
    root.render(
      <MemoryRouter initialEntries={[path]}>
        <LocationObserver />
        <AuthProvider>
          <ActiveBranchProvider>
            <AppRoutes />
          </ActiveBranchProvider>
        </AuthProvider>
      </MemoryRouter>,
    );
  });
}

function input(id: string) {
  const element = container.querySelector<HTMLInputElement>(`#login-${id}`);
  if (!element) throw new Error(`Missing login ${id}`);
  return element;
}

async function fill(id: string, value: string) {
  await act(async () => {
    const element = input(id);
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

async function enterCredentials() {
  await fill('username', 'test-user');
  await fill('password', 'test-password');
}

describe('rendered authentication flow', () => {
  it('keeps the Login form mounted during a delayed rejection and displays remaining attempts', async () => {
    let reply!: (response: Response) => void;
    fetchMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          reply = resolve;
        }),
    );
    await render();
    await enterCredentials();
    await submit();

    expect(input('username').value).toBe('test-user');
    expect(input('username').disabled).toBe(true);
    await submit();
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await act(async () =>
      reply(json({ code: 'INVALID_CREDENTIALS', details: { remainingAttempts: 4 } }, 401)),
    );
    expect(container.textContent).toContain('Bạn còn 4 lần thử');
    expect(input('username').value).toBe('test-user');
    expect(input('password').disabled).toBe(false);
  });

  it('displays the server lock deadline in Vietnam time, permits another username and unlocks at the deadline', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-07T02:00:00Z'));
    let reply!: (response: Response) => void;
    fetchMock.mockImplementation(
      () =>
        new Promise((resolve) => {
          reply = resolve;
        }),
    );
    await render();
    await enterCredentials();
    await submit();
    await act(async () =>
      reply(
        json({ code: 'ACCOUNT_LOCKED', details: { lockedUntil: '2026-11-07T02:15:00Z' } }, 401),
      ),
    );

    expect(container.textContent).toContain('09:15');
    expect(input('password').disabled).toBe(true);
    expect(input('username').disabled).toBe(false);
    await fill('username', 'another-user');
    expect(input('password').disabled).toBe(false);
    await fill('username', 'test-user');
    expect(input('password').disabled).toBe(true);
    await act(async () => vi.advanceTimersByTime(15 * 60 * 1000));
    expect(input('password').disabled).toBe(false);
    fetchMock.mockResolvedValueOnce(json(session()));
    await submit();
    expect(currentPath).toBe('/pos');
  });

  it('redirects an expired session to Login and restores pathname, search and hash after reauthentication', async () => {
    const auth = session();
    sessionStorage.setItem('greenmart.session', JSON.stringify(auth));
    fetchMock.mockResolvedValueOnce(json(auth.user));
    await render('/pos/receipts?range=today#receipt-42');
    fetchMock.mockResolvedValueOnce(json({ code: 'UNAUTHORIZED' }, 401));
    fetchMock.mockResolvedValueOnce(json({ code: 'INVALID_REFRESH_TOKEN' }, 401));
    await act(async () => {
      await expect(getCurrentUser()).rejects.toThrow();
    });
    expect(currentPath).toBe('/login');
    expect(container.textContent).toContain('Phiên đăng nhập đã hết hạn');
    expect(sessionStorage.getItem('greenmart.session')).toBeNull();
    await enterCredentials();
    fetchMock.mockResolvedValueOnce(json(auth));
    await submit();
    expect(currentPath).toBe('/pos/receipts?range=today#receipt-42');
  });

  it('aborts a Login after 15 seconds, retains the form and permits retry', async () => {
    vi.useFakeTimers();
    fetchMock.mockImplementation(
      (_url, init) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => reject(init.signal?.reason), {
            once: true,
          });
        }),
    );
    await render();
    await enterCredentials();
    await submit();
    await act(async () => vi.advanceTimersByTime(15_000));
    expect(container.textContent).toContain('Hết thời gian chờ');
    expect(input('username').value).toBe('test-user');
    expect(input('username').disabled).toBe(false);
    expect(sessionStorage.getItem('greenmart.session')).toBeNull();
    fetchMock.mockResolvedValueOnce(json(session()));
    await submit();
    expect(currentPath).toBe('/pos');
  });

  it('delays session loading 300 ms and offers retry after 15 seconds without erasing the session', async () => {
    vi.useFakeTimers();
    sessionStorage.setItem('greenmart.session', JSON.stringify(session()));
    fetchMock.mockImplementation(() => new Promise(() => undefined));
    await render('/pos');
    await act(async () => vi.advanceTimersByTime(299));
    expect(container.querySelector('[role="status"]')).toBeNull();
    await act(async () => vi.advanceTimersByTime(1));
    expect(container.textContent).toContain('Đang kiểm tra phiên đăng nhập');
    await act(async () => vi.advanceTimersByTime(14_700));
    expect(container.textContent).toContain('Thử lại');
    expect(sessionStorage.getItem('greenmart.session')).not.toBeNull();
    fetchMock.mockResolvedValueOnce(json(session().user));
    await act(async () => container.querySelector('button')!.click());
    expect(container.textContent).toContain('POS');
    expect(currentPath).toBe('/pos');
  });

  it('checks RBAC again if the role changes during reauthentication', async () => {
    await render('/pos/receipts?range=today#receipt-42');
    fetchMock.mockResolvedValueOnce(json(session('ADMIN', 3)));
    await enterCredentials();
    await submit();
    expect(currentPath).toBe('/403');
  });

  it.each([0, 1, 3])('routes ADMIN with %i branches directly to Admin', async (count) => {
    const auth = session('ADMIN', count);
    fetchMock.mockResolvedValueOnce(json(auth));
    await render();
    await enterCredentials();
    await submit();
    expect(currentPath).toBe('/admin');
  });

  it.each(['CASHIER', 'BRANCH_MANAGER', 'STOREKEEPER', 'OWNER'] as const)(
    'requires branch selection for %s with multiple branches',
    async (role) => {
      fetchMock.mockResolvedValueOnce(json(session(role, 3)));
      await render();
      await enterCredentials();
      await submit();
      expect(currentPath).toBe('/select-branch');
      await act(async () =>
        container.querySelector<HTMLButtonElement>('.branch-page__row-select')!.click(),
      );
      await act(async () =>
        container.querySelector<HTMLButtonElement>('.branch-page__actions button')!.click(),
      );
      expect(currentPath).toBe(role === 'CASHIER' ? '/pos' : '/operations');
    },
  );

  it.each([
    { userId: 7, branchId: 99 },
    { userId: 999, branchId: 1 },
  ])('discards an invalid or other-user stored branch on reload: %j', async (stored) => {
    const auth = session('OWNER', 3);
    sessionStorage.setItem('greenmart.session', JSON.stringify(auth));
    sessionStorage.setItem('greenmart.active-branch', JSON.stringify(stored));
    fetchMock.mockResolvedValueOnce(json(auth.user));
    await render('/operations');
    expect(currentPath).toBe('/select-branch');
    expect(sessionStorage.getItem('greenmart.active-branch')).toBeNull();
  });

  it.each(['CASHIER', 'ADMIN'] as const)(
    'denies a direct stock-document URL for %s',
    async (role) => {
      const auth = session(role);
      sessionStorage.setItem('greenmart.session', JSON.stringify(auth));
      fetchMock.mockResolvedValueOnce(json(auth.user));
      await render('/operations/stock-documents');
      expect(currentPath).toBe('/403');
      expect(container.textContent).toContain('Bạn không có quyền truy cập');
    },
  );
});
