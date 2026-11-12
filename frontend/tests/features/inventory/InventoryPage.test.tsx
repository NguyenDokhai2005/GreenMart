import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { InventoryItem } from '../../../src/features/inventory/inventoryApi';
import InventoryPage from '../../../src/features/inventory/InventoryPage';

const { listInventory, scopeRef } = vi.hoisted(() => ({
  listInventory: vi.fn(),
  scopeRef: { current: {} as Record<string, unknown> },
}));
vi.mock('./inventoryApi', () => ({ listInventory }));
vi.mock('../../layouts/operations/useOperationsScope', () => ({
  useOperationsScope: () => scopeRef.current,
}));

const branch1 = { id: 1, code: 'GM-001', name: 'GreenMart Quận 1' };
const lockedScope = {
  scope: { kind: 'branch', branch: branch1 },
  branchId: 1,
  branches: [branch1],
  canPickAll: false,
  locked: true,
  pickAll: () => undefined,
  pickBranch: () => undefined,
};
const allScope = {
  ...lockedScope,
  scope: { kind: 'all' },
  branchId: undefined,
  canPickAll: true,
  locked: false,
};

function item(
  over: Partial<InventoryItem> & Pick<InventoryItem, 'productId' | 'productName'>,
): InventoryItem {
  return {
    branch: branch1,
    sku: `SKU-${over.productId}`,
    unit: 'Chai',
    onHand: 50,
    reserved: 5,
    available: 45,
    reorderPoint: 15,
    stockStatus: 'OK',
    version: 1,
    updatedAt: '2026-11-05T09:31:12+07:00',
    ...over,
  };
}

const DATA: InventoryItem[] = [
  item({ productId: 1, productName: 'Nước mắm Nam Ngư 500ml' }),
  item({
    productId: 2,
    productName: 'Mì Hảo Hảo Tôm Chua Cay',
    unit: 'Thùng',
    onHand: 12,
    reserved: 2,
    available: 10,
    reorderPoint: 20,
    stockStatus: 'LOW',
  }),
  item({
    productId: 3,
    productName: 'Sữa tươi Vinamilk không đường 1L',
    unit: 'Hộp',
    onHand: 5,
    reserved: 5,
    available: 0,
    reorderPoint: 10,
    stockStatus: 'OUT',
  }),
  item({
    productId: 4,
    productName: 'Dầu ăn Tường An 1L',
    onHand: 0,
    reserved: 0,
    available: 0,
    reorderPoint: 15,
    stockStatus: 'OUT',
  }),
];

/** Mô phỏng API: lọc theo stockStatus và q, phân trang theo page/size. */
function fakeApi(all: InventoryItem[]) {
  return async (query: { q?: string; stockStatus?: string; page: number; size: number }) => {
    const rows = all
      .filter((r) => !query.stockStatus || r.stockStatus === query.stockStatus)
      .filter(
        (r) =>
          !query.q || `${r.productName} ${r.sku}`.toLowerCase().includes(query.q.toLowerCase()),
      );
    return {
      page: query.page,
      size: query.size,
      totalElements: rows.length,
      totalPages: Math.ceil(rows.length / query.size),
      content: rows.slice(query.page * query.size, (query.page + 1) * query.size),
    };
  };
}

const renderPage = (url = '/operations/inventory') =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <InventoryPage />
    </MemoryRouter>,
  );

const bodyRows = () => screen.getAllByRole('row').slice(1);
const lastQuery = () => listInventory.mock.calls.at(-1)?.[0];

beforeEach(() => {
  listInventory.mockReset();
  listInventory.mockImplementation(fakeApi(DATA));
  scopeRef.current = lockedScope;
});
afterEach(cleanup);

describe('InventoryPage', () => {
  it('hiện đủ cột và huy hiệu cảnh báo từng dòng', async () => {
    renderPage();
    expect(await screen.findByText('Nước mắm Nam Ngư 500ml')).toBeTruthy();
    for (const col of [
      'Sản phẩm',
      'SKU',
      'Đơn vị tính',
      'Tồn thực',
      'Giữ chỗ',
      'Khả dụng',
      'ROP',
      'Cảnh báo',
    ]) {
      expect(screen.getByRole('columnheader', { name: col })).toBeTruthy();
    }
    // huy hiệu trong bảng (chữ "Tồn thấp", "Hết hàng" cũng là nút lọc nên đếm theo class)
    const count = (tone: string) =>
      document.querySelectorAll(`.inv-table .inv-badge--${tone}`).length;
    expect([count('ok'), count('low'), count('out')]).toEqual([1, 1, 2]);
    expect(document.querySelector('.inv-badge--ok')?.textContent).toBe('Bình thường');
  });

  it('lọc "Hết hàng": chỉ còn dòng khả dụng 0, huy hiệu đỏ (tiêu chí chấp nhận)', async () => {
    renderPage();
    await screen.findByText('Nước mắm Nam Ngư 500ml');

    fireEvent.click(screen.getByRole('button', { name: 'Hết hàng' }));
    await waitFor(() => expect(bodyRows()).toHaveLength(2));

    expect(lastQuery()).toMatchObject({ stockStatus: 'OUT', page: 0 });
    for (const row of bodyRows()) {
      const cells = within(row).getAllByRole('cell');
      expect(cells[5]?.textContent).toBe('0'); // cột Khả dụng
      expect(cells[7]?.querySelector('.inv-badge--out')?.textContent).toBe('Hết hàng');
    }
    expect(screen.getByRole('button', { name: 'Hết hàng' }).getAttribute('aria-pressed')).toBe(
      'true',
    );
  });

  it('lọc không có kết quả: hiện trạng thái rỗng do bộ lọc, "Xóa bộ lọc" trả lại danh sách', async () => {
    listInventory.mockImplementation(fakeApi(DATA.filter((d) => d.stockStatus !== 'LOW')));
    renderPage();
    await screen.findByText('Nước mắm Nam Ngư 500ml');

    fireEvent.click(screen.getByRole('button', { name: 'Tồn thấp' }));
    expect(await screen.findByText('Không có kết quả phù hợp')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }));
    expect(await screen.findByText('Nước mắm Nam Ngư 500ml')).toBeTruthy();
    expect(lastQuery().stockStatus).toBeUndefined();
  });

  it('rỗng không do bộ lọc: hiện "Chưa có dữ liệu tồn kho", không có nút Xóa bộ lọc', async () => {
    listInventory.mockImplementation(fakeApi([]));
    renderPage();
    expect(await screen.findByText('Chưa có dữ liệu tồn kho')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Xóa bộ lọc' })).toBeNull();
  });

  it('lỗi API: hiện thông báo lỗi trong khối kèm Thử lại, bấm Thử lại tải lại', async () => {
    listInventory.mockRejectedValueOnce(new Error('Máy chủ đang bận'));
    renderPage();
    expect(await screen.findByText('Máy chủ đang bận')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByText('Nước mắm Nam Ngư 500ml')).toBeTruthy();
    expect(listInventory).toHaveBeenCalledTimes(2);
    // trang vẫn dùng được: ô tìm kiếm và bộ lọc không bị mất
    expect(screen.getByRole('button', { name: 'Tất cả' })).toBeTruthy();
  });

  it('tìm kiếm: chờ 300 ms rồi gửi q lên API, về trang 1', async () => {
    renderPage();
    await screen.findByText('Nước mắm Nam Ngư 500ml');
    fireEvent.change(screen.getByPlaceholderText(/Nhập tên sản phẩm/), {
      target: { value: 'sữa' },
    });

    await waitFor(() => expect(lastQuery()).toMatchObject({ q: 'sữa', page: 0 }), {
      timeout: 2000,
    });
    expect(await screen.findByText('Sữa tươi Vinamilk không đường 1L')).toBeTruthy();
    expect(screen.queryByText('Nước mắm Nam Ngư 500ml')).toBeNull();
  });

  it('phân trang: bấm Trang 2 gọi API với page = 1 (số trang API từ 0)', async () => {
    const many = Array.from({ length: 45 }, (_, i) =>
      item({ productId: 100 + i, productName: `Sản phẩm ${100 + i}` }),
    );
    listInventory.mockImplementation(fakeApi(many));
    renderPage();
    await screen.findByText('Sản phẩm 100');

    fireEvent.click(screen.getByRole('button', { name: 'Trang 2' }));
    expect(await screen.findByText('Sản phẩm 120')).toBeTruthy();
    expect(lastQuery()).toMatchObject({ page: 1, size: 20 });
    expect(screen.getByRole('button', { name: 'Trang 2' }).getAttribute('aria-current')).toBe(
      'page',
    );
  });

  it('khôi phục bộ lọc và trang từ URL', async () => {
    const many = Array.from({ length: 45 }, (_, i) =>
      item({ productId: 200 + i, productName: `Hàng ${200 + i}`, stockStatus: 'LOW' }),
    );
    listInventory.mockImplementation(fakeApi(many));
    renderPage('/operations/inventory?status=LOW&page=2');
    await screen.findByText('Hàng 220');
    expect(lastQuery()).toMatchObject({ stockStatus: 'LOW', page: 1 });
    expect(screen.getByRole('button', { name: 'Tồn thấp' }).getAttribute('aria-pressed')).toBe(
      'true',
    );
  });

  it('chi nhánh cố định: gửi branchId, không có cột Chi nhánh', async () => {
    renderPage();
    await screen.findByText('Nước mắm Nam Ngư 500ml');
    expect(lastQuery().branchId).toBe(1);
    expect(screen.queryByRole('columnheader', { name: 'Chi nhánh' })).toBeNull();
  });

  it('"Tất cả chi nhánh": không gửi branchId, hiện cột Chi nhánh', async () => {
    scopeRef.current = allScope;
    renderPage();
    await screen.findByText('Nước mắm Nam Ngư 500ml');
    expect(lastQuery().branchId).toBeUndefined();
    expect(screen.getByRole('columnheader', { name: 'Chi nhánh' })).toBeTruthy();
    expect(screen.getAllByText('GM-001').length).toBeGreaterThan(0);
  });
});
