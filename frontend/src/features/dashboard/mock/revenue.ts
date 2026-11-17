import type { RevenuePoint } from '../../../components/charts/types';

const DAYS = 30;
/** 01/10/2026 → 30/10/2026 */
const START = Date.UTC(2026, 9, 1);

/** Dữ liệu mock cố định (không ngẫu nhiên): xu hướng tăng + chu kỳ tuần + dao động, 15–60 triệu đ/ngày. */
function build(): RevenuePoint[] {
  return Array.from({ length: DAYS }, (_, i) => {
    const date = new Date(START + i * 86_400_000).toISOString().slice(0, 10);
    const trend = 24_000_000 + i * 700_000;
    const weekly = Math.sin((i / 7) * 2 * Math.PI) * 7_000_000;
    const wobble = Math.sin(i * 1.7) * 4_000_000;
    const revenue = Math.min(
      60_000_000,
      Math.max(15_000_000, Math.round((trend + weekly + wobble) / 1000) * 1000),
    );
    return { date, revenue };
  });
}

export const REVENUE_30_DAYS: readonly RevenuePoint[] = build();

/** Lấy n ngày gần nhất (7 ngày / 30 ngày). */
export function lastDays(data: readonly RevenuePoint[], days: number): RevenuePoint[] {
  return data.slice(-days);
}
