import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PosReturnsPage from '../../../src/features/returns/PosReturnsPage';

const {
  listOrders,
  getOrder,
  listReturnsByOrder,
  createReturn,
  approveReturn,
  cancelReturn,
  role,
} = vi.hoisted(() => ({
  listOrders: vi.fn(),
  getOrder: vi.fn(),
  listReturnsByOrder: vi.fn(),
  createReturn: vi.fn(),
  approveReturn: vi.fn(),
  cancelReturn: vi.fn(),
  role: { current: 'CASHIER' as string },
}));
vi.mock('../../../src/api/client', () => ({ listOrders, getOrder }));
vi.mock('../../../src/features/returns/returnsApi', () => ({
  listReturnsByOrder,
  createReturn,
  approveReturn,
  cancelReturn,
}));
vi.mock('../../../src/features/auth/useActiveBranch', () => ({
  useActiveBranch: () => ({ state: { status: 'active', branch: { id: 1 } } }),
}));
vi.mock('../../../src/features/auth/useAuth', () => ({
  useAuth: () => ({ state: { status: 'authenticated', user: { id: 7, role: role.current } } }),
}));

const branch = { id: 1, code: 'GM-001', name: 'Quận 1' };
const order = {
  id: 10,
  code: 'POS-GM001-20261121-0001',
  status: 'COMPLETED',
  total: 32000,
  branch,
  items: [
    {
      id: 15,
      productId: 2,
      sku: 'MILK',
      name: 'Sữa',
      unit: 'hộp',
      quantity: 2,
      unitPrice: 16000,
      discount: 0,
      lineTotal: 32000,
    },
  ],
};
const document = {
  id: 20,
  code: 'RET-GM001-20261121-0001',
  status: 'DRAFT',
  orderId: 10,
  orderCode: order.code,
  branch,
  reason: 'Hàng lỗi',
  refundAmount: 16000,
  createdBy: { id: 7, fullName: 'Thu ngân' },
  createdAt: '2026-11-21T00:00:00Z',
  items: [
    {
      id: 30,
      orderItemId: 15,
      productId: 2,
      sku: 'MILK',
      name: 'Sữa',
      unit: 'hộp',
      quantity: 1,
      condition: 'DAMAGED',
      refundAmount: 16000,
    },
  ],
};
beforeEach(() => {
  role.current = 'CASHIER';
  listOrders.mockReset().mockResolvedValue({ content: [order], totalPages: 1 });
  getOrder.mockReset().mockResolvedValue(order);
  listReturnsByOrder.mockReset().mockResolvedValue([]);
  createReturn.mockReset().mockResolvedValue(document);
  approveReturn.mockReset();
  cancelReturn.mockReset().mockResolvedValue({ ...document, status: 'CANCELLED' });
});
afterEach(() => cleanup());

describe('POS returns', () => {
  async function openReturnDocument() {
    render(<PosReturnsPage />);
    fireEvent.change(screen.getByLabelText('Mã đơn hoặc số điện thoại'), {
      target: { value: order.code },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Tìm đơn' }));
    fireEvent.click(await screen.findByRole('button', { name: new RegExp(order.code) }));
    await screen.findByText('Đơn ' + order.code);
  }

  it('searches within the active branch and creates a draft with contract fields', async () => {
    render(<PosReturnsPage />);
    fireEvent.change(screen.getByLabelText('Mã đơn hoặc số điện thoại'), {
      target: { value: order.code },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Tìm đơn' }));
    await screen.findByText(new RegExp(order.code));
    expect(listOrders).toHaveBeenCalledWith(
      { q: order.code, page: 0, size: 20 },
      expect.any(AbortSignal),
    );
    fireEvent.click(screen.getByRole('button', { name: new RegExp(order.code) }));
    await screen.findByText('Đơn ' + order.code);
    fireEvent.change(screen.getByLabelText('Số lượng trả Sữa'), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText('Tình trạng Sữa'), { target: { value: 'DAMAGED' } });
    fireEvent.change(screen.getByLabelText('Lý do trả hàng'), { target: { value: 'Hàng lỗi' } });
    fireEvent.click(screen.getByRole('button', { name: 'Tạo phiếu trả hàng' }));
    await waitFor(() =>
      expect(createReturn).toHaveBeenCalledWith({
        orderId: 10,
        branchId: 1,
        reason: 'Hàng lỗi',
        items: [{ orderItemId: 15, quantity: 1, condition: 'DAMAGED' }],
      }),
    );
    expect(approveReturn).not.toHaveBeenCalled();
  });
  it('ignores an old order response after a new search', async () => {
    let resolveOrder!: (value: typeof order) => void;
    getOrder.mockReturnValue(
      new Promise((resolve) => {
        resolveOrder = resolve;
      }),
    );
    render(<PosReturnsPage />);
    const search = screen.getByLabelText('Mã đơn hoặc số điện thoại');
    fireEvent.change(search, { target: { value: order.code } });
    fireEvent.click(screen.getByRole('button', { name: 'Tìm đơn' }));
    fireEvent.click(await screen.findByRole('button', { name: new RegExp(order.code) }));
    fireEvent.change(search, { target: { value: 'KHAC' } });
    fireEvent.click(screen.getByRole('button', { name: 'Tìm đơn' }));
    resolveOrder(order);
    await waitFor(() =>
      expect(listOrders).toHaveBeenCalledWith(
        { q: 'KHAC', page: 0, size: 20 },
        expect.any(AbortSignal),
      ),
    );
    expect(screen.queryByText('Đơn ' + order.code)).toBeNull();
  });
  it('reserves quantities already requested on draft returns', async () => {
    listReturnsByOrder.mockResolvedValue([document]);
    render(<PosReturnsPage />);
    fireEvent.change(screen.getByLabelText('Mã đơn hoặc số điện thoại'), {
      target: { value: order.code },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Tìm đơn' }));
    fireEvent.click(await screen.findByRole('button', { name: new RegExp(order.code) }));
    await screen.findByText('Đơn ' + order.code);
    expect(screen.getByRole('cell', { name: '1' })).toBeTruthy();
    expect((screen.getByLabelText('Số lượng trả Sữa') as HTMLInputElement).max).toBe('1');
  });
  it('asks the server to confirm remaining quantity for an order from another branch', async () => {
    getOrder.mockResolvedValue({ ...order, branch: { id: 2, code: 'GM-002', name: 'Quận 2' } });
    render(<PosReturnsPage />);
    fireEvent.change(screen.getByLabelText('Mã đơn hoặc số điện thoại'), {
      target: { value: order.code },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Tìm đơn' }));
    fireEvent.click(await screen.findByRole('button', { name: new RegExp(order.code) }));
    await screen.findByText('Đơn ' + order.code);
    expect(screen.getByText('Xác nhận khi gửi')).toBeTruthy();
    expect((screen.getByLabelText('Số lượng trả Sữa') as HTMLInputElement).max).toBe('2');
  });
  it('shows reason validation beside the field without sending a request', async () => {
    render(<PosReturnsPage />);
    fireEvent.change(screen.getByLabelText('Mã đơn hoặc số điện thoại'), {
      target: { value: order.code },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Tìm đơn' }));
    fireEvent.click(await screen.findByRole('button', { name: new RegExp(order.code) }));
    await screen.findByText('Đơn ' + order.code);
    fireEvent.change(screen.getByLabelText('Số lượng trả Sữa'), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText('Lý do trả hàng'), { target: { value: 'x' } });
    fireEvent.click(screen.getByRole('button', { name: 'Tạo phiếu trả hàng' }));
    expect(screen.getByText('Lý do trả hàng phải dài từ 3 đến 255 ký tự.')).toBeTruthy();
    expect(createReturn).not.toHaveBeenCalled();
  });

  it('shows Vietnamese order and return statuses', async () => {
    listReturnsByOrder.mockResolvedValue([
      document,
      { ...document, id: 21, code: 'RET-APPROVED', status: 'APPROVED' },
      { ...document, id: 22, code: 'RET-CANCELLED', status: 'CANCELLED' },
    ]);
    await openReturnDocument();

    expect(screen.getByRole('button', { name: /Hoàn tất/ })).toBeTruthy();
    expect(screen.getByText(/Trạng thái: Hoàn tất/)).toBeTruthy();
    expect(screen.getByText(/· Nháp ·/)).toBeTruthy();
    expect(screen.getByText(/· Đã duyệt ·/)).toBeTruthy();
    expect(screen.getByText(/· Đã hủy ·/)).toBeTruthy();
    expect(screen.queryByText(/· (COMPLETED|DRAFT|APPROVED|CANCELLED) ·/)).toBeNull();
  });

  it('lets a manager cancel their own draft without allowing self-approval', async () => {
    role.current = 'BRANCH_MANAGER';
    listReturnsByOrder.mockResolvedValue([document]);
    await openReturnDocument();

    expect(screen.queryByRole('button', { name: 'Duyệt' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Hủy phiếu' }));
    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận' }));

    await waitFor(() => expect(cancelReturn).toHaveBeenCalledWith(document.id));
    expect(approveReturn).not.toHaveBeenCalled();
    await screen.findByText(/· Đã hủy ·/);
    expect(screen.queryByRole('button', { name: 'Hủy phiếu' })).toBeNull();
  });

  it('lets a manager approve or cancel another creator’s draft', async () => {
    role.current = 'OWNER';
    listReturnsByOrder.mockResolvedValue([
      { ...document, createdBy: { id: 8, fullName: 'Thu ngân khác' } },
    ]);
    await openReturnDocument();

    expect(screen.getByRole('button', { name: 'Duyệt' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Hủy phiếu' })).toBeTruthy();
  });

  it('does not offer approval or cancellation to a cashier', async () => {
    listReturnsByOrder.mockResolvedValue([document]);
    await openReturnDocument();

    expect(screen.queryByRole('button', { name: 'Duyệt' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Hủy phiếu' })).toBeNull();
  });
});
