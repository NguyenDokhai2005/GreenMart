import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import OrdersPage from '../../../src/features/orders/OrdersPage';

const { listOrders, scopeRef, authRef } = vi.hoisted(() => ({
  listOrders: vi.fn(),
  scopeRef: { current: {} as Record<string, unknown> },
  authRef: { current: {} as Record<string, unknown> },
}));

vi.mock('../../../src/api/client', () => ({ listOrders }));
vi.mock('../../../src/layouts/operations/useOperationsScope', () => ({
  useOperationsScope: () => scopeRef.current,
}));
vi.mock('../../../src/features/auth/useAuth', () => ({ useAuth: () => authRef.current }));

const branch = { id: 1, code: 'GM-001', name: 'GreenMart Quận 1' };
const rows = [
  {
    id: 10,
    code: 'WEB-GM001-20261105-0008',
    externalOrderId: 'WEB-778812',
    channel: 'WEB',
    branch,
    customer: { id: 4, name: 'Lê Văn Hùng', phone: '0912345678' },
    status: 'RESERVED',
    itemCount: 3,
    total: 200700,
    createdAt: '2026-11-05T10:15:00+07:00',
  },
  {
    id: 11,
    code: 'WEB-GM001-20261105-0007',
    externalOrderId: null,
    channel: 'WEB',
    branch,
    customer: null,
    status: 'CONFIRMED',
    itemCount: 2,
    total: 154000,
    createdAt: '2026-11-05T09:52:00+07:00',
  },
  {
    id: 12,
    code: 'POS-GM001-20261104-0001',
    externalOrderId: null,
    channel: 'POS',
    branch,
    customer: null,
    status: 'COMPLETED',
    itemCount: 1,
    total: 38000,
    createdAt: '2026-11-04T16:48:00+07:00',
  },
] as const;

function Location() {
  const location = useLocation();
  return (
    <output data-testid="location">
      {location.pathname}
      {location.search}
    </output>
  );
}

function mount(url = '/operations/orders') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <OrdersPage />
      <Location />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  listOrders.mockReset();
  listOrders.mockImplementation(
    async (query: { status?: string[]; channel?: string; q?: string; page: number }) => {
      const filtered = rows.filter(
        (row) =>
          (!query.status || query.status.includes(row.status)) &&
          (!query.channel || query.channel === row.channel) &&
          (!query.q ||
            `${row.code} ${row.externalOrderId ?? ''} ${row.customer?.phone ?? ''}`.includes(
              query.q,
            )),
      );
      return {
        content: filtered,
        page: query.page,
        size: 8,
        totalElements: filtered.length,
        totalPages: filtered.length ? 1 : 0,
      };
    },
  );
  scopeRef.current = {
    scope: { kind: 'branch', branch },
    branchId: 1,
    branches: [branch],
    canPickAll: false,
  };
  authRef.current = { state: { status: 'authenticated', user: { role: 'BRANCH_MANAGER' } } };
});
afterEach(cleanup);

describe('OrdersPage #80', () => {
  it('loads repeated statuses and dates from URL, including after remount', async () => {
    const url = '/operations/orders?status=RESERVED&status=CONFIRMED&from=2026-11-01&to=2026-11-05';
    const view = mount(url);
    expect(await screen.findByText('WEB-GM001-20261105-0008')).toBeTruthy();
    expect(screen.getByText('WEB-GM001-20261105-0007')).toBeTruthy();
    expect(screen.queryByText('POS-GM001-20261104-0001')).toBeNull();
    expect(listOrders.mock.calls.at(-1)?.[0]).toMatchObject({
      branchId: 1,
      status: ['RESERVED', 'CONFIRMED'],
      from: '2026-11-01',
      to: '2026-11-05',
      page: 0,
      size: 8,
    });
    view.unmount();
    mount(url);
    await waitFor(() => expect(listOrders).toHaveBeenCalledTimes(2));
    expect((screen.getByLabelText('Từ ngày') as HTMLInputElement).value).toBe('2026-11-01');
  });

  it('writes multiple statuses and date range to URL when applying filters', async () => {
    mount();
    await screen.findByText('WEB-GM001-20261105-0008');
    fireEvent.click(screen.getByText('Tất cả trạng thái'));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Đang giữ hàng' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Đã xác nhận' }));
    fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-11-01' } });
    fireEvent.change(screen.getByLabelText('Đến ngày'), { target: { value: '2026-11-05' } });
    fireEvent.click(screen.getByRole('button', { name: 'Áp dụng bộ lọc' }));
    await waitFor(() => {
      const search = new URLSearchParams(screen.getByTestId('location').textContent?.split('?')[1]);
      expect(search.getAll('status')).toEqual(['RESERVED', 'CONFIRMED']);
      expect(search.get('from')).toBe('2026-11-01');
      expect(search.get('to')).toBe('2026-11-05');
    });
    expect(listOrders.mock.calls.at(-1)?.[0].status).toEqual(['RESERVED', 'CONFIRMED']);
  });

  it('shows filtered empty state only after API returns and clears filters', async () => {
    mount('/operations/orders?q=missing');
    expect(await screen.findByText('Không có kết quả phù hợp')).toBeTruthy();
    fireEvent.click(screen.getAllByRole('button', { name: 'Xóa bộ lọc' }).at(-1)!);
    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe('/operations/orders'),
    );
    expect(await screen.findByText('WEB-GM001-20261105-0008')).toBeTruthy();
  });

  it('uses an allowed owner branch from URL and ignores an unknown branch ID', async () => {
    const branch2 = { id: 2, code: 'GM-002', name: 'GreenMart Bình Thạnh' };
    scopeRef.current = {
      scope: { kind: 'all' },
      branchId: undefined,
      branches: [branch, branch2],
      canPickAll: true,
    };
    authRef.current = { state: { status: 'authenticated', user: { role: 'OWNER' } } };
    const view = mount('/operations/orders?branchId=2');
    await waitFor(() => expect(listOrders.mock.calls.at(-1)?.[0].branchId).toBe(2));
    expect(screen.getByText(/GM-002 - GreenMart Bình Thạnh · Chủ sở hữu/)).toBeTruthy();
    view.unmount();
    mount('/operations/orders?branchId=999');
    await waitFor(() => expect(listOrders.mock.calls.at(-1)?.[0].branchId).toBeUndefined());
  });

  it('places a 400 validation detail below its filter field', async () => {
    listOrders.mockRejectedValueOnce({
      status: 400,
      code: 'VALIDATION_ERROR',
      message: 'Khoảng ngày không hợp lệ',
      details: [{ field: 'from', message: 'Ngày bắt đầu không hợp lệ' }],
    });
    mount('/operations/orders?from=2026-11-05&to=2026-11-01');
    expect(await screen.findByText('Ngày bắt đầu không hợp lệ')).toBeTruthy();
    expect(screen.getByLabelText('Từ ngày').getAttribute('aria-invalid')).toBe('true');
    expect(screen.queryByRole('button', { name: 'Thử lại' })).toBeNull();
  });
});
