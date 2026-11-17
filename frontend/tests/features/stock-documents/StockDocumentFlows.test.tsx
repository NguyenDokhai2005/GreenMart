import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CurrentUser } from '../../../src/api/client';
import type { components } from '../../../src/api/schema';
import StockDocumentDetailDrawer from '../../../src/features/stock-documents/StockDocumentDetailDrawer';
import StockDocumentFormDialog from '../../../src/features/stock-documents/StockDocumentFormDialog';
import StockDocumentsPage from '../../../src/features/stock-documents/StockDocumentsPage';
import type { StockDocument } from '../../../src/features/stock-documents/stockDocumentApi';

const mocks = vi.hoisted(() => ({
  createStockDocument: vi.fn(),
  updateStockDocument: vi.fn(),
  getStockDocument: vi.fn(),
  approveStockDocument: vi.fn(),
  cancelStockDocument: vi.fn(),
  searchProducts: vi.fn(),
  listInventory: vi.fn(),
  listStockDocuments: vi.fn(),
  userRef: { current: null as unknown },
}));

vi.mock('../../../src/features/stock-documents/stockDocumentApi', () => ({
  createStockDocument: mocks.createStockDocument,
  updateStockDocument: mocks.updateStockDocument,
  listStockDocuments: mocks.listStockDocuments,
  getStockDocument: mocks.getStockDocument,
  approveStockDocument: mocks.approveStockDocument,
  cancelStockDocument: mocks.cancelStockDocument,
}));

vi.mock('../../../src/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../src/api/client')>();
  return { ...actual, searchProducts: mocks.searchProducts };
});

vi.mock('../../../src/features/inventory/inventoryApi', () => ({
  listInventory: mocks.listInventory,
}));

vi.mock('../../../src/features/auth/useAuth', () => ({
  useAuth: () => ({
    state: { status: 'authenticated', user: mocks.userRef.current },
  }),
}));

vi.mock('../../../src/layouts/operations/useOperationsScope', () => ({
  useOperationsScope: () => ({ branchId: 1 }),
}));

const branch = { id: 1, code: 'GM-001', name: 'GreenMart Quận 1' };
const draftDocument: StockDocument = {
  id: 8,
  code: 'NK-GM001-20261108-001',
  type: 'IMPORT',
  branch,
  status: 'DRAFT',
  itemCount: 1,
  createdBy: { id: 7, fullName: 'Nguyễn Thủ Kho' },
  createdAt: '2026-11-08T09:00:00+07:00',
  supplier: 'Công ty Sữa',
  totalCost: 2000,
  items: [
    {
      id: 80,
      productId: 23,
      sku: 'SUA-001',
      productName: 'Sữa tươi 1L',
      unit: 'hộp',
      quantity: 2,
      unitCost: 1000,
    },
  ],
};

const manager = (id: number): CurrentUser => ({
  id,
  username: `manager${id}`,
  fullName: 'Quản lý chi nhánh',
  role: 'BRANCH_MANAGER',
  branches: [branch],
});

function DetailOutlet({ user }: { user: CurrentUser }) {
  return <Outlet context={{ user, onChanged: vi.fn(), onEdit: vi.fn() }} />;
}

function renderDetail(user: CurrentUser) {
  return render(
    <MemoryRouter initialEntries={['/operations/stock-documents/8']}>
      <Routes>
        <Route path="/operations/stock-documents" element={<DetailOutlet user={user} />}>
          <Route path=":id" element={<StockDocumentDetailDrawer />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

function renderList(initialEntry = '/operations/stock-documents') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/operations/stock-documents" element={<StockDocumentsPage />}>
          <Route path=":id" element={<StockDocumentDetailDrawer />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  for (const mock of [
    mocks.createStockDocument,
    mocks.updateStockDocument,
    mocks.getStockDocument,
    mocks.approveStockDocument,
    mocks.cancelStockDocument,
    mocks.searchProducts,
    mocks.listInventory,
    mocks.listStockDocuments,
  ]) {
    mock.mockReset();
  }
  mocks.userRef.current = {
    ...manager(7),
    role: 'STOREKEEPER',
  };
  mocks.listStockDocuments.mockResolvedValue({
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    content: [],
  });
  mocks.getStockDocument.mockResolvedValue(draftDocument);
  mocks.searchProducts.mockResolvedValue({
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
    content: [
      {
        id: 23,
        sku: 'SUA-001',
        barcode: null,
        name: 'Sữa tươi 1L',
        unit: 'hộp',
        categoryId: 3,
        categoryName: 'Sữa',
        salePrice: 2000,
        costPrice: null,
        active: true,
      } satisfies components['schemas']['Product'],
    ],
  });
  mocks.listInventory.mockResolvedValue({
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
    content: [
      {
        branch,
        productId: 23,
        sku: 'SUA-001',
        productName: 'Sữa tươi 1L',
        unit: 'hộp',
        onHand: 12,
        reserved: 2,
        available: 10,
        reorderPoint: 3,
        stockStatus: 'OK',
      },
    ],
  });
});

afterEach(cleanup);

describe('Phiếu kho', () => {
  it('mặc định hiển thị riêng phiếu nhập và chỉ chuyển giữa nhập với xuất', async () => {
    renderList();

    expect(await screen.findByText('Chưa có phiếu nhập')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Phiếu nhập kho' })).toBeTruthy();
    expect(mocks.listStockDocuments).toHaveBeenLastCalledWith(
      expect.objectContaining({ branchId: 1, type: 'IMPORT', page: 0, size: 20 }),
      expect.any(AbortSignal),
    );

    expect(screen.getByRole('button', { name: 'Phiếu nhập' }).getAttribute('aria-pressed')).toBe(
      'true',
    );
    const statusOptions = Array.from(
      (screen.getByLabelText('Trạng thái') as HTMLSelectElement).options,
      (option) => option.textContent,
    );
    expect(statusOptions).not.toContain('Đang vận chuyển');
    expect(statusOptions).not.toContain('Đã nhận');
    expect(screen.queryByRole('button', { name: 'Phiếu chuyển kho' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Phiếu điều chỉnh' })).toBeNull();
    expect(screen.queryByRole('button', { name: '+ Tạo phiếu xuất' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Phiếu xuất' }));
    expect(await screen.findByText('Chưa có phiếu xuất')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Phiếu xuất kho' })).toBeTruthy();
    expect(mocks.listStockDocuments).toHaveBeenLastCalledWith(
      expect.objectContaining({ branchId: 1, type: 'EXPORT' }),
      expect.any(AbortSignal),
    );
    expect(screen.getAllByRole('button', { name: '+ Tạo phiếu xuất' })).toHaveLength(2);
    expect(screen.queryByRole('button', { name: '+ Tạo phiếu nhập' })).toBeNull();
  });

  it('xóa bộ lọc nhưng giữ nguyên màn phiếu xuất', async () => {
    renderList('/operations/stock-documents?type=EXPORT&q=missing');

    expect(await screen.findByText('Không có kết quả phù hợp')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }));
    await waitFor(() =>
      expect(mocks.listStockDocuments).toHaveBeenLastCalledWith(
        expect.objectContaining({ branchId: 1, type: 'EXPORT', q: undefined }),
        expect.any(AbortSignal),
      ),
    );
    expect(screen.getByRole('button', { name: 'Phiếu xuất' }).getAttribute('aria-pressed')).toBe(
      'true',
    );
    expect(screen.getByRole('heading', { name: 'Phiếu xuất kho' })).toBeTruthy();
  });

  it('không hiển thị nút duyệt cho QL chi nhánh là người lập phiếu', async () => {
    renderDetail(manager(7));

    expect(await screen.findByText(draftDocument.code)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Duyệt phiếu' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Sửa phiếu' })).toBeTruthy();
    expect(screen.getByText(/Bạn là người lập phiếu nên không thể tự duyệt/)).toBeTruthy();
  });

  it('mở biểu mẫu sửa từ chi tiết phiếu nháp', async () => {
    renderList('/operations/stock-documents/8');

    fireEvent.click(await screen.findByRole('button', { name: 'Sửa phiếu' }));
    expect(await screen.findByRole('dialog', { name: 'Sửa phiếu nhập kho' })).toBeTruthy();
    expect((screen.getByLabelText(/Nhà cung cấp/) as HTMLInputElement).value).toBe('Công ty Sữa');
  });

  it('cho QL chi nhánh khác người lập thấy thao tác duyệt', async () => {
    renderDetail(manager(9));

    expect(await screen.findByRole('button', { name: 'Duyệt phiếu' })).toBeTruthy();
  });

  it('gửi yêu cầu duyệt và cập nhật trạng thái sau khi API xác nhận', async () => {
    const approved: StockDocument = {
      ...draftDocument,
      status: 'APPROVED',
      approvedBy: { id: 9, fullName: 'Quản lý chi nhánh' },
      approvedAt: '2026-11-08T10:00:00+07:00',
    };
    mocks.getStockDocument.mockResolvedValueOnce(draftDocument).mockResolvedValueOnce(approved);
    mocks.approveStockDocument.mockResolvedValue(approved);
    renderDetail(manager(9));

    fireEvent.click(await screen.findByRole('button', { name: 'Duyệt phiếu' }));
    const confirmation = await screen.findByRole('dialog', { name: 'Duyệt nhập kho?' });
    fireEvent.click(within(confirmation).getByRole('button', { name: 'Duyệt phiếu' }));

    await waitFor(() => expect(mocks.approveStockDocument).toHaveBeenCalledWith(8));
    expect(await screen.findByText('Đã duyệt')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Duyệt phiếu' })).toBeNull();
  });

  it('lưu phiếu nhập thành DRAFT với nhà cung cấp, số lượng và giá nhập', async () => {
    const onSaved = vi.fn();
    mocks.createStockDocument.mockResolvedValue(draftDocument);
    render(
      <StockDocumentFormDialog type="IMPORT" branchId={1} onClose={vi.fn()} onSaved={onSaved} />,
    );

    fireEvent.click(await screen.findByRole('button', { name: /Sữa tươi 1L/ }));
    fireEvent.change(screen.getByLabelText(/Nhà cung cấp/), {
      target: { value: 'Công ty Sữa' },
    });
    fireEvent.change(screen.getByLabelText('Số lượng Sữa tươi 1L'), {
      target: { value: '2' },
    });
    fireEvent.change(screen.getByLabelText('Giá nhập Sữa tươi 1L'), {
      target: { value: '1000' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu phiếu nháp' }));

    await waitFor(() =>
      expect(mocks.createStockDocument).toHaveBeenCalledWith(
        {
          type: 'IMPORT',
          branchId: 1,
          supplier: 'Công ty Sữa',
          note: undefined,
          items: [{ productId: 23, quantity: 2, unitCost: 1000 }],
        },
        expect.any(AbortSignal),
      ),
    );
    expect(onSaved).toHaveBeenCalledWith(draftDocument);
  });

  it('sửa phiếu nháp bằng PUT với dữ liệu ban đầu', async () => {
    const updated = { ...draftDocument, supplier: 'Nhà cung cấp mới' };
    const onSaved = vi.fn();
    mocks.updateStockDocument.mockResolvedValue(updated);
    render(
      <StockDocumentFormDialog
        type="IMPORT"
        branchId={1}
        document={draftDocument}
        onClose={vi.fn()}
        onSaved={onSaved}
      />,
    );

    expect(await screen.findByRole('dialog', { name: 'Sửa phiếu nhập kho' })).toBeTruthy();
    expect((screen.getByLabelText(/Nhà cung cấp/) as HTMLInputElement).value).toBe('Công ty Sữa');
    expect((screen.getByLabelText('Số lượng Sữa tươi 1L') as HTMLInputElement).value).toBe('2');
    fireEvent.change(screen.getByLabelText(/Nhà cung cấp/), {
      target: { value: 'Nhà cung cấp mới' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu thay đổi' }));

    await waitFor(() =>
      expect(mocks.updateStockDocument).toHaveBeenCalledWith(
        8,
        {
          supplier: 'Nhà cung cấp mới',
          note: undefined,
          items: [{ productId: 23, quantity: 2, unitCost: 1000 }],
        },
        expect.any(AbortSignal),
      ),
    );
    expect(mocks.createStockDocument).not.toHaveBeenCalled();
    expect(onSaved).toHaveBeenCalledWith(updated);
  });

  it('giữ dữ liệu khi sửa phiếu nháp gặp 409 INVALID_STATUS', async () => {
    mocks.updateStockDocument.mockRejectedValue(
      Object.assign(new Error('Phiếu đã được duyệt, không thể chỉnh sửa.'), {
        status: 409,
        code: 'INVALID_STATUS',
      }),
    );
    render(
      <StockDocumentFormDialog
        type="IMPORT"
        branchId={1}
        document={draftDocument}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByLabelText(/Nhà cung cấp/), {
      target: { value: 'Nhà cung cấp đã sửa' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu thay đổi' }));

    expect(await screen.findByText('Phiếu đã được duyệt, không thể chỉnh sửa.')).toBeTruthy();
    expect((screen.getByLabelText(/Nhà cung cấp/) as HTMLInputElement).value).toBe(
      'Nhà cung cấp đã sửa',
    );
  });

  it('giữ dữ liệu khi API trả lỗi 409', async () => {
    mocks.createStockDocument.mockRejectedValue(
      Object.assign(new Error('Tồn kho đã thay đổi'), { status: 409, code: 'CONFLICT' }),
    );
    render(
      <StockDocumentFormDialog type="IMPORT" branchId={1} onClose={vi.fn()} onSaved={vi.fn()} />,
    );
    fireEvent.click(await screen.findByRole('button', { name: /Sữa tươi 1L/ }));
    fireEvent.change(screen.getByLabelText(/Nhà cung cấp/), {
      target: { value: 'Công ty Sữa' },
    });
    fireEvent.change(screen.getByLabelText('Số lượng Sữa tươi 1L'), {
      target: { value: '2' },
    });
    fireEvent.change(screen.getByLabelText('Giá nhập Sữa tươi 1L'), {
      target: { value: '1000' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu phiếu nháp' }));

    expect(await screen.findByText('Tồn kho đã thay đổi')).toBeTruthy();
    expect((screen.getByLabelText(/Nhà cung cấp/) as HTMLInputElement).value).toBe('Công ty Sữa');
    expect((screen.getByLabelText('Số lượng Sữa tươi 1L') as HTMLInputElement).value).toBe('2');
  });

  it('báo lỗi 400 dưới trường nhà cung cấp mà không xóa dữ liệu', async () => {
    mocks.createStockDocument.mockRejectedValue(
      Object.assign(new Error('Dữ liệu không hợp lệ'), {
        status: 400,
        code: 'VALIDATION_ERROR',
        details: [{ field: 'supplier', message: 'Tên nhà cung cấp không hợp lệ.' }],
      }),
    );
    render(
      <StockDocumentFormDialog type="IMPORT" branchId={1} onClose={vi.fn()} onSaved={vi.fn()} />,
    );
    fireEvent.click(await screen.findByRole('button', { name: /Sữa tươi 1L/ }));
    fireEvent.change(screen.getByLabelText(/Nhà cung cấp/), {
      target: { value: 'Công ty Sữa' },
    });
    fireEvent.change(screen.getByLabelText('Giá nhập Sữa tươi 1L'), {
      target: { value: '1000' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu phiếu nháp' }));

    expect(await screen.findByText('Tên nhà cung cấp không hợp lệ.')).toBeTruthy();
    expect((screen.getByLabelText(/Nhà cung cấp/) as HTMLInputElement).value).toBe('Công ty Sữa');
  });

  it('yêu cầu giá nhập rõ ràng, chấp nhận giá 0 khi được nhập', async () => {
    mocks.createStockDocument.mockResolvedValue(draftDocument);
    render(
      <StockDocumentFormDialog type="IMPORT" branchId={1} onClose={vi.fn()} onSaved={vi.fn()} />,
    );
    fireEvent.click(await screen.findByRole('button', { name: /Sữa tươi 1L/ }));
    fireEvent.change(screen.getByLabelText(/Nhà cung cấp/), {
      target: { value: 'Công ty Sữa' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu phiếu nháp' }));

    expect(await screen.findByText('Giá nhập phải là số nguyên không âm.')).toBeTruthy();
    expect(mocks.createStockDocument).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('Giá nhập Sữa tươi 1L'), {
      target: { value: '0' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu phiếu nháp' }));
    await waitFor(() => expect(mocks.createStockDocument).toHaveBeenCalled());
  });

  it('không cho xuất vượt khả dụng và gửi đúng lý do khi hợp lệ', async () => {
    const exportDraft: StockDocument = {
      ...draftDocument,
      type: 'EXPORT',
      supplier: null,
      exportReason: 'DAMAGED',
    };
    mocks.createStockDocument.mockResolvedValue(exportDraft);
    render(
      <StockDocumentFormDialog type="EXPORT" branchId={1} onClose={vi.fn()} onSaved={vi.fn()} />,
    );

    const reasonOptions = Array.from(
      (screen.getByLabelText(/Lý do xuất hủy/) as HTMLSelectElement).options,
      (option) => option.textContent,
    );
    expect(reasonOptions).toContain('Mất');
    expect(reasonOptions).toContain('Khác');

    fireEvent.click(await screen.findByRole('button', { name: /Sữa tươi 1L/ }));
    fireEvent.change(screen.getByLabelText(/Lý do xuất hủy/), {
      target: { value: 'DAMAGED' },
    });
    fireEvent.change(screen.getByLabelText('Số lượng Sữa tươi 1L'), {
      target: { value: '11' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu phiếu nháp' }));

    expect(await screen.findByText('Không vượt quá 10 sản phẩm khả dụng.')).toBeTruthy();
    expect(mocks.createStockDocument).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('Số lượng Sữa tươi 1L'), {
      target: { value: '10' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu phiếu nháp' }));

    await waitFor(() =>
      expect(mocks.createStockDocument).toHaveBeenCalledWith(
        {
          type: 'EXPORT',
          branchId: 1,
          exportReason: 'DAMAGED',
          note: undefined,
          items: [{ productId: 23, quantity: 10 }],
        },
        expect.any(AbortSignal),
      ),
    );
  });
});
