import { Link } from 'react-router';
import { APP_PATHS } from '../auth/routePolicy';
import type { StockDocumentSummary } from './stockDocumentApi';
import {
  formatStockDocumentDate,
  STOCK_DOCUMENT_STATUS_LABELS,
  STOCK_DOCUMENT_TYPE_LABELS,
} from './stockDocumentPresentation';

interface StockDocumentTableProps {
  items: readonly StockDocumentSummary[];
  showBranch: boolean;
  search: string;
}

function statusClass(status: StockDocumentSummary['status']): string {
  if (status === 'DRAFT') return 'sd-badge--draft';
  if (status === 'APPROVED' || status === 'RECEIVED') return 'sd-badge--approved';
  if (status === 'IN_TRANSIT') return 'sd-badge--transit';
  return 'sd-badge--cancelled';
}

function TableHead({ showBranch }: { showBranch: boolean }) {
  return (
    <thead>
      <tr>
        <th scope="col">Mã phiếu</th>
        <th scope="col">Loại phiếu</th>
        {showBranch && <th scope="col">Chi nhánh</th>}
        <th scope="col">Người lập</th>
        <th scope="col">Ngày tạo</th>
        <th scope="col" className="sd-number">
          Số mặt hàng
        </th>
        <th scope="col">Trạng thái</th>
        <th scope="col">Chi tiết</th>
      </tr>
    </thead>
  );
}

export default function StockDocumentTable({ items, showBranch, search }: StockDocumentTableProps) {
  return (
    <div className="sd-table-wrap">
      <table className="sd-table">
        <TableHead showBranch={showBranch} />
        <tbody>
          {items.map((document) => {
            const detail = {
              pathname: `${APP_PATHS.stockDocuments}/${document.id}`,
              search,
            };
            return (
              <tr key={document.id}>
                <td>
                  <Link className="sd-code" to={detail}>
                    {document.code}
                  </Link>
                </td>
                <td>{STOCK_DOCUMENT_TYPE_LABELS[document.type]}</td>
                {showBranch && <td>{document.branch.code}</td>}
                <td>{document.createdBy.fullName}</td>
                <td>{formatStockDocumentDate(document.createdAt)}</td>
                <td className="sd-number">{document.itemCount}</td>
                <td>
                  <span className={`sd-badge ${statusClass(document.status)}`}>
                    {STOCK_DOCUMENT_STATUS_LABELS[document.status]}
                  </span>
                </td>
                <td>
                  <Link className="sd-view-link" to={detail}>
                    Xem phiếu
                    <span className="sd-sr-only"> {document.code}</span>
                  </Link>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export function StockDocumentTableSkeleton({ showBranch }: { showBranch: boolean }) {
  const columnCount = showBranch ? 8 : 7;
  return (
    <div className="sd-table-wrap" role="status" aria-busy="true" aria-label="Đang tải phiếu kho">
      <table className="sd-table">
        <TableHead showBranch={showBranch} />
        <tbody>
          {Array.from({ length: 6 }, (_, row) => (
            <tr key={row}>
              {Array.from({ length: columnCount }, (_, column) => (
                <td key={column}>
                  <span className="gm-skeleton sd-skeleton" />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
