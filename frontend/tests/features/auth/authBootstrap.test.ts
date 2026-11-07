import { beforeEach, describe, expect, it, vi } from 'vitest';

const { getCurrentUserMock, readStoredSessionMock } = vi.hoisted(() => ({
  getCurrentUserMock: vi.fn(),
  readStoredSessionMock: vi.fn(),
}));

vi.mock('../../../src/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../src/api/client')>();
  return {
    ...actual,
    getCurrentUser: getCurrentUserMock,
    readStoredSession: readStoredSessionMock,
  };
});

import { ApiError, SessionExpiredError } from '../../../src/api/client';
import type { AuthSession, CurrentUser } from '../../../src/api/client';
import { bootstrapAuthState } from '../../../src/features/auth/authBootstrap';

const session: AuthSession = {
  accessToken: 'access-test',
  refreshToken: 'refresh-test',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: {
    id: 7,
    username: 'cached-user',
    fullName: 'Cached User',
    role: 'CASHIER',
    branches: [],
  },
};

const currentUser: CurrentUser = {
  id: 7,
  username: 'server-user',
  fullName: 'Server User',
  role: 'OWNER',
  branches: [{ id: 1, code: 'GM-001', name: 'GreenMart Quận 1' }],
};

describe('bootstrapAuthState', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    readStoredSessionMock.mockReturnValue(session);
  });

  it('becomes unauthenticated when there is no stored session', async () => {
    readStoredSessionMock.mockReturnValue(null);

    await expect(bootstrapAuthState()).resolves.toEqual({
      state: { status: 'unauthenticated' },
      clearStoredSession: false,
    });
    expect(getCurrentUserMock).not.toHaveBeenCalled();
  });

  it('uses the current user returned by the backend after session validation', async () => {
    getCurrentUserMock.mockResolvedValue(currentUser);

    await expect(bootstrapAuthState()).resolves.toEqual({
      state: { status: 'authenticated', user: currentUser },
      clearStoredSession: false,
    });
    expect(readStoredSessionMock).toHaveBeenCalledOnce();
    expect(getCurrentUserMock).toHaveBeenCalledOnce();
  });

  it('marks malformed stored sessions expired and requests their removal', async () => {
    readStoredSessionMock.mockImplementation(() => {
      throw new SessionExpiredError(new SyntaxError('invalid stored session'));
    });

    const result = await bootstrapAuthState();
    expect(result.state.status).toBe('expired');
    expect(result.clearStoredSession).toBe(true);
    expect(getCurrentUserMock).not.toHaveBeenCalled();
  });

  it('keeps a transient /auth/me failure in the error state', async () => {
    getCurrentUserMock.mockRejectedValue(new TypeError('offline'));

    const result = await bootstrapAuthState();
    expect(result.state.status).toBe('error');
    expect(result.clearStoredSession).toBe(false);
  });

  it('does not classify a refresh network failure as token expiry', async () => {
    getCurrentUserMock.mockRejectedValue(new TypeError('offline'));

    const result = await bootstrapAuthState();
    expect(result.state.status).toBe('error');
    expect(result.clearStoredSession).toBe(false);
  });

  it('marks an explicitly rejected refresh session expired', async () => {
    const invalidRefresh = new ApiError('Invalid refresh token', 401, 'INVALID_REFRESH_TOKEN');
    getCurrentUserMock.mockRejectedValue(new SessionExpiredError(invalidRefresh));

    const result = await bootstrapAuthState();
    expect(result.state.status).toBe('expired');
    expect(result.clearStoredSession).toBe(true);
  });

  it('keeps a server failure in the error state', async () => {
    getCurrentUserMock.mockRejectedValue(new SessionExpiredError(new ApiError('Unavailable', 503)));

    const result = await bootstrapAuthState();
    expect(result.state.status).toBe('error');
    expect(result.clearStoredSession).toBe(false);
  });
});
