import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { BranchRef, CurrentUser, Role } from '../../../src/api/client';
import { OperationsScopeProvider } from '../../../src/layouts/operations/OperationsScopeProvider';
import { useOperationsScope } from '../../../src/layouts/operations/useOperationsScope';

const b1: BranchRef = { id: 1, code: 'GM-001', name: 'GreenMart Quận 1' };
const b2: BranchRef = { id: 2, code: 'GM-002', name: 'GreenMart Bình Thạnh' };
const b3: BranchRef = { id: 3, code: 'GM-003', name: 'GreenMart Tân Bình' };

let user: CurrentUser;
let active: BranchRef | undefined;
const selectBranch = vi.fn((id: number) => {
  active = [b1, b2, b3].find((b) => b.id === id);
  return !!active;
});

vi.mock('../../../src/features/auth/useAuth', () => ({
  useAuth: () => ({ state: { status: 'authenticated', user } }),
}));
vi.mock('../../../src/features/auth/useActiveBranch', () => ({
  useActiveBranch: () => ({
    state: active
      ? { status: 'active', branch: active }
      : { status: 'selection-required', branches: user.branches },
    selectBranch,
    clearBranch: vi.fn(),
  }),
}));

const setup = (role: Role, branches: BranchRef[], activeBranch?: BranchRef, id = 10) => {
  user = { id, username: 'u', fullName: 'U', role, branches };
  active = activeBranch;
  const wrapper = ({ children }: { children: ReactNode }) => (
    <OperationsScopeProvider>{children}</OperationsScopeProvider>
  );
  return renderHook(() => useOperationsScope(), { wrapper });
};

beforeEach(() => {
  sessionStorage.clear();
  selectBranch.mockClear();
});

describe('OperationsScope', () => {
  it('OWNER: mặc định Tất cả chi nhánh, có dropdown (tiêu chí 1)', () => {
    const { result } = setup('OWNER', [b1, b2, b3], b1);
    expect(result.current.scope).toEqual({ kind: 'all' });
    expect(result.current.branchId).toBeUndefined();
    expect(result.current.locked).toBe(false);
    expect(result.current.canPickAll).toBe(true);
  });

  it('OWNER: chọn từng chi nhánh rồi quay lại Tất cả', () => {
    const { result, rerender } = setup('OWNER', [b1, b2, b3], b1);
    act(() => result.current.pickBranch(2));
    rerender();
    expect(selectBranch).toHaveBeenCalledWith(2);
    expect(result.current.scope).toEqual({ kind: 'branch', branch: b2 });
    expect(result.current.branchId).toBe(2);
    act(() => result.current.pickAll());
    expect(result.current.branchId).toBeUndefined();
  });

  it.each<Role>(['BRANCH_MANAGER', 'STOREKEEPER'])(
    '%s được gán 1 chi nhánh: cố định, không dropdown (tiêu chí 2)',
    (role) => {
      const { result } = setup(role, [b1], b1);
      expect(result.current.locked).toBe(true);
      expect(result.current.branchId).toBe(1);
      act(() => result.current.pickBranch(2));
      expect(selectBranch).not.toHaveBeenCalled();
      act(() => result.current.pickAll());
      expect(result.current.branchId).toBe(1);
    },
  );

  it('QLCN nhiều chi nhánh: có dropdown nhưng không có Tất cả', () => {
    const { result } = setup('BRANCH_MANAGER', [b1, b2], b1);
    expect(result.current.locked).toBe(false);
    expect(result.current.canPickAll).toBe(false);
    act(() => result.current.pickAll());
    expect(result.current.scope).toEqual({ kind: 'branch', branch: b1 });
  });

  it('không thừa hưởng lựa chọn đã lưu của tài khoản khác', () => {
    sessionStorage.setItem('ops.scope', '99:branch');
    const { result } = setup('OWNER', [b1, b2], b1, 10);
    expect(result.current.scope).toEqual({ kind: 'all' });
  });

  it('khôi phục lựa chọn đã lưu của chính tài khoản đó', () => {
    sessionStorage.setItem('ops.scope', '10:branch');
    const { result } = setup('OWNER', [b1, b2], b2, 10);
    expect(result.current.scope).toEqual({ kind: 'branch', branch: b2 });
  });
});
