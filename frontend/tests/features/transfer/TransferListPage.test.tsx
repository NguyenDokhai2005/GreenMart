import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../../src/api/client';
import TransferListPage from '../../../src/features/transfers/TransferListPage';
import { b1, b2, makeSummary, makeUser } from '../../../src/features/transfers/transferTestData';

const { api, userRef, scopeRef } = vi.hoisted(() => ({
  api: { listTransfers: vi.fn() },
  userRef: { current: null as unknown },
  scopeRef: { current: {} as Record<string, unknown> },
}));
vi.mock('../../../src/features/transfers/transferApi', () => api);
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
const allScope = {
  ...branchScope,
  scope: { kind: 'all' },
  branchId: undefined,
  canPickAll: true,
  locked: false,
};

const page = (content: ReturnType<typeof makeSummary>[], totalPages = 1) => ({
  page: 0,
  size: 20,
  totalElements: content.length,
  totalPages,
  content,
});

const ROWS = [
  makeSummary({ id: 4, code: 'CK-GM001-20261107-004', status: 'DRAFT' }),
  makeSummary({
    id: 2,
    code: 'CK-GM002-20261106-002',
    status: 'IN_TRANSIT',
    branch: b2,
    toBranch: b1,
  }),
  makeSummary({ id: 3, code: 'CK-GM001-20261105-003', status: 'RECEIVED', hasDiscrepancy: true }),
];

const renderList = (url = '/operations/transfers') =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <TransferListPage />
    </MemoryRouter>,
  );
const lastQuery = () => api.listTransfers.mock.calls.at(-1)?.[0];

beforeEach(() => {
  api.listTransfers.mockReset();
  api.listTransfers.mockResolvedValue(page(ROWS));
  userRef.current = makeUser('BRANCH_MANAGER', 8, [b1]);
  scopeRef.current = branchScope;
});
afterEach(cleanup);

describe('TransferListPage', () => {
  it('hiện danh sách, cột, huy hiệu trạng thái và cờ chênh lệch', async () => {
    renderList();
    expect(await screen.findByText('CK-GM001-20261107-004')).toBeTruthy();
    for (const col of ['Mã phiếu', 'Kho xuất', 'Kho nhận', 'Ngày tạo', 'Trạng thái', 'Thao tác']) {
      expect(screen.getByRole('columnheader', { name: col })).toBeTruthy();
    }
    expect(screen.getAllByText('07/11/2026 14:32').length).toBe(3);
    expect(document.querySelector('.ck-badge--draft')?.textContent).toBe('Nháp');
    expect(document.querySelector('.ck-badge--transit')?.textContent).toBe('Đang vận chuyển');
    expect(document.querySelector('.ck-badge--received')?.textContent).toBe('Đã nhận');
    expect(screen.getByText('Có chênh lệch')).toBeTruthy();
    expect(lastQuery()).toMatchObject({ branchId: 1, page: 0 });
  });

  it('nút thao tác theo quyền: QL chi nhánh gửi thấy Duyệt xuất, kho nhận thấy Nhận hàng', async () => {
    renderList();
    await screen.findByText('CK-GM001-20261107-004');
    expect(screen.getAllByRole('link', { name: 'Duyệt xuất' })).toHaveLength(1);
    expect(screen.getAllByRole('link', { name: 'Nhận hàng' })).toHaveLength(1);
    expect(
      screen
        .getByRole('link', { name: 'Xem chi tiết phiếu CK-GM001-20261107-004' })
        .getAttribute('href'),
    ).toBe('/operations/transfers/4');
  });

  it('thủ kho không có nút Duyệt xuất', async () => {
    userRef.current = makeUser('STOREKEEPER', 9, [b1]);
    renderList();
    await screen.findByText('CK-GM001-20261107-004');
    expect(screen.queryByRole('link', { name: 'Duyệt xuất' })).toBeNull();
    expect(screen.getByRole('link', { name: 'Nhận hàng' })).toBeTruthy();
  });

  it('nút Tạo phiếu chuyển chỉ có khi lập được phiếu (chi nhánh cụ thể, vai trò kho)', async () => {
    renderList();
    await screen.findByText('CK-GM001-20261107-004');
    expect(screen.getByRole('link', { name: '+ Tạo phiếu chuyển' }).getAttribute('href')).toBe(
      '/operations/transfers/new',
    );
    cleanup();

    userRef.current = makeUser('OWNER', 1, [b1, b2]);
    scopeRef.current = allScope;
    renderList();
    await screen.findByText('CK-GM001-20261107-004');
    expect(screen.queryByRole('link', { name: '+ Tạo phiếu chuyển' })).toBeNull();
    expect(lastQuery().branchId).toBeUndefined();
  });

  it('lọc theo trạng thái và khoảng ngày gửi lên API, về trang 1', async () => {
    renderList();
    await screen.findByText('CK-GM001-20261107-004');

    fireEvent.change(screen.getByLabelText('Trạng thái'), { target: { value: 'IN_TRANSIT' } });
    await waitFor(() => expect(lastQuery()).toMatchObject({ status: 'IN_TRANSIT', page: 0 }));

    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-11-01' } });
    await waitFor(() =>
      expect(lastQuery()).toMatchObject({ status: 'IN_TRANSIT', from: '2026-11-01' }),
    );
  });

  it('tìm kiếm chờ 300 ms rồi gửi q', async () => {
    renderList();
    await screen.findByText('CK-GM001-20261107-004');
    fireEvent.change(screen.getByLabelText('Tìm phiếu chuyển kho'), {
      target: { value: 'CK-GM001' },
    });
    await waitFor(() => expect(lastQuery()).toMatchObject({ q: 'CK-GM001', page: 0 }), {
      timeout: 2000,
    });
  });

  it('giữ danh sách hiện tại trong lúc tải kết quả tìm kiếm mới', async () => {
    let resolveSearch: ((result: ReturnType<typeof page>) => void) | undefined;
    api.listTransfers.mockImplementation((query: { q?: string }) => {
      if (query.q) {
        return new Promise((resolve) => {
          resolveSearch = resolve;
        });
      }
      return Promise.resolve(page(ROWS));
    });

    renderList();
    await screen.findByText('CK-GM001-20261107-004');
    fireEvent.change(screen.getByLabelText('Tìm phiếu chuyển kho'), {
      target: { value: 'CK-GM002' },
    });
    await waitFor(() => expect(lastQuery()).toMatchObject({ q: 'CK-GM002', page: 0 }), {
      timeout: 2000,
    });
    await new Promise((resolve) => setTimeout(resolve, 350));

    expect(screen.getByText('CK-GM001-20261107-004')).toBeTruthy();
    expect(screen.queryByRole('status', { name: 'Đang tải' })).toBeNull();

    resolveSearch?.(page([ROWS[1]]));
    expect(await screen.findByText('CK-GM002-20261106-002')).toBeTruthy();
  });

  it('rỗng (chưa có phiếu): mời tạo phiếu đầu tiên khi có quyền', async () => {
    api.listTransfers.mockResolvedValue(page([], 0));
    renderList();
    expect(await screen.findByText('Chưa có phiếu chuyển kho')).toBeTruthy();
    expect(screen.getByRole('button', { name: '+ Tạo phiếu chuyển' })).toBeTruthy();
  });

  it('rỗng do bộ lọc: có Xóa bộ lọc, bấm vào bỏ bộ lọc', async () => {
    api.listTransfers.mockResolvedValue(page([], 0));
    renderList('/operations/transfers?status=DRAFT');
    expect(await screen.findByText('Không có kết quả phù hợp')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }));
    await waitFor(() => expect(lastQuery().status).toBeUndefined());
  });

  it('lỗi API: thông báo trong khối kèm Thử lại', async () => {
    api.listTransfers.mockRejectedValueOnce(new ApiError('Máy chủ đang bận', 500));
    renderList();
    expect(await screen.findByText('Máy chủ đang bận')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByText('CK-GM001-20261107-004')).toBeTruthy();
    expect(screen.getByLabelText('Tìm phiếu chuyển kho')).toBeTruthy(); // bộ lọc vẫn dùng được
  });

  it('phân trang: Trang 2 gọi API với page = 1', async () => {
    api.listTransfers.mockResolvedValue(page(ROWS, 3));
    renderList();
    await screen.findByText('CK-GM001-20261107-004');
    fireEvent.click(screen.getByRole('button', { name: 'Trang 2' }));
    await waitFor(() => expect(lastQuery()).toMatchObject({ page: 1, size: 20 }));
  });
});
