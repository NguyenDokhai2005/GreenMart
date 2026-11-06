import { useEffect, useState } from 'react';

export const LOADING_DELAY_MS = 300;
export const LOADING_TIMEOUT_MS = 15_000;

export interface DelayedLoadingOptions {
  delayMs?: number;
  timeoutMs?: number;
}

export interface DelayedLoadingResult {
  /** true khi đã chờ quá `delayMs` mà vẫn đang tải. */
  showLoading: boolean;
  /** true khi đã chờ quá `timeoutMs` mà vẫn đang tải -> hiện ErrorState có Thử lại. */
  timedOut: boolean;
}

interface Tracker {
  loading: boolean;
  show: boolean;
  timedOut: boolean;
}

/**
 * - Chỉ hiện loading khi chờ quá 300 ms (tránh nháy khi API nhanh).
 * - Quá 15 giây -> `timedOut`.
 * - `isLoading` đổi thì cả hai reset; lần tải sau đếm lại từ đầu.
 *
 * Reset được làm ngay lúc render (mẫu "điều chỉnh state khi prop đổi" của React),
 * còn effect chỉ đặt timer và gọi setState bên trong callback của timer.
 */
export function useDelayedLoading(
  isLoading: boolean,
  { delayMs = LOADING_DELAY_MS, timeoutMs = LOADING_TIMEOUT_MS }: DelayedLoadingOptions = {},
): DelayedLoadingResult {
  const [tracker, setTracker] = useState<Tracker>({ loading: isLoading, show: false, timedOut: false });

  if (tracker.loading !== isLoading) {
    setTracker({ loading: isLoading, show: false, timedOut: false });
  }

  useEffect(() => {
    if (!isLoading) return;
    const delayTimer = setTimeout(() => setTracker((t) => ({ ...t, show: true })), delayMs);
    const timeoutTimer = setTimeout(() => setTracker((t) => ({ ...t, timedOut: true })), timeoutMs);
    return () => {
      clearTimeout(delayTimer);
      clearTimeout(timeoutTimer);
    };
  }, [isLoading, delayMs, timeoutMs]);

  return {
    showLoading: isLoading && tracker.show,
    timedOut: isLoading && tracker.timedOut,
  };
}