// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CurrentUser } from '../../../src/api/client';
import { UserMenu } from '../../../src/layouts/operations/UserMenu';

const { navigate, signOut } = vi.hoisted(() => ({
  navigate: vi.fn(),
  signOut: vi.fn<() => Promise<void>>(),
}));

vi.mock('react-router', () => ({ useNavigate: () => navigate }));
vi.mock('../../../src/features/auth/useAuth', () => ({ useAuth: () => ({ signOut }) }));

const user: CurrentUser = {
  id: 1,
  username: 'owner',
  fullName: 'Chủ chuỗi GreenMart',
  role: 'OWNER',
  branches: [],
};

beforeEach(() => {
  navigate.mockClear();
  signOut.mockReset();
  signOut.mockResolvedValue(undefined);
});

afterEach(cleanup);

const openMenu = () => fireEvent.click(screen.getByRole('button', { name: /Tài khoản/ }));

describe('UserMenu (đăng xuất)', () => {
  it('hiện tên và vai trò; menu đóng cho tới khi bấm nút tài khoản', () => {
    render(<UserMenu user={user} />);

    expect(screen.getByText('Chủ sở hữu')).toBeTruthy();
    expect(screen.getByText('CC')).toBeTruthy();
    expect(screen.queryByRole('menuitem')).toBeNull();

    openMenu();
    expect(screen.getByRole('menuitem', { name: /Đăng xuất/ })).toBeTruthy();
  });

  it('bấm Đăng xuất: gọi signOut rồi về trang đăng nhập', async () => {
    render(<UserMenu user={user} />);
    openMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: /Đăng xuất/ }));

    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/login', { replace: true }));
    expect(signOut).toHaveBeenCalledTimes(1);
  });

  it('signOut lỗi (mất mạng) vẫn về trang đăng nhập', async () => {
    signOut.mockRejectedValue(new Error('network'));
    render(<UserMenu user={user} />);
    openMenu();
    fireEvent.click(screen.getByRole('menuitem', { name: /Đăng xuất/ }));

    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/login', { replace: true }));
  });

  it('nhấn Esc đóng menu', () => {
    render(<UserMenu user={user} />);
    openMenu();
    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.queryByRole('menuitem')).toBeNull();
  });
});
