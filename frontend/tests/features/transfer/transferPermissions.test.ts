import { describe, expect, it } from 'vitest';
import { b1, b2, b3, makeUser } from '../../../src/features/transfers/transferTestData';
import {
  canApprove,
  canCreateTransfer,
  canEditOrCancel,
  canReceive,
  isBlockedSelfApproval,
} from '../../../src/features/transfers/transferPermissions';

const draft = { status: 'DRAFT', branch: b1, toBranch: b2, createdBy: { id: 7 } };
const inTransit = { ...draft, status: 'IN_TRANSIT' };

const storekeeper1 = makeUser('STOREKEEPER', 7, [b1]);
const manager1 = makeUser('BRANCH_MANAGER', 8, [b1]);
const storekeeper2 = makeUser('STOREKEEPER', 9, [b2]);
const owner = makeUser('OWNER', 1, [b1, b2, b3]);

describe('canApprove (BR-13)', () => {
  it('QL chi nhánh nguồn không phải người lập thì duyệt được', () => {
    expect(canApprove(manager1, draft)).toBe(true);
  });
  it('người lập phiếu không tự duyệt được, được báo lý do', () => {
    const creatorManager = makeUser('BRANCH_MANAGER', 7, [b1]);
    expect(canApprove(creatorManager, draft)).toBe(false);
    expect(isBlockedSelfApproval(creatorManager, draft)).toBe(true);
    expect(isBlockedSelfApproval(manager1, draft)).toBe(false);
  });
  it('thủ kho, QL chi nhánh khác, owner không duyệt xuất', () => {
    expect(canApprove(storekeeper1, draft)).toBe(false);
    expect(canApprove(makeUser('BRANCH_MANAGER', 20, [b2]), draft)).toBe(false);
    expect(canApprove(owner, draft)).toBe(false);
  });
  it('chỉ duyệt phiếu Nháp', () => {
    expect(canApprove(manager1, inTransit)).toBe(false);
  });
});

describe('canReceive (BR-16)', () => {
  it('Thủ kho hoặc QL chi nhánh đích nhận được phiếu đang vận chuyển', () => {
    expect(canReceive(storekeeper2, inTransit)).toBe(true);
    expect(canReceive(makeUser('BRANCH_MANAGER', 21, [b2]), inTransit)).toBe(true);
  });
  it('người ở kho gửi hoặc chi nhánh thứ ba không nhận được', () => {
    expect(canReceive(storekeeper1, inTransit)).toBe(false);
    expect(canReceive(makeUser('STOREKEEPER', 22, [b3]), inTransit)).toBe(false);
  });
  it('chỉ nhận phiếu IN_TRANSIT', () => {
    expect(canReceive(storekeeper2, draft)).toBe(false);
    expect(canReceive(storekeeper2, { ...inTransit, status: 'RECEIVED' })).toBe(false);
  });
});

describe('canEditOrCancel / canCreateTransfer', () => {
  it('sửa, hủy phiếu Nháp tại kho gửi', () => {
    expect(canEditOrCancel(storekeeper1, draft)).toBe(true);
    expect(canEditOrCancel(manager1, draft)).toBe(true);
    expect(canEditOrCancel(storekeeper2, draft)).toBe(false);
    expect(canEditOrCancel(storekeeper1, inTransit)).toBe(false);
  });
  it('lập phiếu cần chi nhánh gửi cụ thể và vai trò kho', () => {
    expect(canCreateTransfer(storekeeper1, 1)).toBe(true);
    expect(canCreateTransfer(manager1, 1)).toBe(true);
    expect(canCreateTransfer(storekeeper1, undefined)).toBe(false);
    expect(canCreateTransfer(storekeeper1, 2)).toBe(false);
    expect(canCreateTransfer(owner, 1)).toBe(false);
    expect(canCreateTransfer(makeUser('CASHIER', 30, [b1]), 1)).toBe(false);
  });
});
