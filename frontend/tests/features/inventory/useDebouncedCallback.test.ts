import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useDebouncedCallback } from '../../../src/features/inventory/useDebouncedCallback';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('useDebouncedCallback', () => {
  it('chỉ gọi một lần với tham số cuối sau khi hết thời gian chờ', () => {
    const fn = vi.fn();
    const { result } = renderHook(() => useDebouncedCallback(fn, 300));
    act(() => {
      result.current.call('a');
      vi.advanceTimersByTime(200);
      result.current.call('ab');
      vi.advanceTimersByTime(299);
    });
    expect(fn).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(fn).toHaveBeenCalledTimes(1);
    expect(fn).toHaveBeenCalledWith('ab');
  });

  it('cancel hủy lần gọi đang chờ', () => {
    const fn = vi.fn();
    const { result } = renderHook(() => useDebouncedCallback(fn, 300));
    act(() => {
      result.current.call('a');
      result.current.cancel();
      vi.advanceTimersByTime(1000);
    });
    expect(fn).not.toHaveBeenCalled();
  });
});
