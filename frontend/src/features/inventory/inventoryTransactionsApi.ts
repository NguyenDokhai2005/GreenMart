import { request } from '../../api/client';
import type { operations } from '../../api/schema';

export type InventoryTransactionQuery = NonNullable<
  operations['listInventoryTransactions']['parameters']['query']
>;

export function listInventoryTransactions(query: InventoryTransactionQuery, signal?: AbortSignal) {
  return request('/inventory/transactions', 'get', { query, signal });
}
