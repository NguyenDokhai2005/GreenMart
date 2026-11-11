import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { RevenueTooltipContent } from '../../../src/components/charts/RevenueTooltip';

afterEach(cleanup);

describe('RevenueTooltipContent', () => {
  it('hiện ngày, số tiền VND và tên chuỗi (tiêu chí: tooltip số VND)', () => {
    render(<RevenueTooltipContent active payload={[{ value: 15_000_000 }]} label="2026-10-18" />);
    expect(screen.getByText('15.000.000 đ')).toBeTruthy();
    expect(screen.getByText('18/10/2026')).toBeTruthy();
    expect(screen.getByText('Doanh thu')).toBeTruthy();
  });

  it('không hiện khi chưa rê chuột (inactive) hoặc không có giá trị', () => {
    const a = render(
      <RevenueTooltipContent active={false} payload={[{ value: 1 }]} label="2026-10-18" />,
    );
    expect(a.container.firstChild).toBeNull();
    a.unmount();
    const b = render(<RevenueTooltipContent active payload={[]} label="2026-10-18" />);
    expect(b.container.firstChild).toBeNull();
  });
});
