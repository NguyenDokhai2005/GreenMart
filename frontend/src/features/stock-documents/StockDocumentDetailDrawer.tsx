import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useLocation, useNavigate, useOutletContext, useParams } from 'react-router';
import type { StockDocumentOutletContext } from './StockDocumentsPage';
import { AsyncBlock, Button, Dialog, Input, Toast } from '../../components/ui';
import { mapErrorToState } from '../../lib/errors';
import { APP_PATHS } from '../auth/routePolicy';
import { useRemote } from './useRemote';
import { approveStockDocument, cancelStockDocument, getStockDocument } from './stockDocumentApi';
import type { StockDocument } from './stockDocumentApi';
import {
  exportReasonLabel,
  formatStockDocumentDate,
  formatStockDocumentMoney,
  STOCK_DOCUMENT_STATUS_LABELS,
  STOCK_DOCUMENT_TYPE_LABELS,
} from './stockDocumentPresentation';
import {
  canApproveStockDocument,
  canCancelStockDocument,
  canEditStockDocument,
  isBlockedSelfApproval,
} from './stockDocumentPermissions';
import StockDocumentPageError from './StockDocumentPageError';
import './StockDocuments.css';

function statusClass(status: StockDocument['status']): string {
  if (status === 'DRAFT') return 'sd-badge--draft';
  if (status === 'APPROVED' || status === 'RECEIVED') return 'sd-badge--approved';
  if (status === 'IN_TRANSIT') return 'sd-badge--transit';
  return 'sd-badge--cancelled';
}

function PageContent({ document }: { document: StockDocument }) {
  return (
    <>
      <div className="sd-detail-overview">
        <div>
          <span className="sd-detail-label">Mã phiếu</span>
          <strong>{document.code}</strong>
        </div>
        <span className={`sd-badge ${statusClass(document.status)}`}>
          {STOCK_DOCUMENT_STATUS_LABELS[document.status]}
        </span>
      </div>

      <section className="sd-detail-section" aria-labelledby="sd-detail-info">
        <h3 id="sd-detail-info">Thông tin phiếu</h3>
        <dl className="sd-detail-grid">
          <dt>Loại phiếu</dt>
          <dd>{STOCK_DOCUMENT_TYPE_LABELS[document.type]}</dd>
          <dt>Chi nhánh</dt>
          <dd>
            {document.branch.name} ({document.branch.code})
          </dd>
          {document.toBranch && (
            <>
              <dt>Chi nhánh nhận</dt>
              <dd>
                {document.toBranch.name} ({document.toBranch.code})
              </dd>
            </>
          )}
          {document.supplier && (
            <>
              <dt>Nhà cung cấp</dt>
              <dd>{document.supplier}</dd>
            </>
          )}
          {document.type === 'EXPORT' && (
            <>
              <dt>Lý do xuất hủy</dt>
              <dd>{exportReasonLabel(document.exportReason)}</dd>
            </>
          )}
          <dt>Người lập</dt>
          <dd>{document.createdBy.fullName}</dd>
          <dt>Ngày tạo</dt>
          <dd>{formatStockDocumentDate(document.createdAt)}</dd>
          {document.approvedBy && (
            <>
              <dt>Người duyệt</dt>
              <dd>{document.approvedBy.fullName}</dd>
            </>
          )}
          {document.approvedAt && (
            <>
              <dt>Ngày duyệt</dt>
              <dd>{formatStockDocumentDate(document.approvedAt)}</dd>
            </>
          )}
          {document.totalCost !== undefined && document.totalCost !== null && (
            <>
              <dt>Giá trị phiếu</dt>
              <dd>{formatStockDocumentMoney(document.totalCost)}</dd>
            </>
          )}
          {document.note && (
            <>
              <dt>Ghi chú</dt>
              <dd>{document.note}</dd>
            </>
          )}
        </dl>
      </section>

      <section className="sd-detail-section" aria-labelledby="sd-detail-items">
        <div className="sd-section-heading">
          <h3 id="sd-detail-items">Sản phẩm</h3>
          <span>{document.items.length} mặt hàng</span>
        </div>
        <div className="sd-table-wrap sd-detail-items-scroll">
          <table className="sd-table sd-detail-table">
            <thead>
              <tr>
                <th scope="col">Sản phẩm</th>
                <th scope="col" className="sd-number">
                  Số lượng
                </th>
                {document.type === 'IMPORT' && (
                  <th scope="col" className="sd-number">
                    Giá nhập
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {document.items.map((item) => (
                <tr key={item.id}>
                  <td>
                    <strong>{item.productName}</strong>
                    <small>
                      {item.sku} · {item.unit}
                    </small>
                  </td>
                  <td className="sd-number">
                    {item.quantity} {item.unit}
                  </td>
                  {document.type === 'IMPORT' && (
                    <td className="sd-number">
                      {item.unitCost == null ? '—' : formatStockDocumentMoney(item.unitCost)}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}

export default function StockDocumentDetailDrawer() {
  const { id } = useParams();
  const documentId = Number(id);
  const validId = Number.isSafeInteger(documentId) && documentId > 0;
  const { user, onChanged, onEdit } = useOutletContext<StockDocumentOutletContext>();
  const navigate = useNavigate();
  const location = useLocation();
  const titleId = useId();
  const drawerRef = useRef<HTMLElement>(null);
  const [confirmApprove, setConfirmApprove] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [cancelReason, setCancelReason] = useState('');
  const [cancelError, setCancelError] = useState('');
  const [actionMessage, setActionMessage] = useState('');
  const [success, setSuccess] = useState('');
  const [busy, setBusy] = useState(false);
  const { data, error, isLoading, reload } = useRemote(
    (signal) =>
      validId
        ? getStockDocument(documentId, signal)
        : Promise.reject(new Error('Mã phiếu kho không hợp lệ.')),
    validId ? `document|${documentId}` : 'document|invalid',
  );
  const stockDocument = data?.id === documentId ? data : undefined;
  const close = useCallback(() => {
    navigate({ pathname: APP_PATHS.stockDocuments, search: location.search }, { replace: true });
  }, [location.search, navigate]);

  useEffect(() => {
    const dialog = drawerRef.current;
    const previousFocus =
      globalThis.document.activeElement instanceof HTMLElement
        ? globalThis.document.activeElement
        : null;
    dialog?.focus();
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        close();
        return;
      }
      if (event.key !== 'Tab' || !dialog) return;
      const focusable = Array.from(
        dialog.querySelectorAll<HTMLElement>(
          'button:not(:disabled), input:not(:disabled), textarea:not(:disabled), a[href], [tabindex]:not([tabindex="-1"])',
        ),
      );
      if (focusable.length === 0) {
        event.preventDefault();
        dialog.focus();
      } else if (event.shiftKey && globalThis.document.activeElement === focusable[0]) {
        event.preventDefault();
        focusable[focusable.length - 1].focus();
      } else if (
        !event.shiftKey &&
        globalThis.document.activeElement === focusable[focusable.length - 1]
      ) {
        event.preventDefault();
        focusable[0].focus();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      if (previousFocus?.isConnected) previousFocus.focus();
    };
  }, [close]);

  async function approve() {
    if (!stockDocument || busy) return;
    setBusy(true);
    setActionMessage('');
    try {
      await approveStockDocument(stockDocument.id);
      setConfirmApprove(false);
      setSuccess('Đã duyệt phiếu kho.');
      onChanged();
      reload();
    } catch (requestError) {
      const mapped = mapErrorToState(requestError, 'block');
      setActionMessage(mapped.message ?? 'Không thể duyệt phiếu. Vui lòng thử lại.');
    } finally {
      setBusy(false);
    }
  }

  async function cancel() {
    if (!stockDocument || busy) return;
    if (cancelReason.trim().length < 3) {
      setCancelError('Lý do hủy cần có ít nhất 3 ký tự.');
      return;
    }
    setBusy(true);
    setCancelError('');
    setActionMessage('');
    try {
      await cancelStockDocument(stockDocument.id, cancelReason.trim());
      setConfirmCancel(false);
      setCancelReason('');
      setSuccess('Đã hủy phiếu kho.');
      onChanged();
      reload();
    } catch (requestError) {
      const mapped = mapErrorToState(requestError, 'block');
      setCancelError(mapped.kind === 'form' ? (mapped.fieldErrors.reason ?? '') : '');
      setActionMessage(mapped.message ?? 'Không thể hủy phiếu. Vui lòng thử lại.');
    } finally {
      setBusy(false);
    }
  }

  const pageError = error ? mapErrorToState(error, 'page') : undefined;
  const canApprove = stockDocument ? canApproveStockDocument(user, stockDocument) : false;
  const canCancel = stockDocument ? canCancelStockDocument(user, stockDocument) : false;
  const canEdit = stockDocument ? canEditStockDocument(user, stockDocument) : false;
  const selfApproval = stockDocument ? isBlockedSelfApproval(user, stockDocument) : false;

  return (
    <div
      className="sd-drawer-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !busy) close();
      }}
    >
      <section
        ref={drawerRef}
        className="sd-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
      >
        <header className="sd-drawer-header">
          <div>
            <p>Kho / Phiếu nhập - xuất</p>
            <h2 id={titleId}>Chi tiết phiếu kho</h2>
          </div>
          <button type="button" aria-label="Đóng chi tiết phiếu" onClick={close} disabled={busy}>
            ×
          </button>
        </header>

        <div className="sd-drawer-body">
          {!validId ? (
            <StockDocumentPageError state={{ kind: 'page', page: 'not-found' }} onRetry={reload} />
          ) : pageError?.kind === 'page' ? (
            <StockDocumentPageError state={pageError} onRetry={reload} />
          ) : (
            <>
              {actionMessage && <Toast variant="error" title={actionMessage} />}
              {success && (
                <Toast variant="success" title={success} onClose={() => setSuccess('')} />
              )}
              <AsyncBlock
                isLoading={isLoading}
                error={error}
                onRetry={reload}
                skeleton={
                  <div className="sd-detail-skeleton" role="status" aria-label="Đang tải phiếu" />
                }
              >
                {stockDocument ? <PageContent document={stockDocument} /> : null}
              </AsyncBlock>
              {stockDocument && (canApprove || canCancel || canEdit || selfApproval) && (
                <footer className="sd-detail-actions">
                  {selfApproval && (
                    <p className="sd-self-approval">
                      Bạn là người lập phiếu nên không thể tự duyệt theo BR-13.
                    </p>
                  )}
                  {canEdit && (
                    <Button variant="secondary" onClick={() => onEdit(stockDocument)}>
                      Sửa phiếu
                    </Button>
                  )}
                  {canCancel && (
                    <Button variant="secondary" onClick={() => setConfirmCancel(true)}>
                      Hủy phiếu
                    </Button>
                  )}
                  {canApprove && (
                    <Button onClick={() => setConfirmApprove(true)}>Duyệt phiếu</Button>
                  )}
                </footer>
              )}
            </>
          )}
        </div>
      </section>

      <Dialog
        open={confirmApprove}
        title={`Duyệt ${stockDocument ? STOCK_DOCUMENT_TYPE_LABELS[stockDocument.type].toLowerCase() : 'phiếu'}?`}
        onClose={() => setConfirmApprove(false)}
        onConfirm={() => void approve()}
        confirmText="Duyệt phiếu"
        busy={busy}
      >
        {actionMessage && <Toast variant="error" title={actionMessage} />}
        <p>Sau khi duyệt, tồn kho sẽ được cập nhật và phiếu không thể chỉnh sửa.</p>
      </Dialog>

      <Dialog
        open={confirmCancel}
        title="Hủy phiếu kho?"
        onClose={() => setConfirmCancel(false)}
        onConfirm={() => void cancel()}
        confirmText="Xác nhận hủy"
        destructive
        busy={busy}
      >
        {actionMessage && <Toast variant="error" title={actionMessage} />}
        <Input
          label="Lý do hủy"
          required
          value={cancelReason}
          error={cancelError}
          disabled={busy}
          maxLength={255}
          placeholder="Nhập lý do hủy phiếu"
          onChange={(event) => {
            setCancelReason(event.target.value);
            setCancelError('');
          }}
        />
      </Dialog>
    </div>
  );
}
