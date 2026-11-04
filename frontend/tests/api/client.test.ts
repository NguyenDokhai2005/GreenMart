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
  readStoredSession,
  request,
  searchProducts,
} from '../../src/api/client';
import type { AuthSession } from '../../src/api/client';
import type { components } from '../../src/api/schema';

const session: AuthSession = {
  accessToken: 'access-test',
  refreshToken: 'refresh-test',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: {
    id: 1,
    username: 'test-user',
    fullName: 'Test User',
    role: 'OWNER',
    branches: [],
  },
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });

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
  it('attaches the bearer token and encodes query values and path parameters', async () => {
    await signIn();
    fetchMock.mockImplementation(async () => json({ content: [] }));

    const signal = new AbortController().signal;
    await searchProducts(' sữa & gạo ', signal);
    const [url, init] = fetchMock.mock.calls[0];
    expect(new URL(String(url), 'http://local').searchParams.get('q')).toBe('sữa & gạo');
    expect(init?.signal).toBe(signal);
    expect(new Headers(init?.headers).get('Authorization')).toBe(`Bearer ${session.accessToken}`);

    await listOrders({ status: ['RESERVED', 'CONFIRMED'], page: 0, from: '2026-10-30' });
    const query = new URL(String(fetchMock.mock.calls[1][0]), 'http://local').searchParams;
    expect(query.getAll('status')).toEqual(['RESERVED', 'CONFIRMED']);
    expect(query.get('page')).toBe('0');

    await request('/products', 'get', { query: { active: false } });
    expect(fetchMock.mock.calls[2][0]).toBe('/api/v1/products?active=false');
    await lookupProduct('SKU/A', 2);
    expect(fetchMock.mock.calls[3][0]).toBe('/api/v1/products/lookup?code=SKU%2FA&branchId=2');
    await getOrder(42);
    expect(fetchMock.mock.calls[4][0]).toBe('/api/v1/orders/42');
  });

  it('normalizes HTTP errors and retains business details and trace IDs', async () => {
    const payload = {
      code: 'INSUFFICIENT_STOCK',
      message: 'Not enough stock',
      details: { lines: [{ productId: 7 }] },
      traceId: 'trace-test',
    };
    fetchMock.mockResolvedValueOnce(json(payload, 409));

    await expect(getOrder(1)).rejects.toMatchObject({
      name: 'ApiError',
      status: 409,
      ...payload,
    });
  });

  it('reports malformed and non-JSON responses without hiding HTTP status', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response('{', { headers: { 'content-type': 'application/json' } }),
    );
    await expect(getOrder(1)).rejects.toBeInstanceOf(ApiError);

    fetchMock.mockResolvedValueOnce(new Response('unavailable', { status: 503 }));
    await expect(getOrder(1)).rejects.toMatchObject({ status: 503 });
  });

  it('does not send an already-aborted request or retry a network failure', async () => {
    const controller = new AbortController();
    controller.abort();
    await expect(getOrder(1, controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
    expect(fetchMock).not.toHaveBeenCalled();

    const error = new TypeError('offline');
    fetchMock.mockRejectedValueOnce(error);
    await expect(getOrder(1)).rejects.toBe(error);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('preserves the request body and idempotency key on the single automatic retry', async () => {
    await signIn();
    const idempotencyKey = crypto.randomUUID();
    const payload: components['schemas']['PosOrderRequest'] = {
      branchId: 1,
      items: [{ productId: 101, quantity: 2 }],
      payment: { method: 'CASH', cashReceived: 500000 },
      expectedTotal: 456000,
    };
    const order = { id: 42, code: 'POS-42' };
    fetchMock
      .mockResolvedValueOnce(
        json({ code: 'UNAUTHORIZED', message: 'Expired', traceId: 'trace-expired' }, 401),
      )
      .mockResolvedValueOnce(
        json({ ...session, accessToken: 'access-new', refreshToken: 'refresh-new' }),
      )
      .mockResolvedValueOnce(json(order, 201));

    await expect(
      request('/orders/pos', 'post', {
        body: payload,
        header: { 'Idempotency-Key': idempotencyKey },
      }),
    ).resolves.toMatchObject(order);

    const [first, refresh, retry] = fetchMock.mock.calls;
    expect(first?.[0]).toBe('/api/v1/orders/pos');
    expect(new Headers(first?.[1]?.headers).get('Authorization')).toBe(
      `Bearer ${session.accessToken}`,
    );
    expect(refresh?.[0]).toBe('/api/v1/auth/refresh');
    expect(JSON.parse(String(refresh?.[1]?.body))).toEqual({ refreshToken: 'refresh-test' });
    expect(retry?.[0]).toBe(first?.[0]);
    expect(retry?.[1]?.body).toBe(first?.[1]?.body);
    expect(new Headers(retry?.[1]?.headers).get('Idempotency-Key')).toBe(idempotencyKey);
    expect(new Headers(retry?.[1]?.headers).get('Authorization')).toBe('Bearer access-new');
    expect(readStoredSession()?.refreshToken).toBe('refresh-new');
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('returns contract-shaped product data through the generated response type', async () => {
    const productPage: components['schemas']['ProductPage'] = {
      content: [
        {
          id: 205,
          sku: 'SUA-TH-1L',
          name: 'Milk',
          unit: 'box',
          categoryId: 3,
          categoryName: 'Milk',
          salePrice: 29000,
          costPrice: null,
          active: true,
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    };
    fetchMock.mockResolvedValueOnce(json(productPage));

    const response = await searchProducts('');
    expect(response.content[0]?.salePrice).toBe(29000);
  });
});

describe('session refresh', () => {
  it('refreshes an expired access token and retries current-user validation', async () => {
    await signIn();
    const currentUser = {
      id: 1,
      username: 'test-user',
      fullName: 'Test User',
      role: 'OWNER',
      branches: [],
    };
    fetchMock
      .mockResolvedValueOnce(
        json({ code: 'UNAUTHORIZED', message: 'Expired', traceId: 'trace-expired' }, 401),
      )
      .mockResolvedValueOnce(
        json({ ...session, accessToken: 'access-new', refreshToken: 'refresh-new' }),
      )
      .mockResolvedValueOnce(json(currentUser));

    await expect(getCurrentUser()).resolves.toEqual(currentUser);
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/auth/me',
      '/api/v1/auth/refresh',
      '/api/v1/auth/me',
    ]);
    expect(readStoredSession()?.refreshToken).toBe('refresh-new');
  });

  it('shares one refresh between concurrent 401 responses', async () => {
    await signIn();
    let release!: (response: Response) => void;
    fetchMock.mockImplementation(async (url, init) => {
      if (url === '/api/v1/auth/refresh') {
        return new Promise<Response>((resolve) => {
          release = resolve;
        });
      }
      return new Headers(init?.headers).get('Authorization') === `Bearer ${session.accessToken}`
        ? json({ code: 'UNAUTHORIZED', message: 'Expired', traceId: 'trace' }, 401)
        : json({ id: 1 });
    });

    const first = getCurrentUser();
    const second = getOrder(1);
    await vi.waitFor(() => expect(release).toBeTypeOf('function'));
    release(json({ ...session, accessToken: 'access-new', refreshToken: 'refresh-new' }));
    await Promise.all([first, second]);

    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/v1/auth/refresh')).toHaveLength(1);
    expect(fetchMock).toHaveBeenCalledTimes(5);
  });

  it('clears the session and reports expiry when refresh fails', async () => {
    await signIn();
    fetchMock
      .mockResolvedValueOnce(
        json({ code: 'UNAUTHORIZED', message: 'Expired', traceId: 'trace-expired' }, 401),
      )
      .mockResolvedValueOnce(
        json(
          {
            code: 'INVALID_REFRESH_TOKEN',
            message: 'Refresh token invalid',
            details: null,
            traceId: 'trace-refresh',
          },
          401,
        ),
      );

    await expect(getOrder(1)).rejects.toMatchObject({
      name: 'SessionExpiredError',
      code: 'INVALID_REFRESH_TOKEN',
      traceId: 'trace-refresh',
    });
    expect(sessionStorage.getItem('greenmart.session')).toBeNull();
  });

  it('preserves the session and reports a network error when refresh cannot reach the server', async () => {
    await signIn();
    const offline = new TypeError('offline');
    fetchMock
      .mockResolvedValueOnce(
        json({ code: 'UNAUTHORIZED', message: 'Expired', traceId: 'trace-expired' }, 401),
      )
      .mockRejectedValueOnce(offline);

    await expect(getOrder(1)).rejects.toBe(offline);
    expect(readStoredSession()?.refreshToken).toBe(session.refreshToken);
  });

  it('preserves the session when refresh returns a temporary server error', async () => {
    await signIn();
    fetchMock
      .mockResolvedValueOnce(
        json({ code: 'UNAUTHORIZED', message: 'Expired', traceId: 'trace-expired' }, 401),
      )
      .mockResolvedValueOnce(
        json({ code: 'INTERNAL_ERROR', message: 'Temporarily unavailable' }, 503),
      );

    await expect(getOrder(1)).rejects.toMatchObject({
      name: 'ApiError',
      status: 503,
      code: 'INTERNAL_ERROR',
    });
    expect(readStoredSession()?.refreshToken).toBe(session.refreshToken);
  });

  it('does not enter a refresh loop when the retried request is rejected', async () => {
    await signIn();
    fetchMock
      .mockResolvedValueOnce(
        json({ code: 'UNAUTHORIZED', message: 'Expired', traceId: 'trace-1' }, 401),
      )
      .mockResolvedValueOnce(
        json({ ...session, accessToken: 'access-new', refreshToken: 'refresh-new' }),
      )
      .mockResolvedValueOnce(
        json({ code: 'UNAUTHORIZED', message: 'Still expired', traceId: 'trace-2' }, 401),
      );

    await expect(getOrder(1)).rejects.toMatchObject({ status: 401, code: 'UNAUTHORIZED' });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('does not refresh login failures or anonymous requests', async () => {
    fetchMock.mockResolvedValue(
      json({ code: 'INVALID_CREDENTIALS', message: 'Bad credentials' }, 401),
    );
    await expect(login('test-user', 'test-only')).rejects.toMatchObject({ status: 401 });
    await expect(getOrder(1)).rejects.toMatchObject({ status: 401 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('does not refresh recursively when the refresh endpoint itself rejects', async () => {
    await signIn();
    fetchMock.mockResolvedValueOnce(
      json(
        {
          code: 'INVALID_REFRESH_TOKEN',
          message: 'Refresh token invalid',
          details: null,
          traceId: 'trace-refresh',
        },
        401,
      ),
    );

    await expect(
      request('/auth/refresh', 'post', {
        body: { refreshToken: session.refreshToken },
      }),
    ).rejects.toMatchObject({ status: 401, code: 'INVALID_REFRESH_TOKEN' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('does not resurrect a logged-out session after a late refresh', async () => {
    await signIn();
    let release!: (response: Response) => void;
    fetchMock
      .mockResolvedValueOnce(
        json({ code: 'UNAUTHORIZED', message: 'Expired', traceId: 'trace' }, 401),
      )
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            release = resolve;
          }),
      );

    const pending = getOrder(1);
    const rejection = expect(pending).rejects.toBeInstanceOf(SessionExpiredError);
    await vi.waitFor(() => expect(release).toBeTypeOf('function'));
    clearStoredSession();
    release(json({ ...session, accessToken: 'access-new', refreshToken: 'refresh-new' }));
    await rejection;

    expect(sessionStorage.getItem('greenmart.session')).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('clears local state before logout and does not rotate the refresh token', async () => {
    await signIn();
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));

    await expect(logout()).resolves.toBeUndefined();
    expect(sessionStorage.getItem('greenmart.session')).toBeNull();
    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toEqual({
      refreshToken: 'refresh-test',
    });
    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get('Authorization')).toBe(
      `Bearer ${session.accessToken}`,
    );
  });

  it('keeps the local logout when the server revocation request fails', async () => {
    await signIn();
    fetchMock.mockRejectedValueOnce(new TypeError('offline'));

    await expect(logout()).rejects.toThrow('offline');
    expect(sessionStorage.getItem('greenmart.session')).toBeNull();
  });
});

describe('session mutation races', () => {
  it('does not store a login response that arrives after logout', async () => {
    let releaseLogin!: (response: Response) => void;
    fetchMock.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          releaseLogin = resolve;
        }),
    );

    const pendingLogin = login('late-user', 'test-only');
    await vi.waitFor(() => expect(releaseLogin).toBeTypeOf('function'));

    await expect(logout()).resolves.toBeUndefined();
    expect(sessionStorage.getItem('greenmart.session')).toBeNull();

    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    releaseLogin(
      json({
        ...session,
        accessToken: 'late-access',
        refreshToken: 'late-refresh',
        user: { ...session.user, username: 'late-user' },
      }),
    );
    await pendingLogin;

    expect(sessionStorage.getItem('greenmart.session')).toBeNull();
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/auth/login',
      '/api/v1/auth/logout',
    ]);
    expect(JSON.parse(String(fetchMock.mock.calls[1]?.[1]?.body))).toEqual({
      refreshToken: 'late-refresh',
    });
  });

  it('keeps the latest login when earlier login responses arrive later', async () => {
    const pendingLogins = new Map<string, (response: Response) => void>();
    fetchMock.mockImplementation(async (url, init) => {
      if (url === '/api/v1/auth/login') {
        const body = JSON.parse(String(init?.body)) as { username: string };
        return new Promise<Response>((resolve) => pendingLogins.set(body.username, resolve));
      }
      if (url === '/api/v1/auth/logout') return new Response(null, { status: 204 });
      throw new Error('Unexpected request');
    });

    const firstLogin = login('user-a', 'test-only');
    const latestLogin = login('user-b', 'test-only');
    await vi.waitFor(() => expect(pendingLogins.size).toBe(2));

    pendingLogins.get('user-b')?.(
      json({
        ...session,
        accessToken: 'access-b',
        refreshToken: 'refresh-b',
        user: { ...session.user, username: 'user-b' },
      }),
    );
    await latestLogin;

    pendingLogins.get('user-a')?.(
      json({
        ...session,
        accessToken: 'access-a',
        refreshToken: 'refresh-a',
        user: { ...session.user, username: 'user-a' },
      }),
    );
    await firstLogin;

    expect(readStoredSession()).toMatchObject({
      accessToken: 'access-b',
      refreshToken: 'refresh-b',
    });
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/v1/auth/login',
      '/api/v1/auth/login',
      '/api/v1/auth/logout',
    ]);
    expect(JSON.parse(String(fetchMock.mock.calls[2]?.[1]?.body))).toEqual({
      refreshToken: 'refresh-a',
    });
  });
});

function contractTypeChecks() {
  // @ts-expect-error endpoint absent from current OpenAPI
  request('/does-not-exist', 'get', {});
  // @ts-expect-error method absent from the endpoint
  request('/orders/pos', 'get', {});
  // @ts-expect-error lookup requires branchId
  request('/products/lookup', 'get', { query: { code: 'SKU' } });
  // @ts-expect-error POS requires its body and idempotency header
  request('/orders/pos', 'post', { body: { branchId: 1 } });
  // @ts-expect-error path parameter is numeric
  request('/orders/{orderId}', 'get', { path: { orderId: 'not-a-number' } });
}
void contractTypeChecks;
