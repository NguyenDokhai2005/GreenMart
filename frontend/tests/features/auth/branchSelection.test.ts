import { describe, expect, it } from 'vitest';
import type { BranchRef } from '../../../src/api/client';
import { resolveBranchSelection } from '../../../src/features/auth/branchSelection';

const branchOne: BranchRef = { id: 1, code: 'GM-001', name: 'GreenMart Quận 1' };
const branchTwo: BranchRef = { id: 2, code: 'GM-002', name: 'GreenMart Quận 3' };

describe('resolveBranchSelection', () => {
  it('resolves the only backend-assigned branch without requiring a selection', () => {
    expect(resolveBranchSelection([branchOne])).toEqual({
      status: 'single-branch',
      branch: branchOne,
    });
  });

  it('requires an explicit choice when the backend returns multiple branches', () => {
    expect(resolveBranchSelection([branchOne, branchTwo])).toEqual({
      status: 'selection-required',
      branches: [branchOne, branchTwo],
    });
  });

  it('accepts a selected branch only when it is in the backend-assigned list', () => {
    expect(resolveBranchSelection([branchOne, branchTwo], branchTwo.id)).toEqual({
      status: 'selected-branch',
      branch: branchTwo,
    });
  });

  it('requires a new choice when a remembered branch is no longer assigned', () => {
    expect(resolveBranchSelection([branchOne, branchTwo], 99)).toEqual({
      status: 'selection-required',
      branches: [branchOne, branchTwo],
    });
  });

  it('does not invent a default branch when the backend returns none', () => {
    expect(resolveBranchSelection([])).toEqual({ status: 'no-branches' });
    expect(resolveBranchSelection([], branchOne.id)).toEqual({ status: 'no-branches' });
  });

  it('re-resolves a valid branch against a fresh backend list after reload', () => {
    const branchesFromLogin = [branchOne, branchTwo];
    const branchesFromMeAfterReload = [{ ...branchOne }, { ...branchTwo }];

    const beforeReload = resolveBranchSelection(branchesFromLogin, branchTwo.id);
    const afterReload = resolveBranchSelection(branchesFromMeAfterReload, branchTwo.id);

    expect(beforeReload).toEqual({ status: 'selected-branch', branch: branchTwo });
    expect(afterReload).toEqual({ status: 'selected-branch', branch: branchTwo });
  });
});
