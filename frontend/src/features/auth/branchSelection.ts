import type { BranchRef } from '../../api/client';

export type BranchSelectionResolution =
  | { status: 'no-branches' }
  | { status: 'single-branch'; branch: BranchRef }
  | { status: 'selection-required'; branches: readonly BranchRef[] }
  | { status: 'selected-branch'; branch: BranchRef };

/** Resolves a branch only from the backend-assigned BranchRef list. */
export function resolveBranchSelection(
  branches: readonly BranchRef[],
  selectedBranchId?: number,
): BranchSelectionResolution {
  if (branches.length === 0) return { status: 'no-branches' };

  if (selectedBranchId !== undefined) {
    const selectedBranch = branches.find((branch) => branch.id === selectedBranchId);
    if (selectedBranch) return { status: 'selected-branch', branch: selectedBranch };
  }

  if (branches.length === 1) return { status: 'single-branch', branch: branches[0]! };
  return { status: 'selection-required', branches };
}
