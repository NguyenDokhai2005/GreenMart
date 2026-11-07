import type { Role } from '../../api/client';

// Proposed #72 routes approved by the user; not a Lead decision.
export const CATALOG_ROOT = '/catalog';
export const CATALOG_PATHS = {
  products: `${CATALOG_ROOT}/products`,
  customers: `${CATALOG_ROOT}/customers`,
} as const;
export const CATALOG_ROLES: readonly Role[] = [
  'ADMIN',
  'OWNER',
  'BRANCH_MANAGER',
  'CASHIER',
  'STOREKEEPER',
];
export const canEditProducts = (role: Role) => role === 'OWNER';
export const canEditCustomers = (role: Role) =>
  ['OWNER', 'BRANCH_MANAGER', 'CASHIER'].includes(role);
export const money = (amount: number) => `${new Intl.NumberFormat('vi-VN').format(amount)} ₫`;
