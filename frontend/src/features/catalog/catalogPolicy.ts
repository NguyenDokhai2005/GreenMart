import type { Role } from '../../api/client';

export const canEditProducts = (role: Role) => role === 'OWNER';
export const canEditCustomers = (role: Role) =>
  ['OWNER', 'BRANCH_MANAGER', 'CASHIER'].includes(role);
export const money = (amount: number) => `${new Intl.NumberFormat('vi-VN').format(amount)} ₫`;
