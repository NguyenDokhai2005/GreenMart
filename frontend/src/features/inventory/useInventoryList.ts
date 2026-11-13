import { useEffect, useState } from 'react';
import { listInventory } from './inventoryApi';
import type { InventoryPageData } from './inventoryApi';
import { toApiQuery } from './inventoryQuery';
import type { StatusFilter } from './inventoryQuery';

interface Params {
  branchId: number | undefined;
  q: string;
  status: StatusFilter;
  /** Số trang từ 1 (như trên URL). */
  page: number;
}

interface Settled {
  key: string;
  data?: InventoryPageData;
  error?: unknown;
}

/**
 * Tải danh sách tồn kho. Đổi bộ lọc, trang hoặc chi nhánh thì hủy yêu cầu cũ và tải lại.
 * `data` giữ kết quả trước đó trong lúc tải để AsyncBlock chỉ hiện Skeleton khi chờ quá 300 ms (#228).
 */
export function useInventoryList({ branchId, q, status, page }: Params) {
  const [attempt, setAttempt] = useState(0);
  const [settled, setSettled] = useState<Settled | null>(null);
  const key = `${branchId ?? 'all'}|${q}|${status ?? ''}|${page}|${attempt}`;

  useEffect(() => {
    const controller = new AbortController();
    listInventory(toApiQuery({ branchId, q, status, page }), controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setSettled({ key, data });
      },
      (error: unknown) => {
        if (!controller.signal.aborted) setSettled({ key, error });
      },
    );
    return () => controller.abort();
  }, [key, branchId, q, status, page]);

  const isLoading = settled?.key !== key;
  return {
    data: settled?.data,
    error: isLoading ? undefined : settled?.error,
    isLoading,
    retry: () => setAttempt((n) => n + 1),
  };
}
