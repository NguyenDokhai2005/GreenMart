import { createContext } from 'react';
import type { CurrentUser } from '../../api/client';
import type { AuthState, SettledAuthState } from './authState';

export interface AuthContextValue {
  state: AuthState;
  transitionId: number;
  signIn: (username: string, password: string) => Promise<CurrentUser>;
  signOut: () => Promise<void>;
  retrySessionCheck: () => Promise<void>;
  beginTransition: () => number;
  resolveTransition: (transitionId: number, state: SettledAuthState) => void;
  cancelTransition: (transitionId: number) => void;
  replaceState: (state: AuthState) => void;
  isCurrentTransition: (transitionId: number) => boolean;
}

export const AuthContext = createContext<AuthContextValue | undefined>(undefined);
