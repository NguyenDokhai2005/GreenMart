import type { components, paths, operations } from './schema';

const API_BASE_URL = '/api/v1';
const SESSION_STORAGE_KEY = 'greenmart.session';

export type Role = components['schemas']['Role'];
export type PaymentMethod = components['schemas']['PaymentMethod'];
export type BranchRef = components['schemas']['BranchRef'];
export type CurrentUser = components['schemas']['CurrentUser'];
export type AuthSession = components['schemas']['TokenResponse'];
export type Product = components['schemas']['Product'];
export type ProductPage = components['schemas']['ProductPage'];
export type QuoteLine = components['schemas']['QuoteLine'];
export type Quote = components['schemas']['Quote'];
export type PosOrder = components['schemas']['Order'];
type QuoteRequest = components['schemas']['QuoteRequest'];
type PosOrderRequest = components['schemas']['PosOrderRequest'];

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly traceId?: string;
  readonly details?: unknown;

  constructor(message: string, status: number, code?: string, traceId?: string, details?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.traceId = traceId;
    this.details = details;
  }
}

function isAuthSession(value: unknown): value is AuthSession {
  if (typeof value !== 'object' || value === null) return false;

  const session = value as Partial<AuthSession>;
  return (
    typeof session.accessToken === 'string' &&
    typeof session.refreshToken === 'string' &&
    session.tokenType === 'Bearer' &&
    typeof session.expiresIn === 'number' &&
    typeof session.user === 'object' &&
    session.user !== null
  );
}

export function readStoredSession(): AuthSession | null {
  const value = sessionStorage.getItem(SESSION_STORAGE_KEY);
  if (!value) return null;

  try {
    const parsed: unknown = JSON.parse(value);
    if (!isAuthSession(parsed)) throw new Error('Dữ liệu phiên đăng nhập không hợp lệ.');
    return parsed;
  } catch (error) {
    sessionStorage.removeItem(SESSION_STORAGE_KEY);
    if (error instanceof SyntaxError) {
      throw new Error('Không thể đọc phiên đăng nhập đã lưu.', { cause: error });
    }
    throw error;
  }
}

function storeSession(session: AuthSession): void {
  sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
}

export function clearStoredSession(): void {
  sessionStorage.removeItem(SESSION_STORAGE_KEY);
}

async function requestRaw<T>(
  path: string,
  init: RequestInit = {},
  accessToken?: string,
): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);

  const response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers });
  if (response.status === 204) return undefined as T;

  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes('application/json')) {
    const message = response.ok
      ? 'API trả về dữ liệu không phải JSON.'
      : `Yêu cầu thất bại (HTTP ${response.status}).`;
    throw new ApiError(message, response.status);
  }

  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    init.signal?.throwIfAborted();
    throw new ApiError('API trả về JSON không hợp lệ.', response.status);
  }

  if (!response.ok) {
    const error =
      typeof payload === 'object' && payload !== null
        ? (payload as Partial<components['schemas']['ErrorResponse']>)
        : {};
    throw new ApiError(
      typeof error.message === 'string'
        ? error.message
        : `Yêu cầu thất bại (HTTP ${response.status}).`,
      response.status,
      typeof error.code === 'string' ? error.code : undefined,
      typeof error.traceId === 'string' ? error.traceId : undefined,
      error.details,
    );
  }

  return payload as T;
}

// A client-side session failure is separate from ErrorResponse.code.
export class SessionExpiredError extends ApiError {
  constructor(error?: ApiError) {
    super(
      'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.',
      401,
      error?.code,
      error?.traceId,
      error?.details,
    );
    this.name = 'SessionExpiredError';
  }
}

const refreshes = new Map<string, Promise<AuthSession>>();

function refreshSession(session: AuthSession): Promise<AuthSession> {
  const current = readStoredSession();
  if (!current) return Promise.reject(new SessionExpiredError());
  if (current.user.id !== session.user.id) return Promise.reject(new SessionExpiredError());
  if (current.refreshToken !== session.refreshToken) return Promise.resolve(current);
  const pending = refreshes.get(session.refreshToken);
  if (pending) return pending;
  const refresh = requestRaw<AuthSession>('/auth/refresh', {
    method: 'POST',
    body: JSON.stringify({ refreshToken: session.refreshToken }),
  })
    .then((next) => {
      // Logout or a new login must not be overwritten by a late refresh.
      if (readStoredSession()?.refreshToken !== session.refreshToken) {
        throw new SessionExpiredError();
      }
      storeSession(next);
      return next;
    })
    .catch((error: unknown) => {
      if (error instanceof ApiError && error.status === 401) {
        if (readStoredSession()?.refreshToken === session.refreshToken) clearStoredSession();
        throw new SessionExpiredError(error);
      }
      throw error;
    })
    .finally(() => {
      refreshes.delete(session.refreshToken);
    });
  refreshes.set(session.refreshToken, refresh);
  return refresh;
}

async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  init.signal?.throwIfAborted();
  const session = readStoredSession();
  try {
    return await requestRaw<T>(path, init, session?.accessToken);
  } catch (error) {
    init.signal?.throwIfAborted();
    if (
      !(error instanceof ApiError) ||
      error.status !== 401 ||
      !session ||
      ['/auth/login', '/auth/refresh', '/auth/logout'].includes(path)
    )
      throw error;
    const refreshed = await refreshSession(session);
    init.signal?.throwIfAborted();
    return requestRaw<T>(path, init, refreshed.accessToken);
  }
}

type Method = 'get' | 'post' | 'put' | 'patch' | 'delete';
type Operation<P extends keyof paths, M extends Method> = NonNullable<paths[P][M]>;
type Body<O> = O extends { requestBody: { content: { 'application/json': infer B } } } ? B : never;
type ResponseBody<R> = R extends { content: { 'application/json': infer B } } ? B : void;
type Success<O> = O extends { responses: infer R }
  ? ResponseBody<R[Extract<keyof R, 200 | 201 | 204>]>
  : never;
type Params<O> = O extends { parameters: infer P } ? P : never;
type RequestOptions<O> = Params<O> &
  (Body<O> extends never ? { body?: never } : { body: Body<O> }) & { signal?: AbortSignal };

/** Method, path, parameters and body are checked against generated OpenAPI types. */
export function request<P extends keyof paths, M extends Method>(
  path: P,
  method: M & (Operation<P, M> extends never ? never : M),
  options: RequestOptions<Operation<P, M>>,
): Promise<Success<Operation<P, M>>> {
  const params = options as {
    path?: Record<string, string | number>;
    query?: Record<string, unknown>;
    header?: Record<string, string>;
    body?: unknown;
    signal?: AbortSignal;
  };
  let url: string = path;
  for (const [key, value] of Object.entries(params.path ?? {})) {
    url = url.replace(`{${key}}`, encodeURIComponent(String(value)));
  }
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params.query ?? {})) {
    if (value === undefined || value === null) continue;
    for (const item of Array.isArray(value) ? value : [value]) query.append(key, String(item));
  }
  if (query.size) url += `?${query}`;
  return apiRequest(url, {
    method: method.toUpperCase(),
    headers: params.header,
    body: params.body === undefined ? undefined : JSON.stringify(params.body),
    signal: params.signal,
  });
}

export async function login(username: string, password: string): Promise<AuthSession> {
  const credentials: components['schemas']['LoginRequest'] = { username, password };
  const session = await requestRaw<AuthSession>('/auth/login', {
    method: 'POST',
    body: JSON.stringify(credentials),
  });
  storeSession(session);
  return session;
}

export function getCurrentUser(): Promise<CurrentUser> {
  return request('/auth/me', 'get', {});
}

export async function logout(): Promise<void> {
  const session = readStoredSession();
  clearStoredSession();
  if (!session) return;
  // Do not rotate a refresh token while trying to revoke it.
  return requestRaw<void>(
    '/auth/logout',
    {
      method: 'POST',
      body: JSON.stringify({ refreshToken: session.refreshToken }),
    },
    session.accessToken,
  );
}

export function searchProducts(query: string, signal: AbortSignal): Promise<ProductPage> {
  return request('/products', 'get', {
    query: { active: true, page: 0, size: 100, q: query.trim() || undefined },
    signal,
  });
}

export function quoteOrder(
  branchId: number,
  items: QuoteRequest['items'],
  signal: AbortSignal,
): Promise<Quote> {
  return request('/orders/quote', 'post', { body: { branchId, items }, signal });
}

export function completePosOrder(
  input: PosOrderRequest,
  idempotencyKey: string,
): Promise<PosOrder> {
  return request('/orders/pos', 'post', {
    body: input,
    header: { 'Idempotency-Key': idempotencyKey },
  });
}

export function listBranches(active?: boolean, signal?: AbortSignal) {
  return request('/branches', 'get', { query: { active }, signal });
}

export function lookupProduct(code: string, branchId: number, signal?: AbortSignal) {
  return request('/products/lookup', 'get', { query: { code, branchId }, signal });
}

export function listOrders(
  query: operations['listOrders']['parameters']['query'] = {},
  signal?: AbortSignal,
) {
  return request('/orders', 'get', { query, signal });
}

export function getOrder(orderId: number, signal?: AbortSignal) {
  return request('/orders/{orderId}', 'get', { path: { orderId }, signal });
}
