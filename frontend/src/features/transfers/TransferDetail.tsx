import { useState } from 'react';
import { Link } from 'react-router';
import type { CurrentUser } from '../../api/client';
import Button from '../../components/ui/tsx/Button';
import { Toast } from '../../components/ui';
import { TRANSFERS_PATH, transferEditPath } from './paths';
import { summarizeReceive, toReceivePayload } from './receiveCalc';
import { approveTransfer, cancelTransfer, receiveTransfer } from './transferApi';
import type { TransferDoc } from './transferApi';
import {
  canApprove,
  canEditOrCancel,
  canReceive,
  isBlockedSelfApproval,
} from './transferPermissions';
import { stepStates, stepSubtitle } from './transferStatus';
import {
  CancelledItemsCard,
  DraftItemsCard,
  ReceiveCard,
  ReceivedCard,
} from './TransferDetailViews';
import TransferInfoCard from './TransferInfoCard';
import TransferStepper from './TransferStepper';
import { useAction } from './useAction';

interface TransferDetailProps {
  doc: TransferDoc;
  user: CurrentUser;
  /** Gọi sau khi một thao tác ghi thành công để tải lại phiếu. */
  onChanged: () => void;
  flash?: 'created' | 'updated';
}

/** Chi tiết phiếu chuyển kho: duyệt xuất (bước 2), nhận hàng và đối soát (bước 3). */
export default function TransferDetail({ doc, user, onChanged, flash }: TransferDetailProps) {
  const approve = useAction();
  const receive = useAction();
  const cancel = useAction();
  // Chặn bấm lặp trong lúc phiếu đang tải lại sau khi thao tác thành công.
  const [submitted, setSubmitted] = useState(false);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [cancelReason, setCancelReason] = useState('');
  const [received, setReceived] = useState<Record<number, string>>(() =>
    Object.fromEntries(doc.items.map((i) => [i.id, String(i.quantity)])),
  );

  const summary = summarizeReceive(
    doc.items.map((i) => ({
      itemId: i.id,
      quantity: i.quantity,
      receivedText: received[i.id] ?? '',
    })),
  );
  const receivedShortfall = doc.items.reduce(
    (sum, i) => sum + Math.max(0, -(i.difference ?? 0)),
    0,
  );
  const hasDiscrepancy =
    doc.status === 'RECEIVED'
      ? (doc.hasDiscrepancy ?? receivedShortfall > 0)
      : summary.hasDiscrepancy;

  const mayReceive = canReceive(user, doc);
  const mayApprove = canApprove(user, doc);
  const mayEdit = canEditOrCancel(user, doc);

  function done<T>(result: T | undefined) {
    if (result !== undefined) {
      setSubmitted(true);
      onChanged();
    }
  }

  const failure = approve.error
    ? { title: 'Không thể duyệt xuất', message: approve.error.message }
    : receive.error
      ? { title: 'Không thể xác nhận nhận hàng', message: receive.error.message }
      : cancel.error
        ? { title: 'Không thể hủy phiếu', message: cancel.error.message }
        : null;

  const busy = approve.busy || receive.busy || cancel.busy || submitted;

  return (
    <>
      <header className="ck-header">
        <div>
          <h1>Phiếu chuyển kho {doc.code}</h1>
          <p>{stepSubtitle(doc.status, hasDiscrepancy)}</p>
        </div>
        <Link className="gm-button gm-button--secondary" to={TRANSFERS_PATH}>
          Quay lại danh sách
        </Link>
      </header>

      <TransferStepper states={stepStates(doc.status)} />

      {flash && (
        <Toast
          variant="success"
          title={flash === 'created' ? `Đã tạo phiếu ${doc.code}` : `Đã lưu phiếu ${doc.code}`}
          description={
            flash === 'created'
              ? 'Phiếu đang ở trạng thái Nháp, chờ quản lý chi nhánh gửi duyệt xuất.'
              : 'Các thay đổi đã được ghi nhận.'
          }
        />
      )}
      {failure && <Toast variant="error" title={failure.title} description={failure.message} />}

      {doc.status === 'IN_TRANSIT' && mayReceive && summary.hasDiscrepancy && (
        <div className="ck-alert ck-alert--danger" role="alert">
          Phát hiện chênh lệch (Thiếu {summary.shortfallTotal} sản phẩm)
        </div>
      )}
      {doc.status === 'RECEIVED' && receivedShortfall > 0 && (
        <div className="ck-alert ck-alert--warning" role="status">
          Phiếu nhận thiếu {receivedShortfall} sản phẩm. Phần thiếu được ghi vào báo cáo hao hụt.
        </div>
      )}
      {doc.status === 'CANCELLED' && (
        <div className="ck-alert ck-alert--muted" role="status">
          Phiếu đã hủy. Tồn kho không thay đổi.
        </div>
      )}

      <div className="ck-columns">
        <div className="ck-stack">
          {doc.status === 'DRAFT' && <DraftItemsCard doc={doc} />}
          {(doc.status === 'IN_TRANSIT' || doc.status === 'APPROVED') && (
            <ReceiveCard
              doc={doc}
              editable={mayReceive}
              values={received}
              onChange={(itemId, text) => setReceived((prev) => ({ ...prev, [itemId]: text }))}
              rows={summary.rows}
            />
          )}
          {doc.status === 'RECEIVED' && <ReceivedCard doc={doc} />}
          {doc.status === 'CANCELLED' && <CancelledItemsCard doc={doc} />}
        </div>

        <aside className="ck-stack">
          <TransferInfoCard doc={doc} />
          {doc.status === 'IN_TRANSIT' && mayReceive && (
            <section className="ck-hint" aria-label="Điều gì xảy ra khi nhận hàng">
              <b>Khi bấm "Xác nhận nhận hàng"</b>
              <ul>
                <li>Phiếu chuyển sang trạng thái Đã nhận.</li>
                <li>Tồn kho nhận tăng theo số thực nhận.</li>
                {summary.hasDiscrepancy && <li>Phần thiếu được ghi vào báo cáo hao hụt.</li>}
              </ul>
            </section>
          )}
        </aside>
      </div>

      {doc.status === 'DRAFT' && (
        <>
          {cancelOpen && (
            <section className="ck-card ck-cancel" aria-label="Hủy phiếu">
              <label className="gm-field__label" htmlFor="ck-cancel-reason">
                Lý do hủy phiếu{' '}
                <span className="gm-field__required" aria-hidden="true">
                  *
                </span>
              </label>
              <textarea
                id="ck-cancel-reason"
                className="ck-textarea"
                rows={3}
                value={cancelReason}
                onChange={(e) => setCancelReason(e.target.value)}
              />
              <div className="ck-cancel__actions">
                <Button
                  variant="secondary"
                  onClick={() => setCancelOpen(false)}
                  disabled={cancel.busy}
                >
                  Không hủy
                </Button>
                <Button
                  variant="destructive"
                  disabled={busy || cancelReason.trim() === ''}
                  onClick={() =>
                    void cancel.run(() => cancelTransfer(doc.id, cancelReason.trim())).then(done)
                  }
                >
                  Xác nhận hủy phiếu
                </Button>
              </div>
            </section>
          )}
          <div className="ck-footer">
            {isBlockedSelfApproval(user, doc) && (
              <span className="ck-footer__hint">
                Người lập phiếu không được tự duyệt. Cần một quản lý chi nhánh khác duyệt xuất.
              </span>
            )}
            {!mayApprove && !isBlockedSelfApproval(user, doc) && (
              <span className="ck-footer__hint">
                Phiếu đang chờ quản lý chi nhánh {doc.branch.code} duyệt xuất.
              </span>
            )}
            {mayEdit && !cancelOpen && (
              <Button variant="secondary" onClick={() => setCancelOpen(true)} disabled={busy}>
                Hủy phiếu
              </Button>
            )}
            {mayEdit && (
              <Link className="gm-button gm-button--secondary" to={transferEditPath(doc.id)}>
                Sửa phiếu
              </Link>
            )}
            {mayApprove && (
              <Button
                disabled={busy}
                onClick={() => void approve.run(() => approveTransfer(doc.id)).then(done)}
              >
                {approve.busy ? 'Đang duyệt…' : 'Duyệt xuất'}
              </Button>
            )}
          </div>
        </>
      )}

      {doc.status === 'IN_TRANSIT' && (
        <div className="ck-footer">
          {mayReceive ? (
            <Button
              disabled={busy || summary.hasInvalid}
              onClick={() =>
                void receive
                  .run(() => receiveTransfer(doc.id, toReceivePayload(summary.rows)))
                  .then(done)
              }
            >
              {receive.busy ? 'Đang xác nhận…' : 'Xác nhận nhận hàng'}
            </Button>
          ) : (
            <span className="ck-footer__hint">
              Đang chờ kho nhận {doc.toBranch?.code ?? ''} xác nhận nhận hàng.
            </span>
          )}
        </div>
      )}
    </>
  );
}
