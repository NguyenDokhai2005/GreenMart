import { describe, expect, it } from 'vitest';
import {
  APP_PATHS,
  canOpenRoutePath,
  getRequestedPath,
  getRolesForPath,
  getWorkspacePath,
  resolveAuthHomeRedirect,
} from '../../../src/features/auth/routePolicy';

describe('auth route policy', () => {
  it('maps each backend role to the selected workspace root', () => {
    expect(getWorkspacePath('CASHIER')).toBe(APP_PATHS.pos);
    expect(getWorkspacePath('BRANCH_MANAGER')).toBe(APP_PATHS.operations);
    expect(getWorkspacePath('STOREKEEPER')).toBe(APP_PATHS.operations);
    expect(getWorkspacePath('OWNER')).toBe(APP_PATHS.operations);
    expect(getWorkspacePath('ADMIN')).toBe(APP_PATHS.admin);
  });

  it('uses the OpenAPI stock-document role list for the protected frontend route', () => {
    expect(getRolesForPath(APP_PATHS.stockDocuments)).toEqual([
      'OWNER',
      'BRANCH_MANAGER',
      'STOREKEEPER',
    ]);
    expect(canOpenRoutePath('CASHIER', APP_PATHS.stockDocuments)).toBe(false);
    expect(canOpenRoutePath('STOREKEEPER', APP_PATHS.stockDocuments)).toBe(true);
  });

  it('allows CASHIER into POS and denies Operations, stock documents, and Admin', () => {
    expect(canOpenRoutePath('CASHIER', APP_PATHS.pos)).toBe(true);
    expect(canOpenRoutePath('CASHIER', APP_PATHS.operations)).toBe(false);
    expect(canOpenRoutePath('CASHIER', APP_PATHS.stockDocuments)).toBe(false);
    expect(canOpenRoutePath('CASHIER', APP_PATHS.admin)).toBe(false);
  });

  it('allows ADMIN only in its declared workspace', () => {
    expect(canOpenRoutePath('ADMIN', APP_PATHS.admin)).toBe(true);
    expect(canOpenRoutePath('ADMIN', APP_PATHS.pos)).toBe(false);
    expect(canOpenRoutePath('ADMIN', APP_PATHS.operations)).toBe(false);
    expect(canOpenRoutePath('ADMIN', APP_PATHS.stockDocuments)).toBe(false);
  });

  it('Given ADMIN has zero, one, or three branches, When home routing resolves, Then it always enters Admin', () => {
    const branchStatuses = ['no-branches', 'active', 'selection-required'] as const;

    for (const branchStatus of branchStatuses) {
      expect(resolveAuthHomeRedirect('ADMIN', branchStatus)).toEqual({
        status: 'redirect',
        to: APP_PATHS.admin,
      });
    }
  });

  it('Given a normal role has one branch, When home routing resolves, Then it enters its workspace', () => {
    expect(resolveAuthHomeRedirect('CASHIER', 'active')).toEqual({
      status: 'redirect',
      to: APP_PATHS.pos,
    });
  });

  it('Given a normal role has multiple branches, When home routing resolves, Then it still requires selection', () => {
    for (const role of ['CASHIER', 'BRANCH_MANAGER', 'STOREKEEPER', 'OWNER'] as const) {
      expect(resolveAuthHomeRedirect(role, 'selection-required')).toEqual({
        status: 'select-branch',
      });
    }
  });

  it('Given ADMIN requested a permitted workspace path, When home routing resolves, Then it preserves that destination', () => {
    expect(resolveAuthHomeRedirect('ADMIN', 'selection-required', '/admin/users?page=2')).toEqual({
      status: 'redirect',
      to: '/admin/users?page=2',
    });
  });

  it('does not grant ADMIN access outside explicitly permitted routes', () => {
    expect(canOpenRoutePath('ADMIN', APP_PATHS.stockDocuments)).toBe(false);
    expect(canOpenRoutePath('ADMIN', APP_PATHS.admin)).toBe(true);
  });

  it('does not grant an allowlist when the route has no declared policy', () => {
    expect(canOpenRoutePath('ADMIN', '/unregistered')).toBe(false);
  });

  it('preserves same-origin requested paths and rejects protocol-relative paths', () => {
    expect(
      getRequestedPath({
        from: { pathname: APP_PATHS.stockDocuments, search: '?page=2', hash: '#list' },
      }),
    ).toBe('/operations/stock-documents?page=2#list');
    expect(getRequestedPath({ from: { pathname: '//attacker.example' } })).toBeUndefined();
    expect(getRequestedPath(null)).toBeUndefined();
  });

  it('Given a cashier returns from login to POS, When the requested location is restored, Then path, search, hash, and RBAC are preserved', () => {
    const location = { pathname: APP_PATHS.pos, search: '?tab=cart', hash: '#items' };
    const restoredPath = getRequestedPath({ from: location });

    expect(restoredPath).toBe('/pos?tab=cart#items');
    expect(canOpenRoutePath('CASHIER', location.pathname)).toBe(true);
    expect(canOpenRoutePath('STOREKEEPER', location.pathname)).toBe(false);
  });
});
