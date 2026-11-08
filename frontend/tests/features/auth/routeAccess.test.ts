import { describe, expect, it } from 'vitest';
import type { CurrentUser, Role } from '../../../src/api/client';
import type { AuthState } from '../../../src/features/auth/authState';
import { evaluateRouteAccess } from '../../../src/features/auth/routeAccess';

const user = (role: Role): CurrentUser => ({
  id: 7,
  username: 'test-user',
  fullName: 'Test User',
  role,
  branches: [],
});

describe('evaluateRouteAccess', () => {
  it('blocks a protected route until authentication is established', () => {
    expect(evaluateRouteAccess({ status: 'unauthenticated' }, ['STOREKEEPER'])).toBe(
      'unauthenticated',
    );
  });

  it('allows a backend role present in the route allowlist', () => {
    const state: AuthState = { status: 'authenticated', user: user('STOREKEEPER') };

    expect(evaluateRouteAccess(state, ['OWNER', 'BRANCH_MANAGER', 'STOREKEEPER'])).toBe('allow');
  });

  it('denies direct access when the authenticated role is not allowed', () => {
    const state: AuthState = { status: 'authenticated', user: user('CASHIER') };

    expect(evaluateRouteAccess(state, ['STOREKEEPER'])).toBe('forbidden');
  });

  it('denies CASHIER access to the stock-document list role allowlist', () => {
    const stockDocumentListRoles: readonly Role[] = ['OWNER', 'BRANCH_MANAGER', 'STOREKEEPER'];
    const state: AuthState = { status: 'authenticated', user: user('CASHIER') };

    expect(evaluateRouteAccess(state, stockDocumentListRoles)).toBe('forbidden');
  });

  it('does not allow protected content while the session is being checked', () => {
    const decision = evaluateRouteAccess({ status: 'checking' }, ['CASHIER']);

    expect(decision).toBe('checking');
    expect(decision).not.toBe('allow');
  });

  it('does not grant ADMIN access unless the allowlist includes ADMIN', () => {
    const state: AuthState = { status: 'authenticated', user: user('ADMIN') };

    expect(evaluateRouteAccess(state, ['OWNER'])).toBe('forbidden');
  });

  it('allows any authenticated role only when no x-roles restriction exists', () => {
    const state: AuthState = { status: 'authenticated', user: user('CASHIER') };

    expect(evaluateRouteAccess(state)).toBe('allow');
  });
});
