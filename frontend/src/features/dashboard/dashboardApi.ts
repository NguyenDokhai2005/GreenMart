import { request } from '../../api/client';
import type { operations } from '../../api/schema';

export type RevenueQuery = NonNullable<operations['getRevenueReport']['parameters']['query']>;
export type TopProductsQuery = NonNullable<
  operations['getTopProductsReport']['parameters']['query']
>;
export type LowStockQuery = NonNullable<operations['listLowStockAlerts']['parameters']['query']>;

export const dashboardApi = {
  revenue: (query: RevenueQuery, signal: AbortSignal) =>
    request('/reports/revenue', 'get', { query, signal }),
  topProducts: (query: TopProductsQuery, signal: AbortSignal) =>
    request('/reports/top-products', 'get', { query, signal }),
  lowStockAlerts: (query: LowStockQuery, signal: AbortSignal) =>
    request('/alerts/low-stock', 'get', { query, signal }),
};

// IDs follow the POS, WEB, MARKET insertion order in V3__reference_data.sql.
export const CHANNEL_OPTIONS = [
  { label: 'Bán tại quầy', value: '1' },
  { label: 'Website', value: '2' },
  { label: 'Sàn TMĐT', value: '3' },
] as const;
