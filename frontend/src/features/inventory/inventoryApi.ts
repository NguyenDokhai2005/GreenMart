import { request } from '../../api/client';

export interface InventoryQuery {
  /** undefined = mọi chi nhánh được phép xem (OWNER/ADMIN chọn "Tất cả chi nhánh"). */
  branchId?: number;
  q?: string;
  stockStatus?: 'OK' | 'LOW' | 'OUT';
  /** Số trang bắt đầu từ 0 (đúng quy ước API). */
  page: number;
  size: number;
}

/** GET /inventory: tồn thực, giữ chỗ, khả dụng theo sản phẩm × chi nhánh (INV-01). */
export function listInventory(query: InventoryQuery, signal?: AbortSignal) {
  return request('/inventory', 'get', {
    query: {
      branchId: query.branchId,
      q: query.q || undefined,
      stockStatus: query.stockStatus,
      page: query.page,
      size: query.size,
    },
    signal,
  });
}

export type InventoryPageData = Awaited<ReturnType<typeof listInventory>>;
export type InventoryItem = InventoryPageData['content'][number];
export type StockStatus = InventoryItem['stockStatus'];
