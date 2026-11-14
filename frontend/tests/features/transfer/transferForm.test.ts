import { describe, expect, it } from 'vitest';
import {
  mapBackendFields,
  toPayloadItems,
  validateTransferForm,
} from '../../../src/features/transfers/transferForm';
import type { DraftItem } from '../../../src/features/transfers/transferForm';

const item = (productId: number, quantity: string, available?: number): DraftItem => ({
  productId,
  sku: `SP${productId}`,
  productName: `Hàng ${productId}`,
  unit: 'Thùng',
  quantity,
  available,
});
const availableOf = (i: DraftItem) => i.available;

describe('validateTransferForm', () => {
  it('thiếu chi nhánh đích và sản phẩm', () => {
    const r = validateTransferForm({ toBranchId: '', items: [] }, availableOf);
    expect(r.valid).toBe(false);
    expect(r.toBranch).toBe('Chọn chi nhánh đích');
    expect(r.items).toBe('Thêm ít nhất một sản phẩm cần chuyển');
  });

  it('số lượng: trống, không phải số nguyên dương, vượt tồn khả dụng', () => {
    const r = validateTransferForm(
      {
        toBranchId: '2',
        items: [item(1, ''), item(2, '0'), item(3, '2.5'), item(4, '500', 120), item(5, '50', 120)],
      },
      availableOf,
    );
    expect(r.rows[1]).toBe('Nhập số lượng');
    expect(r.rows[2]).toBe('Số lượng phải là số nguyên lớn hơn 0');
    expect(r.rows[3]).toBe('Số lượng phải là số nguyên lớn hơn 0');
    expect(r.rows[4]).toBe('Vượt tồn khả dụng (120)');
    expect(r.rows[5]).toBeUndefined();
  });

  it('hợp lệ khi có đích, có hàng, số lượng trong tồn', () => {
    expect(
      validateTransferForm({ toBranchId: '2', items: [item(1, '50', 120)] }, availableOf).valid,
    ).toBe(true);
  });

  it('chưa biết tồn thì để backend kiểm tra', () => {
    expect(
      validateTransferForm({ toBranchId: '2', items: [item(1, '9999')] }, availableOf).valid,
    ).toBe(true);
  });
});

describe('payload và lỗi backend', () => {
  it('toPayloadItems đổi chuỗi sang số', () => {
    expect(toPayloadItems([item(1, '50')])).toEqual([{ productId: 1, quantity: 50 }]);
  });

  it('mapBackendFields gắn lỗi items[i] vào đúng sản phẩm', () => {
    const items = [item(10, '1'), item(20, '2')];
    expect(mapBackendFields({ 'items[1].quantity': 'sai', toBranchId: 'bắt buộc' }, items)).toEqual(
      {
        toBranch: 'bắt buộc',
        rows: { 20: 'sai' },
      },
    );
  });
});
