import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import StockAlertsPage from '../../../src/features/dashboard/StockAlertsPage';

const { lowStockAlerts } = vi.hoisted(() => ({
  lowStockAlerts: vi.fn(),
}));

vi.mock('../../../src/features/dashboard/dashboardApi', () => ({
  dashboardApi: {
    lowStockAlerts,
  },
}));

vi.mock('../../../src/layouts/operations/useOperationsScope', () => ({
  useOperationsScope: () => ({
    branchId: 7,
    branches: [],
    canPickAll: false,
    pickAll: vi.fn(),
  }),
}));

describe('StockAlertsPage', () => {
  beforeEach(() => {
    lowStockAlerts.mockResolvedValue([]);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('navigates back to the dashboard from the alerts page', () => {
    render(
      <MemoryRouter initialEntries={['/operations/alerts']}>
        <Routes>
          <Route path="/operations/alerts" element={<StockAlertsPage />} />
          <Route path="/operations" element={<div>Dashboard tổng quan</div>} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Quay lại tổng quan' }));
    expect(screen.getByText('Dashboard tổng quan')).toBeTruthy();
  });
});
