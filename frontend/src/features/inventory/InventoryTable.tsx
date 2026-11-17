import type { InventoryItem } from './inventoryApi';
import { formatQty } from './stockStatus';
import StockStatusBadge from './StockStatusBadge';

interface InventoryTableProps {
  items: readonly InventoryItem[];
  /** Hiện cột Chi nhánh khi xem "Tất cả chi nhánh" (một sản phẩm xuất hiện ở nhiều chi nhánh). */
  showBranch: boolean;
}

const NUMERIC = 'inv-num';

function Head({ showBranch }: { showBranch: boolean }) {
  return (
    <thead>
      <tr>
        <th scope="col">Sản phẩm</th>
        <th scope="col">SKU</th>
        {showBranch && <th scope="col">Chi nhánh</th>}
        <th scope="col">Đơn vị tính</th>
        <th scope="col" className={NUMERIC}>
          Tồn thực
        </th>
        <th scope="col" className={NUMERIC}>
          Giữ chỗ
        </th>
        <th scope="col" className={NUMERIC}>
          Khả dụng
        </th>
        <th scope="col" className={NUMERIC}>
          ROP
        </th>
        <th scope="col">Cảnh báo</th>
      </tr>
    </thead>
  );
}

export default function InventoryTable({ items, showBranch }: InventoryTableProps) {
  return (
    <div className="inv-table-wrap">
      <table className="inv-table">
        <Head showBranch={showBranch} />
        <tbody>
          {items.map((it) => (
            <tr key={`${it.branch.id}-${it.productId}`}>
              <td>{it.productName}</td>
              <td>{it.sku}</td>
              {showBranch && <td>{it.branch.code}</td>}
              <td>{it.unit}</td>
              <td className={NUMERIC}>{formatQty(it.onHand)}</td>
              <td className={NUMERIC}>{formatQty(it.reserved)}</td>
              <td className={NUMERIC}>{formatQty(it.available)}</td>
              <td className={NUMERIC}>
                {it.reorderPoint == null ? '—' : formatQty(it.reorderPoint)}
              </td>
              <td>
                <StockStatusBadge status={it.stockStatus} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Skeleton cho bảng: giữ nguyên tiêu đề cột, chỉ các dòng dữ liệu là khung chờ (#228). */
export function InventoryTableSkeleton({ showBranch }: { showBranch: boolean }) {
  const cols = showBranch ? 9 : 8;
  return (
    <div className="inv-table-wrap" role="status" aria-busy="true" aria-label="Đang tải">
      <table className="inv-table">
        <Head showBranch={showBranch} />
        <tbody>
          {Array.from({ length: 8 }, (_, r) => (
            <tr key={r}>
              {Array.from({ length: cols }, (_, c) => (
                <td key={c}>
                  <span
                    className="gm-skeleton inv-skel"
                    style={{ width: c === 0 ? '70%' : '55%' }}
                  />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
