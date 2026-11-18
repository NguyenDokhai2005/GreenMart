import type { CurrentUser } from '../../api/client';

/** Phần tối thiểu của phiếu để tính quyền (dùng được cho cả dòng danh sách lẫn chi tiết). */
export interface TransferLike {
  status: string;
  branch: { id: number };
  toBranch?: { id: number } | null;
  createdBy: { id: number };
}

type UserLike = Pick<CurrentUser, 'id' | 'role' | 'branches'>;

const inBranch = (user: UserLike, branchId: number) => user.branches.some((b) => b.id === branchId);
const isWarehouseRole = (user: UserLike) =>
  user.role === 'STOREKEEPER' || user.role === 'BRANCH_MANAGER';

/**
 * Các hàm dưới chỉ quyết định hiện hay ẩn nút. Backend vẫn kiểm tra vai trò, chi nhánh (BR-11)
 * và người duyệt khác người lập (BR-13); lỗi từ backend được hiện cho người dùng.
 */

/** Lập phiếu: Thủ kho hoặc QL chi nhánh, kho gửi là chi nhánh đang làm việc. */
export function canCreateTransfer(user: UserLike, sourceBranchId: number | undefined): boolean {
  return sourceBranchId !== undefined && isWarehouseRole(user) && inBranch(user, sourceBranchId);
}

/** Sửa hoặc hủy phiếu nháp tại kho gửi. */
export function canEditOrCancel(user: UserLike, doc: TransferLike): boolean {
  return doc.status === 'DRAFT' && isWarehouseRole(user) && inBranch(user, doc.branch.id);
}

/** Duyệt xuất: QL chi nhánh nguồn, không phải người lập phiếu (BR-13). */
export function canApprove(user: UserLike, doc: TransferLike): boolean {
  return (
    doc.status === 'DRAFT' &&
    user.role === 'BRANCH_MANAGER' &&
    inBranch(user, doc.branch.id) &&
    doc.createdBy.id !== user.id
  );
}

/** QL chi nhánh nguồn là người lập phiếu: thấy lý do không được tự duyệt thay vì nút. */
export function isBlockedSelfApproval(user: UserLike, doc: TransferLike): boolean {
  return (
    doc.status === 'DRAFT' &&
    user.role === 'BRANCH_MANAGER' &&
    inBranch(user, doc.branch.id) &&
    doc.createdBy.id === user.id
  );
}

/** Nhận hàng: người dùng thuộc chi nhánh đích (BR-16). */
export function canReceive(user: UserLike, doc: TransferLike): boolean {
  return (
    doc.status === 'IN_TRANSIT' &&
    !!doc.toBranch &&
    isWarehouseRole(user) &&
    inBranch(user, doc.toBranch.id)
  );
}
