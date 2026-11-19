import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { components } from '../../../src/api/schema';
import InventoryLedgerPage from '../../../src/features/inventory/InventoryLedgerPage';
import LedgerOrderDetailDialog from '../../../src/features/inventory/LedgerOrderDetailDialog';
import type { InventoryTransactionQuery } from '../../../src/features/inventory/inventoryTransactionsApi';

const mocks = vi.hoisted(() => ({
  listInventoryTransactions: vi.fn(),
  getProduct: vi.fn(),
  getOrder: vi.fn(),
}));

vi.mock('../../../src/features/inventory/inventoryTransactionsApi', () => ({
  listInventoryTransactions: mocks.listInventoryTransactions,
}));

vi.mock('../../../src/features/catalog/catalogApi', () => ({
  catalogApi: { product: mocks.getProduct },
}));

vi.mock('../../../src/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../src/api/client')>();
  return { ...actual, getOrder: mocks.getOrder };
});

vi.mock('../../../src/layouts/operations/useOperationsScope', () => ({
  useOperationsScope: () => ({ branchId: 1 }),
}));

const order: components['schemas']['Order'] = {
  id: 99,
  code: 'ORD-99321',
  channel: 'POS',
  branch: { id: 1, code: 'GM-001', name: 'GreenMart Quận 1' },
  status: 'COMPLETED',
  total: 25000,
  createdAt: '2026-10-20T14:30:00+07:00',
  items: [
    {
      id: 1,
      productId: 1,
      sku: 'NM-500',
      name: 'Nước mắm Nam Ngư 500ml',
      unit: 'chai',
      quantity: 1,
      unitPrice: 25000,
      discount: 0,
      lineTotal: 25000,
    },
  ],
  subtotal: 25000,
  itemDiscount: 0,
  orderDiscount: 0,
  manualDiscount: 0,
  discount: 0,
  payments: [],
};

const transactions: components['schemas']['InventoryTransaction'][] = [
  {
    id: 10045,
    branchId: 1,
    productId: 1,
    sku: 'NM-500',
    type: 'SALE',
    deltaOnHand: -5,
    onHandAfter: 30,
    deltaReserved: 0,
    reservedAfter: 0,
    refType: 'ORDER',
    refId: 99,
    refCode: 'ORD-99321',
    createdAt: '2026-10-20T14:30:00+07:00',
  },
  {
    id: 10044,
    branchId: 1,
    productId: 2,
    sku: 'VNM-1L',
    type: 'IMPORT',
    deltaOnHand: 50,
    onHandAfter: 50,
    deltaReserved: 0,
    reservedAfter: 0,
    refType: 'STOCK_DOCUMENT',
    refId: 8,
    refCode: 'NK-GM001-001',
    createdAt: '2026-10-20T10:15:00+07:00',
  },
  {
    id: 10043,
    branchId: 1,
    productId: 2,
    sku: 'VNM-1L',
    type: 'TRANSFER_OUT',
    deltaOnHand: -5,
    onHandAfter: 45,
    deltaReserved: 0,
    reservedAfter: 0,
    refType: 'STOCK_DOCUMENT',
    refId: 11,
    refCode: 'PX-GM001-TRANSFER-011',
    createdAt: '2026-10-20T09:15:00+07:00',
  },
  {
    id: 10042,
    branchId: 1,
    productId: 2,
    sku: 'VNM-1L',
    type: 'TRANSFER_IN',
    deltaOnHand: 3,
    onHandAfter: 53,
    deltaReserved: 0,
    reservedAfter: 0,
    refType: 'STOCK_DOCUMENT',
    refId: 12,
    refCode: 'PX-GM002-TRANSFER-012',
    createdAt: '2026-10-20T08:15:00+07:00',
  },
];

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/operations/ledger']}>
      <Routes>
        <Route path="/operations/ledger" element={<InventoryLedgerPage />}>
          <Route path="orders/:id" element={<LedgerOrderDetailDialog />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  mocks.listInventoryTransactions.mockReset();
  mocks.getProduct.mockReset();
  mocks.getOrder.mockReset();
  mocks.listInventoryTransactions.mockImplementation(async (query: InventoryTransactionQuery) => ({
    page: query.page ?? 0,
    size: query.size ?? 20,
    totalElements: transactions.length,
    totalPages: 1,
    content: transactions,
  }));
  mocks.getProduct.mockImplementation(async (id: number) => ({
    id,
    name: id === 1 ? 'Nước mắm Nam Ngư 500ml' : 'Sữa tươi Vinamilk không đường 1L',
    sku: id === 1 ? 'NM-500' : 'VNM-1L',
  }));
  mocks.getOrder.mockResolvedValue(order);
});

afterEach(cleanup);

describe('InventoryLedgerPage', () => {
  it('hiển thị giao dịch và mở đơn gốc khi bấm mã tham chiếu', async () => {
    renderPage();

    expect(await screen.findByText('Nước mắm Nam Ngư 500ml')).toBeTruthy();
    expect(screen.getByRole('columnheader', { name: 'Mã giao dịch' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'ORD-99321' }).getAttribute('href')).toBe(
      '/operations/ledger/orders/99',
    );
    expect(screen.getByRole('link', { name: 'NK-GM001-001' }).getAttribute('href')).toBe(
      '/operations/stock-documents/8',
    );
    expect(screen.getByRole('link', { name: 'PX-GM001-TRANSFER-011' }).getAttribute('href')).toBe(
      '/operations/transfers/11',
    );
    expect(screen.getByRole('link', { name: 'PX-GM002-TRANSFER-012' }).getAttribute('href')).toBe(
      '/operations/transfers/12',
    );
    expect(screen.getAllByText('-5')).toHaveLength(2);
    expect(screen.getByText('+50')).toBeTruthy();

    fireEvent.click(screen.getByRole('link', { name: 'ORD-99321' }));
    const dialog = await screen.findByRole('dialog', { name: 'Chi tiết đơn hàng' });
    expect(within(dialog).getByText('Nước mắm Nam Ngư 500ml')).toBeTruthy();
    expect(mocks.getOrder).toHaveBeenCalledWith(99, expect.any(AbortSignal));
  });

  it('lọc tăng/giảm theo biến động khả dụng', async () => {
    renderPage();
    await screen.findByText('Nước mắm Nam Ngư 500ml');

    fireEvent.click(screen.getByRole('button', { name: '+ Tăng' }));
    await waitFor(() => {
      expect(screen.queryByText('ORD-99321')).toBeNull();
      expect(screen.getByText('NK-GM001-001')).toBeTruthy();
    });

    fireEvent.click(screen.getByRole('button', { name: '- Giảm' }));
    expect(await screen.findByText('ORD-99321')).toBeTruthy();
    expect(screen.queryByText('NK-GM001-001')).toBeNull();
  });

  it('tìm theo mã tham chiếu và hiện nút xóa bộ lọc khi không có kết quả', async () => {
    renderPage();
    await screen.findByText('Nước mắm Nam Ngư 500ml');

    fireEvent.change(screen.getByPlaceholderText('Mã GD, SKU, tham chiếu...'), {
      target: { value: 'no-match' },
    });
    expect(await screen.findByText('Không có kết quả phù hợp')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }));
    expect(await screen.findByText('ORD-99321')).toBeTruthy();
  });
});
