import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DashboardPage from '../../../src/features/dashboard/DashboardPage';

const { dashboardApiMock, branch, scopeState } = vi.hoisted(() => {
  const branch = { id: 7, code: 'GM-007', name: 'GreenMart Quận 7' };
  return {
    branch,
    scopeState: {
      branchId: branch.id,
      branches: [branch],
      canPickAll: false,
      pickAll: vi.fn(),
      scope: { kind: 'branch', branch },
    },
    dashboardApiMock: {
      revenue: vi.fn(),
      topProducts: vi.fn(),
      lowStockAlerts: vi.fn(),
    },
  };
});

vi.mock('../../../src/features/dashboard/dashboardApi', () => ({
  dashboardApi: dashboardApiMock,
  CHANNEL_OPTIONS: [
    { label: 'Bán tại quầy', value: '1' },
    { label: 'Website', value: '2' },
    { label: 'Sàn TMĐT', value: '3' },
  ],
}));

vi.mock('../../../src/layouts/operations/useOperationsScope', () => ({
  useOperationsScope: () => scopeState,
}));

describe('DashboardPage', () => {
  beforeEach(() => {
    scopeState.branchId = branch.id;
    scopeState.canPickAll = false;
    scopeState.scope = { kind: 'branch', branch };
    dashboardApiMock.revenue.mockResolvedValue({
      summary: {
        totalGrossRevenue: 120_000,
        totalReturnAmount: 20_000,
        totalNetRevenue: 100_000,
        totalOrders: 2,
        totalReturns: 1,
      },
      points: [
        {
          date: '2026-11-20',
          revenue: 100_000,
          grossRevenue: 120_000,
          returnAmount: 20_000,
          orderCount: 2,
          returnCount: 1,
        },
      ],
    });
    dashboardApiMock.topProducts.mockResolvedValue({
      period: { from: '2026-10-22', to: '2026-11-20' },
      topSelling: [],
      slowSelling: [],
    });
    dashboardApiMock.lowStockAlerts.mockResolvedValue([
      {
        id: 1,
        branchId: branch.id,
        productId: 11,
        sku: 'MILK-01',
        productName: 'Sữa tồn thấp',
        severity: 'LOW',
        available: 2,
        reorderPoint: 5,
        openedAt: '2026-11-20T08:00:00+07:00',
      },
      {
        id: 2,
        branchId: branch.id,
        productId: 12,
        sku: 'WATER-01',
        productName: 'Nước hết hàng',
        severity: 'OUT',
        available: 0,
        reorderPoint: 4,
        openedAt: '2026-11-20T07:00:00+07:00',
      },
    ]);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('loads live dashboard reports, prioritizes out-of-stock alerts, and filters by channel', async () => {
    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );

    const metrics = screen.getByLabelText('Chỉ số kinh doanh 30 ngày');
    expect(await within(metrics).findByText('100.000 đ')).toBeTruthy();
    expect(screen.getByText('Nước hết hàng')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Doanh thu theo chi nhánh' })).toBeNull();
    expect(screen.getByRole('heading', { name: 'Top sản phẩm bán chạy' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Xem tất cả' }).getAttribute('href')).toBe(
      '/operations/alerts',
    );

    const alertRows = screen
      .getByRole('heading', { name: 'Cảnh báo tồn kho' })
      .closest('section')
      ?.querySelectorAll('li');
    expect(alertRows?.[0]?.textContent).toContain('Nước hết hàng');
    expect(alertRows?.[1]?.textContent).toContain('Sữa tồn thấp');
    expect(dashboardApiMock.lowStockAlerts).toHaveBeenCalledWith(
      { branchId: branch.id },
      expect.anything(),
    );
    expect(dashboardApiMock.revenue).toHaveBeenCalledWith(
      expect.objectContaining({ branchId: branch.id, groupBy: 'day' }),
      expect.anything(),
    );

    fireEvent.change(screen.getByRole('combobox', { name: 'Lọc theo kênh bán' }), {
      target: { value: '2' },
    });
    await waitFor(() => {
      expect(dashboardApiMock.revenue).toHaveBeenCalledWith(
        expect.objectContaining({ branchId: branch.id, channelId: 2 }),
        expect.anything(),
      );
      expect(dashboardApiMock.topProducts).toHaveBeenCalledWith(
        expect.objectContaining({ branchId: branch.id, channelId: 2, limit: 10 }),
        expect.anything(),
      );
    });
  });

  it('shows branch revenue only to users who can view all branches', () => {
    scopeState.canPickAll = true;
    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: 'Doanh thu theo chi nhánh' })).toBeTruthy();
  });
});
