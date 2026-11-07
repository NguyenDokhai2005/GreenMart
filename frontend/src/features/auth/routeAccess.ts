import type { Role } from '../../api/client';
import type { AuthState } from './authState';

export type RouteAccessDecision = 'allow' | 'checking' | 'unauthenticated' | 'forbidden' | 'error';

/** Evaluates authentication and an OpenAPI-derived role allowlist without depending on routing. */
export function evaluateRouteAccess(
  authState: AuthState,
  allowedRoles?: readonly Role[],
): RouteAccessDecision {
  if (authState.status === 'checking') return 'checking';
  if (authState.status === 'unauthenticated' || authState.status === 'expired') {
    return 'unauthenticated';
  }
  if (authState.status === 'error') return 'error';

  if (allowedRoles === undefined || allowedRoles.includes(authState.user.role)) return 'allow';
  return 'forbidden';
}
