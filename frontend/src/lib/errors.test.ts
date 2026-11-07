import { describe, expect, it } from 'vitest';
import { ApiError, SessionExpiredError } from '../../src/api/client';
import { mapErrorToState } from '../../src/lib/errors';

const apiError = (
  status: number,
  code?: string,
  message = 'msg',
  extra: { traceId?: string; details?: unknown } = {},
) => new ApiError(message, status, code, extra.traceId, extra.details);

describe('mapErrorToState', () => {
  it('400 VALIDATION_ERROR -> form, theo từng ô', () => {
    const s = mapErrorToState(
      apiError(400, 'VALIDATION_ERROR', 'Dữ liệu không hợp lệ', {
        details: [
          { field: 'qty', message: 'Phải > 0' },
          { field: 'qty', message: 'bỏ qua' },
          { field: 'sku', message: 'Bắt buộc' },
        ],
      }),
    );
    expect(s).toEqual({
      kind: 'form',
      message: 'Dữ liệu không hợp lệ',
      code: 'VALIDATION_ERROR',
      fieldErrors: { qty: 'Phải > 0', sku: 'Bắt buộc' },
    });
  });

  it('400 VALIDATION_ERROR với details không phải mảng -> fieldErrors rỗng', () => {
    const s = mapErrorToState(apiError(400, 'VALIDATION_ERROR', 'Sai', { details: { lines: [] } }));
    expect(s).toMatchObject({ kind: 'form', fieldErrors: {} });
  });

  it('400 VALIDATION_ERROR bỏ qua phần tử sai hình dạng', () => {
    const s = mapErrorToState(
      apiError(400, 'VALIDATION_ERROR', 'Sai', {
        details: [null, 'x', { field: 'a' }, { field: 'b', message: 'ok' }],
      }),
    );
    expect(s).toMatchObject({ kind: 'form', fieldErrors: { b: 'ok' } });
  });

  it.each([409, 422])('%i -> dialog, giữ details dạng object', (status) => {
    expect(
      mapErrorToState(
        apiError(status, 'OUT_OF_STOCK', 'Hết hàng', { details: { lines: [{ sku: 'A' }] } }),
        'page',
      ),
    ).toEqual({
      kind: 'dialog',
      message: 'Hết hàng',
      code: 'OUT_OF_STOCK',
      details: { lines: [{ sku: 'A' }] },
    });
  });

  it('500 trong khối -> block có Thử lại; trong trang -> server-error kèm traceId', () => {
    const err = apiError(500, 'INTERNAL', 'Lỗi', { traceId: 't-1' });
    expect(mapErrorToState(err, 'block')).toMatchObject({
      kind: 'block',
      retryable: true,
      traceId: 't-1',
    });
    expect(mapErrorToState(err, 'page')).toMatchObject({
      kind: 'page',
      page: 'server-error',
      traceId: 't-1',
    });
  });

  it('403 FORBIDDEN / BRANCH_FORBIDDEN: trang -> forbidden, khối -> block', () => {
    expect(mapErrorToState(apiError(403, 'BRANCH_FORBIDDEN'), 'page')).toMatchObject({
      kind: 'page',
      page: 'forbidden',
    });
    expect(mapErrorToState(apiError(403, 'FORBIDDEN'), 'block').kind).toBe('block');
  });

  it('404 cả trang -> not-found', () => {
    expect(mapErrorToState(apiError(404, 'NOT_FOUND'), 'page')).toMatchObject({
      kind: 'page',
      page: 'not-found',
    });
  });

  it.each(['UNAUTHORIZED', 'INVALID_REFRESH_TOKEN'])('401 %s -> session-expired', (code) => {
    expect(mapErrorToState(apiError(401, code))).toEqual({ kind: 'page', page: 'session-expired' });
  });

  it('SessionExpiredError của client.ts -> session-expired, kể cả khi không có code', () => {
    expect(mapErrorToState(new SessionExpiredError())).toEqual({
      kind: 'page',
      page: 'session-expired',
    });
    expect(
      mapErrorToState(new SessionExpiredError(apiError(401, 'INVALID_REFRESH_TOKEN')), 'block'),
    ).toEqual({
      kind: 'page',
      page: 'session-expired',
    });
  });

  it('401 INVALID_CREDENTIALS -> form với message, KHÔNG phải session-expired', () => {
    const s = mapErrorToState(
      apiError(401, 'INVALID_CREDENTIALS', 'Sai mật khẩu. Còn 2 lần thử.', {
        details: { remainingAttempts: 2 },
      }),
      'page',
    );
    expect(s).toEqual({
      kind: 'form',
      fieldErrors: {},
      message: 'Sai mật khẩu. Còn 2 lần thử.',
      code: 'INVALID_CREDENTIALS',
      details: { remainingAttempts: 2 },
    });
  });

  it('fetch không tới được máy chủ (TypeError): trang -> offline, khối -> block', () => {
    const err = new TypeError('Failed to fetch');
    expect(mapErrorToState(err, 'page')).toMatchObject({ kind: 'page', page: 'offline' });
    expect(mapErrorToState(err, 'block')).toMatchObject({ kind: 'block', retryable: true });
  });

  it('TypeError do lỗi lập trình KHÔNG bị coi là mất mạng', () => {
    const err = new TypeError("Cannot read properties of undefined (reading 'x')");
    expect(mapErrorToState(err, 'page')).toMatchObject({ kind: 'page', page: 'server-error' });
  });

  it('lỗi không phải HTTP: khối -> block chung', () => {
    expect(mapErrorToState(new Error('boom'), 'block')).toMatchObject({
      kind: 'block',
      retryable: true,
    });
  });

  it('timeout -> block có Thử lại', () => {
    expect(mapErrorToState({ isTimeout: true }, 'page')).toMatchObject({
      kind: 'block',
      retryable: true,
    });
  });
});
