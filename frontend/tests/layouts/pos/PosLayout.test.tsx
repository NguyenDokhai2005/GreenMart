// @vitest-environment jsdom
import { act } from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getCurrentUser, type AuthSession, type Role } from '../../../src/api/client';
import { AuthProvider } from '../../../src/features/auth/AuthProvider';
import { ActiveBranchProvider } from '../../../src/features/auth/ActiveBranchProvider';
import { AppRoutes } from '../../../src/features/auth/AppRoutes';
import { POS_NAVIGATION } from '../../../src/layouts/pos/posNavigation';

const branches = [1, 2].map((id) => ({ id, code: `GM-00${id}`, name: `GreenMart Quận ${id}` }));
function session(role: Role = 'CASHIER', count = 1): AuthSession {
  return {
    accessToken: 'test-access',
    refreshToken: 'test-refresh',
    tokenType: 'Bearer',
    expiresIn: 900,
    user: {
      id: 7,
      username: 'test-user',
      fullName: 'Nguyễn An',
      role,
      branches: branches.slice(0, count),
    },
  };
}
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;

function LocationProbe() {
  const location = useLocation();
  return (
    <output data-testid="location">
      {location.pathname}
      {location.search}
      {location.hash}
    </output>
  );
}

function renderApp(path = '/pos', auth: AuthSession | null = session()) {
  if (auth) sessionStorage.setItem('greenmart.session', JSON.stringify(auth));
  fetchMock.mockResolvedValue(json(auth?.user));
  return render(
    <MemoryRouter initialEntries={[path]}>
      <LocationProbe />
      <AuthProvider>
        <ActiveBranchProvider>
          <AppRoutes />
        </ActiveBranchProvider>
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  fetchMock = vi.fn<typeof fetch>();
  vi.stubGlobal('fetch', fetchMock);
  sessionStorage.clear();
});
afterEach(() => {
  cleanup();
  sessionStorage.clear();
  vi.unstubAllGlobals();
});

describe('Given authenticated POS staff, When opening the workspace', () => {
  it.each(['CASHIER', 'BRANCH_MANAGER'] as const)(
    'Then %s sees their backend identity, branch and five permitted menus',
    async (role) => {
      renderApp('/pos', session(role));
      await screen.findByRole('heading', { name: 'Bán hàng tại quầy' });
      expect(screen.getByRole('banner').textContent).toContain('GM-001 · GreenMart Quận 1');
      expect(screen.getByRole('banner').textContent).toContain('Nguyễn An');
      const nav = screen.getByRole('navigation', { name: 'Điều hướng POS' });
      expect(within(nav).getAllByRole('link')).toHaveLength(5);
      for (const item of POS_NAVIGATION) {
        expect(within(nav).getByRole('link', { name: item.label }).getAttribute('href')).toBe(
          item.path,
        );
      }
      expect(within(nav).queryByText('Đơn online')).toBeNull();
      expect(within(nav).queryByText('Phiếu kho')).toBeNull();
      expect(screen.queryByRole('button', { name: /sửa|thanh toán|duyệt/i })).toBeNull();
      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(String(fetchMock.mock.calls[0]![0])).toBe('/api/v1/auth/me');
    },
  );

  it.each(POS_NAVIGATION)(
    'Then direct URL $path renders the matching page and active menu after reload',
    async (item) => {
      const heading =
        item.segment === 'customers'
          ? 'Tra cứu khách hàng'
          : item.segment === 'products'
            ? 'Sản phẩm'
            : item.title;
      const view = renderApp(`${item.path}?range=today#detail`);
      await screen.findByRole('heading', { name: heading });
      expect(screen.getByRole('link', { name: item.label }).getAttribute('aria-current')).toBe(
        'page',
      );
      expect(
        screen.getAllByRole('link').filter((link) => link.getAttribute('aria-current') === 'page'),
      ).toHaveLength(1);
      view.unmount();
      renderApp(`${item.path}?range=today#detail`);
      await screen.findByRole('heading', { name: heading });
      expect(screen.getByTestId('location').textContent).toBe(`${item.path}?range=today#detail`);
    },
  );

  it('Then mouse navigation keeps header/sidebar mounted and moves focus to the new heading', async () => {
    renderApp();
    await screen.findByRole('heading', { name: 'Bán hàng tại quầy' });
    const header = screen.getByRole('banner');
    const nav = screen.getByRole('navigation');
    fireEvent.click(screen.getByRole('link', { name: 'Hóa đơn' }));
    const title = await screen.findByRole('heading', { name: 'Hóa đơn' });
    expect(screen.getByRole('banner')).toBe(header);
    expect(screen.getByRole('navigation')).toBe(nav);
    expect(document.activeElement).toBe(title);
    expect(screen.getByRole('link', { name: 'Bán hàng' }).getAttribute('aria-current')).toBeNull();
  });

  it('Then a valid URL with a trailing slash still marks the current menu active', async () => {
    renderApp('/pos/receipts/?range=today');
    await screen.findByRole('heading', { name: 'Hóa đơn' });
    expect(screen.getByRole('link', { name: 'Hóa đơn' }).getAttribute('aria-current')).toBe('page');
  });

  it('Then unknown POS URLs show shared 404 inside the shell and return to POS', async () => {
    renderApp('/pos/receipts/not-a-route');
    await screen.findByRole('heading', { name: 'Không tìm thấy trang' });
    expect(screen.getByRole('banner')).toBeTruthy();
    expect(screen.getByRole('navigation')).toBeTruthy();
    expect(screen.queryByRole('link', { current: 'page' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Về trang chủ' }));
    await screen.findByRole('heading', { name: 'Bán hàng tại quầy' });
  });
});

describe('Given POS shortcuts, When using keyboard navigation', () => {
  it('Then Ctrl+Alt+1–5 changes menus, including macOS Option-modified key values', async () => {
    renderApp();
    await screen.findByRole('heading', { name: 'Bán hàng tại quầy' });
    for (const item of [...POS_NAVIGATION].reverse()) {
      const accepted = fireEvent.keyDown(window, {
        code: item.code,
        key: '¡',
        ctrlKey: true,
        altKey: true,
      });
      expect(accepted).toBe(false);
      await screen.findByRole('heading', {
        name:
          item.segment === 'customers'
            ? 'Tra cứu khách hàng'
            : item.segment === 'products'
              ? 'Sản phẩm'
              : item.title,
      });
      expect(screen.getByTestId('location').textContent).toBe(item.path);
    }
  });

  it('Then inputs, editable widgets, IME, repeats, dialogs and other modifiers keep their keystrokes', async () => {
    const view = renderApp();
    await screen.findByRole('heading', { name: 'Bán hàng tại quầy' });
    for (const tag of ['input', 'textarea', 'select']) {
      const field = document.createElement(tag);
      view.container.append(field);
      expect(fireEvent.keyDown(field, { code: 'Digit2', ctrlKey: true, altKey: true })).toBe(true);
      field.remove();
    }
    const editable = document.createElement('div');
    editable.setAttribute('contenteditable', 'true');
    view.container.append(editable);
    fireEvent.keyDown(editable, { code: 'Digit2', ctrlKey: true, altKey: true });
    editable.remove();
    for (const extra of [
      { isComposing: true },
      { repeat: true },
      { metaKey: true },
      { shiftKey: true },
    ]) {
      fireEvent.keyDown(window, { code: 'Digit2', ctrlKey: true, altKey: true, ...extra });
    }
    const dialog = document.createElement('div');
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-modal', 'true');
    view.container.append(dialog);
    fireEvent.keyDown(window, { code: 'Digit2', ctrlKey: true, altKey: true });
    dialog.remove();
    const prevented = new KeyboardEvent('keydown', {
      code: 'Digit2',
      ctrlKey: true,
      altKey: true,
      cancelable: true,
    });
    prevented.preventDefault();
    fireEvent(window, prevented);
    for (const code of ['F2', 'F4', 'Escape', 'Enter']) {
      expect(fireEvent.keyDown(window, { code, key: code })).toBe(true);
    }
    expect(screen.getByTestId('location').textContent).toBe('/pos');
    view.unmount();
    expect(fireEvent.keyDown(window, { code: 'Digit2', ctrlKey: true, altKey: true })).toBe(true);
  });
});

describe('Given the existing authentication and branch guards', () => {
  it('When signing out from the header, Then session/branch are cleared and another account gets only its own identity and branch', async () => {
    const previous = session();
    renderApp('/pos/receipts?range=today#old-account', previous);
    await screen.findByRole('heading', { name: 'Hóa đơn' });
    expect(sessionStorage.getItem('greenmart.active-branch')).not.toBeNull();
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    fireEvent.click(within(screen.getByRole('banner')).getByRole('button', { name: 'Đăng xuất' }));
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/login'));
    expect(sessionStorage.getItem('greenmart.session')).toBeNull();
    expect(sessionStorage.getItem('greenmart.active-branch')).toBeNull();
    expect(screen.queryByRole('navigation')).toBeNull();
    const logoutCall = fetchMock.mock.calls.find(([url]) => String(url) === '/api/v1/auth/logout');
    expect(logoutCall?.[1]?.method).toBe('POST');
    expect(JSON.parse(String(logoutCall?.[1]?.body))).toEqual({
      refreshToken: previous.refreshToken,
    });

    const next: AuthSession = {
      ...session(),
      accessToken: 'next-access',
      refreshToken: 'next-refresh',
      user: {
        ...session().user,
        id: 42,
        username: 'next-user',
        fullName: 'Thu ngân mới',
        branches: [branches[1]!],
      },
    };
    fireEvent.input(document.querySelector('#login-username')!, {
      target: { value: next.user.username },
    });
    fireEvent.input(document.querySelector('#login-password')!, {
      target: { value: 'next-password' },
    });
    fetchMock.mockResolvedValueOnce(json(next));
    fireEvent.submit(document.querySelector('form')!);
    await screen.findByRole('heading', { name: 'Bán hàng tại quầy' });
    expect(screen.getByTestId('location').textContent).toBe('/pos');
    expect(screen.getByRole('banner').textContent).toContain('Thu ngân mới');
    expect(screen.getByRole('banner').textContent).toContain('GM-002');
    expect(screen.getByRole('banner').textContent).not.toContain(previous.user.fullName);
    expect(JSON.parse(sessionStorage.getItem('greenmart.session')!)).toEqual(next);
    expect(JSON.parse(sessionStorage.getItem('greenmart.active-branch')!)).toEqual({
      userId: 42,
      branchId: 2,
    });
  });

  it.each(['resolve', 'reject'] as const)(
    'When an old logout %s finishes after a new login, Then the new session and route stay intact',
    async (outcome) => {
      renderApp();
      await screen.findByRole('heading', { name: 'Bán hàng tại quầy' });
      let complete!: (value: Response) => void;
      let fail!: (reason: Error) => void;
      fetchMock.mockImplementationOnce(
        () =>
          new Promise((resolve, reject) => {
            complete = resolve;
            fail = reject;
          }),
      );
      fireEvent.click(
        within(screen.getByRole('banner')).getByRole('button', { name: 'Đăng xuất' }),
      );
      await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/login'));
      expect(sessionStorage.getItem('greenmart.session')).toBeNull();
      expect(sessionStorage.getItem('greenmart.active-branch')).toBeNull();

      const next = {
        ...session(),
        accessToken: 'new-access',
        refreshToken: 'new-refresh',
        user: { ...session().user, id: 42, fullName: 'Tài khoản mới' },
      };
      fireEvent.input(document.querySelector('#login-username')!, {
        target: { value: 'next-user' },
      });
      fireEvent.input(document.querySelector('#login-password')!, {
        target: { value: 'next-password' },
      });
      fetchMock.mockResolvedValueOnce(json(next));
      fireEvent.submit(document.querySelector('form')!);
      await screen.findByRole('heading', { name: 'Bán hàng tại quầy' });
      fireEvent.click(screen.getByRole('link', { name: 'Tra cứu khách' }));
      await screen.findByRole('heading', { name: 'Tra cứu khách hàng' });
      await act(async () => {
        if (outcome === 'resolve') complete(new Response(null, { status: 204 }));
        else fail(new TypeError('Failed to fetch'));
      });
      expect(screen.getByTestId('location').textContent).toBe('/pos/customers');
      expect(document.querySelector('.pos-layout__header')?.textContent).toContain('Tài khoản mới');
      expect(JSON.parse(sessionStorage.getItem('greenmart.session')!).refreshToken).toBe(
        'new-refresh',
      );
      expect(JSON.parse(sessionStorage.getItem('greenmart.active-branch')!).userId).toBe(42);
    },
  );

  it.each(['ADMIN', 'OWNER', 'STOREKEEPER'] as const)(
    'When %s opens a POS deep link, Then the existing 403 guard denies access',
    async (role) => {
      renderApp('/pos/products', session(role));
      await screen.findByRole('heading', { name: 'Bạn không có quyền truy cập' });
      expect(screen.getByTestId('location').textContent).toBe('/403');
      expect(screen.queryByRole('navigation', { name: 'Điều hướng POS' })).toBeNull();
    },
  );

  it('When CASHIER opens stock documents, Then Operations remains forbidden', async () => {
    renderApp('/operations/stock-documents');
    await screen.findByRole('heading', { name: 'Bạn không có quyền truy cập' });
    expect(screen.getByTestId('location').textContent).toBe('/403');
  });

  it('When multiple branches require selection, Then no POS content appears until a permitted branch is chosen', async () => {
    renderApp('/pos/customers?phone=0901234567#result', session('CASHIER', 2));
    await screen.findByRole('heading', { name: 'Bắt đầu ca làm việc' });
    expect(screen.queryByRole('navigation')).toBeNull();
    fireEvent.click(document.querySelector<HTMLButtonElement>('.branch-page__row-select')!);
    fireEvent.click(document.querySelector<HTMLButtonElement>('.branch-page__actions button')!);
    await screen.findByRole('heading', { name: 'Tra cứu khách hàng' });
    expect(screen.getByTestId('location').textContent).toBe(
      '/pos/customers?phone=0901234567#result',
    );
  });

  it('When refresh fails mid-session, Then Login preserves the complete POS URL and rechecks access after login', async () => {
    renderApp('/pos/receipts?range=today#receipt-42');
    await screen.findByRole('heading', { name: 'Hóa đơn' });
    fetchMock.mockResolvedValueOnce(json({ code: 'UNAUTHORIZED' }, 401));
    fetchMock.mockResolvedValueOnce(json({ code: 'INVALID_REFRESH_TOKEN' }, 401));
    await act(async () => {
      await expect(getCurrentUser()).rejects.toThrow();
    });
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/login'));
    expect(screen.queryByRole('navigation')).toBeNull();
    expect(screen.getByText(/Phiên đăng nhập đã hết hạn/)).toBeTruthy();
    fireEvent.input(document.querySelector('#login-username')!, { target: { value: 'test-user' } });
    fireEvent.input(document.querySelector('#login-password')!, {
      target: { value: 'test-password' },
    });
    fetchMock.mockResolvedValueOnce(json(session()));
    fireEvent.submit(document.querySelector('form')!);
    await screen.findByRole('heading', { name: 'Hóa đơn' });
    expect(screen.getByTestId('location').textContent).toBe('/pos/receipts?range=today#receipt-42');
  });
});
