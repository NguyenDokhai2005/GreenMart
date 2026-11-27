import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../../src/api/client';
import type { components } from '../../../src/api/schema';
import ReportsPage from '../../../src/features/reports/ReportsPage';

const mocks = vi.hoisted(() => ({
  getRevenueReport: vi.fn(),
  getTopProductsReport: vi.fn(),
}));

vi.mock('../../../src/features/reports/reportApi', () => ({
  getRevenueReport: mocks.getRevenueReport,
  getTopProductsReport: mocks.getTopProductsReport,
}));

vi.mock('../../../src/layouts/operations/useOperationsScope', () => ({
  useOperationsScope: () => ({
    branchId: 7,
    scope: { kind: 'branch', branch: { id: 7, code: 'GM-007', name: 'Chi nhánh 7' } },
  }),
}));

const revenue: components['schemas']['RevenueReportResponse'] = {
  summary: {
    totalGrossRevenue: 120000,
    totalReturnAmount: 20000,
    totalNetRevenue: 100000,
    totalOrders: 8,
    totalReturns: 1,
  },
  points: [
    {
      date: '2026-11-01',
      revenue: 100000,
      grossRevenue: 120000,
      returnAmount: 20000,
      orderCount: 8,
      returnCount: 1,
    },
  ],
};

const products: components['schemas']['TopProductsResponse'] = {
  period: { from: '2026-11-01', to: '2026-11-10' },
  topSelling: [
    {
      productId: 1,
      sku: 'RICE-01',
      name: 'Gạo thơm',
      categoryName: 'Gạo',
      soldQuantity: 10,
      returnedQuantity: 1,
      netQuantity: 9,
      revenue: 90000,
    },
  ],
  slowSelling: [],
};

function renderPage() {
  return render(
    <MemoryRouter>
      <ReportsPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  mocks.getRevenueReport.mockReset().mockResolvedValue(revenue);
  mocks.getTopProductsReport.mockReset().mockResolvedValue(products);
});

afterEach(cleanup);

describe('ReportsPage', () => {
  it('loads revenue and top products for the selected dates and branch', async () => {
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Báo cáo' })).toBeTruthy();
    fireEvent.change(screen.getByLabelText(/Từ ngày/), { target: { value: '2026-11-01' } });
    fireEvent.change(screen.getByLabelText(/Đến ngày/), { target: { value: '2026-11-10' } });
    fireEvent.change(screen.getByLabelText('Gom doanh thu theo'), { target: { value: 'month' } });
    fireEvent.click(screen.getByRole('button', { name: 'Xem báo cáo' }));

    await waitFor(() => {
      expect(mocks.getRevenueReport).toHaveBeenLastCalledWith(
        expect.objectContaining({
          from: '2026-11-01',
          to: '2026-11-10',
          groupBy: 'month',
          branchId: 7,
        }),
        expect.any(AbortSignal),
      );
      expect(mocks.getTopProductsReport).toHaveBeenLastCalledWith(
        expect.objectContaining({ from: '2026-11-01', to: '2026-11-10', limit: 10, branchId: 7 }),
        expect.any(AbortSignal),
      );
    });
    expect(await screen.findByText('Gạo thơm')).toBeTruthy();
    expect(screen.getAllByText('100.000 đ').length).toBeGreaterThan(0);
  });

  it('does not show a retry action for a forbidden report response', async () => {
    mocks.getRevenueReport.mockRejectedValue(new ApiError('Không có quyền', 403, 'FORBIDDEN'));
    renderPage();

    expect(await screen.findByText('Không có quyền')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Thử lại' })).toBeNull();
  });
});
