import { describe, expect, it } from 'vitest';
import { REVENUE_30_DAYS, lastDays } from '../../../src/features/dashboard/mock/revenue';

describe('REVENUE_30_DAYS (mock doanh thu 30 ngày)', () => {
  it('đủ 30 ngày liên tiếp từ 01/10 đến 30/10/2026', () => {
    expect(REVENUE_30_DAYS).toHaveLength(30);
    expect(REVENUE_30_DAYS[0]?.date).toBe('2026-10-01');
    expect(REVENUE_30_DAYS[29]?.date).toBe('2026-10-30');
    const dates = REVENUE_30_DAYS.map((p) => p.date);
    expect(new Set(dates).size).toBe(30);
    expect([...dates].sort()).toEqual(dates);
  });

  it('doanh thu là VND số nguyên, nằm trong 15–60 triệu', () => {
    for (const p of REVENUE_30_DAYS) {
      expect(Number.isInteger(p.revenue)).toBe(true);
      expect(p.revenue).toBeGreaterThanOrEqual(15_000_000);
      expect(p.revenue).toBeLessThanOrEqual(60_000_000);
    }
  });

  it('lastDays lấy đúng n ngày gần nhất', () => {
    const week = lastDays(REVENUE_30_DAYS, 7);
    expect(week).toHaveLength(7);
    expect(week).toEqual(REVENUE_30_DAYS.slice(-7));
  });
});
