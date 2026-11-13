import { useCallback, useEffect, useRef } from 'react';

/** Gọi `fn` sau `delay` ms kể từ lần gọi cuối. `cancel` hủy lần gọi đang chờ. */
export function useDebouncedCallback<A extends unknown[]>(fn: (...args: A) => void, delay: number) {
  const latest = useRef(fn);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    latest.current = fn;
  });

  const cancel = useCallback(() => clearTimeout(timer.current), []);
  const call = useCallback(
    (...args: A) => {
      clearTimeout(timer.current);
      timer.current = setTimeout(() => latest.current(...args), delay);
    },
    [delay],
  );

  useEffect(() => cancel, [cancel]);
  return { call, cancel };
}
