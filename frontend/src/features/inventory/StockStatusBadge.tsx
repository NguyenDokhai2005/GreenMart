import type { StockStatus } from './inventoryApi';
import { STOCK_STATUS_LABEL } from './stockStatus';

const TONE: Record<StockStatus, string> = { OK: 'ok', LOW: 'low', OUT: 'out' };

/** Huy hiệu cảnh báo tồn: Bình thường (xanh), Tồn thấp (cam), Hết hàng (đỏ). Quy tắc theo BR-09. */
export default function StockStatusBadge({ status }: { status: StockStatus }) {
  return (
    <span className={`inv-badge inv-badge--${TONE[status]}`}>{STOCK_STATUS_LABEL[status]}</span>
  );
}
