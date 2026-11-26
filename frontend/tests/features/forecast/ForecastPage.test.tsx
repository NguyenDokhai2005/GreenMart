import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ForecastPage from '../../../src/features/forecast/ForecastPage';

const { getForecast, getReplenishment, listInventory, scopeState } = vi.hoisted(() => ({
  getForecast: vi.fn(),
  getReplenishment: vi.fn(),
  listInventory: vi.fn(),
  scopeState: {
    branchId: 7,
    branches: [{ id: 7, code: 'GM-007', name: 'Quận 7' }],
    canPickAll: false,
    pickAll: vi.fn(),
    scope: { kind: 'branch', branch: { id: 7, code: 'GM-007', name: 'Quận 7' } },
  },
}));

vi.mock('../../../src/features/inventory/inventoryApi', () => ({
  getReplenishment,
  listInventory,
}));

vi.mock('../../../src/features/forecast/forecastApi', () => ({
  getForecast,
}));

vi.mock('../../../src/layouts/operations/useOperationsScope', () => ({
  useOperationsScope: () => scopeState,
}));

const inventoryItem = {
  branch: { id: 7, code: 'GM-007', name: 'Quận 7' },
  productId: 11,
  sku: 'MILK-01',
  productName: 'Sữa tươi',
  unit: 'hộp',
  onHand: 2,
  reserved: 0,
  available: 2,
  reorderPoint: 5,
  stockStatus: 'LOW',
  version: 1,
  updatedAt: '2026-11-20T08:00:00+07:00',
};

const forecastResponse = {
  runId: 5,
  cutoffDate: '2026-11-20',
  generatedAt: '2026-11-20T08:00:00+07:00',
  modelVersion: 'model-v2',
  branch: inventoryItem.branch,
  productId: inventoryItem.productId,
  sku: inventoryItem.sku,
  productName: inventoryItem.productName,
  unit: inventoryItem.unit,
  horizons: [
    {
      horizon: 7,
      totalPredictedQty: 14,
      points: [{ date: '2026-11-21', predictedQty: 2, actualQty: null }],
    },
    {
      horizon: 30,
      totalPredictedQty: 60,
      points: [{ date: '2026-11-21', predictedQty: 2, actualQty: null }],
    },
  ],
};

describe('ForecastPage', () => {
  beforeEach(() => {
    listInventory.mockResolvedValue({
      content: [inventoryItem],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    });
    getReplenishment.mockResolvedValue({
      branchId: 7,
      productId: 11,
      available: 2,
      incoming: 0,
      reorderPoint: 5,
      safetyStock: 2,
      forecastDemand: 4,
      packSize: 1,
      suggestedQuantity: 6,
    });
    getForecast.mockResolvedValue(forecastResponse);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('loads live inventory forecasts and switches the displayed horizon', async () => {
    render(
      <MemoryRouter>
        <ForecastPage />
      </MemoryRouter>,
    );

    expect(await screen.findByRole('heading', { name: 'Sữa tươi' })).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Xem tồn kho' })).toBeNull();
    expect(await screen.findByText(/SKU MILK-01/)).toBeTruthy();
    expect(screen.getByText(/Model model-v2/)).toBeTruthy();
    expect(screen.getAllByText('6').length).toBeGreaterThan(0);
    expect(listInventory).toHaveBeenCalledWith(
      { branchId: 7, q: undefined, stockStatus: undefined, page: 0, size: 10 },
      expect.anything(),
    );
    expect(getReplenishment).toHaveBeenCalledWith(7, 11, expect.anything());
    await waitFor(() => {
      expect(getForecast).toHaveBeenCalledWith({ branchId: 7, productId: 11 }, expect.anything());
    });
    expect(
      await screen.findByRole('group', { name: 'Biểu đồ dự báo nhu cầu 7 ngày' }),
    ).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '30 ngày' }));
    expect(
      await screen.findByRole('group', { name: 'Biểu đồ dự báo nhu cầu 30 ngày' }),
    ).toBeTruthy();
  });

  it('shows an empty forecast state when replenishment has no forecast', async () => {
    getReplenishment.mockResolvedValueOnce({
      branchId: 7,
      productId: 11,
      available: 2,
      incoming: 0,
      reorderPoint: null,
      safetyStock: null,
      forecastDemand: null,
      packSize: 1,
      suggestedQuantity: null,
    });

    render(
      <MemoryRouter>
        <ForecastPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('Chưa có dữ liệu dự báo cho sản phẩm này')).toBeTruthy();
    expect(getForecast).not.toHaveBeenCalled();
  });

  it('does not show an empty placeholder while the inventory request is pending', async () => {
    listInventory.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          setTimeout(
            () =>
              resolve({
                content: [inventoryItem],
                page: 0,
                size: 10,
                totalElements: 1,
                totalPages: 1,
              }),
            100,
          );
        }),
    );

    render(
      <MemoryRouter>
        <ForecastPage />
      </MemoryRouter>,
    );

    expect(screen.queryByText('Chọn một sản phẩm có dự báo ở bảng bên dưới')).toBeNull();
    expect(await screen.findByRole('heading', { name: 'Sữa tươi' })).toBeTruthy();
  });
});
