import type { components, operations } from '../../api/schema';

export type OrderStatus = components['schemas']['OrderStatus'];
export type OrderChannel = components['schemas']['ChannelCode'];
export type OrdersQuery = NonNullable<operations['listOrders']['parameters']['query']>;

export const ORDER_STATUSES: readonly OrderStatus[] = [
  'NEW',
  'RESERVED',
  'CONFIRMED',
  'COMPLETED',
  'CANCELLED',
  'EXPIRED',
  'REJECTED',
  'RETURNED',
];

export const STATUS_LABELS: Record<OrderStatus, string> = {
  NEW: 'Mới',
  RESERVED: 'Đang giữ hàng',
  CONFIRMED: 'Đã xác nhận',
  COMPLETED: 'Hoàn tất',
  CANCELLED: 'Đã hủy',
  EXPIRED: 'Hết hạn',
  REJECTED: 'Từ chối',
  RETURNED: 'Đã trả hàng',
};

export interface OrderFilters {
  q: string;
  channel: OrderChannel | '';
  branchId: number | undefined;
  status: OrderStatus[];
  from: string;
  to: string;
  customerId: number | undefined;
  page: number;
}

const isDate = (value: string): boolean => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().startsWith(value);
};
const positiveId = (value: string | null): number | undefined => {
  if (!value || !/^[1-9]\d*$/.test(value)) return undefined;
  const id = Number(value);
  return Number.isSafeInteger(id) ? id : undefined;
};

export function readOrderFilters(params: URLSearchParams): OrderFilters {
  const rawChannel = params.get('channel');
  const rawPage = params.get('page');
  const page = positiveId(rawPage) ?? 1;
  return {
    q: params.get('q') ?? '',
    channel:
      rawChannel === 'POS' || rawChannel === 'WEB' || rawChannel === 'MARKET' ? rawChannel : '',
    branchId: positiveId(params.get('branchId')),
    status: [
      ...new Set(
        params
          .getAll('status')
          .filter((s): s is OrderStatus => ORDER_STATUSES.includes(s as OrderStatus)),
      ),
    ],
    from: isDate(params.get('from') ?? '') ? params.get('from')! : '',
    to: isDate(params.get('to') ?? '') ? params.get('to')! : '',
    customerId: positiveId(params.get('customerId')),
    page,
  };
}

export function orderFiltersToParams(filters: OrderFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.q.trim()) params.set('q', filters.q.trim());
  if (filters.channel) params.set('channel', filters.channel);
  if (filters.branchId) params.set('branchId', String(filters.branchId));
  for (const status of ORDER_STATUSES) {
    if (filters.status.includes(status)) params.append('status', status);
  }
  if (filters.from) params.set('from', filters.from);
  if (filters.to) params.set('to', filters.to);
  if (filters.customerId) params.set('customerId', String(filters.customerId));
  if (filters.page > 1) params.set('page', String(filters.page));
  return params;
}

export function orderFiltersToApi(
  filters: OrderFilters,
  branchId: number | undefined,
): OrdersQuery {
  return {
    branchId,
    channel: filters.channel || undefined,
    status: filters.status.length ? filters.status : undefined,
    from: filters.from || undefined,
    to: filters.to || undefined,
    customerId: filters.customerId,
    q: filters.q.trim() || undefined,
    page: filters.page - 1,
    size: 8,
    sort: 'createdAt,desc',
  };
}

export function hasOrderFilters(filters: OrderFilters): boolean {
  return Boolean(
    filters.q ||
    filters.channel ||
    filters.branchId ||
    filters.status.length ||
    filters.from ||
    filters.to ||
    filters.customerId,
  );
}
