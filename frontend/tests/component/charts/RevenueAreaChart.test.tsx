import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { REVENUE_30_DAYS, lastDays } from '../../../src/features/dashboard/mock/revenue';
import RevenueAreaChart from '../../../src/components/charts/RevenueAreaChart';
import Sparkline from '../../../src/components/charts/Sparkline';

beforeEach(() => {
  // jsdom không có ResizeObserver và luôn đo kích thước = 0; ResponsiveContainer cần cả hai.
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
    width: 640,
    height: 320,
    x: 0,
    y: 0,
    top: 0,
    left: 0,
    right: 640,
    bottom: 320,
    toJSON: () => ({}),
  });
});
afterEach(cleanup);

// jsdom không đo được chữ nên không dựng nội dung nhãn; kiểm tra số tick của trục X.
// Định dạng nhãn ngày đã có test riêng ở format.test.ts.
const xTickCount = (root: HTMLElement) =>
  root.querySelectorAll('.recharts-xAxis .recharts-cartesian-axis-tick').length;

describe('RevenueAreaChart', () => {
  it('vẽ đường vùng và trục ngày từ dữ liệu mock 30 ngày', () => {
    const { container } = render(<RevenueAreaChart data={REVENUE_30_DAYS} />);
    expect(container.querySelector('.recharts-area')).not.toBeNull();
    // 30 điểm: cách 3 ngày một nhãn → 10 nhãn
    expect(xTickCount(container)).toBe(10);
  });

  it('mô tả cho trình đọc màn hình gồm số ngày và tổng doanh thu', () => {
    render(<RevenueAreaChart data={REVENUE_30_DAYS} />);
    const total = REVENUE_30_DAYS.reduce((s, p) => s + p.revenue, 0);
    const label = screen.getByRole('group').getAttribute('aria-label') ?? '';
    expect(label).toContain('30 ngày');
    expect(label).toContain(new Intl.NumberFormat('vi-VN').format(total));
  });

  it('chuyển 7 ngày: hiện nhãn từng ngày (7 tick)', () => {
    const { container } = render(<RevenueAreaChart data={lastDays(REVENUE_30_DAYS, 7)} />);
    expect(xTickCount(container)).toBe(7);
  });

  it('nhiều biểu đồ trên một trang không trùng id gradient', () => {
    const { container } = render(
      <>
        <RevenueAreaChart data={REVENUE_30_DAYS} />
        <RevenueAreaChart data={REVENUE_30_DAYS} />
      </>,
    );
    const ids = [...container.querySelectorAll('linearGradient')].map((g) => g.id);
    expect(ids).toHaveLength(2);
    expect(new Set(ids).size).toBe(2);
  });
});

describe('Sparkline', () => {
  it('render không trục, có mô tả', () => {
    const data = REVENUE_30_DAYS.map((p) => ({ date: p.date, value: p.revenue }));
    const { container } = render(<Sparkline data={data} tone="danger" ariaLabel="Đơn hủy" />);
    expect(screen.getByRole('img', { name: 'Đơn hủy' })).toBeTruthy();
    expect(container.querySelector('.recharts-xAxis')).toBeNull();
  });
});
