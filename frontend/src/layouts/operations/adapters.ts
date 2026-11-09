import type { BranchRef, CurrentUser, Role } from '../../api/client';

export const ROLE_LABEL: Record<Role, string> = {
  OWNER: 'Chủ sở hữu',
  BRANCH_MANAGER: 'Quản lý chi nhánh',
  STOREKEEPER: 'Thủ kho',
  CASHIER: 'Thu ngân',
  ADMIN: 'Admin',
};

export const userDisplayName = (u: CurrentUser) => u.fullName;

/** "GM-001 GreenMart Quận 1" */
export const branchLabel = (b: BranchRef) => `${b.code} ${b.name}`;
