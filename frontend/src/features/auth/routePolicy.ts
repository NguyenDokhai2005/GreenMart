import type { Role } from '../../api/client';
import type { ActiveBranchState } from './ActiveBranchContext';
import { CATALOG_ROOT, CATALOG_ROLES } from '../catalog/catalogPolicy';

export const APP_PATHS = {
  home: '/',
  login: '/login',
  selectBranch: '/select-branch',
  pos: '/pos',
  operations: '/operations',
  stockDocuments: '/operations/stock-documents',
  admin: '/admin',
  forbidden: '/403',
} as const;

export const POS_ROLES: readonly Role[] = ['BRANCH_MANAGER', 'CASHIER'];
export const OPERATIONS_ROLES: readonly Role[] = ['OWNER', 'BRANCH_MANAGER', 'STOREKEEPER'];
export const STOCK_DOCUMENT_ROLES: readonly Role[] = ['OWNER', 'BRANCH_MANAGER', 'STOREKEEPER'];
export const ADMIN_ROLES: readonly Role[] = ['ADMIN'];

export function getWorkspacePath(role: Role): string {
  switch (role) {
    case 'CASHIER':
      return APP_PATHS.pos;
    case 'ADMIN':
      return APP_PATHS.admin;
    case 'OWNER':
    case 'BRANCH_MANAGER':
    case 'STOREKEEPER':
      return APP_PATHS.operations;
  }
}

export type AuthHomeRedirectDecision =
  | { status: 'checking' }
  | { status: 'unauthenticated' }
  | { status: 'select-branch' }
  | { status: 'redirect'; to: string };

export function resolveAuthHomeRedirect(
  role: Role,
  branchStatus: ActiveBranchState['status'],
  requestedPath?: string,
): AuthHomeRedirectDecision {
  if (role === 'ADMIN') {
    return { status: 'redirect', to: requestedPath ?? getWorkspacePath(role) };
  }

  if (branchStatus === 'checking') return { status: 'checking' };
  if (branchStatus === 'unauthenticated') return { status: 'unauthenticated' };
  if (branchStatus !== 'active') return { status: 'select-branch' };

  return { status: 'redirect', to: requestedPath ?? getWorkspacePath(role) };
}

export function getRolesForPath(pathname: string): readonly Role[] | undefined {
  if (pathname === CATALOG_ROOT || pathname.startsWith(`${CATALOG_ROOT}/`)) return CATALOG_ROLES;
  if (
    pathname === APP_PATHS.stockDocuments ||
    pathname.startsWith(`${APP_PATHS.stockDocuments}/`)
  ) {
    return STOCK_DOCUMENT_ROLES;
  }
  if (pathname === APP_PATHS.pos || pathname.startsWith(`${APP_PATHS.pos}/`)) return POS_ROLES;
  if (pathname === APP_PATHS.operations || pathname.startsWith(`${APP_PATHS.operations}/`)) {
    return OPERATIONS_ROLES;
  }
  if (pathname === APP_PATHS.admin || pathname.startsWith(`${APP_PATHS.admin}/`)) {
    return ADMIN_ROLES;
  }
  return undefined;
}

export function canOpenRoutePath(role: Role, pathname: string): boolean {
  const allowedRoles = getRolesForPath(pathname);
  return allowedRoles !== undefined && allowedRoles.includes(role);
}

export function getRequestedPath(state: unknown): string | undefined {
  if (typeof state !== 'object' || state === null || !('from' in state)) return undefined;
  const from = (state as { from?: unknown }).from;
  if (typeof from !== 'object' || from === null || !('pathname' in from)) return undefined;

  const location = from as { pathname?: unknown; search?: unknown; hash?: unknown };
  if (
    typeof location.pathname !== 'string' ||
    !location.pathname.startsWith('/') ||
    location.pathname.startsWith('//')
  ) {
    return undefined;
  }

  const search = typeof location.search === 'string' ? location.search : '';
  const hash = typeof location.hash === 'string' ? location.hash : '';
  return `${location.pathname}${search}${hash}`;
}
