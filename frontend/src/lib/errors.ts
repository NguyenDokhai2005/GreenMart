/**
 * Ánh xạ lỗi -> trạng thái giao diện (issue #228).
 * Nhận trực tiếp thứ bắt được trong `catch` / `query.error`: `ApiError`, `SessionExpiredError`
 * của `api/client.ts`, hoặc lỗi `fetch` (TypeError). Không import client để tránh phụ thuộc vòng;
 * nhận diện theo hình dạng (duck typing).
 */

/**
 * Hình dạng tối thiểu mà `mapErrorToState` đọc. `ApiError` của client.ts thỏa mãn sẵn:
 * `details` tùy theo mã lỗi (400: danh sách lỗi theo ô; 409/422: object như `{ lines }`, `{ quote }`;
 * INVALID_CREDENTIALS: `{ remainingAttempts }`) nên khai `unknown`.
 */
export interface ApiErrorLike {
  name?: string;
  status?: number;
  code?: string;
  message?: string;
  traceId?: string;
  details?: unknown;
  /** Không gọi được máy chủ (mất mạng, DNS, CORS...). */
  isNetworkError?: boolean;
  /** Quá 15 giây không có phản hồi (từ `useDelayedLoading`). */
  isTimeout?: boolean;
}

export interface ErrorDetail {
  field: string;
  message: string;
}

/** `block`: một API lỗi, trang vẫn dùng được. `page`: lỗi cả trang. */
export type ErrorScope = 'block' | 'page';

export type SpecialPage =
  'not-found' | 'forbidden' | 'server-error' | 'offline' | 'session-expired';

export type UiErrorState =
  | { kind: 'block'; message: string; retryable: true; traceId?: string }
  | {
      kind: 'form';
      /** Lỗi theo từng ô; rỗng nếu lỗi chung của form (ví dụ sai mật khẩu). */
      fieldErrors: Record<string, string>;
      message: string;
      code?: string;
      /** Dữ liệu thô từ backend (ví dụ `{ remainingAttempts }`), để nơi gọi tự đọc. */
      details?: unknown;
    }
  | { kind: 'dialog'; message: string; code?: string; details?: unknown }
  | { kind: 'page'; page: SpecialPage; message?: string; traceId?: string };

export const GENERIC_ERROR_MESSAGE = 'Đã có lỗi xảy ra. Vui lòng thử lại.';
export const TIMEOUT_MESSAGE = 'Máy chủ phản hồi quá lâu. Vui lòng thử lại.';
export const NETWORK_MESSAGE = 'Không kết nối được máy chủ.';

/** Lớp lỗi `SessionExpiredError` của client.ts (refresh token đã thất bại hoặc phiên lưu bị hỏng). */
const SESSION_EXPIRED_ERROR_NAME = 'SessionExpiredError';

/**
 * Mã 401 nghĩa là phiên đã hết. Các mã 401 khác (INVALID_CREDENTIALS khi đăng nhập sai...)
 * KHÔNG phải hết phiên.
 */
const SESSION_EXPIRED_CODES: readonly string[] = ['UNAUTHORIZED', 'INVALID_REFRESH_TOKEN'];

/** Thông điệp TypeError của fetch khi không tới được máy chủ (Chrome, Firefox, Safari). */
const FETCH_FAILURE_PATTERN = /failed to fetch|networkerror|load failed|network request failed/i;

function str(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function isErrorDetail(value: unknown): value is ErrorDetail {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return typeof v.field === 'string' && typeof v.message === 'string';
}

/** Chỉ ép `details` sang danh sách lỗi theo ô khi đúng là mảng; giữ lỗi đầu tiên của mỗi ô. */
function toFieldErrors(details: unknown): Record<string, string> {
  const fieldErrors: Record<string, string> = {};
  if (!Array.isArray(details)) return fieldErrors;
  for (const d of details) {
    if (isErrorDetail(d) && !(d.field in fieldErrors)) fieldErrors[d.field] = d.message;
  }
  return fieldErrors;
}

function toApiErrorLike(error: unknown): ApiErrorLike {
  if (typeof error !== 'object' || error === null) return {};
  const e = error as Record<string, unknown>;
  const like: ApiErrorLike = {
    name: str(e.name),
    status: typeof e.status === 'number' ? e.status : undefined,
    code: str(e.code),
    message: str(e.message),
    traceId: str(e.traceId),
    details: e.details,
    isNetworkError: e.isNetworkError === true,
    isTimeout: e.isTimeout === true,
  };
  if (error instanceof TypeError && FETCH_FAILURE_PATTERN.test(error.message)) {
    like.isNetworkError = true;
  }
  return like;
}

export function mapErrorToState(error: unknown, scope: ErrorScope = 'block'): UiErrorState {
  const e = toApiErrorLike(error);
  const message = e.message ?? GENERIC_ERROR_MESSAGE;
  const { status, code, details, traceId } = e;

  if (e.isTimeout) {
    return { kind: 'block', message: TIMEOUT_MESSAGE, retryable: true };
  }

  if (e.isNetworkError) {
    return scope === 'page'
      ? { kind: 'page', page: 'offline', message: NETWORK_MESSAGE }
      : { kind: 'block', message: NETWORK_MESSAGE, retryable: true };
  }

  // SessionExpiredError: client.ts đã thử refresh và thất bại -> luôn là hết phiên, bất kể `code`.
  if (e.name === SESSION_EXPIRED_ERROR_NAME) {
    return { kind: 'page', page: 'session-expired' };
  }

  // Không có `status`: không phải lỗi HTTP (lỗi giao diện không lường trước...).
  if (status === undefined) {
    return scope === 'page'
      ? { kind: 'page', page: 'server-error', message: GENERIC_ERROR_MESSAGE }
      : { kind: 'block', message: GENERIC_ERROR_MESSAGE, retryable: true };
  }

  if (status === 401) {
    if (code && SESSION_EXPIRED_CODES.includes(code)) {
      return { kind: 'page', page: 'session-expired' };
    }
    // INVALID_CREDENTIALS...: lỗi của form (đăng nhập, đổi mật khẩu), hiện `message` tại chỗ.
    return { kind: 'form', fieldErrors: {}, message, code, details };
  }

  // 400 VALIDATION_ERROR: báo dưới từng ô theo `details` (chỉ khi là mảng).
  if (status === 400 && code === 'VALIDATION_ERROR') {
    return { kind: 'form', fieldErrors: toFieldErrors(details), message, code };
  }

  // 409/422 (hết hàng, báo giá đổi...): hộp thoại/Toast, không mất dữ liệu đang nhập.
  if (status === 409 || status === 422) {
    return { kind: 'dialog', message, code, details };
  }

  if (scope === 'page') {
    if (status === 403 || code === 'FORBIDDEN' || code === 'BRANCH_FORBIDDEN') {
      return { kind: 'page', page: 'forbidden', message };
    }
    if (status === 404) {
      return { kind: 'page', page: 'not-found', message };
    }
    if (status >= 500) {
      return { kind: 'page', page: 'server-error', message, traceId };
    }
  }

  return { kind: 'block', message, retryable: true, traceId };
}
