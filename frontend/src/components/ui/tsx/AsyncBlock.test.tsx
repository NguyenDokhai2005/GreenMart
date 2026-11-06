// @vitest-environment jsdom
import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AsyncBlock from './AsyncBlock';

const empty = { message: 'Không có dữ liệu' };

describe('AsyncBlock', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('đang tải: không bao giờ hiện EmptyState', () => {
    render(
      <AsyncBlock isLoading isEmpty empty={empty}>
        <p>data</p>
      </AsyncBlock>,
    );
    act(() => vi.advanceTimersByTime(400));
    expect(screen.queryByText('Không có dữ liệu')).toBeNull();
    expect(screen.getByRole('status', { name: 'Đang tải' })).toBeTruthy();
  });

  it('tải xong và rỗng -> EmptyState', () => {
    render(
      <AsyncBlock isLoading={false} isEmpty empty={empty}>
        <p>data</p>
      </AsyncBlock>,
    );
    expect(screen.getByText('Không có dữ liệu')).toBeTruthy();
  });

  it('lỗi 500 -> ErrorState và Thử lại gọi đúng onRetry', () => {
    const onRetry = vi.fn();
    render(
      <AsyncBlock
        isLoading={false}
        error={{ status: 500, body: { code: 'X', message: 'Lỗi máy chủ' } }}
        onRetry={onRetry}
      >
        <p>data</p>
      </AsyncBlock>,
    );
    screen.getByRole('button', { name: 'Thử lại' }).click();
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('quá 15 giây -> ErrorState có Thử lại', () => {
    render(
      <AsyncBlock isLoading onRetry={() => {}}>
        <p>data</p>
      </AsyncBlock>,
    );
    act(() => vi.advanceTimersByTime(15_000));
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeTruthy();
  });
});
