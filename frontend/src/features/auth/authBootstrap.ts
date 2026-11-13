import { ApiError, getCurrentUser, readStoredSession, SessionExpiredError } from '../../api/client';
import type { AuthState } from './authState';

export interface AuthBootstrapResult {
  state: Exclude<AuthState, { status: 'checking' }>;
  clearStoredSession: boolean;
}

function isTemporaryFailure(error: unknown): boolean {
  if (error instanceof TypeError) return true;
  return (
    error instanceof ApiError &&
    (error.status === 408 || error.status === 504 || error.status >= 500)
  );
}

function isTemporarySessionFailure(error: unknown): boolean {
  return error instanceof SessionExpiredError && isTemporaryFailure(error.cause);
}

export async function bootstrapAuthState(): Promise<AuthBootstrapResult> {
  try {
    const session = readStoredSession();
    if (!session) {
      return { state: { status: 'unauthenticated' }, clearStoredSession: false };
    }

    const user = await getCurrentUser();
    return { state: { status: 'authenticated', user }, clearStoredSession: false };
  } catch (error) {
    if (isTemporarySessionFailure(error) || isTemporaryFailure(error)) {
      return { state: { status: 'error', error }, clearStoredSession: false };
    }

    if (
      error instanceof SessionExpiredError ||
      (error instanceof ApiError && error.status === 401)
    ) {
      return { state: { status: 'expired', error }, clearStoredSession: true };
    }

    return { state: { status: 'error', error }, clearStoredSession: false };
  }
}
