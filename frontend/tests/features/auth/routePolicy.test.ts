import { describe, expect, it } from 'vitest';
import {
  APP_PATHS,
  canOpenRoutePath,
  getRequestedPath,
  getRolesForPath,
  getWorkspacePath,
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
});
