/**
 * Ánh xạ lỗi API -> trạng thái giao diện (issue #228).
 * Chỉ chứa logic thuần, không phụ thuộc React để dễ test.
 */

export interface ErrorDetail {
  field: string;
  message: string;
}

/** Hình dạng `ErrorResponse` do backend trả về. */
export interface ErrorResponse {
  code: string; // VALIDATION_ERROR, FORBIDDEN, BRANCH_FORBIDDEN, ...
  message: string;
  details?: ErrorDetail[];
  traceId?: string;
}

/** Lỗi đã chuẩn hóa từ tầng gọi API (axios/fetch wrapper). */
export interface ApiErrorLike {
  status?: number; // undefined nếu không nhận được phản hồi
  body?: ErrorResponse;
  /** Không gọi được máy chủ (mất mạng, DNS, CORS...). */
  isNetworkError?: boolean;
  /** Quá 15 giây không có phản hồi. */
  isTimeout?: boolean;
}

/** `block`: một API lỗi, trang vẫn dùng được. `page`: lỗi cả trang. */
export type ErrorScope = 'block' | 'page';

export type SpecialPage =
  'not-found' | 'forbidden' | 'server-error' | 'offline' | 'session-expired';

export type UiErrorState =
  | { kind: 'block'; message: string; retryable: true; traceId?: string }
  | { kind: 'form'; fieldErrors: Record<string, string>; message: string }
  | { kind: 'dialog'; message: string; code?: string }
  | { kind: 'page'; page: SpecialPage; message?: string; traceId?: string };

export const GENERIC_ERROR_MESSAGE = 'Đã có lỗi xảy ra. Vui lòng thử lại.';
export const TIMEOUT_MESSAGE = 'Máy chủ phản hồi quá lâu. Vui lòng thử lại.';
export const NETWORK_MESSAGE = 'Không kết nối được máy chủ.';

export function mapErrorToState(error: ApiErrorLike, scope: ErrorScope = 'block'): UiErrorState {
  const message = error.body?.message ?? GENERIC_ERROR_MESSAGE;
  const code = error.body?.code;
  const traceId = error.body?.traceId;
  const status = error.status;

  if (error.isTimeout) {
    return { kind: 'block', message: TIMEOUT_MESSAGE, retryable: true };
  }

  if (error.isNetworkError || status === undefined) {
    return scope === 'page'
      ? { kind: 'page', page: 'offline', message: NETWORK_MESSAGE }
      : { kind: 'block', message: NETWORK_MESSAGE, retryable: true };
  }

  // 401: làm mới token đã thất bại ở tầng API -> luôn là lỗi cả phiên.
  if (status === 401) {
    return { kind: 'page', page: 'session-expired' };
  }

  // 400 VALIDATION_ERROR: báo dưới từng ô theo `details`.
  if (status === 400 && code === 'VALIDATION_ERROR') {
    const fieldErrors: Record<string, string> = {};
    for (const d of error.body?.details ?? []) {
      if (!(d.field in fieldErrors)) fieldErrors[d.field] = d.message; // giữ lỗi đầu tiên mỗi ô
    }
    return { kind: 'form', fieldErrors, message };
  }

  // 409/422 (hết hàng, báo giá đổi...): hộp thoại/Toast, không mất dữ liệu đang nhập.
  if (status === 409 || status === 422) {
    return { kind: 'dialog', message, code };
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
