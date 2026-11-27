import { request } from '../../api/client';
import type { operations } from '../../api/schema';

export type RevenueReportQuery = NonNullable<operations['getRevenueReport']['parameters']['query']>;
export type TopProductsReportQuery = NonNullable<
  operations['getTopProductsReport']['parameters']['query']
>;

export function getRevenueReport(query: RevenueReportQuery, signal?: AbortSignal) {
  return request('/reports/revenue', 'get', { query, signal });
}

export function getTopProductsReport(query: TopProductsReportQuery, signal?: AbortSignal) {
  return request('/reports/top-products', 'get', { query, signal });
}
