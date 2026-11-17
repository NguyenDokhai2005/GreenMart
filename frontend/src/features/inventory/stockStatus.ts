import type { StockStatus } from './inventoryApi';

export const STOCK_STATUS_LABEL: Record<StockStatus, string> = {
  OK: 'Bình thường',
  LOW: 'Tồn thấp',
  OUT: 'Hết hàng',
};

const nf = new Intl.NumberFormat('vi-VN');
export const formatQty = (n: number) => nf.format(n);
