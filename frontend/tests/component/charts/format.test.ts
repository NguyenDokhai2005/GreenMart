import { describe, expect, it } from 'vitest';
import {
  formatDayFull,
  formatDayLabel,
  formatVnd,
  formatVndCompact,
} from '../../../src/components/charts/format';

describe('formatVnd', () => {
  it('dùng dấu chấm ngăn nghìn và hậu tố đ', () => {
    expect(formatVnd(15_000_000)).toBe('15.000.000 đ');
    expect(formatVnd(200_700)).toBe('200.700 đ');
    expect(formatVnd(0)).toBe('0 đ');
  });
  it('làm tròn về số nguyên', () => {
    expect(formatVnd(1234.6)).toBe('1.235 đ');
  });
});

describe('formatVndCompact', () => {
  it.each([
    [60_000_000, '60M'],
    [1_500_000, '1,5M'],
    [2_500, '2,5K'],
    [1_000_000_000, '1B'],
    [0, '0'],
  ])('%d → %s', (n, expected) => {
    expect(formatVndCompact(n)).toBe(expected);
  });
});

describe('nhãn ngày', () => {
  it('cắt chuỗi ISO, không lệch múi giờ', () => {
    expect(formatDayLabel('2026-10-18')).toBe('18/10');
    expect(formatDayFull('2026-10-18')).toBe('18/10/2026');
  });
});
