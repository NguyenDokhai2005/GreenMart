import type { CurrentUser } from '../../api/client';

export type AuthState =
  | { status: 'checking' }
  | { status: 'unauthenticated' }
  | { status: 'authenticated'; user: CurrentUser }
  | { status: 'expired'; error?: unknown }
  | { status: 'error'; error: unknown };

export type SettledAuthState = Exclude<AuthState, { status: 'checking' }>;

export interface AuthStateStore {
  state: AuthState;
  transitionId: number;
}

export type AuthStateAction =
  | { type: 'begin'; transitionId: number }
  | { type: 'resolve'; transitionId: number; state: SettledAuthState }
  | { type: 'replace'; transitionId: number; state: AuthState };

export const initialAuthState: AuthStateStore = {
  state: { status: 'checking' },
  transitionId: 0,
};

export function authStateReducer(current: AuthStateStore, action: AuthStateAction): AuthStateStore {
  if (action.type === 'begin') {
    if (action.transitionId <= current.transitionId) return current;
    return { state: { status: 'checking' }, transitionId: action.transitionId };
  }

  if (action.type === 'resolve') {
    if (action.transitionId !== current.transitionId) return current;
    return { state: action.state, transitionId: current.transitionId };
  }

  if (action.transitionId <= current.transitionId) return current;
  return { state: action.state, transitionId: action.transitionId };
}
