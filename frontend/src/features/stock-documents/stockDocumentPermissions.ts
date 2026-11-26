import type { CurrentUser } from '../../api/client';
import type { StockDocument } from './stockDocumentApi';

type UserLike = Pick<CurrentUser, 'id' | 'role' | 'branches'>;
type DocumentLike = Pick<StockDocument, 'status' | 'branch' | 'createdBy' | 'type'>;

const belongsToBranch = (user: UserLike, branchId: number) =>
  user.branches.some((branch) => branch.id === branchId);

export function canCreateStockDocument(user: UserLike, branchId: number | undefined): boolean {
  return (
    branchId !== undefined &&
    (user.role === 'STOREKEEPER' || user.role === 'BRANCH_MANAGER') &&
    belongsToBranch(user, branchId)
  );
}

export function canEditStockDocument(user: UserLike, document: DocumentLike): boolean {
  return (
    document.status === 'DRAFT' &&
    (user.role === 'STOREKEEPER' || user.role === 'BRANCH_MANAGER') &&
    belongsToBranch(user, document.branch.id)
  );
}

export function canApproveStockDocument(user: UserLike, document: DocumentLike): boolean {
  return (
    document.status === 'DRAFT' &&
    ((user.role === 'OWNER' && document.type !== 'TRANSFER') ||
      (user.role === 'BRANCH_MANAGER' && belongsToBranch(user, document.branch.id))) &&
    document.createdBy.id !== user.id
  );
}

export function isBlockedSelfApproval(user: UserLike, document: DocumentLike): boolean {
  return (
    document.status === 'DRAFT' &&
    ((user.role === 'OWNER' && document.type !== 'TRANSFER') ||
      (user.role === 'BRANCH_MANAGER' && belongsToBranch(user, document.branch.id))) &&
    document.createdBy.id === user.id
  );
}

export function canCancelStockDocument(user: UserLike, document: DocumentLike): boolean {
  return (
    document.status === 'DRAFT' &&
    (user.role === 'STOREKEEPER' || user.role === 'BRANCH_MANAGER') &&
    belongsToBranch(user, document.branch.id)
  );
}
