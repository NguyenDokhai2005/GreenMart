import { useEffect, useRef, useState } from 'react';

interface Settled<T> {
  key: string;
  data?: T;
  error?: unknown;
}

/**
 * Tải dữ liệu theo `key`: đổi key thì hủy yêu cầu cũ và tải lại; `reload` tải lại cùng key.
 * `data` giữ kết quả trước đó trong lúc tải để AsyncBlock chỉ hiện Skeleton khi chờ quá 300 ms (#228).
 */
export function useRemote<T>(load: (signal: AbortSignal) => Promise<T>, key: string) {
  const [attempt, setAttempt] = useState(0);
  const [settled, setSettled] = useState<Settled<T> | null>(null);
  const loadRef = useRef(load);
  const fullKey = `${key}#${attempt}`;

  useEffect(() => {
    loadRef.current = load;
  });

  useEffect(() => {
    const controller = new AbortController();
    loadRef.current(controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setSettled({ key: fullKey, data });
      },
      (error: unknown) => {
        if (!controller.signal.aborted) setSettled({ key: fullKey, error });
      },
    );
    return () => controller.abort();
  }, [fullKey]);

  const isLoading = settled?.key !== fullKey;
  return {
    data: settled?.data,
    error: isLoading ? undefined : settled?.error,
    isLoading,
    reload: () => setAttempt((n) => n + 1),
  };
}
