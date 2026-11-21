import { useEffect, useState } from 'react';
import { ApiError, listOrders } from '../../api/client';
import type { components } from '../../api/schema';
import { TIMEOUT_MESSAGE } from '../../lib/errors';
import type { OrdersQuery } from './ordersQuery';

type OrderSummaryPage = components['schemas']['OrderSummaryPage'];
interface Settled {
  key: string;
  data?: OrderSummaryPage;
  error?: unknown;
}

export function useOrdersList(query: OrdersQuery) {
  const [attempt, setAttempt] = useState(0);
  const [settled, setSettled] = useState<Settled | null>(null);
  const key = `${JSON.stringify(query)}:${attempt}`;

  useEffect(() => {
    const controller = new AbortController();
    const timer = globalThis.setTimeout(() => {
      controller.abort();
      setSettled({ key, error: new ApiError(TIMEOUT_MESSAGE, 408) });
    }, 15_000);
    void listOrders(query, controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) {
          globalThis.clearTimeout(timer);
          setSettled({ key, data });
        }
      },
      (error: unknown) => {
        if (!controller.signal.aborted) {
          globalThis.clearTimeout(timer);
          setSettled({ key, error });
        }
      },
    );
    return () => {
      globalThis.clearTimeout(timer);
      controller.abort();
    };
  }, [key, query]);

  const isLoading = settled?.key !== key;
  return {
    data: settled?.data,
    error: isLoading ? undefined : settled?.error,
    isLoading,
    retry: () => setAttempt((n) => n + 1),
  };
}
