import { createContext } from 'react';
import type { BranchRef } from '../../api/client';

export type OperationsScope = { kind: 'all' } | { kind: 'branch'; branch: BranchRef };

export interface OperationsScopeValue {
  scope: OperationsScope;
  /** Truyền vào query API; undefined = tất cả chi nhánh. */
  branchId: number | undefined;
  /** Danh sách chi nhánh được gán từ backend (CurrentUser.branches). */
  branches: readonly BranchRef[];
  canPickAll: boolean;
  /** true: không có dropdown (chi nhánh cố định). */
  locked: boolean;
  pickAll: () => void;
  pickBranch: (branchId: number) => void;
}

export const OperationsScopeContext = createContext<OperationsScopeValue | undefined>(undefined);
