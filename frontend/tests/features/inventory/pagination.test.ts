import { describe, expect, it } from 'vitest';
import { getPageItems } from '../../../src/features/inventory/pagination';

describe('getPageItems', () => {
  it('tổng 8 trang theo Figma: trang 1 → 1 2 3 … 8', () => {
    expect(getPageItems(1, 8)).toEqual([1, 2, 3, 'ellipsis', 8]);
  });
  it('trang giữa có dấu … hai phía', () => {
    expect(getPageItems(6, 10)).toEqual([1, 'ellipsis', 5, 6, 7, 'ellipsis', 10]);
  });
  it('khoảng hở một trang thì hiện luôn số trang, không dùng …', () => {
    expect(getPageItems(4, 8)).toEqual([1, 2, 3, 4, 5, 'ellipsis', 8]);
  });
  it('trang cuối', () => {
    expect(getPageItems(8, 8)).toEqual([1, 'ellipsis', 6, 7, 8]);
  });
  it('ít trang thì hiện hết; một trang hoặc không có thì không hiện', () => {
    expect(getPageItems(2, 4)).toEqual([1, 2, 3, 4]);
    expect(getPageItems(1, 1)).toEqual([]);
    expect(getPageItems(1, 0)).toEqual([]);
  });
});
