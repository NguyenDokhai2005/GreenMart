import { describe, expect, it } from 'vitest';
import {
  authStateReducer,
  initialAuthState,
  type AuthStateStore,
} from '../../../src/features/auth/authState';
import type { CurrentUser } from '../../../src/api/client';

const currentUser: CurrentUser = {
  id: 7,
  username: 'thungan.gm001',
  fullName: 'Nguyễn Thu Ngân',
  role: 'CASHIER',
  branches: [{ id: 1, code: 'GM-001', name: 'GreenMart Quận 1' }],
};

describe('authStateReducer', () => {
  it('starts in the checking state', () => {
    expect(initialAuthState).toEqual({ state: { status: 'checking' }, transitionId: 0 });
  });

  it('resolves the latest transition to its settled state', () => {
    const checking: AuthStateStore = authStateReducer(initialAuthState, {
      type: 'begin',
      transitionId: 1,
    });
    const settled: AuthStateStore = authStateReducer(checking, {
      type: 'resolve',
      transitionId: 1,
      state: { status: 'authenticated', user: currentUser },
    });

    expect(settled).toEqual({
      state: { status: 'authenticated', user: currentUser },
      transitionId: 1,
    });
  });

  it('ignores a result from an older transition', () => {
    const current = authStateReducer(
      authStateReducer(initialAuthState, { type: 'begin', transitionId: 1 }),
      { type: 'begin', transitionId: 2 },
    );
    const staleResult = authStateReducer(current, {
      type: 'resolve',
      transitionId: 1,
      state: { status: 'unauthenticated' },
    });

    expect(staleResult).toBe(current);
    expect(staleResult.state).toEqual({ status: 'checking' });
  });

  it('Given a session-expired event replaces state, When an older transition resolves, Then expired remains', () => {
    const current = authStateReducer(initialAuthState, { type: 'begin', transitionId: 1 });
    const replaced = authStateReducer(current, {
      type: 'replace',
      transitionId: 2,
      state: { status: 'expired', error: new Error('expired') },
    });
    const staleResult = authStateReducer(replaced, {
      type: 'resolve',
      transitionId: 1,
      state: { status: 'unauthenticated' },
    });

    expect(staleResult).toBe(replaced);
    expect(staleResult.state.status).toBe('expired');
  });

  it('keeps logout unauthenticated when an earlier bootstrap resolves late', () => {
    const checking = authStateReducer(initialAuthState, { type: 'begin', transitionId: 1 });
    const logout = authStateReducer(checking, { type: 'begin', transitionId: 2 });
    const loggedOut = authStateReducer(logout, {
      type: 'resolve',
      transitionId: 2,
      state: { status: 'unauthenticated' },
    });
    const lateBootstrap = authStateReducer(loggedOut, {
      type: 'resolve',
      transitionId: 1,
      state: { status: 'authenticated', user: currentUser },
    });

    expect(lateBootstrap).toBe(loggedOut);
    expect(lateBootstrap.state).toEqual({ status: 'unauthenticated' });
  });
});
