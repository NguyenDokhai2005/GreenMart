// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LOADING_DELAY_MS, LOADING_TIMEOUT_MS, useDelayedLoading } from '../../src/hooks/useDelayedLoading';

describe('useDelayedLoading', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('không hiện loading khi API trả về trước 300 ms', () => {
    const { result, rerender } = renderHook(({ l }) => useDelayedLoading(l), { initialProps: { l: true } });
    act(() => vi.advanceTimersByTime(200));
    expect(result.current.showLoading).toBe(false);
    rerender({ l: false });
    act(() => vi.advanceTimersByTime(1000));
    expect(result.current.showLoading).toBe(false);
  });

  it('hiện loading sau 300 ms', () => {
    const { result } = renderHook(() => useDelayedLoading(true));
    act(() => vi.advanceTimersByTime(LOADING_DELAY_MS - 1));
    expect(result.current.showLoading).toBe(false);
    act(() => vi.advanceTimersByTime(1));
    expect(result.current.showLoading).toBe(true);
    expect(result.current.timedOut).toBe(false);
  });

  it('báo timedOut sau 15 giây', () => {
    const { result } = renderHook(() => useDelayedLoading(true));
    act(() => vi.advanceTimersByTime(LOADING_TIMEOUT_MS - 1));
    expect(result.current.timedOut).toBe(false);
    act(() => vi.advanceTimersByTime(1));
    expect(result.current.timedOut).toBe(true);
  });

  it('reset khi tải xong và đếm lại ở lần tải sau', () => {
    const { result, rerender } = renderHook(({ l }) => useDelayedLoading(l), { initialProps: { l: true } });
    act(() => vi.advanceTimersByTime(LOADING_TIMEOUT_MS));
    expect(result.current).toEqual({ showLoading: true, timedOut: true });
    rerender({ l: false });
    expect(result.current).toEqual({ showLoading: false, timedOut: false });
    rerender({ l: true });
    expect(result.current.showLoading).toBe(false);
  });
});