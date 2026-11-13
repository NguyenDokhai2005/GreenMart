import type { InventoryQuery } from './inventoryApi';

export const PAGE_SIZE = 20;

/** Bộ lọc "Cảnh báo tồn" trên giao diện; undefined = Tất cả. */
export type StatusFilter = 'OUT' | 'LOW' | undefined;

export const STATUS_FILTERS: { value: StatusFilter; label: string }[] = [
  { value: undefined, label: 'Tất cả' },
  { value: 'OUT', label: 'Hết hàng' },
  { value: 'LOW', label: 'Tồn thấp' },
];

export function parseStatus(value: string | null): StatusFilter {
  return value === 'OUT' || value === 'LOW' ? value : undefined;
}

/** URL dùng số trang từ 1; giá trị sai hoặc nhỏ hơn 1 về trang 1. */
export function parsePage(value: string | null): number {
  const n = Number(value);
  return Number.isInteger(n) && n >= 1 ? n : 1;
}

export function toApiQuery(input: {
  branchId: number | undefined;
  q: string;
  status: StatusFilter;
  page: number;
}): InventoryQuery {
  return {
    branchId: input.branchId,
    q: input.q.trim() || undefined,
    stockStatus: input.status,
    page: input.page - 1,
    size: PAGE_SIZE,
  };
}
