import type { components, operations, paths } from './schema';

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '');
const SESSION_STORAGE_KEY = 'greenmart.session';
const AUTH_EXEMPT_PATHS = new Set(['/auth/login', '/auth/refresh']);

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

  constructor(
    message: string,
    status: number,
    code?: string,
    traceId?: string,
    details?: unknown,
    cause?: unknown,
  ) {
    super(message, { cause });
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.traceId = traceId;
    this.details = details;
  }
}

export class SessionExpiredError extends ApiError {
  constructor(cause?: unknown) {
    const apiError = cause instanceof ApiError ? cause : undefined;
    super(
      'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.',
      401,
      apiError?.code,
      apiError?.traceId,
      apiError?.details,
      cause,
    );
    this.name = 'SessionExpiredError';
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isAuthSession(value: unknown): value is AuthSession {
  if (!isRecord(value) || !isRecord(value.user)) return false;
  return (
    typeof value.accessToken === 'string' &&
    value.accessToken.length > 0 &&
    typeof value.refreshToken === 'string' &&
    value.refreshToken.length > 0 &&
    value.tokenType === 'Bearer' &&
    typeof value.expiresIn === 'number' &&
    typeof value.user.id === 'number' &&
    typeof value.user.username === 'string' &&
    typeof value.user.fullName === 'string' &&
    typeof value.user.role === 'string' &&
    Array.isArray(value.user.branches)
  );
}

function getSessionStorage(): Storage | undefined {
  return typeof globalThis.sessionStorage === 'undefined' ? undefined : globalThis.sessionStorage;
}

export function readStoredSession(): AuthSession | null {
  const storage = getSessionStorage();
  if (!storage) return null;

  const value = storage.getItem(SESSION_STORAGE_KEY);
  if (!value) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch (error) {
    clearStoredSession();
    throw new SessionExpiredError(error);
  }

  if (!isAuthSession(parsed)) {
    clearStoredSession();
    throw new SessionExpiredError();
  }

  return parsed;
}

function storeSession(session: AuthSession): void {
  const storage = getSessionStorage();
  if (!storage) throw new Error('Không thể lưu phiên đăng nhập ngoài trình duyệt.');
  storage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
}

export function clearStoredSession(): void {
  getSessionStorage()?.removeItem(SESSION_STORAGE_KEY);
}

function parseError(payload: unknown, status: number): ApiError {
  const error = isRecord(payload) ? payload : {};
  return new ApiError(
    typeof error.message === 'string' ? error.message : `Yêu cầu thất bại (HTTP ${status}).`,
    status,
    typeof error.code === 'string' ? error.code : undefined,
    typeof error.traceId === 'string' ? error.traceId : undefined,
    error.details,
  );
}

async function requestRaw<T>(
  path: string,
  init: RequestInit = {},
  accessToken?: string,
): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);

  const response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers });
  if (response.status === 204) return undefined as T;

  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.toLowerCase().includes('application/json')) {
    throw new ApiError(
      response.ok
        ? 'API trả về dữ liệu không phải JSON.'
        : `Yêu cầu thất bại (HTTP ${response.status}).`,
      response.status,
    );
  }

  let payload: unknown;
  try {
    payload = await response.json();
  } catch (error) {
    throw new ApiError(
      'API trả về JSON không hợp lệ.',
      response.status,
      undefined,
      undefined,
      undefined,
      error,
    );
  }

  if (!response.ok) throw parseError(payload, response.status);
  return payload as T;
}

const refreshes = new Map<string, Promise<AuthSession>>();

function refreshSession(session: AuthSession): Promise<AuthSession> {
  const current = readStoredSession();
  if (!current || current.user.id !== session.user.id) {
    return Promise.reject(new SessionExpiredError());
  }
  if (current.refreshToken !== session.refreshToken) return Promise.resolve(current);

  const pending = refreshes.get(session.refreshToken);
  if (pending) return pending;

  const refresh = requestRaw<unknown>('/auth/refresh', {
    method: 'POST',
    body: JSON.stringify({ refreshToken: session.refreshToken }),
  })
    .then((value) => {
      if (!isAuthSession(value)) {
        throw new ApiError('API trả về phiên đăng nhập không hợp lệ.', 502);
      }

      if (readStoredSession()?.refreshToken !== session.refreshToken) {
        throw new SessionExpiredError();
      }

      storeSession(value);
      return value;
    })
    .catch((error: unknown) => {
      if (readStoredSession()?.refreshToken === session.refreshToken) clearStoredSession();
      if (error instanceof SessionExpiredError) throw error;
      throw new SessionExpiredError(error);
    })
    .finally(() => {
      refreshes.delete(session.refreshToken);
    });

  refreshes.set(session.refreshToken, refresh);
  return refresh;
}

async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  init.signal?.throwIfAborted();
  const session = AUTH_EXEMPT_PATHS.has(path) ? null : readStoredSession();

  try {
    return await requestRaw<T>(path, init, session?.accessToken);
  } catch (error) {
    init.signal?.throwIfAborted();
    if (
      !(error instanceof ApiError) ||
      error.status !== 401 ||
      error.code !== 'UNAUTHORIZED' ||
      !session ||
      AUTH_EXEMPT_PATHS.has(path)
    ) {
      throw error;
    }

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
  ? ResponseBody<R[Extract<keyof R, 200 | 201 | 202 | 203 | 204>]>
  : never;
type Params<O> = O extends { parameters: infer P } ? P : never;
type RequestOptions<O> = Params<O> &
  (Body<O> extends never ? { body?: never } : { body: Body<O> }) & { signal?: AbortSignal };

type RequestParameters = {
  path?: Record<string, string | number>;
  query?: Record<string, unknown>;
  header?: Record<string, string>;
  body?: unknown;
  signal?: AbortSignal;
};

/** Method, path, parameters and body are checked against generated OpenAPI types. */
export function request<P extends keyof paths, M extends Method>(
  path: P,
  method: M & (Operation<P, M> extends never ? never : M),
  options: RequestOptions<Operation<P, M>>,
): Promise<Success<Operation<P, M>>> {
  const params = options as RequestParameters;
  let url: string = path;
  for (const [key, value] of Object.entries(params.path ?? {})) {
    url = url.replace(`{${key}}`, encodeURIComponent(String(value)));
  }

  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params.query ?? {})) {
    if (value === undefined || value === null) continue;
    for (const item of Array.isArray(value) ? value : [value]) {
      if (item !== undefined && item !== null) query.append(key, String(item));
    }
  }
  if (query.size > 0) url += `?${query.toString()}`;

  return apiRequest(url, {
    method: method.toUpperCase(),
    headers: params.header,
    body: params.body === undefined ? undefined : JSON.stringify(params.body),
    signal: params.signal,
  });
}

export async function login(username: string, password: string): Promise<AuthSession> {
  const credentials: components['schemas']['LoginRequest'] = { username, password };
  const value = await requestRaw<unknown>('/auth/login', {
    method: 'POST',
    body: JSON.stringify(credentials),
  });
  if (!isAuthSession(value)) throw new ApiError('API trả về phiên đăng nhập không hợp lệ.', 502);
  storeSession(value);
  return value;
}

export function getCurrentUser(): Promise<CurrentUser> {
  return request('/auth/me', 'get', {});
}

export async function logout(): Promise<void> {
  const session = readStoredSession();
  clearStoredSession();
  if (!session) return;

  await requestRaw<void>(
    '/auth/logout',
    {
      method: 'POST',
      body: JSON.stringify({ refreshToken: session.refreshToken }),
    },
    session.accessToken,
  );
}

export function searchProducts(query: string, signal?: AbortSignal): Promise<ProductPage> {
  return request('/products', 'get', {
    query: { active: true, page: 0, size: 100, q: query.trim() || undefined },
    signal,
  });
}

export function quoteOrder(input: QuoteRequest, signal?: AbortSignal): Promise<Quote> {
  return request('/orders/quote', 'post', { body: input, signal });
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
