import { createContext } from 'react';
import type { BranchRef } from '../../api/client';

export type ActiveBranchState =
  | { status: 'checking' }
  | { status: 'unauthenticated' }
  | { status: 'no-branches' }
  | { status: 'selection-required'; branches: readonly BranchRef[] }
  | { status: 'active'; branch: BranchRef };

export interface BranchContextValue {
  state: ActiveBranchState;
  selectBranch: (branchId: number) => boolean;
  clearBranch: () => void;
}

export const ActiveBranchContext = createContext<BranchContextValue | undefined>(undefined);
