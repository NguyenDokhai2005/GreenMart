import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../../src/api/client';
import type { CurrentUser } from '../../../src/api/client';
import type { components } from '../../../src/api/schema';
import PromotionsPage from '../../../src/features/promotions/PromotionsPage';
import type { Promotion } from '../../../src/features/promotions/promotionApi';

const mocks = vi.hoisted(() => ({
  listPromotions: vi.fn(),
  createPromotion: vi.fn(),
  updatePromotion: vi.fn(),
  changePromotionStatus: vi.fn(),
  searchProducts: vi.fn(),
  user: { current: null as unknown },
}));

vi.mock('../../../src/features/promotions/promotionApi', () => ({
  listPromotions: mocks.listPromotions,
  createPromotion: mocks.createPromotion,
  updatePromotion: mocks.updatePromotion,
  changePromotionStatus: mocks.changePromotionStatus,
}));

vi.mock('../../../src/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../src/api/client')>();
  return { ...actual, searchProducts: mocks.searchProducts };
});

vi.mock('../../../src/features/auth/useAuth', () => ({
  useAuth: () => ({ state: { status: 'authenticated', user: mocks.user.current } }),
}));

vi.mock('../../../src/layouts/operations/useOperationsScope', () => ({
  useOperationsScope: () => ({
    branchId: 1,
    scope: { kind: 'branch', branch: { id: 1, code: 'GM-001', name: 'Chi nhánh 1' } },
    branches: [{ id: 1, code: 'GM-001', name: 'Chi nhánh 1' }],
  }),
}));

const owner: CurrentUser = {
  id: 1,
  username: 'owner',
  fullName: 'Chủ chuỗi',
  role: 'OWNER',
  branches: [{ id: 1, code: 'GM-001', name: 'Chi nhánh 1' }],
};

const promotion: Promotion = {
  id: 5,
  code: 'KM-ITEM',
  type: 'AMOUNT',
  level: 'ITEM',
  value: 1000,
  maxDiscount: null,
  minOrder: null,
  minQuantity: 2,
  startAt: '2026-12-01T00:00:00+07:00',
  endAt: '2026-12-31T23:59:59+07:00',
  usageLimit: null,
  usedCount: 0,
  status: 'DRAFT',
  productIds: [9],
  branchIds: [],
  channels: [],
};

function renderPage() {
  return render(
    <MemoryRouter>
      <PromotionsPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  mocks.user.current = owner;
  mocks.listPromotions.mockReset().mockResolvedValue({
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
    content: [promotion],
  });
  mocks.createPromotion.mockReset().mockResolvedValue(promotion);
  mocks.updatePromotion.mockReset().mockResolvedValue(promotion);
  mocks.changePromotionStatus.mockReset().mockResolvedValue({ ...promotion, status: 'ACTIVE' });
  mocks.searchProducts.mockReset().mockResolvedValue({
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
    content: [
      {
        id: 9,
        sku: 'SUA-09',
        barcode: null,
        name: 'Sữa tươi',
        unit: 'hộp',
        categoryId: 2,
        categoryName: 'Sữa',
        salePrice: 2000,
        costPrice: null,
        active: true,
      } satisfies components['schemas']['Product'],
    ],
  });
});

afterEach(cleanup);

describe('PromotionsPage', () => {
  it('filters by status and level and lets OWNER change a valid status', async () => {
    renderPage();

    expect(await screen.findByText('KM-ITEM')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Trạng thái'), { target: { value: 'ACTIVE' } });
    fireEvent.change(screen.getByLabelText('Cấp áp dụng'), { target: { value: 'ITEM' } });
    await waitFor(() => {
      expect(mocks.listPromotions).toHaveBeenLastCalledWith(
        expect.objectContaining({ branchId: 1, status: ['ACTIVE'], level: 'ITEM' }),
        expect.any(AbortSignal),
      );
    });

    fireEvent.click(screen.getByRole('button', { name: 'Kích hoạt KM-ITEM' }));
    await waitFor(() => expect(mocks.changePromotionStatus).toHaveBeenCalledWith(5, 'ACTIVE'));
  });

  it('submits ITEM minQuantity and shows validation details from the API', async () => {
    mocks.createPromotion.mockRejectedValue(
      new ApiError('Dữ liệu không hợp lệ', 400, 'VALIDATION_ERROR', undefined, [
        { field: 'minQuantity', message: 'phải lớn hơn 1' },
      ]),
    );
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '+ Tạo khuyến mại' }));
    fireEvent.change(screen.getByLabelText(/Mã khuyến mại/), { target: { value: 'NEW-ITEM' } });
    const dialog = within(screen.getByRole('dialog'));
    fireEvent.change(dialog.getByLabelText('Cấp áp dụng'), { target: { value: 'ITEM' } });
    fireEvent.change(dialog.getByLabelText('Loại giảm giá'), { target: { value: 'AMOUNT' } });
    fireEvent.change(dialog.getByLabelText(/Mức giảm \(VND\)/), { target: { value: '500' } });
    fireEvent.change(screen.getByLabelText('Số lượng tối thiểu trên một dòng'), {
      target: { value: '3' },
    });
    fireEvent.change(screen.getByLabelText('Tìm sản phẩm'), { target: { value: 'Sữa' } });
    fireEvent.click(await screen.findByRole('button', { name: /Sữa tươi.*Thêm/ }));
    fireEvent.change(dialog.getByLabelText(/Bắt đầu/), {
      target: { value: '2027-01-01T10:00' },
    });
    fireEvent.change(dialog.getByLabelText(/Kết thúc/), {
      target: { value: '2027-01-05T10:00' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Tạo bản nháp' }));

    await waitFor(() => {
      expect(mocks.createPromotion).toHaveBeenCalledWith(
        expect.objectContaining({
          code: 'NEW-ITEM',
          level: 'ITEM',
          minQuantity: 3,
          productIds: [9],
        }),
      );
    });
    expect(await screen.findByText('phải lớn hơn 1')).toBeTruthy();
  });

  it('keeps branch managers read-only', async () => {
    mocks.user.current = { ...owner, role: 'BRANCH_MANAGER' };
    renderPage();

    expect(await screen.findByText('KM-ITEM')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '+ Tạo khuyến mại' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Kích hoạt KM-ITEM' })).toBeNull();
    expect(screen.getByText(/Chỉ Chủ chuỗi/)).toBeTruthy();
  });
});
