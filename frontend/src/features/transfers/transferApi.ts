import { listBranches, request } from '../../api/client';

export interface TransferListQuery {
  /** undefined = mọi chi nhánh được phép xem. API khớp cả chi nhánh nguồn lẫn đích. */
  branchId?: number;
  q?: string;
  status?: 'DRAFT' | 'IN_TRANSIT' | 'RECEIVED' | 'CANCELLED';
  /** YYYY-MM-DD, gồm cả ngày này. */
  from?: string;
  to?: string;
  /** Số trang từ 0 (đúng quy ước API). */
  page: number;
  size: number;
}

/** GET /stock-documents?type=TRANSFER */
export function listTransfers(query: TransferListQuery, signal?: AbortSignal) {
  return request('/stock-documents', 'get', {
    query: {
      type: 'TRANSFER',
      branchId: query.branchId,
      q: query.q || undefined,
      status: query.status ? [query.status] : undefined,
      from: query.from || undefined,
      to: query.to || undefined,
      page: query.page,
      size: query.size,
    },
    signal,
  });
}

export function getTransfer(documentId: number, signal?: AbortSignal) {
  return request('/stock-documents/{documentId}', 'get', { path: { documentId }, signal });
}

export interface TransferDraftItem {
  productId: number;
  quantity: number;
}

/** POST /stock-documents (phiếu DRAFT, tồn chưa đổi, BR-13). */
export function createTransfer(input: {
  branchId: number;
  toBranchId: number;
  note?: string;
  items: TransferDraftItem[];
}) {
  return request('/stock-documents', 'post', {
    body: {
      type: 'TRANSFER',
      branchId: input.branchId,
      toBranchId: input.toBranchId,
      note: input.note,
      items: input.items,
    },
  });
}

/** PUT /stock-documents/{id}: chỉ phiếu DRAFT. */
export function updateTransfer(
  documentId: number,
  input: { toBranchId: number; note?: string; items: TransferDraftItem[] },
) {
  return request('/stock-documents/{documentId}', 'put', {
    path: { documentId },
    body: { toBranchId: input.toBranchId, note: input.note, items: input.items },
  });
}

/** POST /stock-documents/{id}/approve: DRAFT → IN_TRANSIT, tồn kho gửi giảm. */
export function approveTransfer(documentId: number) {
  return request('/stock-documents/{documentId}/approve', 'post', { path: { documentId } });
}

/** POST /stock-documents/{id}/receive: đủ mọi dòng, nhận không vượt số xuất. */
export function receiveTransfer(
  documentId: number,
  items: { itemId: number; receivedQuantity: number }[],
) {
  return request('/stock-documents/{documentId}/receive', 'post', {
    path: { documentId },
    body: { items },
  });
}

/** POST /stock-documents/{id}/cancel: chỉ phiếu DRAFT. */
export function cancelTransfer(documentId: number, reason: string) {
  return request('/stock-documents/{documentId}/cancel', 'post', {
    path: { documentId },
    body: { reason },
  });
}

/** GET /inventory/{branchId}/{productId}: tồn khả dụng của một sản phẩm tại một chi nhánh. */
export function getStockItem(branchId: number, productId: number, signal?: AbortSignal) {
  return request('/inventory/{branchId}/{productId}', 'get', {
    path: { branchId, productId },
    signal,
  });
}

/** Chi nhánh đang hoạt động. Lưu ý: vai trò không phải Owner/Admin chỉ nhận chi nhánh được gán. */
export function listActiveBranches(signal?: AbortSignal) {
  return listBranches(true, signal);
}

export type TransferSummary = Awaited<ReturnType<typeof listTransfers>>['content'][number];
export type TransferDoc = Awaited<ReturnType<typeof getTransfer>>;
export type TransferItem = TransferDoc['items'][number];
export type TransferStatus = TransferSummary['status'];
