import { useCallback, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { useActiveBranch } from '../../features/auth/useActiveBranch';
import { useAuth } from '../../features/auth/useAuth';
import { canPickAllBranches } from './menuConfig';
import { OperationsScopeContext } from './OperationsScopeContext';
import type { OperationsScope, OperationsScopeValue } from './OperationsScopeContext';

const KEY = 'ops.scope';

// Lưu kèm id người dùng để đăng xuất rồi đăng nhập tài khoản khác không thừa hưởng lựa chọn cũ.
const readStored = (userId?: number): string | null => {
  try {
    const raw = sessionStorage.getItem(KEY);
    return raw && userId !== undefined && raw.startsWith(`${userId}:`)
      ? raw.slice(`${userId}:`.length)
      : null;
  } catch {
    // The preference is optional when browser storage is unavailable.
    return null;
  }
};
const writeStored = (userId: number | undefined, v: string) => {
  if (userId === undefined) return;
  try {
    sessionStorage.setItem(KEY, `${userId}:${v}`);
  } catch {
    // Keep the current selection in memory when browser storage is unavailable.
  }
};

/**
 * Phạm vi dữ liệu của khu Operations, đặt trên chi nhánh đang chọn của SV04 (useActiveBranch).
 * "Tất cả chi nhánh" chỉ có ở lớp này, chỉ OWNER/ADMIN dùng được (BR-11).
 */
export function OperationsScopeProvider({ children }: { children: ReactNode }) {
  const { state } = useAuth();
  const active = useActiveBranch();
  const user = state.status === 'authenticated' ? state.user : null;
  const canPickAll = !!user && canPickAllBranches(user.role);
  const branches = useMemo(() => user?.branches ?? [], [user]);
  const locked = !canPickAll && branches.length <= 1;
  const [pickedBranch, setPickedBranch] = useState(() => readStored(user?.id) === 'branch');

  const activeBranch = active.state.status === 'active' ? active.state.branch : undefined;
  // OWNER/ADMIN mặc định xem tất cả; vai trò khác luôn theo chi nhánh đang chọn.
  const scope = useMemo<OperationsScope>(
    () =>
      activeBranch && (!canPickAll || pickedBranch)
        ? { kind: 'branch', branch: activeBranch }
        : { kind: 'all' },
    [activeBranch, canPickAll, pickedBranch],
  );

  const pickAll = useCallback(() => {
    if (!canPickAll) return;
    setPickedBranch(false);
    writeStored(user?.id, 'all');
  }, [canPickAll, user?.id]);

  const pickBranch = useCallback(
    (branchId: number) => {
      if (locked || !active.selectBranch(branchId)) return;
      setPickedBranch(true);
      writeStored(user?.id, 'branch');
    },
    [active, locked, user?.id],
  );

  const value = useMemo<OperationsScopeValue>(
    () => ({
      scope,
      branches,
      canPickAll,
      locked,
      pickAll,
      pickBranch,
      branchId: scope.kind === 'branch' ? scope.branch.id : undefined,
    }),
    [scope, branches, canPickAll, locked, pickAll, pickBranch],
  );

  return (
    <OperationsScopeContext.Provider value={value}>{children}</OperationsScopeContext.Provider>
  );
}
