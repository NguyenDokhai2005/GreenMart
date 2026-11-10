import { describe, expect, it } from 'vitest';
import type { Role } from '../../../src/api/client';
import {
  canPickAllBranches,
  menuForRole,
  operationsHomePath,
  type MenuItem,
} from '../../../src/layouts/operations/menuConfig';

const keys = (items: MenuItem[]): string[] =>
  items.flatMap((i) => [i.key, ...keys(i.children ?? [])]);

describe('menuForRole (AUTH-02)', () => {
  it('THỦ KHO không thấy Khuyến mại, Quản trị và các mục chỉ dành cho quản lý', () => {
    const k = keys(menuForRole('STOREKEEPER'));
    for (const hidden of [
      'promotions',
      'admin',
      'admin-dashboard',
      'overview',
      'forecast',
      'reports',
    ]) {
      expect(k).not.toContain(hidden);
    }
    expect(k).toEqual(
      expect.arrayContaining([
        'orders',
        'products',
        'customers',
        'warehouse',
        'inventory',
        'stock-documents',
      ]),
    );
  });

  it.each<Role>(['OWNER', 'BRANCH_MANAGER'])(
    '%s thấy menu quản lý, không thấy Quản trị',
    (role) => {
      const k = keys(menuForRole(role));
      expect(k).toEqual(
        expect.arrayContaining([
          'overview',
          'customers',
          'promotions',
          'forecast',
          'reports',
          'warehouse',
        ]),
      );
      expect(k).not.toContain('admin');
      expect(k).not.toContain('admin-dashboard');
    },
  );

  it('ADMIN chỉ thấy Dashboard và nhóm Quản trị', () => {
    const top = menuForRole('ADMIN').map((i) => i.key);
    expect(top).toEqual(['admin-dashboard', 'admin']);
  });

  it('CASHIER không có mục nào trong khu Operations', () => {
    expect(menuForRole('CASHIER')).toEqual([]);
  });

  it('nhóm Kho có đủ 5 mục con và Phiếu nhập/xuất trỏ đúng route của SV04', () => {
    const wh = menuForRole('OWNER').find((i) => i.key === 'warehouse');
    expect(wh?.children).toHaveLength(5);
    expect(wh?.children?.find((c) => c.key === 'stock-documents')?.to).toBe(
      '/operations/stock-documents',
    );
  });
});

describe('helpers', () => {
  it('chỉ OWNER và ADMIN chọn được Tất cả chi nhánh (BR-11)', () => {
    expect(canPickAllBranches('OWNER')).toBe(true);
    expect(canPickAllBranches('ADMIN')).toBe(true);
    expect(canPickAllBranches('BRANCH_MANAGER')).toBe(false);
    expect(canPickAllBranches('STOREKEEPER')).toBe(false);
  });

  it('THỦ KHO vào Tồn kho, vai trò khác vào Tổng quan', () => {
    expect(operationsHomePath('STOREKEEPER')).toBe('/operations/inventory');
    expect(operationsHomePath('OWNER')).toBe('/operations');
    expect(operationsHomePath('BRANCH_MANAGER')).toBe('/operations');
  });
});
