import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  SessionExpiredError,
  clearStoredSession,
  getCurrentUser,
  getOrder,
  listOrders,
  login,
  logout,
  lookupProduct,
  request,
  searchProducts,
} from '../../src/api/client';

const session = {
  accessToken: 'test-access',
  refreshToken: 'test-refresh',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: { id: 1 },
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;
beforeEach(() => {
  const storage = new Map<string, string>();
  vi.stubGlobal('sessionStorage', {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
    removeItem: (key: string) => storage.delete(key),
  });
  fetchMock = vi.fn<typeof fetch>();
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => vi.unstubAllGlobals());
async function signIn() {
  fetchMock.mockResolvedValueOnce(json(session));
  await login('test-user', 'test-only');
  fetchMock.mockClear();
}

describe('API transport', () => {
  it('encodes search and forwards cancellation and authorization', async () => {
    await signIn();
    fetchMock.mockResolvedValueOnce(json({ content: [] }));
    const signal = new AbortController().signal;
    await searchProducts(' sữa & gạo ', signal);
    const [url, init] = fetchMock.mock.calls[0];
    expect(new URL(String(url), 'http://local').searchParams.get('q')).toBe('sữa & gạo');
    expect(init?.signal).toBe(signal);
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer test-access');
  });
  it('serializes repeated statuses, false, zero and required lookup parameters', async () => {
    fetchMock.mockImplementation(async () => json({}));
    await listOrders({ status: ['RESERVED', 'CONFIRMED'], page: 0, from: '2026-10-30' });
    const query = new URL(String(fetchMock.mock.calls[0][0]), 'http://local').searchParams;
    expect(query.getAll('status')).toEqual(['RESERVED', 'CONFIRMED']);
    expect(query.get('page')).toBe('0');
    await request('/products', 'get', { query: { active: false } });
    expect(fetchMock.mock.calls[1][0]).toBe('/api/v1/products?active=false');
    await lookupProduct('SKU/A', 2);
    expect(fetchMock.mock.calls[2][0]).toBe('/api/v1/products/lookup?code=SKU%2FA&branchId=2');
    await getOrder(42);
    expect(fetchMock.mock.calls[3][0]).toBe('/api/v1/orders/42');
  });
  it('preserves error payload without interpreting its message or code', async () => {
    const payload = {
      code: 'TEST_BACKEND_CODE',
      message: 'backend message',
      details: { lines: [1] },
      traceId: 'trace-test',
    };
    fetchMock.mockResolvedValueOnce(json(payload, 409));
    await expect(getOrder(1)).rejects.toMatchObject({ ...payload, status: 409 });
  });
  it('does not retry a network failure', async () => {
    const error = new TypeError('offline');
    fetchMock.mockRejectedValueOnce(error);
    await expect(getOrder(1)).rejects.toBe(error);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it('reports invalid JSON and non-JSON responses', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response('{', { headers: { 'content-type': 'application/json' } }),
    );
    await expect(getOrder(1)).rejects.toBeInstanceOf(ApiError);
    fetchMock.mockResolvedValueOnce(new Response('unavailable', { status: 503 }));
    await expect(getOrder(1)).rejects.toMatchObject({ status: 503 });
  });
  it('does not send an already aborted request', async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(getOrder(1, controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('session refresh', () => {
  it('shares a refresh across simultaneous 401s and retries with the new token', async () => {
    await signIn();
    let release!: (response: Response) => void;
    fetchMock.mockImplementation(async (url, init) => {
      if (url === '/api/v1/auth/refresh')
        return new Promise<Response>((resolve) => {
          release = resolve;
        });
      return new Headers(init?.headers).get('Authorization') === 'Bearer test-access'
        ? json({}, 401)
        : json({ id: 1 });
    });
    const first = getCurrentUser();
    const second = getOrder(1);
    await vi.waitFor(() => expect(release).toBeTypeOf('function'));
    release(json({ ...session, accessToken: 'new-access', refreshToken: 'new-refresh' }));
    await Promise.all([first, second]);
    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/v1/auth/refresh')).toHaveLength(1);
    expect(fetchMock).toHaveBeenCalledTimes(5);
  });
  it('retries at most once even when the new access token is rejected', async () => {
    await signIn();
    fetchMock
      .mockResolvedValueOnce(json({}, 401))
      .mockResolvedValueOnce(json({ ...session, accessToken: 'new' }))
      .mockResolvedValueOnce(json({}, 401));
    await expect(getOrder(1)).rejects.toMatchObject({ status: 401 });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });
  it('clears an expired session while retaining the original backend code', async () => {
    await signIn();
    fetchMock
      .mockResolvedValueOnce(json({}, 401))
      .mockResolvedValueOnce(json({ code: 'TEST_EXPIRED', traceId: 'trace' }, 401));
    await expect(getOrder(1)).rejects.toMatchObject({
      name: 'SessionExpiredError',
      code: 'TEST_EXPIRED',
      traceId: 'trace',
    });
    expect(sessionStorage.getItem('greenmart.session')).toBeNull();
  });
  it('does not refresh failed login or anonymous requests', async () => {
    fetchMock.mockResolvedValue(json({}, 401));
    await expect(login('test', 'test-only')).rejects.toMatchObject({ status: 401 });
    await expect(getOrder(1)).rejects.toMatchObject({ status: 401 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
  it('does not resurrect a logged-out session after late refresh', async () => {
    await signIn();
    let release!: (response: Response) => void;
    fetchMock.mockResolvedValueOnce(json({}, 401)).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          release = resolve;
        }),
    );
    const pending = getOrder(1);
    const rejection = expect(pending).rejects.toBeInstanceOf(SessionExpiredError);
    await vi.waitFor(() => expect(release).toBeTypeOf('function'));
    clearStoredSession();
    release(json({ ...session, accessToken: 'new' }));
    await rejection;
    expect(sessionStorage.getItem('greenmart.session')).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
  it('handles 204 logout and clears local state without rotating tokens', async () => {
    await signIn();
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await expect(logout()).resolves.toBeUndefined();
    expect(sessionStorage.getItem('greenmart.session')).toBeNull();
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({
      refreshToken: 'test-refresh',
    });
  });
});

it('preserves 422 details and never automatically retries a checkout', async () => {
  fetchMock.mockResolvedValueOnce(
    json(
      {
        code: 'TEST_RULE',
        message: 'rule failure',
        details: { quote: { total: 2 } },
        traceId: 'test-trace',
      },
      422,
    ),
  );
  await expect(
    request('/orders/pos', 'post', {
      header: { 'Idempotency-Key': '8f5eeb93-9560-4d7b-bd9b-7930e965bcec' },
      body: {
        branchId: 1,
        items: [{ productId: 1, quantity: 1 }],
        expectedTotal: 1,
        payment: { method: 'CASH', cashReceived: 1 },
      },
    }),
  ).rejects.toMatchObject({ status: 422, details: { quote: { total: 2 } } });
  expect(fetchMock).toHaveBeenCalledTimes(1);
});
it('keeps the session on refresh network failure', async () => {
  await signIn();
  const offline = new TypeError('offline');
  fetchMock.mockResolvedValueOnce(json({}, 401)).mockRejectedValueOnce(offline);
  await expect(getOrder(1)).rejects.toBe(offline);
  expect(sessionStorage.getItem('greenmart.session')).not.toBeNull();
});
it('does not replay a cancelled request after shared refresh finishes', async () => {
  await signIn();
  const controller = new AbortController();
  let release!: (response: Response) => void;
  fetchMock.mockResolvedValueOnce(json({}, 401)).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const pending = getOrder(1, controller.signal);
  const rejection = expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  await vi.waitFor(() => expect(release).toBeTypeOf('function'));
  controller.abort();
  release(json({ ...session, accessToken: 'new' }));
  await rejection;
  expect(fetchMock).toHaveBeenCalledTimes(2);
});
