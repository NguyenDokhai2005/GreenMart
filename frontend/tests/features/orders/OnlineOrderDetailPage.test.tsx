import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import OnlineOrderDetailPage from '../../../src/features/orders/OnlineOrderDetailPage';

const { getOrder, completeOnlineOrder, cancelOnlineOrder, authRef } = vi.hoisted(() => ({
  getOrder: vi.fn(),
  completeOnlineOrder: vi.fn(),
  cancelOnlineOrder: vi.fn(),
  authRef: { current: { state: { status: 'authenticated', user: { role: 'BRANCH_MANAGER' } } } },
}));

vi.mock('../../../src/api/client', () => ({ getOrder, completeOnlineOrder, cancelOnlineOrder }));
vi.mock('../../../src/features/auth/useAuth', () => ({ useAuth: () => authRef.current }));
vi.mock('../../../src/features/auth/useActiveBranch', () => ({
  useActiveBranch: () => ({ clearBranch: vi.fn() }),
}));

const order = {
  id: 42,
  code: 'WEB-GM001-20261105-0007',
  channel: 'WEB',
  externalOrderId: 'WEB-7788009',
  branch: { id: 1, code: 'GM-001', name: 'Quận 1' },
  status: 'CONFIRMED',
  createdAt: '2026-11-05T09:52:00+07:00',
  customer: { id: 5, name: 'Trần Thị Mai', phone: '0987654321' },
  items: [
    {
      id: 8,
      productId: 205,
      sku: 'SUA-TH-1L',
      name: 'Sữa tươi',
      unit: 'hộp',
      quantity: 2,
      unitPrice: 80000,
      discount: 6000,
      lineTotal: 154000,
    },
  ],
  payments: [
    { id: 9, method: 'QR', amount: 154000, status: 'PAID', paidAt: '2026-11-05T09:53:00+07:00' },
  ],
  subtotal: 160000,
  itemDiscount: 6000,
  orderDiscount: 0,
  manualDiscount: 0,
  discount: 6000,
  total: 154000,
};

function mount() {
  return render(
    <MemoryRouter initialEntries={['/operations/orders/42']}>
      <Routes>
        <Route path="/operations/orders/:orderId" element={<OnlineOrderDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  getOrder.mockReset().mockResolvedValue(order);
  completeOnlineOrder.mockReset();
  cancelOnlineOrder.mockReset();
  authRef.current = { state: { status: 'authenticated', user: { role: 'BRANCH_MANAGER' } } };
});
afterEach(cleanup);

describe('Online order detail #81', () => {
  it('shows order details and completes a confirmed order as branch manager', async () => {
    completeOnlineOrder.mockResolvedValue({ ...order, status: 'COMPLETED' });
    mount();
    expect(await screen.findByText('WEB-7788009')).toBeTruthy();
    expect(screen.getByText('Trần Thị Mai')).toBeTruthy();
    expect(screen.getAllByText('154.000 đ').length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: 'Hoàn tất đơn' }));
    expect(screen.getByRole('dialog', { name: `Hoàn tất đơn ${order.code}?` })).toBeTruthy();
    fireEvent.click(screen.getByRole('dialog').querySelector('button.gm-button--primary')!);
    await waitFor(() => expect(completeOnlineOrder).toHaveBeenCalledWith(42));
    expect(await screen.findByText('Đơn đã hoàn tất, không thể hủy.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Hủy đơn' })).toBeNull();
  });

  it('requires a note for OTHER and sends only contracted cancellation fields', async () => {
    getOrder.mockResolvedValue({ ...order, status: 'RESERVED' });
    cancelOnlineOrder.mockResolvedValue({
      ...order,
      status: 'CANCELLED',
      statusReason: 'OTHER',
      statusNote: 'Khách đổi ý',
    });
    mount();
    fireEvent.click(await screen.findByRole('button', { name: 'Hủy đơn' }));
    const dialog = screen.getByRole('dialog');
    expect(dialog.textContent).toContain('hoàn tiền theo phương thức gốc');
    fireEvent.change(screen.getByLabelText('Lý do hủy'), { target: { value: 'OTHER' } });
    expect(
      (dialog.querySelector('button.gm-button--destructive') as HTMLButtonElement).disabled,
    ).toBe(true);
    fireEvent.change(screen.getByLabelText('Ghi chú'), { target: { value: 'Khách đổi ý' } });
    fireEvent.click(dialog.querySelector('button.gm-button--destructive')!);
    await waitFor(() =>
      expect(cancelOnlineOrder).toHaveBeenCalledWith(42, { reason: 'OTHER', note: 'Khách đổi ý' }),
    );
    expect(await screen.findByText('Đơn đã hủy, không thể xử lý thêm.')).toBeTruthy();
  });

  it('restricts actions by role and order status', async () => {
    authRef.current = { state: { status: 'authenticated', user: { role: 'STOREKEEPER' } } };
    const view = mount();
    expect(await screen.findByRole('button', { name: 'Hoàn tất đơn' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Hủy đơn' })).toBeNull();
    view.unmount();
    authRef.current = { state: { status: 'authenticated', user: { role: 'OWNER' } } };
    mount();
    expect(await screen.findByText(order.code)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Hoàn tất đơn' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Hủy đơn' })).toBeNull();
  });
});
