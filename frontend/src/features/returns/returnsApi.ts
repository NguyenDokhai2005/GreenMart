import { request } from '../../api/client';
import type { components } from '../../api/schema';

export type ReturnDocument = components['schemas']['ReturnDocument'];
export type CreateReturnRequest = components['schemas']['CreateReturnRequest'];
export const listReturnsByOrder = (orderId: number, signal?: AbortSignal) =>
  request('/returns', 'get', { query: { orderId }, signal });
export const createReturn = (body: CreateReturnRequest) => request('/returns', 'post', { body });
export const approveReturn = (returnId: number) =>
  request('/returns/{returnId}/approve', 'post', { path: { returnId } });
export const cancelReturn = (returnId: number) =>
  request('/returns/{returnId}/cancel', 'post', { path: { returnId } });
