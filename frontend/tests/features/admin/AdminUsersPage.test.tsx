import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AdminUsersPage from '../../../src/features/admin/AdminUsersPage';

const { listBranches, listUsers, getUser, createUser, unlockUser } = vi.hoisted(() => ({
  listBranches: vi.fn(),
  listUsers: vi.fn(),
  getUser: vi.fn(),
  createUser: vi.fn(),
  unlockUser: vi.fn(),
}));
vi.mock('../../../src/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../../src/api/client')>()),
  listBranches,
}));
vi.mock('../../../src/features/admin/usersApi', () => ({
  listUsers,
  getUser,
  createUser,
  unlockUser,
}));
vi.mock('../../../src/features/auth/useAuth', () => ({
  useAuth: () => ({ state: { status: 'authenticated', user: { id: 99, role: 'ADMIN' } } }),
}));

const branch = { id: 1, code: 'GM-001', name: 'Quận 1' };
const user = {
  id: 7,
  username: 'cashier',
  fullName: 'Thu Ngân',
  role: 'CASHIER',
  branches: [branch],
  active: true,
  locked: true,
};
function Location() {
  const location = useLocation();
  return <output data-testid="location">{location.search}</output>;
}
function mount(mode: 'staff' | 'locked', path = '/admin/staff') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AdminUsersPage mode={mode} />
      <Location />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  listBranches.mockReset().mockResolvedValue([branch]);
  listUsers
    .mockReset()
    .mockResolvedValue({ content: [user], page: 0, size: 10, totalPages: 1, totalElements: 1 });
  getUser.mockReset().mockResolvedValue(user);
  createUser.mockReset().mockResolvedValue(user);
  unlockUser.mockReset().mockResolvedValue(undefined);
});
afterEach(() => cleanup());

describe('Admin users', () => {
  it('keeps filters in the URL and sends only contract query fields', async () => {
    mount('staff');
    await screen.findByText('cashier');
    fireEvent.change(screen.getByLabelText('Vai trò'), { target: { value: 'CASHIER' } });
    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toContain('role=CASHIER'),
    );
    expect(listUsers).toHaveBeenLastCalledWith(
      expect.objectContaining({ role: 'CASHIER', page: 0, size: 10 }),
      expect.any(AbortSignal),
    );
  });

  it('rejects staff creation without a branch before sending', async () => {
    mount('staff');
    await screen.findByText('cashier');
    fireEvent.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));
    fireEvent.change(screen.getByLabelText('Tên đăng nhập'), { target: { value: 'new.cashier' } });
    fireEvent.change(screen.getByLabelText('Họ tên'), { target: { value: 'Nhân viên mới' } });
    fireEvent.change(screen.getByLabelText('Mật khẩu ban đầu'), { target: { value: 'Demo1234' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu' }));
    expect(await screen.findByText('Chọn ít nhất một chi nhánh.')).toBeTruthy();
    expect(createUser).not.toHaveBeenCalled();
  });

  it('shows locked users from all API pages and removes one after unlock', async () => {
    listUsers.mockImplementation(async (query: { page: number }) =>
      query.page === 0
        ? { content: [], page: 0, size: 100, totalPages: 2, totalElements: 1 }
        : { content: [user], page: 1, size: 100, totalPages: 2, totalElements: 1 },
    );
    mount('locked', '/admin/locked');
    await screen.findByText('cashier');
    expect(listUsers).toHaveBeenCalledWith({ page: 1, size: 100 }, expect.any(AbortSignal));
    fireEvent.click(screen.getByRole('button', { name: 'Mở khóa' }));
    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận' }));
    await waitFor(() => expect(unlockUser).toHaveBeenCalledWith(7));
  });
});
