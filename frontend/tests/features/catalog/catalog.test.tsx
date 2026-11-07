// @vitest-environment jsdom
import { act } from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../../../src/features/auth/AppRoutes';
import { AuthProvider } from '../../../src/features/auth/AuthProvider';
import { ActiveBranchProvider } from '../../../src/features/auth/ActiveBranchProvider';
import { canOpenRoutePath } from '../../../src/features/auth/routePolicy';
import { validateCatalogForm } from '../../../src/features/catalog/catalogForm';
import type { AuthSession, Role } from '../../../src/api/client';

const product = {
  id: 1,
  sku: 'GM001',
  name: 'Gạo thơm 5 kg',
  barcode: '8930000000019',
  unit: 'túi',
  categoryId: 1,
  categoryName: 'Lương thực',
  salePrice: 150000,
  costPrice: 99999,
  active: true,
};
const customer = {
  id: 1,
  name: 'Khách mẫu 01',
  phone: '0900000001',
  email: 'khach01@example.com',
  createdAt: '2026-11-07T00:00:00Z',
};
const page = <T,>(items: T[], index = 0, total = items.length) => ({
  content: items,
  page: index,
  size: 3,
  totalElements: total,
  totalPages: Math.ceil(total / 3),
});
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const makeSession = (role: Role = 'CASHIER'): AuthSession => ({
  accessToken: 'catalog-access',
  refreshToken: 'catalog-refresh',
  expiresIn: 900,
  tokenType: 'Bearer',
  user: {
    id: 7,
    username: 'catalog-test',
    fullName: 'Nguyễn An',
    role,
    branches: [{ id: 1, code: 'GM-001', name: 'GreenMart Quận 1' }],
  },
});
let currentSession: AuthSession;
let fetchMock: ReturnType<typeof vi.fn<typeof fetch>>;
let handler: (url: URL, init?: RequestInit) => Promise<Response>;
function Location() {
  const l = useLocation();
  return (
    <output data-testid="location">
      {l.pathname}
      {l.search}
      {l.hash}
    </output>
  );
}
async function mount(path = '/catalog/products', role: Role = 'CASHIER') {
  currentSession = makeSession(role);
  sessionStorage.setItem('greenmart.session', JSON.stringify(currentSession));
  await act(async () => {
    render(
      <MemoryRouter initialEntries={[path]}>
        <Location />
        <AuthProvider>
          <ActiveBranchProvider>
            <AppRoutes />
          </ActiveBranchProvider>
        </AuthProvider>
      </MemoryRouter>,
    );
  });
}
const urlOf = (value: RequestInfo | URL) => new URL(String(value), 'http://localhost');
beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  sessionStorage.clear();
  currentSession = makeSession();
  handler = async (url) => {
    if (url.pathname.endsWith('/auth/me')) return json(currentSession.user);
    if (url.pathname.endsWith('/categories')) return json([{ id: 1, name: 'Lương thực' }]);
    if (url.pathname.endsWith('/products')) return json(page([product], 0, 6));
    if (url.pathname.endsWith('/customers')) return json(page([customer]));
    throw new Error(`Unexpected URL: ${url.pathname}`);
  };
  fetchMock = vi.fn<typeof fetch>().mockImplementation((url, init) => handler(urlOf(url), init));
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => {
  cleanup();
  sessionStorage.clear();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('catalog #72', () => {
  it('Given CASHIER, When products load, Then hides cost and all product writes even if API supplies cost', async () => {
    await mount();
    expect(await screen.findByText(product.name)).toBeTruthy();
    expect(screen.queryByText('Giá vốn')).toBeNull();
    expect(document.body.textContent).not.toContain('99999');
    expect(screen.queryByRole('button', { name: /Sửa|Thêm sản phẩm/ })).toBeNull();
    expect(screen.getByText('150.000 ₫')).toBeTruthy();
  });
  it.each(['ADMIN', 'BRANCH_MANAGER', 'STOREKEEPER'] as Role[])(
    'Given %s, Then product mutation remains hidden',
    async (role) => {
      await mount('/catalog/products', role);
      await screen.findByText(product.name);
      expect(screen.queryByRole('button', { name: /Sửa|Thêm sản phẩm/ })).toBeNull();
    },
  );
  it.each(['Gạo', 'GM001', '8930000000019'])(
    'Given search %s, When entered, Then sends q and resets page',
    async (q) => {
      await mount('/catalog/products?page=1');
      await screen.findByText(product.name);
      fireEvent.change(screen.getByLabelText('Tìm sản phẩm'), { target: { value: q } });
      await waitFor(() =>
        expect(
          fetchMock.mock.calls.some(
            ([url]) =>
              urlOf(url).searchParams.get('q') === q && urlOf(url).searchParams.get('page') === '0',
          ),
        ).toBe(true),
      );
      expect(screen.getByTestId('location').textContent).toContain('page=0');
    },
  );
  it('Given page/filter URL, When refreshed directly, Then preserves valid filters', async () => {
    await mount('/catalog/products?q=GM001&categoryId=1&active=false&page=1&size=10');
    await screen.findByText(product.name);
    const call = fetchMock.mock.calls.find(([url]) => urlOf(url).pathname.endsWith('/products'))!;
    expect(urlOf(call[0]).searchParams.toString()).toBe(
      'q=GM001&page=1&size=10&active=false&categoryId=1',
    );
  });
  it('Given loaded list, When next page and size change, Then requests server pagination', async () => {
    await mount();
    await screen.findByText(product.name);
    fireEvent.click(screen.getByRole('button', { name: 'Sau ›' }));
    await waitFor(() => expect(screen.getByTestId('location').textContent).toContain('page=1'));
    await screen.findByText(product.name);
    fireEvent.change(screen.getByLabelText('Số dòng mỗi trang'), { target: { value: '20' } });
    await waitFor(() => expect(screen.getByTestId('location').textContent).toContain('size=20'));
    expect(screen.getByTestId('location').textContent).toContain('page=0');
  });
  it('Given empty filtered result, Then clear filters reloads unfiltered list', async () => {
    const base = handler;
    handler = (url, init) =>
      url.searchParams.has('q') ? Promise.resolve(json(page([]))) : base(url, init);
    await mount('/catalog/products?q=no-match');
    await screen.findByText('Không có kết quả phù hợp');
    fireEvent.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }));
    expect(await screen.findByText(product.name)).toBeTruthy();
  });
  it('Given initial request under 300 ms, Then neither empty Table nor Skeleton flashes', async () => {
    let resolve!: (r: Response) => void;
    const base = handler;
    handler = (url, init) =>
      url.pathname.endsWith('/products')
        ? new Promise((r) => {
            resolve = r;
          })
        : base(url, init);
    await mount();
    expect(screen.queryByText('Không có dữ liệu.')).toBeNull();
    expect(screen.queryByText('Chưa có dữ liệu')).toBeNull();
    expect(screen.queryByLabelText('Đang tải')).toBeNull();
    await act(async () => resolve(json(page([product]))));
    expect(screen.getByText(product.name)).toBeTruthy();
  });
  it('Given pending request, When 300 ms / 15 seconds elapse, Then shows Skeleton / retry without stale success', async () => {
    vi.useFakeTimers();
    const base = handler;
    let resolve!: (r: Response) => void;
    handler = (url, init) =>
      url.pathname.endsWith('/products')
        ? new Promise((r) => {
            resolve = r;
          })
        : base(url, init);
    await mount();
    await act(async () => vi.advanceTimersByTime(301));
    expect(screen.getByLabelText('Đang tải')).toBeTruthy();
    await act(async () => vi.advanceTimersByTime(15000));
    expect(screen.getByText('Máy chủ phản hồi quá lâu. Vui lòng thử lại.')).toBeTruthy();
    await act(async () => resolve(json(page([product]))));
    expect(screen.queryByText(product.name)).toBeNull();
    handler = base;
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Thử lại' })));
    expect(screen.getByText(product.name)).toBeTruthy();
  });
  it('Given rapid typing, Then keeps every character locally and coalesces URL updates', async () => {
    vi.useFakeTimers();
    await mount();
    const input = screen.getByLabelText('Tìm sản phẩm') as HTMLInputElement;
    for (const value of ['K', 'Kh', 'Khá', 'Khác', 'Khách']) {
      fireEvent.change(input, { target: { value } });
      await act(async () => vi.advanceTimersByTime(10));
      expect(input.value).toBe(value);
    }
    expect(screen.getByTestId('location').textContent).not.toContain('q=');
    await act(async () => vi.advanceTimersByTime(301));
    expect(input.value).toBe('Khách');
    expect(fetchMock.mock.calls.filter(([url]) => urlOf(url).searchParams.has('q'))).toHaveLength(
      1,
    );
    expect(fetchMock.mock.calls.some(([url]) => urlOf(url).searchParams.get('q') === 'Khách')).toBe(
      true,
    );
  });
  it('Given old search resolves last, Then never replaces newer results', async () => {
    const base = handler;
    let old!: (r: Response) => void;
    handler = (url, init) =>
      url.searchParams.get('q') === 'old'
        ? new Promise((r) => {
            old = r;
          })
        : base(url, init);
    await mount();
    await screen.findByText(product.name);
    fireEvent.change(screen.getByLabelText('Tìm sản phẩm'), { target: { value: 'old' } });
    await waitFor(() => expect(old).toBeTypeOf('function'));
    fireEvent.change(screen.getByLabelText('Tìm sản phẩm'), { target: { value: 'new' } });
    await screen.findByText(product.name);
    await act(async () => old(json(page([{ ...product, name: 'STALE' }]))));
    expect(screen.queryByText('STALE')).toBeNull();
  });
  it.each([403, 404, 500])(
    'Given list HTTP %s, Then keeps filters and shows API message/trace with retry',
    async (status) => {
      const base = handler;
      handler = (url, init) =>
        url.pathname.endsWith('/products')
          ? Promise.resolve(
              json({ code: 'ERROR', message: 'Test error', traceId: 'trace-72' }, status),
            )
          : base(url, init);
      await mount();
      expect(await screen.findByText('Test error')).toBeTruthy();
      expect(document.body.textContent).toContain('trace-72');
      expect(screen.getByRole('button', { name: 'Thử lại' })).toBeTruthy();
      expect((screen.getByLabelText('Tìm sản phẩm') as HTMLInputElement).disabled).toBe(false);
    },
  );
  it('Given category API failure, Then list remains usable', async () => {
    const base = handler;
    handler = (url, init) =>
      url.pathname.endsWith('/categories')
        ? Promise.resolve(json({ message: 'Failure' }, 500))
        : base(url, init);
    await mount();
    await screen.findByText(product.name);
    expect(screen.getByText(/Không tải được danh mục/)).toBeTruthy();
    expect((screen.getByLabelText('Tìm sản phẩm') as HTMLInputElement).disabled).toBe(false);
  });
  it('Given OWNER edits price, When saving, Then sends full ProductRequest and shows server price immediately', async () => {
    let updated = product;
    const base = handler;
    handler = (url, init) => {
      if (url.pathname.endsWith('/products/1') && init?.method === 'PUT') {
        const body = JSON.parse(String(init.body));
        expect(body).toEqual({
          sku: product.sku,
          barcode: product.barcode,
          name: product.name,
          unit: product.unit,
          categoryId: 1,
          salePrice: 170000,
          costPrice: 99999,
          active: true,
        });
        updated = { ...product, salePrice: body.salePrice };
        return Promise.resolve(json(updated));
      }
      if (url.pathname.endsWith('/products')) return Promise.resolve(json(page([updated])));
      return base(url, init);
    };
    await mount('/catalog/products', 'OWNER');
    await screen.findByText(product.name);
    fireEvent.click(screen.getByRole('button', { name: `Sửa ${product.name}` }));
    fireEvent.change(screen.getByLabelText(/^Giá bán/), { target: { value: '170000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu' }));
    expect(await screen.findByText('170.000 ₫')).toBeTruthy();
    expect(screen.getByText('Đã lưu sản phẩm thành công')).toBeTruthy();
  });
  it('Given customer field error from API, Then preserves inputs and displays field message', async () => {
    const base = handler;
    handler = (url, init) =>
      init?.method === 'POST'
        ? Promise.resolve(
            json(
              {
                code: 'VALIDATION_ERROR',
                message: 'Dữ liệu không hợp lệ',
                details: [{ field: 'phone', message: 'Số điện thoại bị từ chối' }],
              },
              400,
            ),
          )
        : base(url, init);
    await mount('/catalog/customers');
    await screen.findByText(customer.name);
    fireEvent.click(screen.getByRole('button', { name: 'Thêm khách hàng' }));
    fireEvent.change(screen.getByLabelText(/^Tên khách hàng/), { target: { value: 'Khách mới' } });
    fireEvent.change(screen.getByLabelText(/^Số điện thoại/), { target: { value: '0901234567' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu' }));
    expect(await screen.findByText('Số điện thoại bị từ chối')).toBeTruthy();
    expect((screen.getByLabelText(/^Tên khách hàng/) as HTMLInputElement).value).toBe('Khách mới');
  });
  it('Given duplicate phone 409, Then keeps entered data and reports server message', async () => {
    const base = handler;
    handler = (url, init) =>
      init?.method === 'PUT'
        ? Promise.resolve(
            json({ code: 'DUPLICATE_PHONE', message: 'Số điện thoại đã tồn tại' }, 409),
          )
        : base(url, init);
    await mount('/catalog/customers');
    await screen.findByText(customer.name);
    fireEvent.click(screen.getByRole('button', { name: `Sửa ${customer.name}` }));
    fireEvent.click(screen.getByRole('button', { name: 'Lưu' }));
    expect(await screen.findByText('Số điện thoại đã tồn tại')).toBeTruthy();
    expect((screen.getByLabelText(/^Số điện thoại/) as HTMLInputElement).value).toBe(
      customer.phone,
    );
  });
  it('Given invalid phone, Then no POST request; cancelling dirty form asks confirmation', async () => {
    await mount('/catalog/customers');
    await screen.findByText(customer.name);
    fireEvent.click(screen.getByRole('button', { name: 'Thêm khách hàng' }));
    fireEvent.change(screen.getByLabelText(/^Tên khách hàng/), { target: { value: 'Khách mới' } });
    fireEvent.change(screen.getByLabelText(/^Số điện thoại/), { target: { value: '123' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu' }));
    expect(screen.getByText(/Số điện thoại gồm/)).toBeTruthy();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Quay lại' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Tiếp tục nhập' }));
    expect((screen.getByLabelText(/^Tên khách hàng/) as HTMLInputElement).value).toBe('Khách mới');
  });
  it('Given a pending save, Then blocks duplicate submits and ignores late results after timeout', async () => {
    vi.useFakeTimers();
    const base = handler;
    let reply!: (r: Response) => void;
    handler = (url, init) =>
      init?.method === 'PUT'
        ? new Promise((r) => {
            reply = r;
          })
        : base(url, init);
    await mount('/catalog/customers');
    fireEvent.click(screen.getByRole('button', { name: `Sửa ${customer.name}` }));
    fireEvent.click(screen.getByRole('button', { name: 'Lưu' }));
    const form = document.querySelector('form')!;
    fireEvent.submit(form);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
    await act(async () => vi.advanceTimersByTime(15001));
    expect(screen.getByText(/Chưa xác nhận được kết quả lưu/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Lưu' }) as HTMLButtonElement).disabled).toBe(true);
    await act(async () => reply(json(customer)));
    expect(screen.queryByText('Đã lưu khách hàng thành công')).toBeNull();
    expect(screen.getByLabelText(/^Tên khách hàng/)).toBeTruthy();
  });
  it.each(['ADMIN', 'STOREKEEPER'] as Role[])(
    'Given %s, Then customer write controls are hidden',
    async (role) => {
      await mount('/catalog/customers', role);
      await screen.findByText(customer.name);
      expect(screen.queryByRole('button', { name: /Sửa|Thêm khách hàng/ })).toBeNull();
    },
  );
  it('Given unknown catalog URL, Then 404 without business request', async () => {
    await mount('/catalog/unknown');
    expect(screen.getByText('Không tìm thấy trang')).toBeTruthy();
    expect(fetchMock.mock.calls.every(([url]) => urlOf(url).pathname.endsWith('/auth/me'))).toBe(
      true,
    );
  });
  it('Given no session, Then direct catalog URL goes to Login without catalog requests', async () => {
    await act(async () => {
      render(
        <MemoryRouter initialEntries={['/catalog/products']}>
          <Location />
          <AuthProvider>
            <ActiveBranchProvider>
              <AppRoutes />
            </ActiveBranchProvider>
          </AuthProvider>
        </MemoryRouter>,
      );
    });
    expect(screen.getByTestId('location').textContent).toBe('/login');
    expect(fetchMock).not.toHaveBeenCalled();
  });
  it('Given render failure, Then shared 500 keeps header and catalog navigation', async () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const base = handler;
    handler = (url, init) =>
      url.pathname.endsWith('/products')
        ? Promise.resolve(json({ content: null }))
        : base(url, init);
    await mount();
    expect(await screen.findByText('Lỗi hệ thống')).toBeTruthy();
    expect(screen.getByRole('navigation', { name: 'Danh mục' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Đăng xuất' })).toBeTruthy();
    log.mockRestore();
  });
  it('Given valid new customer, When saving, Then uses the contract body and returns to refreshed list', async () => {
    const base = handler;
    let created = false;
    handler = (url, init) => {
      if (url.pathname.endsWith('/customers') && init?.method === 'POST') {
        expect(JSON.parse(String(init.body))).toEqual({
          name: 'Khách mới',
          phone: '0901234567',
          email: null,
        });
        created = true;
        return Promise.resolve(json({ ...customer, name: 'Khách mới' }, 201));
      }
      if (url.pathname.endsWith('/customers') && created)
        return Promise.resolve(json(page([{ ...customer, name: 'Khách mới' }])));
      return base(url, init);
    };
    await mount('/catalog/customers');
    await screen.findByText(customer.name);
    fireEvent.click(screen.getByRole('button', { name: 'Thêm khách hàng' }));
    fireEvent.change(screen.getByLabelText(/^Tên khách hàng/), {
      target: { value: ' Khách mới ' },
    });
    fireEvent.change(screen.getByLabelText(/^Số điện thoại/), { target: { value: '0901234567' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu' }));
    expect(await screen.findByText('Đã lưu khách hàng thành công')).toBeTruthy();
    expect(await screen.findByText('Khách mới')).toBeTruthy();
  });
  it('Given OWNER creates a product, Then POST includes the full typed request and no fake fields', async () => {
    const base = handler;
    handler = (url, init) => {
      if (url.pathname.endsWith('/products') && init?.method === 'POST') {
        expect(JSON.parse(String(init.body))).toEqual({
          sku: 'QA-72',
          name: 'Sản phẩm mới',
          barcode: null,
          unit: 'túi',
          categoryId: 1,
          salePrice: 0,
          costPrice: 0,
          active: true,
        });
        return Promise.resolve(json({ ...product, sku: 'QA-72' }, 201));
      }
      return base(url, init);
    };
    await mount('/catalog/products', 'OWNER');
    await screen.findByText(product.name);
    fireEvent.click(screen.getByRole('button', { name: 'Thêm sản phẩm' }));
    for (const [label, value] of [
      ['SKU', 'QA-72'],
      ['Tên sản phẩm', 'Sản phẩm mới'],
      ['Đơn vị', 'túi'],
      ['Giá bán', '0'],
      ['Giá vốn', '0'],
    ] as const) {
      fireEvent.change(screen.getByLabelText(new RegExp('^' + label)), { target: { value } });
    }
    fireEvent.change(screen.getByLabelText(/^Danh mục/), { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu' }));
    expect(await screen.findByText('Đã lưu sản phẩm thành công')).toBeTruthy();
  });
  it('Given known catalog route, Then auth return policy permits it without changing POS/Operations rights', () => {
    expect(canOpenRoutePath('OWNER', '/catalog/products')).toBe(true);
    expect(canOpenRoutePath('CASHIER', '/catalog/customers')).toBe(true);
    expect(canOpenRoutePath('OWNER', '/pos')).toBe(false);
    expect(canOpenRoutePath('CASHIER', '/operations')).toBe(false);
  });
  it('Given expired refresh, When signing in again, Then returns to the catalog URL with filters', async () => {
    let expired = true;
    const base = handler;
    handler = (url, init) => {
      if (url.pathname.endsWith('/products') && expired)
        return Promise.resolve(json({ code: 'UNAUTHORIZED', message: 'Expired' }, 401));
      if (url.pathname.endsWith('/auth/refresh'))
        return Promise.resolve(json({ code: 'INVALID_REFRESH_TOKEN', message: 'Expired' }, 401));
      if (url.pathname.endsWith('/auth/login')) {
        expired = false;
        return Promise.resolve(json(currentSession));
      }
      return base(url, init);
    };
    await mount('/catalog/products?q=GM001&page=1#results');
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/login'));
    fireEvent.change(screen.getByLabelText(/^Tên đăng nhập/), {
      target: { value: 'catalog-test' },
    });
    fireEvent.change(screen.getByLabelText(/^Mật khẩu/), { target: { value: 'test-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));
    await waitFor(() =>
      expect(screen.getByTestId('location').textContent).toBe(
        '/catalog/products?q=GM001&page=1#results',
      ),
    );
    expect(await screen.findByText(product.name)).toBeTruthy();
  });
  it('Given logout during a pending catalog write, Then clears session and ignores the outgoing response', async () => {
    let reply!: (r: Response) => void;
    const base = handler;
    handler = (url, init) => {
      if (url.pathname.endsWith('/auth/logout'))
        return Promise.resolve(new Response(null, { status: 204 }));
      if (init?.method === 'PUT')
        return new Promise((r) => {
          reply = r;
        });
      return base(url, init);
    };
    await mount('/catalog/customers');
    await screen.findByText(customer.name);
    fireEvent.click(screen.getByRole('button', { name: `Sửa ${customer.name}` }));
    fireEvent.click(screen.getByRole('button', { name: 'Lưu' }));
    fireEvent.click(screen.getByRole('button', { name: 'Đăng xuất' }));
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/login'));
    expect(sessionStorage.getItem('greenmart.session')).toBeNull();
    await act(async () => reply(json(customer)));
    expect(screen.queryByText('Đã lưu khách hàng thành công')).toBeNull();
    expect(screen.queryByLabelText(/^Tên khách hàng/)).toBeNull();
  });
  it('Given contract phone length, Then accepts 10 and 11 digits and rejects invalid product money', () => {
    expect(validateCatalogForm('customer', { name: 'A', phone: '0901234567' })).toEqual({});
    expect(validateCatalogForm('customer', { name: 'A', phone: '09012345678' })).toEqual({});
    const errors = validateCatalogForm('product', {
      sku: 'lowercase',
      name: 'A',
      unit: 'túi',
      categoryId: '1',
      salePrice: '-1',
      costPrice: '1.5',
    });
    expect(Object.keys(errors).sort()).toEqual(['costPrice', 'salePrice', 'sku']);
  });
});
