import { describe, expect, it } from 'vitest';
import {
  parseDateParam,
  parsePageParam,
  parseTransferStatus,
  toTransferQuery,
  TRANSFER_PAGE_SIZE,
} from '../../../src/features/transfers/transferQuery';

describe('transferQuery', () => {
  it('trạng thái chỉ nhận 4 giá trị của phiếu chuyển', () => {
    expect(parseTransferStatus('IN_TRANSIT')).toBe('IN_TRANSIT');
    expect(parseTransferStatus('APPROVED')).toBeUndefined();
    expect(parseTransferStatus(null)).toBeUndefined();
  });

  it('ngày phải đúng YYYY-MM-DD và có thật', () => {
    expect(parseDateParam('2026-11-07')).toBe('2026-11-07');
    expect(parseDateParam('2026-02-30')).toBeUndefined();
    expect(parseDateParam('07/11/2026')).toBeUndefined();
    expect(parseDateParam('')).toBeUndefined();
  });

  it('số trang từ 1', () => {
    expect(parsePageParam('2')).toBe(2);
    expect(parsePageParam('0')).toBe(1);
    expect(parsePageParam('x')).toBe(1);
  });

  it('đổi sang tham số API: trang từ 0, bỏ chuỗi tìm rỗng', () => {
    expect(
      toTransferQuery({
        branchId: 1,
        q: ' CK ',
        status: 'DRAFT',
        from: '2026-11-01',
        to: undefined,
        page: 3,
      }),
    ).toEqual({
      branchId: 1,
      q: 'CK',
      status: 'DRAFT',
      from: '2026-11-01',
      to: undefined,
      page: 2,
      size: TRANSFER_PAGE_SIZE,
    });
    expect(
      toTransferQuery({
        branchId: undefined,
        q: '  ',
        status: undefined,
        from: undefined,
        to: undefined,
        page: 1,
      }).q,
    ).toBeUndefined();
  });
});
