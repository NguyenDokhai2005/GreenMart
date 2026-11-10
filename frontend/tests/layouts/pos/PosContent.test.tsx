// @vitest-environment jsdom
import { act, useState } from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../../../src/features/auth/AuthProvider';
import { ActiveBranchProvider } from '../../../src/features/auth/ActiveBranchProvider';
import PosLayout from '../../../src/layouts/pos/PosLayout';
import type { PosPageLoader } from '../../../src/layouts/pos/PosRouteContent';
import type { PosPageProps } from '../../../src/layouts/pos/PosPlaceholderPage';
import { ApiError } from '../../../src/api/client';
import { ErrorState } from '../../../src/components/ui';
import { useActiveBranch } from '../../../src/features/auth/useActiveBranch';

const auth = {
  accessToken: 'test-access',
  refreshToken: 'test-refresh',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: {
    id: 7,
    username: 'test-user',
    fullName: 'Nguyễn An',
    role: 'CASHIER',
    branches: [{ id: 1, code: 'GM-001', name: 'GreenMart Quận 1' }],
  },
};
function TestPage({ item }: PosPageProps) {
  return (
    <>
      <h1 data-pos-heading tabIndex={-1}>
        {item.title}
      </h1>
      <input aria-label="Dữ liệu đang nhập" defaultValue="giữ nguyên" />
    </>
  );
}
const ready = { default: TestPage };

async function renderContent(loadPage: PosPageLoader) {
  render(
    <MemoryRouter initialEntries={['/pos']}>
      <AuthProvider>
        <ActiveBranchProvider>
          <Routes>
            <Route path="/pos/*" element={<PosLayout loadPage={loadPage} />} />
          </Routes>
        </ActiveBranchProvider>
      </AuthProvider>
    </MemoryRouter>,
  );
  await act(async () => {});
}

beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  sessionStorage.setItem('greenmart.session', JSON.stringify(auth));
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(JSON.stringify(auth.user), {
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  );
});
afterEach(() => {
  cleanup();
  sessionStorage.clear();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('Given content loading inside the POS shell', () => {
  it('When the active branch changes without leaving the route, Then old branch input is not reused', async () => {
    const second = { id: 2, code: 'GM-002', name: 'GreenMart Quận 2' };
    const multiBranch = {
      ...auth,
      user: { ...auth.user, branches: [...auth.user.branches, second] },
    };
    sessionStorage.setItem('greenmart.session', JSON.stringify(multiBranch));
    sessionStorage.setItem('greenmart.active-branch', JSON.stringify({ userId: 7, branchId: 1 }));
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify(multiBranch.user), {
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
    function BranchPage({ item }: PosPageProps) {
      const branch = useActiveBranch();
      return (
        <>
          <TestPage item={item} />
          <button onClick={() => branch.selectBranch(2)}>Chuyển chi nhánh trong fixture</button>
        </>
      );
    }
    await renderContent(async () => ({ default: BranchPage }));
    const previousInput = screen.getByRole('textbox');
    fireEvent.input(previousInput, { target: { value: 'Dữ liệu GM-001' } });
    fireEvent.click(screen.getByRole('button', { name: 'Chuyển chi nhánh trong fixture' }));
    await act(async () => {});
    expect(screen.getByRole('banner').textContent).toContain('GM-002');
    expect(screen.getByRole('textbox')).not.toBe(previousInput);
    expect((screen.getByRole('textbox') as HTMLInputElement).value).toBe('giữ nguyên');
  });

  it('When content resolves in 200 ms, Then neither loading nor empty flashes', async () => {
    vi.useFakeTimers();
    const loader: PosPageLoader = () =>
      new Promise((resolve) => setTimeout(() => resolve(ready), 200));
    await renderContent(loader);
    expect(screen.getByRole('banner')).toBeTruthy();
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.queryByText('Màn hình đang được chuẩn bị')).toBeNull();
    await act(async () => vi.advanceTimersByTime(200));
    expect(screen.getByRole('heading', { name: 'Bán hàng tại quầy' })).toBeTruthy();
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('When waiting exceeds 300 ms and 15 s, Then loader/timeout stay in content, retry resets timers, and menus remain usable', async () => {
    vi.useFakeTimers();
    const loader = vi.fn<PosPageLoader>().mockImplementation(() => new Promise(() => undefined));
    await renderContent(loader);
    const header = screen.getByRole('banner');
    const nav = screen.getByRole('navigation');
    await act(async () => vi.advanceTimersByTime(299));
    expect(screen.queryByRole('status')).toBeNull();
    await act(async () => vi.advanceTimersByTime(101));
    expect(screen.getByRole('status').textContent).toContain('Đang tải nội dung');
    await act(async () => vi.advanceTimersByTime(14_600));
    expect(screen.getByRole('alert').textContent).toContain('Máy chủ phản hồi quá lâu');
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    await act(async () => {});
    expect(loader).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
    loader.mockResolvedValueOnce(ready);
    fireEvent.click(screen.getByRole('link', { name: 'Hóa đơn' }));
    await act(async () => {});
    expect(screen.getByRole('heading', { name: 'Hóa đơn' })).toBeTruthy();
    expect(screen.getByRole('banner')).toBe(header);
    expect(screen.getByRole('navigation')).toBe(nav);
  });

  it('When an old navigation resolves late, Then it cannot replace the current page', async () => {
    let resolveOld!: (value: typeof ready) => void;
    const loader = vi
      .fn<PosPageLoader>()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveOld = resolve;
          }),
      )
      .mockResolvedValue(ready);
    await renderContent(loader);
    fireEvent.click(screen.getByRole('link', { name: 'Hóa đơn' }));
    await screen.findByRole('heading', { name: 'Hóa đơn' });
    await act(async () => resolveOld(ready));
    expect(screen.queryByRole('heading', { name: 'Bán hàng tại quầy' })).toBeNull();
    expect(screen.getByRole('heading', { name: 'Hóa đơn' })).toBeTruthy();
  });
});

describe('Given a page error', () => {
  it.each([
    {
      error: new ApiError('Không có quyền ở chi nhánh này', 403, 'BRANCH_FORBIDDEN'),
      title: 'Bạn không có quyền truy cập',
    },
    { error: new ApiError('Không tìm thấy', 404, 'NOT_FOUND'), title: 'Không tìm thấy trang' },
    {
      error: new ApiError('Lỗi máy chủ', 500, 'INTERNAL_ERROR', 'trace-pos-71'),
      title: 'Lỗi hệ thống',
    },
    { error: new TypeError('Failed to fetch'), title: 'Mất kết nối' },
  ])(
    'When $title occurs, Then the shared page stays inside the existing shell',
    async ({ error, title }) => {
      const loader = vi.fn<PosPageLoader>().mockRejectedValue(error);
      await renderContent(loader);
      expect(screen.getByRole('heading', { name: title })).toBeTruthy();
      expect(screen.getByRole('banner')).toBeTruthy();
      expect(screen.getByRole('navigation')).toBeTruthy();
      if (title === 'Lỗi hệ thống') {
        expect(screen.getByText('Mã lỗi: trace-pos-71')).toBeTruthy();
        loader.mockResolvedValueOnce(ready);
        fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
        await screen.findByRole('heading', { name: 'Bán hàng tại quầy' });
      }
      if (title === 'Mất kết nối') {
        loader.mockResolvedValueOnce(ready);
        fireEvent(window, new Event('online'));
        await screen.findByRole('heading', { name: 'Bán hàng tại quầy' });
      }
    },
  );

  it.each([new Error('Unexpected render failure'), undefined, null])(
    'When rendering throws %s, Then shared 500 catches it without losing navigation and can retry',
    async (error) => {
      const log = vi.spyOn(console, 'error').mockImplementation(() => undefined);
      function BrokenPage(): never {
        throw error;
      }
      const loader = vi
        .fn<PosPageLoader>()
        .mockResolvedValueOnce({ default: BrokenPage })
        .mockResolvedValue(ready);
      await renderContent(loader);
      expect(screen.getByRole('heading', { name: 'Lỗi hệ thống' })).toBeTruthy();
      fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
      await screen.findByRole('heading', { name: 'Bán hàng tại quầy' });
      expect(screen.getByRole('navigation')).toBeTruthy();
      log.mockRestore();
    },
  );

  it('When one content block fails and retries, Then input in another block keeps its value', async () => {
    function BlockFixture() {
      const [failed, setFailed] = useState(false);
      return (
        <>
          <h1 data-pos-heading tabIndex={-1}>
            Nội dung thử tích hợp
          </h1>
          <input aria-label="Ghi chú đang nhập" defaultValue="" />
          <section aria-label="Khối kết quả">
            {failed ? (
              <ErrorState message="Không tải được kết quả" onRetry={() => setFailed(false)} />
            ) : (
              <button onClick={() => setFailed(true)}>Gây lỗi khối</button>
            )}
          </section>
        </>
      );
    }
    await renderContent(async () => ({ default: BlockFixture }));
    const input = screen.getByRole('textbox', { name: 'Ghi chú đang nhập' }) as HTMLInputElement;
    fireEvent.input(input, { target: { value: 'Dữ liệu cần giữ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Gây lỗi khối' }));
    expect(input.value).toBe('Dữ liệu cần giữ');
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    expect(screen.getByRole('textbox')).toBe(input);
    expect(input.value).toBe('Dữ liệu cần giữ');
  });
});
