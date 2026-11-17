import { request } from '../../api/client';
import type { components, operations } from '../../api/schema';

export type StockDocument = components['schemas']['StockDocument'];
export type StockDocumentSummary = components['schemas']['StockDocumentSummary'];
export type StockDocumentType = components['schemas']['StockDocumentType'];
export type StockDocumentStatus = components['schemas']['StockDocumentStatus'];
export type ExportReason = components['schemas']['ExportReason'];
export type StockDocumentQuery = NonNullable<
  operations['listStockDocuments']['parameters']['query']
>;

export function listStockDocuments(query: StockDocumentQuery, signal?: AbortSignal) {
  return request('/stock-documents', 'get', { query, signal });
}

export function getStockDocument(documentId: number, signal?: AbortSignal) {
  return request('/stock-documents/{documentId}', 'get', {
    path: { documentId },
    signal,
  });
}

export function createStockDocument(
  body: components['schemas']['StockDocumentRequest'],
  signal?: AbortSignal,
) {
  return request('/stock-documents', 'post', { body, signal });
}

export function updateStockDocument(
  documentId: number,
  body: components['schemas']['StockDocumentUpdateRequest'],
  signal?: AbortSignal,
) {
  return request('/stock-documents/{documentId}', 'put', {
    path: { documentId },
    body,
    signal,
  });
}

export function approveStockDocument(documentId: number) {
  return request('/stock-documents/{documentId}/approve', 'post', {
    path: { documentId },
  });
}

export function cancelStockDocument(documentId: number, reason: string) {
  return request('/stock-documents/{documentId}/cancel', 'post', {
    path: { documentId },
    body: { reason },
  });
}
