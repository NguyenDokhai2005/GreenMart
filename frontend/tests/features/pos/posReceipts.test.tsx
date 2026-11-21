import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import PosReceiptsPage from '../../../src/features/pos/PosReceiptsPage';
import { POS_NAVIGATION } from '../../../src/layouts/pos/posNavigation';

const { listOrders, getOrder } = vi.hoisted(() => ({ listOrders: vi.fn(), getOrder: vi.fn() }));
vi.mock('../../../src/api/client', () => ({ listOrders, getOrder }));
vi.mock('../../../src/features/auth/useActiveBranch', () => ({
  useActiveBranch: () => ({
    state: { status: 'active', branch: { id: 1, code: 'GM-001', name: 'Quận 1' } },
  }),
}));

const order = {
  id: 42,
  code: 'POS-GM001-20261116-0042',
  branch: { id: 1, code: 'GM-001', name: 'Quận 1' },
  channel: 'POS',
  status: 'COMPLETED',
  createdAt: '2026-11-16T09:00:00+07:00',
  total: 108000,
  createdBy: { id: 5, fullName: 'Trần Thu Ngân' },
  customer: { id: 7, name: 'Lê Hoàng Mai', phone: '0900000000' },
  items: [
    {
      id: 9,
      productId: 11,
      sku: 'SUA-TH',
      name: 'Sữa tươi',
      unit: 'hộp',
      quantity: 2,
      unitPrice: 64000,
      discount: 12000,
      lineTotal: 116000,
      promotion: { id: 3, code: 'KM-SUA', discount: 12000 },
    },
  ],
  subtotal: 128000,
  itemDiscount: 12000,
  orderDiscount: 8000,
  manualDiscount: 0,
  discount: 20000,
  orderPromotion: { id: 4, code: 'DON-8K', discount: 8000 },
  payments: [
    { id: 8, method: 'CASH', status: 'PAID', amount: 108000, cashReceived: 150000, change: 42000 },
  ],
};

function renderPage(path = '/pos/receipts') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <PosReceiptsPage item={POS_NAVIGATION[1]} />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  listOrders.mockReset().mockResolvedValue({ content: [order] });
  getOrder.mockReset().mockResolvedValue(order);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it('loads POS receipts for the active branch and prints persisted order details', async () => {
  const print = vi.fn();
  vi.stubGlobal('print', print);
  renderPage();
  await waitFor(() =>
    expect(listOrders).toHaveBeenCalledWith(
      { branchId: 1, channel: 'POS', page: 0, size: 20 },
      expect.any(AbortSignal),
    ),
  );
  fireEvent.click(await screen.findByRole('button', { name: /POS-GM001-20261116-0042/ }));
  await waitFor(() => expect(getOrder).toHaveBeenCalledWith(42, expect.any(AbortSignal)));
  expect(await screen.findByRole('article', { name: `Hóa đơn ${order.code}` })).toBeTruthy();
  expect(screen.getByText('Trần Thu Ngân')).toBeTruthy();
  expect(screen.getByText(/Lê Hoàng Mai/)).toBeTruthy();
  expect(screen.getByText(/KM-SUA/)).toBeTruthy();
  expect(screen.getByText(/DON-8K/)).toBeTruthy();
  expect(screen.getAllByText('108.000 ₫').length).toBeGreaterThan(0);
  expect(screen.getByText('150.000 ₫')).toBeTruthy();
  expect(screen.getByText('42.000 ₫')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'In hóa đơn' }));
  expect(print).toHaveBeenCalledOnce();
});

it('opens a completed receipt directly and retries a failed API request', async () => {
  getOrder.mockRejectedValueOnce({ status: 500, message: 'Lỗi máy chủ' });
  renderPage('/pos/receipts?orderId=42');
  expect(await screen.findByText('Lỗi hệ thống')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
  expect(await screen.findByRole('article', { name: `Hóa đơn ${order.code}` })).toBeTruthy();
  expect(getOrder).toHaveBeenCalledTimes(2);
  expect(listOrders).not.toHaveBeenCalled();
});

it('loads another page of receipts using the API page parameter', async () => {
  listOrders.mockResolvedValue({ content: [order], totalPages: 3 });
  renderPage();
  fireEvent.click(await screen.findByRole('button', { name: 'Sau' }));
  await waitFor(() =>
    expect(listOrders).toHaveBeenLastCalledWith(
      { branchId: 1, channel: 'POS', page: 1, size: 20 },
      expect.any(AbortSignal),
    ),
  );
  expect(await screen.findByText('Trang 2/3')).toBeTruthy();
});
