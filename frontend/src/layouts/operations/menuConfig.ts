import type { Role } from '../../api/client';
import { APP_PATHS, canOpenRoutePath } from '../../features/auth/routePolicy';

export type IconName =
  | 'dashboard'
  | 'orders'
  | 'products'
  | 'customers'
  | 'promo'
  | 'warehouse'
  | 'forecast'
  | 'report'
  | 'admin';

export interface MenuItem {
  key: string;
  label: string;
  to?: string;
  icon?: IconName;
  roles: readonly Role[]; // lọc hiển thị theo mục (routePolicy mới chặn theo khu)
  children?: MenuItem[];
}

const OP = APP_PATHS.operations;
const AD = APP_PATHS.admin;
const MANAGER: readonly Role[] = ['OWNER', 'BRANCH_MANAGER'];
const STAFF: readonly Role[] = ['OWNER', 'BRANCH_MANAGER', 'STOREKEEPER'];

/** Nguồn duy nhất của menu Operations/Admin (AUTH-02). */
export const MENU: MenuItem[] = [
  { key: 'overview', label: 'Tổng quan', to: OP, icon: 'dashboard', roles: MANAGER },
  { key: 'admin-dashboard', label: 'Dashboard', to: AD, icon: 'dashboard', roles: ['ADMIN'] },
  { key: 'orders', label: 'Đơn hàng', to: `${OP}/orders`, icon: 'orders', roles: STAFF },
  { key: 'products', label: 'Sản phẩm', to: `${OP}/products`, icon: 'products', roles: STAFF },
  { key: 'customers', label: 'Khách hàng', to: `${OP}/customers`, icon: 'customers', roles: STAFF },
  { key: 'promotions', label: 'Khuyến mại', to: `${OP}/promotions`, icon: 'promo', roles: MANAGER },
  {
    key: 'warehouse',
    label: 'Kho',
    icon: 'warehouse',
    roles: STAFF,
    children: [
      { key: 'inventory', label: 'Tồn kho', to: `${OP}/inventory`, roles: STAFF },
      {
        key: 'stock-documents',
        label: 'Phiếu nhập / xuất',
        to: APP_PATHS.stockDocuments,
        roles: STAFF,
      },
      { key: 'transfers', label: 'Chuyển kho', to: `${OP}/transfers`, roles: STAFF },
      { key: 'stocktake', label: 'Kiểm kê', to: `${OP}/stocktake`, roles: STAFF },
      { key: 'ledger', label: 'Sổ giao dịch', to: `${OP}/ledger`, roles: STAFF },
    ],
  },
  { key: 'forecast', label: 'Dự báo', to: `${OP}/forecast`, icon: 'forecast', roles: MANAGER },
  { key: 'reports', label: 'Báo cáo', to: `${OP}/reports`, icon: 'report', roles: MANAGER },
  {
    key: 'admin',
    label: 'Quản trị',
    roles: ['ADMIN'],
    children: [
      { key: 'branches', label: 'Chi nhánh', to: `${AD}/branches`, roles: ['ADMIN'] },
      { key: 'staff', label: 'Nhân sự & phân quyền', to: `${AD}/staff`, roles: ['ADMIN'] },
      { key: 'settings', label: 'Cấu hình hệ thống', to: `${AD}/settings`, roles: ['ADMIN'] },
      { key: 'audit', label: 'Nhật ký hoạt động', to: `${AD}/audit`, roles: ['ADMIN'] },
      { key: 'channels', label: 'Trạng thái đồng bộ kênh', to: `${AD}/channels`, roles: ['ADMIN'] },
      { key: 'locked', label: 'Danh sách tài khoản bị khóa', to: `${AD}/locked`, roles: ['ADMIN'] },
      { key: 'recent', label: 'Nhật ký gần đây', to: `${AD}/recent`, roles: ['ADMIN'] },
    ],
  },
];

/** Hiện mục khi vai trò nằm trong `roles` VÀ routePolicy cho phép mở đường dẫn đó. */
export function menuForRole(role: Role, items: MenuItem[] = MENU): MenuItem[] {
  return items.flatMap((i) => {
    if (!i.roles.includes(role)) return [];
    if (i.children) {
      const children = menuForRole(role, i.children);
      return children.length ? [{ ...i, children }] : [];
    }
    return !i.to || canOpenRoutePath(role, i.to) ? [i] : [];
  });
}

export const canPickAllBranches = (role: Role) => role === 'OWNER' || role === 'ADMIN';

/** Trang đầu của khu Operations: THỦ KHO không có Tổng quan nên vào Tồn kho. */
export const operationsHomePath = (role: Role) => (role === 'STOREKEEPER' ? `${OP}/inventory` : OP);

/**
 * Nhãn breadcrumb cho đường dẫn hiện tại: mục con của nhóm hiện "Nhóm / Mục" (vd "Kho / Tồn kho"), mục thường hiện tên mục.
 */
export function breadcrumbFor(
  pathname: string,
  items: MenuItem[] = MENU,
  parent?: string,
): string | undefined {
  for (const i of items) {
    const hit = i.children ? breadcrumbFor(pathname, i.children, i.label) : undefined;
    if (hit) return hit;
    const matches =
      !!i.to &&
      (pathname === i.to || (i.to.split('/').length > 2 && pathname.startsWith(`${i.to}/`)));
    if (matches) return parent ? `${parent} / ${i.label}` : i.label;
  }
  return undefined;
}
