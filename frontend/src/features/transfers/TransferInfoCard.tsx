import { formatDateTime } from './formatDate';
import type { TransferDoc } from './transferApi';
import TransferStatusBadge from './TransferStatusBadge';

/** Khối "Thông tin chung" bên phải trang chi tiết. */
export default function TransferInfoCard({ doc }: { doc: TransferDoc }) {
  return (
    <section className="ck-card ck-info" aria-labelledby="ck-info-title">
      <h2 id="ck-info-title">Thông tin chung</h2>
      <dl>
        <dt>Mã phiếu</dt>
        <dd>{doc.code}</dd>
        <dt>Trạng thái</dt>
        <dd>
          <TransferStatusBadge status={doc.status} />
        </dd>
        <dt>Kho gửi</dt>
        <dd>{doc.branch.code}</dd>
        <dt>Kho nhận</dt>
        <dd>{doc.toBranch?.code ?? '—'}</dd>
        <dt>Ngày tạo</dt>
        <dd>{formatDateTime(doc.createdAt)}</dd>
        <dt>Người tạo</dt>
        <dd>{doc.createdBy.fullName}</dd>
        {doc.approvedAt && (
          <>
            <dt>Ngày duyệt</dt>
            <dd>
              {formatDateTime(doc.approvedAt)}
              {doc.approvedBy ? ` · ${doc.approvedBy.fullName}` : ''}
            </dd>
          </>
        )}
        {doc.receivedAt && (
          <>
            <dt>Ngày nhận</dt>
            <dd>
              {formatDateTime(doc.receivedAt)}
              {doc.receivedBy ? ` · ${doc.receivedBy.fullName}` : ''}
            </dd>
          </>
        )}
        <dt>Lý do</dt>
        <dd>{doc.note || '—'}</dd>
      </dl>
    </section>
  );
}
