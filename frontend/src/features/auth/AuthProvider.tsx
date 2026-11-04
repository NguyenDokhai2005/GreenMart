import { useCallback, useEffect, useReducer, useRef } from 'react';
import type { ReactNode } from 'react';
import { clearStoredSession, login, logout } from '../../api/client';
import { clearStoredActiveBranch } from './activeBranchStorage';
import { AuthContext } from './AuthContext';
import type { AuthContextValue } from './AuthContext';
import { bootstrapAuthState } from './authBootstrap';
import {
  authStateReducer,
  initialAuthState,
  type AuthState,
  type SettledAuthState,
} from './authState';

interface AuthProviderProps {
  children: ReactNode;
}

export function AuthProvider({ children }: AuthProviderProps) {
  const [store, dispatch] = useReducer(authStateReducer, initialAuthState);
  const mountedRef = useRef(true);
  const transitionIdRef = useRef(initialAuthState.transitionId);
  const lastSettledStateRef = useRef<SettledAuthState>({ status: 'unauthenticated' });
  const bootstrapPromiseRef = useRef<ReturnType<typeof bootstrapAuthState> | null>(null);

  const beginTransition = useCallback(() => {
    const transitionId = ++transitionIdRef.current;
    if (mountedRef.current) dispatch({ type: 'begin', transitionId });
    return transitionId;
  }, []);

  const resolveTransition = useCallback((transitionId: number, state: SettledAuthState) => {
    if (!mountedRef.current || transitionId !== transitionIdRef.current) return;
    lastSettledStateRef.current = state;
    dispatch({ type: 'resolve', transitionId, state });
  }, []);

  const cancelTransition = useCallback((transitionId: number) => {
    if (!mountedRef.current || transitionId !== transitionIdRef.current) return;
    const nextTransitionId = ++transitionIdRef.current;
    dispatch({
      type: 'replace',
      transitionId: nextTransitionId,
      state: lastSettledStateRef.current,
    });
  }, []);

  const replaceState = useCallback((state: AuthState) => {
    const transitionId = ++transitionIdRef.current;
    if (state.status !== 'checking') lastSettledStateRef.current = state;
    if (mountedRef.current) dispatch({ type: 'replace', transitionId, state });
  }, []);

  const isCurrentTransition = useCallback(
    (transitionId: number) => mountedRef.current && transitionId === transitionIdRef.current,
    [],
  );

  const applyBootstrapResult = useCallback(
    async (transitionId: number, force = false) => {
      if (force || !bootstrapPromiseRef.current) {
        bootstrapPromiseRef.current = bootstrapAuthState();
      }

      const result = await bootstrapPromiseRef.current;
      if (!isCurrentTransition(transitionId)) return;

      if (result.clearStoredSession) clearStoredSession();
      resolveTransition(transitionId, result.state);
    },
    [isCurrentTransition, resolveTransition],
  );

  const retrySessionCheck = useCallback(async () => {
    const transitionId = beginTransition();
    await applyBootstrapResult(transitionId, true);
  }, [applyBootstrapResult, beginTransition]);

  const signOut = useCallback(async () => {
    const transitionId = beginTransition();
    clearStoredActiveBranch();
    const pendingLogout = logout();

    // logout() clears the local session synchronously before its network request.
    resolveTransition(transitionId, { status: 'unauthenticated' });
    await pendingLogout;
  }, [beginTransition, resolveTransition]);

  useEffect(() => {
    mountedRef.current = true;
    const transitionId = beginTransition();
    void applyBootstrapResult(transitionId);

    return () => {
      mountedRef.current = false;
      transitionIdRef.current += 1;
    };
  }, [applyBootstrapResult, beginTransition]);

  const signIn = useCallback(
    async (username: string, password: string) => {
      const transitionId = beginTransition();
      try {
        const session = await login(username, password);
        resolveTransition(transitionId, { status: 'authenticated', user: session.user });
        return session.user;
      } catch (error) {
        if (isCurrentTransition(transitionId)) {
          resolveTransition(transitionId, { status: 'unauthenticated' });
        }
        throw error;
      }
    },
    [beginTransition, isCurrentTransition, resolveTransition],
  );

  const value: AuthContextValue = {
    state: store.state,
    transitionId: store.transitionId,
    signIn,
    signOut,
    retrySessionCheck,
    beginTransition,
    resolveTransition,
    cancelTransition,
    replaceState,
    isCurrentTransition,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
