import { describe, expect, it } from 'vitest';
import { ApiError } from '../../../src/api/client';
import { parseApiError } from '../../../src/features/transfers/formErrors';

describe('parseApiError', () => {
  it('400: lỗi theo từng ô từ details', () => {
    const e = new ApiError('Dữ liệu không hợp lệ', 400, 'VALIDATION_ERROR', 'abc', [
      { field: 'items[0].quantity', message: 'phải lớn hơn 0' },
      { field: 'toBranchId', message: 'bắt buộc' },
    ]);
    expect(parseApiError(e)).toEqual({
      message: 'Dữ liệu không hợp lệ',
      fields: { 'items[0].quantity': 'phải lớn hơn 0', toBranchId: 'bắt buộc' },
      traceId: 'abc',
    });
  });

  it('409 INSUFFICIENT_STOCK: thông báo kèm số khả dụng từng dòng', () => {
    const e = new ApiError('Không đủ tồn', 409, 'INSUFFICIENT_STOCK', undefined, {
      lines: [{ sku: 'SP101', available: 8 }],
    });
    expect(parseApiError(e).message).toBe('Không đủ tồn (SP101: khả dụng 8)');
  });

  it('422: giữ nguyên thông báo của backend', () => {
    const e = new ApiError('Người lập phiếu không được tự duyệt', 422, 'SELF_APPROVAL_FORBIDDEN');
    expect(parseApiError(e)).toMatchObject({
      message: 'Người lập phiếu không được tự duyệt',
      fields: {},
    });
  });

  it('lỗi mạng (không phải ApiError) có thông báo thân thiện', () => {
    expect(parseApiError(new TypeError('Failed to fetch')).message).toBe(
      'Không thể kết nối máy chủ. Kiểm tra mạng rồi thử lại.',
    );
  });
});
