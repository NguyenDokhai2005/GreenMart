import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../../src/api/client';
import {
  TransferCreatePage,
  TransferEditPage,
} from '../../../src/features/transfers/TransferFormPage';
import { b1, b2, b3, makeDoc, makeUser } from '../../../src/features/transfers/transferTestData';

const { api, inventory, userRef, scopeRef } = vi.hoisted(() => ({
  api: {
    createTransfer: vi.fn(),
    updateTransfer: vi.fn(),
    getTransfer: vi.fn(),
    getStockItem: vi.fn(),
    listActiveBranches: vi.fn(),
  },
  inventory: { listInventory: vi.fn() },
  userRef: { current: null as unknown },
  scopeRef: { current: {} as Record<string, unknown> },
}));
vi.mock('../../../src/features/transfers/transferApi', () => api);
vi.mock('../../../src/features/inventory/inventoryApi', () => inventory);
vi.mock('../../../src/features/auth/useAuth', () => ({
  useAuth: () => ({ state: { status: 'authenticated', user: userRef.current } }),
}));
vi.mock('../../../src/layouts/operations/useOperationsScope', () => ({
  useOperationsScope: () => scopeRef.current,
}));

const branchScope = {
  scope: { kind: 'branch', branch: b1 },
  branchId: 1,
  branches: [b1],
  canPickAll: false,
  locked: true,
  pickAll: () => undefined,
  pickBranch: () => undefined,
};

const STOCK = {
  page: 0,
  size: 10,
  totalElements: 2,
  totalPages: 1,
  content: [
    {
      branch: b1,
      productId: 101,
      sku: 'SP001',
      productName: 'Nước suối Lavie 500ml',
      unit: 'Thùng',
      onHand: 120,
      reserved: 0,
      available: 120,
      stockStatus: 'OK',
      version: 1,
      updatedAt: '',
    },
    {
      branch: b1,
      productId: 102,
      sku: 'SP023',
      productName: 'Bánh snack Oishi',
      unit: 'Thùng',
      onHand: 40,
      reserved: 0,
      available: 40,
      stockStatus: 'OK',
      version: 1,
      updatedAt: '',
    },
  ],
};

const routes = (
  <Routes>
    <Route path="/operations/transfers/new" element={<TransferCreatePage />} />
    <Route path="/operations/transfers/:id/edit" element={<TransferEditPage />} />
    <Route path="/operations/transfers/:id" element={<div>MÀN CHI TIẾT</div>} />
    <Route path="/operations/transfers" element={<div>MÀN DANH SÁCH</div>} />
  </Routes>
);
const renderAt = (url: string) =>
  render(<MemoryRouter initialEntries={[url]}>{routes}</MemoryRouter>);

/** Điền form hợp lệ: thêm sản phẩm, chọn kho nhận. */
async function fillValidForm() {
  await screen.findByRole('option', { name: /GM-002/ });
  fireEvent.click(screen.getByRole('button', { name: '+ Thêm sản phẩm' }));
  fireEvent.click(await screen.findByRole('button', { name: /Nước suối Lavie 500ml/ }));
  fireEvent.change(screen.getByLabelText('Số lượng chuyển Nước suối Lavie 500ml'), {
    target: { value: '50' },
  });
  fireEvent.change(screen.getByLabelText(/Kho nhận/), { target: { value: '2' } });
}

beforeEach(() => {
  for (const fn of [...Object.values(api), ...Object.values(inventory)]) fn.mockReset();
  api.listActiveBranches.mockResolvedValue([b1, b2, b3]);
  api.getStockItem.mockResolvedValue({ available: 120 });
  inventory.listInventory.mockResolvedValue(STOCK);
  userRef.current = makeUser('STOREKEEPER', 7, [b1]);
  scopeRef.current = branchScope;
});
afterEach(cleanup);

describe('Lập phiếu chuyển kho (bước 1)', () => {
  it('kho gửi tự động, kho nhận không gồm chính kho gửi, danh sách sản phẩm ban đầu rỗng', async () => {
    renderAt('/operations/transfers/new');
    expect(await screen.findByText('Bước 1 · Lập phiếu (Nháp)')).toBeTruthy();
    expect((screen.getByLabelText('Kho gửi') as HTMLInputElement).value).toBe('GM-001 (tự động)');
    const options = (await screen.findAllByRole('option')).map((o) => o.textContent);
    expect(options).toContain('GM-002 GreenMart Bình Thạnh');
    expect(options.some((t) => t?.includes('GM-001'))).toBe(false);
    expect(screen.getByText('Chưa có sản phẩm chuyển')).toBeTruthy();
  });

  it('lập phiếu hợp lệ: gọi API đúng nội dung rồi chuyển sang chi tiết phiếu', async () => {
    api.createTransfer.mockResolvedValue({ id: 99 });
    renderAt('/operations/transfers/new');
    await fillValidForm();
    fireEvent.change(screen.getByLabelText('Lý do chuyển kho'), {
      target: { value: 'Bổ sung hàng cho kho nhận' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Tạo phiếu' }));

    await waitFor(() =>
      expect(api.createTransfer).toHaveBeenCalledWith({
        branchId: 1,
        toBranchId: 2,
        note: 'Bổ sung hàng cho kho nhận',
        items: [{ productId: 101, quantity: 50 }],
      }),
    );
    expect(await screen.findByText('MÀN CHI TIẾT')).toBeTruthy();
  });

  it('chưa chọn kho nhận, chưa có sản phẩm: báo lỗi tại ô, không gọi API', async () => {
    renderAt('/operations/transfers/new');
    await screen.findByRole('option', { name: /GM-002/ });
    fireEvent.click(screen.getByRole('button', { name: 'Tạo phiếu' }));
    // "Chọn chi nhánh đích" cũng là chữ của tùy chọn mặc định nên đọc đúng ô báo lỗi
    await waitFor(() =>
      expect(document.getElementById('ck-to-branch-error')?.textContent).toBe(
        'Chọn chi nhánh đích',
      ),
    );
    expect(screen.getByText('Thêm ít nhất một sản phẩm cần chuyển')).toBeTruthy();
    expect(api.createTransfer).not.toHaveBeenCalled();
  });

  it('số lượng vượt tồn khả dụng: báo dưới ô, không gọi API', async () => {
    renderAt('/operations/transfers/new');
    await fillValidForm();
    fireEvent.change(screen.getByLabelText('Số lượng chuyển Nước suối Lavie 500ml'), {
      target: { value: '500' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Tạo phiếu' }));
    expect(await screen.findByText('Vượt tồn khả dụng (120)')).toBeTruthy();
    expect(api.createTransfer).not.toHaveBeenCalled();
  });

  it('không thêm trùng sản phẩm đã có trong phiếu', async () => {
    renderAt('/operations/transfers/new');
    await fillValidForm();
    fireEvent.click(screen.getByRole('button', { name: '+ Thêm sản phẩm' }));
    await screen.findByRole('button', { name: /Bánh snack Oishi/ });
    expect(screen.queryByRole('button', { name: /Nước suối Lavie 500ml.*Khả dụng/ })).toBeNull();
  });

  it('xóa sản phẩm khỏi phiếu', async () => {
    renderAt('/operations/transfers/new');
    await fillValidForm();
    fireEvent.click(screen.getByRole('button', { name: 'Xóa Nước suối Lavie 500ml khỏi phiếu' }));
    expect(screen.getByText('Chưa có sản phẩm chuyển')).toBeTruthy();
  });

  it('lỗi 400 từ backend: báo dưới từng ô, dữ liệu đang nhập không mất', async () => {
    api.createTransfer.mockRejectedValue(
      new ApiError('Dữ liệu không hợp lệ', 400, 'VALIDATION_ERROR', undefined, [
        { field: 'items[0].quantity', message: 'phải lớn hơn 0' },
      ]),
    );
    renderAt('/operations/transfers/new');
    await fillValidForm();
    fireEvent.click(screen.getByRole('button', { name: 'Tạo phiếu' }));

    expect(await screen.findByText('phải lớn hơn 0')).toBeTruthy();
    expect(
      (screen.getByLabelText('Số lượng chuyển Nước suối Lavie 500ml') as HTMLInputElement).value,
    ).toBe('50');
    expect((screen.getByLabelText(/Kho nhận/) as HTMLSelectElement).value).toBe('2');
  });

  it('lỗi 409 thiếu tồn: báo bằng Toast, giữ nguyên form', async () => {
    api.createTransfer.mockRejectedValue(
      new ApiError('Không đủ tồn khả dụng', 409, 'INSUFFICIENT_STOCK'),
    );
    renderAt('/operations/transfers/new');
    await fillValidForm();
    fireEvent.click(screen.getByRole('button', { name: 'Tạo phiếu' }));
    expect(await screen.findByText('Không đủ tồn khả dụng')).toBeTruthy();
    expect(screen.getByLabelText('Số lượng chuyển Nước suối Lavie 500ml')).toBeTruthy();
  });

  it('không tải được danh sách chi nhánh: lỗi trong khối kèm Thử lại', async () => {
    api.listActiveBranches.mockRejectedValueOnce(new ApiError('Máy chủ đang bận', 500));
    renderAt('/operations/transfers/new');
    expect(await screen.findByText('Máy chủ đang bận')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByRole('option', { name: /GM-002/ })).toBeTruthy();
  });

  it('xem tất cả chi nhánh: yêu cầu chọn một chi nhánh trước', async () => {
    scopeRef.current = {
      ...branchScope,
      scope: { kind: 'all' },
      branchId: undefined,
      canPickAll: true,
      locked: false,
    };
    userRef.current = makeUser('OWNER', 1, [b1, b2, b3]);
    renderAt('/operations/transfers/new');
    expect(await screen.findByText('Chọn một chi nhánh để lập phiếu')).toBeTruthy();
  });

  it('vai trò không được lập phiếu', async () => {
    userRef.current = makeUser('CASHIER', 30, [b1]);
    renderAt('/operations/transfers/new');
    expect(await screen.findByText('Bạn không có quyền lập phiếu chuyển kho')).toBeTruthy();
  });
});

describe('Sửa phiếu nháp', () => {
  it('nạp sẵn dữ liệu phiếu và lưu bằng PUT', async () => {
    api.getTransfer.mockResolvedValue(
      makeDoc({
        id: 5,
        code: 'CK-GM001-20261107-004',
        status: 'DRAFT',
        items: [makeDoc({ id: 5, code: 'x', status: 'DRAFT' }).items[0]!],
      }),
    );
    api.updateTransfer.mockResolvedValue({ id: 5 });
    renderAt('/operations/transfers/5/edit');

    expect(await screen.findByText('Sửa phiếu chuyển kho CK-GM001-20261107-004')).toBeTruthy();
    expect(
      ((await screen.findByLabelText('Số lượng chuyển Nước suối Lavie 500ml')) as HTMLInputElement)
        .value,
    ).toBe('50');
    await waitFor(() => expect(api.getStockItem).toHaveBeenCalledWith(1, 101, expect.anything()));

    fireEvent.change(screen.getByLabelText('Số lượng chuyển Nước suối Lavie 500ml'), {
      target: { value: '40' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu phiếu' }));
    await waitFor(() =>
      expect(api.updateTransfer).toHaveBeenCalledWith(5, {
        toBranchId: 2,
        note: 'Bổ sung hàng cho kho nhận',
        items: [{ productId: 101, quantity: 40 }],
      }),
    );
    expect(await screen.findByText('MÀN CHI TIẾT')).toBeTruthy();
  });

  it('phiếu không còn là Nháp: không cho sửa', async () => {
    api.getTransfer.mockResolvedValue(makeDoc({ id: 5, code: 'CK-1', status: 'IN_TRANSIT' }));
    renderAt('/operations/transfers/5/edit');
    expect(await screen.findByText('Không thể sửa phiếu này')).toBeTruthy();
  });
});
