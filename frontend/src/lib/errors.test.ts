import { describe, expect, it } from 'vitest';
import { mapErrorToState } from '../../src/lib/errors';

const body = (code: string, message = 'msg', extra = {}) => ({ code, message, ...extra });

describe('mapErrorToState', () => {
  it('400 VALIDATION_ERROR -> form, theo từng ô', () => {
    const s = mapErrorToState({
      status: 400,
      body: body('VALIDATION_ERROR', 'Dữ liệu không hợp lệ', {
        details: [{ field: 'qty', message: 'Phải > 0' }, { field: 'qty', message: 'bỏ qua' }, { field: 'sku', message: 'Bắt buộc' }],
      }),
    });
    expect(s).toEqual({ kind: 'form', message: 'Dữ liệu không hợp lệ', fieldErrors: { qty: 'Phải > 0', sku: 'Bắt buộc' } });
  });

  it.each([409, 422])('%i -> dialog, không phải trang lỗi', (status) => {
    expect(mapErrorToState({ status, body: body('OUT_OF_STOCK', 'Hết hàng') }, 'page')).toEqual({
      kind: 'dialog', message: 'Hết hàng', code: 'OUT_OF_STOCK',
    });
  });

  it('500 trong khối -> block có Thử lại; trong trang -> server-error kèm traceId', () => {
    const err = { status: 500, body: body('INTERNAL', 'Lỗi', { traceId: 't-1' }) };
    expect(mapErrorToState(err, 'block')).toMatchObject({ kind: 'block', retryable: true, traceId: 't-1' });
    expect(mapErrorToState(err, 'page')).toMatchObject({ kind: 'page', page: 'server-error', traceId: 't-1' });
  });

  it('403 FORBIDDEN / BRANCH_FORBIDDEN: trang -> forbidden, khối -> block', () => {
    expect(mapErrorToState({ status: 403, body: body('BRANCH_FORBIDDEN') }, 'page')).toMatchObject({ kind: 'page', page: 'forbidden' });
    expect(mapErrorToState({ status: 403, body: body('FORBIDDEN') }, 'block').kind).toBe('block');
  });

  it('404 cả trang -> not-found', () => {
    expect(mapErrorToState({ status: 404, body: body('NOT_FOUND') }, 'page')).toMatchObject({ kind: 'page', page: 'not-found' });
  });

  it('401 -> session-expired', () => {
    expect(mapErrorToState({ status: 401, body: body('UNAUTHORIZED') })).toEqual({ kind: 'page', page: 'session-expired' });
  });

  it('không có phản hồi: trang -> offline, khối -> block', () => {
    expect(mapErrorToState({ isNetworkError: true }, 'page')).toMatchObject({ kind: 'page', page: 'offline' });
    expect(mapErrorToState({ isNetworkError: true }, 'block').kind).toBe('block');
  });

  it('timeout -> block có Thử lại', () => {
    expect(mapErrorToState({ isTimeout: true }, 'page')).toMatchObject({ kind: 'block', retryable: true });
  });
});