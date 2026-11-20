import { useCallback, useState } from 'react';
import { parseApiError } from './formErrors';
import type { ParsedApiError } from './formErrors';

/** Chạy một thao tác ghi (duyệt, nhận, hủy, lưu): theo dõi đang chạy và lỗi, trả undefined khi lỗi. */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ParsedApiError | null>(null);

  const run = useCallback(async <T>(fn: () => Promise<T>): Promise<T | undefined> => {
    setBusy(true);
    setError(null);
    try {
      return await fn();
    } catch (e) {
      setError(parseApiError(e));
      return undefined;
    } finally {
      setBusy(false);
    }
  }, []);

  const clearError = useCallback(() => setError(null), []);
  return { busy, error, run, clearError };
}
