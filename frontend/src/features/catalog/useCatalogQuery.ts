import { useEffect, useState } from 'react';
import { ApiError } from '../../api/client';
import { TIMEOUT_MESSAGE } from '../../lib/errors';

interface Result<T> {
  key: string;
  data?: T;
  error?: unknown;
  pending: boolean;
}

export function useCatalogQuery<T>(
  key: string,
  load: (signal: AbortSignal) => Promise<T>,
  enabled = true,
) {
  const [attempt, setAttempt] = useState(0);
  const requestKey = `${key}:${attempt}`;
  const [result, setResult] = useState<Result<T>>({ key: '', pending: true });
  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      controller.abort();
      setResult({ key: requestKey, pending: false, error: new ApiError(TIMEOUT_MESSAGE, 408) });
    }, 15_000);
    void load(controller.signal)
      .then(
        (data) => {
          if (!settled) setResult({ key: requestKey, data, pending: false });
        },
        (error: unknown) => {
          if (!settled) setResult({ key: requestKey, error, pending: false });
        },
      )
      .finally(() => {
        settled = true;
        clearTimeout(timer);
      });
    return () => {
      settled = true;
      clearTimeout(timer);
      controller.abort();
    };
  }, [requestKey, load, enabled]);
  return {
    ...(enabled
      ? result.key === requestKey
        ? result
        : { key: requestKey, pending: true }
      : { key: requestKey, pending: false }),
    retry: () => setAttempt((n) => n + 1),
  };
}
