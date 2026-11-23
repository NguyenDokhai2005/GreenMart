import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../../src/api/client';
import AdminBranchesPage from '../../../src/features/admin/AdminBranchesPage';
import AdminChannelsPage from '../../../src/features/admin/AdminChannelsPage';

const { listBranches, listSyncLogs, retrySyncLog } = vi.hoisted(() => ({
  listBranches: vi.fn(),
  listSyncLogs: vi.fn(),
  retrySyncLog: vi.fn(),
}));

vi.mock('../../../src/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../src/api/client')>();
  return {
    ...actual,
    listBranches,
    listSyncLogs,
    retrySyncLog,
  };
});

afterEach(cleanup);

beforeEach(() => {
  listBranches.mockReset();
  listSyncLogs.mockReset();
  retrySyncLog.mockReset();
  listBranches.mockResolvedValue([
    { id: 1, code: 'GM-001', name: 'GreenMart Quận 1', address: '123 Lê Lợi', active: true },
  ]);
  listSyncLogs.mockResolvedValue({
    page: 0,
    size: 20,
    totalElements: 3,
    content: [
      {
        id: 11,
        channelCode: 'WEB',
        direction: 'OUT',
        externalId: 'WEB-11',
        status: 'FAILED',
        attempts: 2,
        error: 'Callback timeout',
        createdAt: '2026-11-22T10:00:00+07:00',
      },
      {
        id: 12,
        channelCode: 'MARKET',
        direction: 'IN',
        externalId: 'MARKET-12',
        status: 'SUCCESS',
        attempts: 1,
        createdAt: '2026-11-22T09:00:00+07:00',
      },
    ],
  });
  retrySyncLog.mockResolvedValue(undefined);
});

describe('Admin pages', () => {
  it('shows active branches without create or edit actions', async () => {
    render(
      <MemoryRouter>
        <AdminBranchesPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('GM-001')).toBeTruthy();
    expect(screen.getByText('GreenMart Quận 1')).toBeTruthy();
    expect(screen.getByText('123 Lê Lợi')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /tạo|thêm|sửa/i })).toBeNull();
    expect(listBranches).toHaveBeenCalledWith(true, expect.any(AbortSignal));
  });

  it('keeps channel/status filters and page in the URL', async () => {
    render(
      <MemoryRouter initialEntries={['/admin/channels?channel=WEB&status=FAILED&page=1']}>
        <AdminChannelsPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('WEB-11')).toBeTruthy();
    expect(listSyncLogs).toHaveBeenCalledWith(
      expect.objectContaining({ channelCode: 'WEB', status: 'FAILED', page: 0, size: 20 }),
      expect.any(AbortSignal),
    );
    expect(
      (screen.getByRole('combobox', { name: 'Lọc theo kênh' }) as HTMLSelectElement).value,
    ).toBe('WEB');
    expect(
      (screen.getByRole('combobox', { name: 'Lọc theo trạng thái' }) as HTMLSelectElement).value,
    ).toBe('FAILED');
  });

  it('confirms retry and changes FAILED log to RETRYING', async () => {
    render(
      <MemoryRouter>
        <AdminChannelsPage />
      </MemoryRouter>,
    );

    await screen.findByText('WEB-11');
    fireEvent.click(screen.getByRole('button', { name: 'Gửi lại' }));
    expect(screen.getByRole('dialog', { name: 'Gửi lại callback?' })).toBeTruthy();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Gửi lại' }));

    await waitFor(() => expect(retrySyncLog).toHaveBeenCalledWith(11));
    expect(await waitFor(() => document.querySelector('.admin-status--retrying'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Gửi lại' })).toBeNull();
  });

  it('shows 409 and does not retry the same log again', async () => {
    retrySyncLog.mockRejectedValue(new ApiError('Conflict', 409));
    render(
      <MemoryRouter>
        <AdminChannelsPage />
      </MemoryRouter>,
    );

    await screen.findByText('WEB-11');
    fireEvent.click(screen.getByRole('button', { name: 'Gửi lại' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Gửi lại' }));

    expect(await screen.findByText('Không thể gửi lại')).toBeTruthy();
    expect(retrySyncLog).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: 'Gửi lại' })).toBeNull();
  });
});
