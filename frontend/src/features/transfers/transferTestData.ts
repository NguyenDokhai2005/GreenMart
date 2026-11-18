/** Dữ liệu dựng sẵn dùng chung cho test của màn chuyển kho. */
import type { BranchRef, CurrentUser, Role } from '../../api/client';
import type { TransferDoc, TransferItem, TransferSummary } from './transferApi';

export const b1: BranchRef = { id: 1, code: 'GM-001', name: 'GreenMart Quận 1' };
export const b2: BranchRef = { id: 2, code: 'GM-002', name: 'GreenMart Bình Thạnh' };
export const b3: BranchRef = { id: 3, code: 'GM-003', name: 'GreenMart Tân Bình' };

export const makeUser = (role: Role, id: number, branches: BranchRef[]): CurrentUser => ({
  id,
  username: `u${id}`,
  fullName: `Người dùng ${id}`,
  role,
  branches,
});

export const makeItem = (
  id: number,
  productId: number,
  name: string,
  quantity: number,
  extra: Partial<TransferItem> = {},
): TransferItem => ({
  id,
  productId,
  sku: `SP${productId}`,
  productName: name,
  unit: 'Thùng',
  quantity,
  ...extra,
});

export const makeSummary = (
  over: Partial<TransferSummary> & Pick<TransferSummary, 'id' | 'code' | 'status'>,
): TransferSummary => ({
  type: 'TRANSFER',
  branch: b1,
  toBranch: b2,
  itemCount: 3,
  createdBy: { id: 7, fullName: 'Trần Minh' },
  createdAt: '2026-11-07T14:32:00+07:00',
  ...over,
});

export const makeDoc = (
  over: Partial<TransferDoc> & Pick<TransferDoc, 'id' | 'code' | 'status'>,
): TransferDoc => ({
  type: 'TRANSFER',
  branch: b1,
  toBranch: b2,
  itemCount: 3,
  createdBy: { id: 7, fullName: 'Trần Minh' },
  createdAt: '2026-11-07T09:05:00+07:00',
  note: 'Bổ sung hàng cho kho nhận',
  items: [
    makeItem(11, 101, 'Nước suối Lavie 500ml', 50),
    makeItem(12, 102, 'Bánh snack Oishi', 28),
    makeItem(13, 103, 'Trà xanh C2 455ml', 20),
  ],
  ...over,
});
