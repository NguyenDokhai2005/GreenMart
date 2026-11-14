import type { TransferListQuery } from './transferApi';
import { TRANSFER_FILTER_STATUSES } from './transferStatus';
import type { TransferFilterStatus } from './transferStatus';

export const TRANSFER_PAGE_SIZE = 20;

export function parseTransferStatus(value: string | null): TransferFilterStatus | undefined {
  return TRANSFER_FILTER_STATUSES.find((s) => s === value);
}

/** Chỉ nhận ngày dạng YYYY-MM-DD hợp lệ; sai thì bỏ qua bộ lọc. */
export function parseDateParam(value: string | null): string | undefined {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined;
  const d = new Date(`${value}T00:00:00Z`);
  return Number.isNaN(d.getTime()) || d.toISOString().slice(0, 10) !== value ? undefined : value;
}

export function parsePageParam(value: string | null): number {
  const n = Number(value);
  return Number.isInteger(n) && n >= 1 ? n : 1;
}

export function toTransferQuery(input: {
  branchId: number | undefined;
  q: string;
  status: TransferFilterStatus | undefined;
  from: string | undefined;
  to: string | undefined;
  page: number;
}): TransferListQuery {
  return {
    branchId: input.branchId,
    q: input.q.trim() || undefined,
    status: input.status,
    from: input.from,
    to: input.to,
    page: input.page - 1,
    size: TRANSFER_PAGE_SIZE,
  };
}
