import { describe, expect, it } from 'vitest';
import {
  PAGE_SIZE,
  parsePage,
  parseStatus,
  toApiQuery,
} from '../../../src/features/inventory/inventoryQuery';

describe('inventoryQuery', () => {
  it('parseStatus chỉ nhận OUT và LOW', () => {
    expect(parseStatus('OUT')).toBe('OUT');
    expect(parseStatus('LOW')).toBe('LOW');
    expect(parseStatus('OK')).toBeUndefined();
    expect(parseStatus(null)).toBeUndefined();
  });

  it('parsePage: số sai hoặc nhỏ hơn 1 về trang 1', () => {
    expect(parsePage('3')).toBe(3);
    expect(parsePage('0')).toBe(1);
    expect(parsePage('abc')).toBe(1);
    expect(parsePage('2.5')).toBe(1);
    expect(parsePage(null)).toBe(1);
  });

  it('toApiQuery đổi số trang URL (từ 1) sang số trang API (từ 0) và bỏ chuỗi tìm kiếm rỗng', () => {
    expect(toApiQuery({ branchId: 1, q: '  ', status: 'OUT', page: 3 })).toEqual({
      branchId: 1,
      q: undefined,
      stockStatus: 'OUT',
      page: 2,
      size: PAGE_SIZE,
    });
    expect(toApiQuery({ branchId: undefined, q: ' sữa ', status: undefined, page: 1 }).q).toBe(
      'sữa',
    );
  });
});
