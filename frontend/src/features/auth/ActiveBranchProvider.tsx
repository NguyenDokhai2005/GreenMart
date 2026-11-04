import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import {
  clearStoredActiveBranch,
  readStoredActiveBranchId,
  writeStoredActiveBranch,
} from './activeBranchStorage';
import { ActiveBranchContext } from './ActiveBranchContext';
import { resolveBranchSelection } from './branchSelection';
import { useAuth } from './useAuth';
import type { ActiveBranchState } from './ActiveBranchContext';

interface ActiveBranchProviderProps {
  children: ReactNode;
}

export function ActiveBranchProvider({ children }: ActiveBranchProviderProps) {
  const { state: authState, transitionId } = useAuth();
  const user = authState.status === 'authenticated' ? authState.user : null;
  const [selection, setSelection] = useState<{
    transitionId: number;
    userId: number;
    branchId?: number;
  } | null>(null);

  useEffect(() => {
    if (authState.status === 'checking' || authState.status === 'error') return;

    if (authState.status !== 'authenticated') {
      clearStoredActiveBranch();
      return;
    }

    const storedBranchId = readStoredActiveBranchId(authState.user.id);
    const resolved = resolveBranchSelection(authState.user.branches, storedBranchId);
    if (resolved.status === 'single-branch' || resolved.status === 'selected-branch') {
      writeStoredActiveBranch(authState.user.id, resolved.branch.id);
      return;
    }

    if (storedBranchId !== undefined) clearStoredActiveBranch();
  }, [authState, transitionId]);

  const selectBranch = useCallback(
    (branchId: number) => {
      if (!user) return false;
      const resolved = resolveBranchSelection(user.branches, branchId);
      if (resolved.status !== 'selected-branch') return false;

      writeStoredActiveBranch(user.id, resolved.branch.id);
      setSelection({ transitionId, userId: user.id, branchId: resolved.branch.id });
      return true;
    },
    [transitionId, user],
  );

  const clearBranch = useCallback(() => {
    clearStoredActiveBranch();
    if (user) setSelection({ transitionId, userId: user.id });
  }, [transitionId, user]);

  const branchState = useMemo<ActiveBranchState>(() => {
    if (authState.status === 'checking' || authState.status === 'error')
      return { status: 'checking' };
    if (authState.status !== 'authenticated' || !user) return { status: 'unauthenticated' };
    const selectedBranchId =
      selection?.transitionId === transitionId && selection.userId === user.id
        ? selection.branchId
        : readStoredActiveBranchId(user.id);
    const resolved = resolveBranchSelection(user.branches, selectedBranchId);
    if (resolved.status === 'no-branches') return { status: 'no-branches' };
    if (resolved.status === 'selection-required') {
      return { status: 'selection-required', branches: resolved.branches };
    }
    return { status: 'active', branch: resolved.branch };
  }, [authState.status, selection, transitionId, user]);

  const value = useMemo(
    () => ({ state: branchState, selectBranch, clearBranch }),
    [branchState, clearBranch, selectBranch],
  );

  return <ActiveBranchContext.Provider value={value}>{children}</ActiveBranchContext.Provider>;
}
