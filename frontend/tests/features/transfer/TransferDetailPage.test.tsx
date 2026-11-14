import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../../src/api/client';
import TransferDetailPage from '../../../src/features/transfers/TransferDetailPage';
import {
  b1,
  b2,
  makeDoc,
  makeItem,
  makeUser,
} from '../../../src/features/transfers/transferTestData';

const { api, userRef } = vi.hoisted(() => ({
  api: {
    getTransfer: vi.fn(),
    approveTransfer: vi.fn(),
    receiveTransfer: vi.fn(),
    cancelTransfer: vi.fn(),
    getStockItem: vi.fn(),
  },
  userRef: { current: null as unknown },
}));
vi.mock('../../../src/features/transfers/transferApi', () => api);
vi.mock('../../../src/features/auth/useAuth', () => ({
  useAuth: () => ({ state: { status: 'authenticated', user: userRef.current } }),
}));

const renderDetail = () =>
  render(
    <MemoryRouter initialEntries={['/operations/transfers/5']}>
      <Routes>
        <Route path="/operations/transfers/:id" element={<TransferDetailPage />} />
        <Route path="/operations/transfers/:id/edit" element={<div>MÀN SỬA PHIẾU</div>} />
        <Route path="/operations/transfers" element={<div>MÀN DANH SÁCH</div>} />
      </Routes>
    </MemoryRouter>,
  );

const draftDoc = makeDoc({ id: 5, code: 'CK-GM001-20261107-004', status: 'DRAFT' });
const inTransitDoc = makeDoc({
  id: 5,
  code: 'CK-GM001-20261107-004',
  status: 'IN_TRANSIT',
  approvedAt: '2026-11-07T10:00:00+07:00',
});
const receivedDoc = makeDoc({
  id: 5,
  code: 'CK-GM001-20261107-004',
  status: 'RECEIVED',
  hasDiscrepancy: true,
  approvedAt: '2026-11-07T10:00:00+07:00',
  receivedAt: '2026-11-08T09:00:00+07:00',
  receivedBy: { id: 9, fullName: 'Thu Hà' },
  items: [
    makeItem(11, 101, 'Nước suối Lavie 500ml', 50, { receivedQuantity: 50, difference: 0 }),
    makeItem(12, 102, 'Bánh snack Oishi', 28, { receivedQuantity: 24, difference: -4 }),
    makeItem(13, 103, 'Trà xanh C2 455ml', 20, { receivedQuantity: 20, difference: 0 }),
  ],
});

beforeEach(() => {
  for (const fn of Object.values(api)) fn.mockReset();
  api.getTransfer.mockResolvedValue(draftDoc);
  api.getStockItem.mockResolvedValue({ available: 120 });
  userRef.current = makeUser('BRANCH_MANAGER', 8, [b1]);
});
afterEach(cleanup);

describe('Nhận hàng chuyển kho (tiêu chí chấp nhận #96)', () => {
  it('kho đích nhập số nhận thiếu → phiếu Đã nhận, chênh lệch âm màu cam', async () => {
    userRef.current = makeUser('STOREKEEPER', 9, [b2]);
    api.getTransfer.mockReset();
    api.getTransfer.mockResolvedValueOnce(inTransitDoc).mockResolvedValue(receivedDoc);
    api.receiveTransfer.mockResolvedValue(receivedDoc);
    renderDetail();

    expect(await screen.findByText('Bước 3 · Nhận hàng tại kho đích')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('SL thực nhận Bánh snack Oishi'), {
      target: { value: '24' },
    });

    // đang nhập: chênh lệch -4 (đỏ theo Figma) và cảnh báo thiếu
    expect(screen.getByText('-4').className).toContain('ck-diff--entry-short');
    expect(screen.getByRole('alert').textContent).toContain(
      'Phát hiện chênh lệch (Thiếu 4 sản phẩm)',
    );

    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận nhận hàng' }));
    await waitFor(() =>
      expect(api.receiveTransfer).toHaveBeenCalledWith(5, [
        { itemId: 11, receivedQuantity: 50 },
        { itemId: 12, receivedQuantity: 24 },
        { itemId: 13, receivedQuantity: 20 },
      ]),
    );

    // sau khi nhận: phiếu RECEIVED, chênh lệch âm màu cam
    expect(await screen.findByText('Hoàn thành · Đã nhận hàng, có chênh lệch')).toBeTruthy();
    const diff = screen.getByText('-4');
    expect(diff.className).toContain('ck-diff--short');
    expect(diff.className).not.toContain('entry');
    expect(screen.getByText(/Phiếu nhận thiếu 4 sản phẩm/)).toBeTruthy();
    expect(screen.getAllByText('Đã nhận').length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: 'Xác nhận nhận hàng' })).toBeNull();
  });

  it('không cho nhận quá số lượng xuất; nút xác nhận bị khóa', async () => {
    userRef.current = makeUser('STOREKEEPER', 9, [b2]);
    api.getTransfer.mockResolvedValue(inTransitDoc);
    renderDetail();
    await screen.findByText('Bước 3 · Nhận hàng tại kho đích');

    fireEvent.change(screen.getByLabelText('SL thực nhận Nước suối Lavie 500ml'), {
      target: { value: '51' },
    });
    expect(screen.getByText('Không nhận quá số lượng xuất (50)')).toBeTruthy();
    expect(
      (screen.getByRole('button', { name: 'Xác nhận nhận hàng' }) as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(api.receiveTransfer).not.toHaveBeenCalled();
  });

  it('nhận đủ: chênh lệch 0, không có cảnh báo thiếu', async () => {
    userRef.current = makeUser('STOREKEEPER', 9, [b2]);
    api.getTransfer.mockResolvedValue(inTransitDoc);
    renderDetail();
    await screen.findByText('Bước 3 · Nhận hàng tại kho đích');
    expect(screen.queryByText(/Phát hiện chênh lệch/)).toBeNull();
    expect(screen.getAllByText('0').length).toBeGreaterThanOrEqual(3);
  });

  it('người ở kho gửi chỉ xem: không có ô nhập, không có nút xác nhận', async () => {
    userRef.current = makeUser('STOREKEEPER', 7, [b1]);
    api.getTransfer.mockResolvedValue(inTransitDoc);
    renderDetail();
    await screen.findByText('Bước 3 · Nhận hàng tại kho đích');
    expect(screen.queryByLabelText(/SL thực nhận/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Xác nhận nhận hàng' })).toBeNull();
    expect(screen.getByText(/Đang chờ kho nhận GM-002/)).toBeTruthy();
  });

  it('lỗi 409 khi nhận: báo trong Toast, giữ nguyên số đang nhập', async () => {
    userRef.current = makeUser('STOREKEEPER', 9, [b2]);
    api.getTransfer.mockResolvedValue(inTransitDoc);
    api.receiveTransfer.mockRejectedValue(
      new ApiError('Phiếu không còn ở trạng thái đang vận chuyển', 409, 'INVALID_STATUS'),
    );
    renderDetail();
    await screen.findByText('Bước 3 · Nhận hàng tại kho đích');
    fireEvent.change(screen.getByLabelText('SL thực nhận Bánh snack Oishi'), {
      target: { value: '24' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận nhận hàng' }));

    expect(await screen.findByText('Phiếu không còn ở trạng thái đang vận chuyển')).toBeTruthy();
    expect((screen.getByLabelText('SL thực nhận Bánh snack Oishi') as HTMLInputElement).value).toBe(
      '24',
    );
  });
});

describe('Duyệt xuất (bước 2)', () => {
  it('QL chi nhánh gửi (không phải người lập) duyệt được, phiếu sang bước 3', async () => {
    api.getTransfer.mockReset();
    api.getTransfer.mockResolvedValueOnce(draftDoc).mockResolvedValue(inTransitDoc);
    api.approveTransfer.mockResolvedValue(inTransitDoc);
    renderDetail();

    expect(await screen.findByText('Bước 2 · Duyệt xuất tại kho gửi')).toBeTruthy();
    expect(await screen.findByText('Kiểm đếm thực tế trước khi giao vận chuyển')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Duyệt xuất' }));
    await waitFor(() => expect(api.approveTransfer).toHaveBeenCalledWith(5));
    expect(await screen.findByText('Bước 3 · Nhận hàng tại kho đích')).toBeTruthy();
  });

  it('hiện tồn khả dụng của kho gửi cho từng dòng', async () => {
    renderDetail();
    await screen.findByText('Bước 2 · Duyệt xuất tại kho gửi');
    await waitFor(() => expect(screen.getAllByText('120').length).toBe(3));
    expect(api.getStockItem).toHaveBeenCalledWith(1, 101, expect.anything());
  });

  it('người lập phiếu là QL chi nhánh: không tự duyệt được (BR-13)', async () => {
    userRef.current = makeUser('BRANCH_MANAGER', 7, [b1]);
    renderDetail();
    await screen.findByText('Bước 2 · Duyệt xuất tại kho gửi');
    expect(screen.queryByRole('button', { name: 'Duyệt xuất' })).toBeNull();
    expect(screen.getByText(/Người lập phiếu không được tự duyệt/)).toBeTruthy();
  });

  it('lỗi 422 từ backend: báo bằng Toast, nút Duyệt xuất vẫn còn', async () => {
    api.approveTransfer.mockRejectedValue(
      new ApiError('Người duyệt phải khác người lập phiếu', 422, 'SELF_APPROVAL_FORBIDDEN'),
    );
    renderDetail();
    await screen.findByText('Bước 2 · Duyệt xuất tại kho gửi');
    fireEvent.click(screen.getByRole('button', { name: 'Duyệt xuất' }));
    expect(await screen.findByText('Người duyệt phải khác người lập phiếu')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Duyệt xuất' })).toBeTruthy();
  });

  it('thiếu tồn khi duyệt (409): hiện thông báo kèm số khả dụng', async () => {
    api.approveTransfer.mockRejectedValue(
      new ApiError('Không đủ tồn khả dụng', 409, 'INSUFFICIENT_STOCK', undefined, {
        lines: [{ sku: 'SP101', available: 8 }],
      }),
    );
    renderDetail();
    await screen.findByText('Bước 2 · Duyệt xuất tại kho gửi');
    fireEvent.click(screen.getByRole('button', { name: 'Duyệt xuất' }));
    expect(await screen.findByText('Không đủ tồn khả dụng (SP101: khả dụng 8)')).toBeTruthy();
  });
});

describe('Hủy và sửa phiếu nháp', () => {
  beforeEach(() => {
    userRef.current = makeUser('STOREKEEPER', 7, [b1]);
  });

  it('hủy phiếu cần lý do, gửi lên API', async () => {
    api.cancelTransfer.mockResolvedValue({ ...draftDoc, status: 'CANCELLED' });
    renderDetail();
    await screen.findByText('Bước 2 · Duyệt xuất tại kho gửi');

    fireEvent.click(screen.getByRole('button', { name: 'Hủy phiếu' }));
    const confirm = screen.getByRole('button', { name: 'Xác nhận hủy phiếu' }) as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Lý do hủy phiếu/), {
      target: { value: 'Nhập sai số lượng' },
    });
    expect(confirm.disabled).toBe(false);
    fireEvent.click(confirm);
    await waitFor(() => expect(api.cancelTransfer).toHaveBeenCalledWith(5, 'Nhập sai số lượng'));
  });

  it('có liên kết Sửa phiếu; thủ kho (người lập) không thấy nút Duyệt xuất', async () => {
    renderDetail();
    await screen.findByText('Bước 2 · Duyệt xuất tại kho gửi');
    expect(screen.getByRole('link', { name: 'Sửa phiếu' }).getAttribute('href')).toBe(
      '/operations/transfers/5/edit',
    );
    expect(screen.queryByRole('button', { name: 'Duyệt xuất' })).toBeNull();
    expect(screen.getByText(/Phiếu đang chờ quản lý chi nhánh GM-001 duyệt xuất/)).toBeTruthy();
  });
});

describe('Trạng thái khác', () => {
  it('phiếu đã hủy: banner, không có nút thao tác', async () => {
    api.getTransfer.mockResolvedValue({ ...draftDoc, status: 'CANCELLED' });
    renderDetail();
    expect(await screen.findByText('Phiếu đã hủy. Tồn kho không thay đổi.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Duyệt xuất' })).toBeNull();
  });

  it('không tải được phiếu: thông báo lỗi kèm Thử lại và liên kết về danh sách', async () => {
    api.getTransfer.mockRejectedValueOnce(
      new ApiError('Không tìm thấy phiếu kho', 404, 'NOT_FOUND'),
    );
    renderDetail();
    expect(await screen.findByText('Không tìm thấy phiếu kho')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Quay lại danh sách' }).getAttribute('href')).toBe(
      '/operations/transfers',
    );
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(await screen.findByText('Bước 2 · Duyệt xuất tại kho gửi')).toBeTruthy();
  });
});
