import { act, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, expect, it, vi } from 'vitest';
import { getCurrentUser, login, logout, type AuthSession } from '../../../src/api/client';
import { MemoryRouter } from 'react-router';
import LoginPage from '../../../src/features/auth/LoginPage';
import { AuthProvider } from '../../../src/features/auth/AuthProvider';
import { useAuth } from '../../../src/features/auth/useAuth';

afterEach(() => {
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

it('shows server revocation failure even if a caller handles the logout rejection', async () => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  const json = (value: unknown, status = 200) =>
    new Response(JSON.stringify(value), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  const session: AuthSession = {
    accessToken: 'access',
    refreshToken: 'refresh',
    tokenType: 'Bearer',
    expiresIn: 900,
    user: { id: 7, username: 'cashier', fullName: 'Cashier', role: 'CASHIER', branches: [] },
  };
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValueOnce(json(session));
  vi.stubGlobal('fetch', fetchMock);
  await login('cashier', 'test-only');
  fetchMock.mockResolvedValueOnce(json(session.user));
  const container = document.createElement('div');
  document.body.append(container);
  const root = createRoot(container);
  try {
    await act(async () => {
      root.render(
        <MemoryRouter>
          <AuthProvider>
            <LoginPage />
          </AuthProvider>
        </MemoryRouter>,
      );
    });
    fetchMock.mockResolvedValueOnce(json({ code: 'AUTH_UNAVAILABLE' }, 503));
    await act(async () => {
      await logout().catch(() => undefined);
    });
    expect(container.textContent).toContain('Máy chủ chưa xác nhận thu hồi phiên.');
    expect(sessionStorage.getItem('greenmart.session')).toBeNull();
  } finally {
    await act(async () => root.unmount());
    container.remove();
  }
});

it('updates the authenticated role and branches when a refresh replaces the user profile', async () => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  const session: AuthSession = {
    accessToken: 'old-access',
    refreshToken: 'old-refresh',
    tokenType: 'Bearer',
    expiresIn: 900,
    user: { id: 7, username: 'cashier', fullName: 'Cashier', role: 'CASHIER', branches: [] },
  };
  const changed: AuthSession = {
    ...session,
    accessToken: 'new-access',
    refreshToken: 'new-refresh',
    user: {
      ...session.user,
      role: 'STOREKEEPER',
      branches: [{ id: 2, code: 'B2', name: 'Branch 2' }],
    },
  };
  const json = (value: unknown, status = 200) =>
    new Response(JSON.stringify(value), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  sessionStorage.setItem('greenmart.session', JSON.stringify(session));
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValueOnce(json(session.user));
  vi.stubGlobal('fetch', fetchMock);
  const container = document.createElement('div');
  document.body.append(container);
  const root = createRoot(container);
  let profile = '';
  function Observer() {
    const { state } = useAuth();
    useEffect(() => {
      if (state.status === 'authenticated')
        profile = `${state.user.role}:${state.user.branches[0]?.id ?? ''}`;
    }, [state]);
    return null;
  }
  try {
    await act(async () => {
      root.render(
        <AuthProvider>
          <Observer />
        </AuthProvider>,
      );
    });
    expect(profile).toBe('CASHIER:');
    fetchMock.mockResolvedValueOnce(json({ code: 'UNAUTHORIZED' }, 401));
    fetchMock.mockResolvedValueOnce(json(changed));
    fetchMock.mockResolvedValueOnce(json(changed.user));
    await act(async () => {
      await getCurrentUser();
    });
    expect(profile).toBe('STOREKEEPER:2');
  } finally {
    await act(async () => root.unmount());
    container.remove();
  }
});
