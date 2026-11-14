import { ApiError } from '../../api/client';

export interface ParsedApiError {
  /** Thông báo hiển thị cho người dùng (Toast hoặc hộp thông báo). */
  message: string;
  /** Lỗi theo từng ô, khóa là tên trường backend trả về (vd `items[0].quantity`, `toBranchId`). */
  fields: Record<string, string>;
  traceId?: string;
}

const NETWORK_MESSAGE = 'Không thể kết nối máy chủ. Kiểm tra mạng rồi thử lại.';

const isRecord = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

/** Chuyển lỗi gọi API thành thông báo và lỗi theo ô. 400 → lỗi từng ô (details), 409/422 → thông báo (không mất dữ liệu đang nhập). */
export function parseApiError(error: unknown): ParsedApiError {
  if (!(error instanceof ApiError)) return { message: NETWORK_MESSAGE, fields: {} };

  const fields: Record<string, string> = {};
  if (Array.isArray(error.details)) {
    for (const d of error.details) {
      if (isRecord(d) && typeof d.field === 'string' && typeof d.message === 'string')
        fields[d.field] = d.message;
    }
  }

  let message = error.message;
  // 409 INSUFFICIENT_STOCK: details.lines liệt kê dòng thiếu kèm số khả dụng.
  if (isRecord(error.details) && Array.isArray(error.details.lines)) {
    const parts = error.details.lines.flatMap((l: unknown) =>
      isRecord(l) && typeof l.available === 'number'
        ? [`${String(l.sku ?? l.productId ?? '')}: khả dụng ${l.available}`]
        : [],
    );
    if (parts.length > 0) message = `${message} (${parts.join('; ')})`;
  }

  return { message, fields, traceId: error.traceId };
}
