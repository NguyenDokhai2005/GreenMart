import { Link } from 'react-router';
import type { CurrentUser } from '../../api/client';
import { formatDateTime } from './formatDate';
import { transferDetailPath } from './paths';
import type { TransferSummary } from './transferApi';
import { canApprove, canReceive } from './transferPermissions';
import TransferStatusBadge from './TransferStatusBadge';

const Head = () => (
  <thead>
    <tr>
      <th scope="col">Mã phiếu</th>
      <th scope="col">Kho xuất</th>
      <th scope="col">Kho nhận</th>
      <th scope="col">Ngày tạo</th>
      <th scope="col" className="ck-num">
        Số mặt hàng
      </th>
      <th scope="col">Trạng thái</th>
      <th scope="col">Thao tác</th>
    </tr>
  </thead>
);

const EyeIcon = () => (
  <svg
    width="18"
    height="18"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="1.8"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" />
    <circle cx="12" cy="12" r="3" />
  </svg>
);

export default function TransferTable({
  items,
  user,
}: {
  items: readonly TransferSummary[];
  user: CurrentUser;
}) {
  return (
    <div className="ck-table-wrap">
      <table className="ck-table">
        <Head />
        <tbody>
          {items.map((t) => {
            const detail = transferDetailPath(t.id);
            return (
              <tr key={t.id}>
                <td>
                  <Link className="ck-code" to={detail}>
                    {t.code}
                  </Link>
                </td>
                <td>{t.branch.code}</td>
                <td>{t.toBranch?.code ?? '—'}</td>
                <td>{formatDateTime(t.createdAt)}</td>
                <td className="ck-num">{t.itemCount}</td>
                <td>
                  <span className="ck-badges">
                    <TransferStatusBadge status={t.status} />
                    {t.hasDiscrepancy && (
                      <span className="ck-badge ck-badge--short">Có chênh lệch</span>
                    )}
                  </span>
                </td>
                <td>
                  <span className="ck-actions">
                    <Link
                      className="ck-icon-link"
                      to={detail}
                      aria-label={`Xem chi tiết phiếu ${t.code}`}
                    >
                      <EyeIcon />
                    </Link>
                    {canApprove(user, t) && (
                      <Link className="gm-button gm-button--primary ck-row-action" to={detail}>
                        Duyệt xuất
                      </Link>
                    )}
                    {canReceive(user, t) && (
                      <Link className="gm-button gm-button--primary ck-row-action" to={detail}>
                        Nhận hàng
                      </Link>
                    )}
                  </span>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export function TransferTableSkeleton() {
  return (
    <div className="ck-table-wrap" role="status" aria-busy="true" aria-label="Đang tải">
      <table className="ck-table">
        <Head />
        <tbody>
          {Array.from({ length: 6 }, (_, r) => (
            <tr key={r}>
              {Array.from({ length: 7 }, (_, c) => (
                <td key={c}>
                  <span
                    className="gm-skeleton ck-skel"
                    style={{ width: c === 0 ? '80%' : '55%' }}
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
