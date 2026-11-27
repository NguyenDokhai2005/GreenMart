import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useOutletContext, useParams } from 'react-router';
import { AsyncBlock, Button, Toast } from '../../components/ui';
import type { components } from '../../api/schema';
import { mapErrorToState } from '../../lib/errors';
import { APP_PATHS } from '../auth/routePolicy';
import { useRemote } from '../stock-documents/useRemote';
import {
  approveStockDocument,
  getStockDocument,
  reconcileStockDocument,
  updateStockDocument,
} from '../stock-documents/stockDocumentApi';
import type { StockDocument } from '../stock-documents/stockDocumentApi';
import {
  canApproveStockDocument,
  canEditStockDocument,
  isBlockedSelfApproval,
} from '../stock-documents/stockDocumentPermissions';
import { getPendingStocktakeCounts, saveStocktakeCountBatches } from './stocktakeCounts';
import type { StocktakeOutletContext } from './StocktakePage';
import '../stock-documents/StockDocuments.css';
import './stocktake.css';

type CountValues = Record<number, string>;

function countValues(items: StockDocument['items']): CountValues {
  return items.reduce<CountValues>((values, item) => {
    values[item.productId] = item.countedQuantity == null ? '' : String(item.countedQuantity);
    return values;
  }, {});
}

function StocktakeContent({
  document,
  counts,
  setCounts,
  editable,
}: {
  document: StockDocument;
  counts: CountValues;
  setCounts: (update: (current: CountValues) => CountValues) => void;
  editable: boolean;
}) {
  return (
    <section className="sd-detail-section" aria-labelledby="stocktake-count-title">
      <div className="sd-section-heading">
        <div>
          <h3 id="stocktake-count-title">Số đếm và chênh lệch</h3>
          <p>Chênh lệch = số đếm thực tế − tồn tại thời điểm chụp phiếu.</p>
        </div>
        <span>{document.items.length} mặt hàng</span>
      </div>
      <div className="sd-table-wrap sd-detail-items-scroll">
        <table className="sd-table sd-detail-table">
          <thead>
            <tr>
              <th scope="col">Sản phẩm</th>
              <th scope="col" className="sd-number">
                Tồn snapshot
              </th>
              <th scope="col" className="sd-number">
                Số đếm thực tế
              </th>
              <th scope="col" className="sd-number">
                Chênh lệch
              </th>
            </tr>
          </thead>
          <tbody>
            {document.items.map((item) => {
              const text = counts[item.productId] ?? '';
              const counted = text === '' ? undefined : Number(text);
              const difference =
                counted !== undefined && Number.isSafeInteger(counted)
                  ? counted - item.quantity
                  : undefined;
              return (
                <tr key={item.productId}>
                  <td>
                    <strong>{item.productName}</strong>
                    <small>
                      {item.sku} · {item.unit}
                    </small>
                  </td>
                  <td className="sd-number">
                    {item.quantity} {item.unit}
                  </td>
                  <td className="sd-number">
                    {editable ? (
                      <label className="stocktake-count-field">
                        <span className="sd-sr-only">Số đếm {item.productName}</span>
                        <input
                          type="number"
                          min={0}
                          step={1}
                          value={text}
                          aria-label={`Số đếm ${item.productName}`}
                          onChange={(event) =>
                            setCounts((current) => ({
                              ...current,
                              [item.productId]: event.target.value,
                            }))
                          }
                        />
                      </label>
                    ) : (
                      <>
                        {item.countedQuantity ?? '—'} {item.unit}
                      </>
                    )}
                  </td>
                  <td
                    className={`sd-number ${difference !== undefined && difference < 0 ? 'stocktake-difference--negative' : ''}`}
                  >
                    {difference === undefined ? '—' : `${difference > 0 ? '+' : ''}${difference}`}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function Reconciliation({
  reconciliation,
}: {
  reconciliation: components['schemas']['StocktakeReconciliation'];
}) {
  const blockedLines = reconciliation.lines.filter((line) => line.shortfall > 0);
  if (reconciliation.inventoryReady && blockedLines.length === 0) return null;
  return (
    <section className="stocktake-reconciliation" aria-labelledby="stocktake-reconciliation-title">
      <h3 id="stocktake-reconciliation-title">Đối soát hàng đang giữ</h3>
      {!reconciliation.inventoryReady && blockedLines.length > 0 && (
        <p role="alert">
          Tồn dự kiến thấp hơn hàng đang giữ. Không thể duyệt phiếu kiểm kê cho đến khi đối soát
          xong.
        </p>
      )}
      {!reconciliation.inventoryReady && blockedLines.length === 0 && (
        <p role="alert">Phiếu chưa đủ điều kiện duyệt; kiểm tra số đếm và đọc lại đối soát.</p>
      )}
      {blockedLines.map((line) => (
        <article className="stocktake-reconciliation__line" key={line.productId}>
          <strong>{line.sku}</strong>
          <dl>
            <dt>Tồn dự kiến</dt>
            <dd>{line.projectedOnHand ?? '—'}</dd>
            <dt>Đang giữ</dt>
            <dd>{line.reserved}</dd>
            <dt>Thiếu</dt>
            <dd>{line.shortfall}</dd>
            {line.reservationGap !== 0 && (
              <>
                <dt>Chênh lệch dữ liệu giữ chỗ</dt>
                <dd>{line.reservationGap}</dd>
              </>
            )}
          </dl>
          {line.reservationOrders.length > 0 && (
            <ul>
              {line.reservationOrders.map((order) => (
                <li key={order.orderId}>
                  {order.code} · {order.status} · {order.quantity}
                </li>
              ))}
            </ul>
          )}
          {line.reservationOrderCount > line.reservationOrders.length && (
            <p>
              Hiển thị {line.reservationOrders.length} trong {line.reservationOrderCount} đơn đang
              giữ.
            </p>
          )}
        </article>
      ))}
    </section>
  );
}

export default function StocktakeDetailDrawer() {
  const { id } = useParams();
  const documentId = Number(id);
  const validId = Number.isSafeInteger(documentId) && documentId > 0;
  const { user, onChanged } = useOutletContext<StocktakeOutletContext>();
  const navigate = useNavigate();
  const location = useLocation();
  const drawerRef = useRef<HTMLElement>(null);
  const [countEdits, setCountEdits] = useState<Record<number, CountValues>>({});
  const [savedCountValues, setSavedCountValues] = useState<Record<number, CountValues>>({});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const {
    data: documentData,
    error,
    isLoading,
    reload,
  } = useRemote(
    (signal) =>
      validId
        ? getStockDocument(documentId, signal).then((value) => {
            if (value.type !== 'ADJUST') throw new Error('Phiếu không phải phiếu kiểm kê.');
            return value;
          })
        : Promise.reject(new Error('Mã phiếu kiểm kê không hợp lệ.')),
    validId ? `stocktake-document|${documentId}` : 'stocktake-document|invalid',
  );
  const stocktake = documentData?.id === documentId ? documentData : undefined;
  const originalCounts = stocktake ? countValues(stocktake.items) : {};
  const counts = { ...originalCounts, ...countEdits[documentId] };
  const savedCounts = savedCountValues[documentId] ?? originalCounts;
  const setCounts = (update: (current: CountValues) => CountValues) => {
    setCountEdits((current) => ({
      ...current,
      [documentId]: update({ ...originalCounts, ...current[documentId] }),
    }));
  };
  const reconciliation = useRemote(
    (signal) =>
      validId && stocktake?.status === 'DRAFT'
        ? reconcileStockDocument(documentId, signal)
        : Promise.resolve(null),
    validId
      ? `stocktake-reconciliation|${documentId}|${stocktake?.status ?? 'loading'}`
      : 'stocktake-reconciliation|invalid',
  );

  useEffect(() => {
    const previousFocus =
      globalThis.document.activeElement instanceof HTMLElement
        ? globalThis.document.activeElement
        : null;
    drawerRef.current?.focus();
    return () => {
      if (previousFocus?.isConnected) previousFocus.focus();
    };
  }, []);

  function close() {
    navigate({ pathname: APP_PATHS.stocktake, search: location.search }, { replace: true });
  }

  const editable = stocktake ? canEditStockDocument(user, stocktake) : false;
  const changed = Object.keys(counts).some(
    (key) => counts[Number(key)] !== savedCounts[Number(key)],
  );
  const pendingCountChanges = stocktake
    ? getPendingStocktakeCounts(stocktake.items, counts, savedCounts)
    : [];
  const hasClearedSavedCounts =
    stocktake?.items.some(
      (item) =>
        counts[item.productId] !== savedCounts[item.productId] && counts[item.productId] === '',
    ) ?? false;
  const invalidCounts =
    stocktake?.items.some((item) => {
      const value = counts[item.productId] ?? '';
      return value !== '' && (!Number.isSafeInteger(Number(value)) || Number(value) < 0);
    }) ?? false;
  const allCounted =
    stocktake?.items.every((item) => {
      const value = counts[item.productId] ?? '';
      return value !== '' && Number.isSafeInteger(Number(value)) && Number(value) >= 0;
    }) ?? false;
  const canApprove =
    !!stocktake &&
    canApproveStockDocument(user, stocktake) &&
    allCounted &&
    !invalidCounts &&
    !changed &&
    reconciliation.data?.lines.every((line) => line.shortfall === 0) === true &&
    reconciliation.data?.inventoryReady === true &&
    !reconciliation.isLoading &&
    !reconciliation.error;
  const selfApproval = stocktake ? isBlockedSelfApproval(user, stocktake) : false;

  async function saveCounts() {
    if (!stocktake || busy || invalidCounts || !changed || pendingCountChanges.length === 0) {
      if (hasClearedSavedCounts && pendingCountChanges.length === 0) {
        setMessage('Không thể xóa số đếm đã lưu bằng cách để trống. Hãy nhập số đếm hợp lệ.');
      }
      return;
    }
    setBusy(true);
    setMessage('');
    let savedItemCount = 0;
    try {
      await saveStocktakeCountBatches(
        stocktake.id,
        pendingCountChanges,
        updateStockDocument,
        (batch) => {
          savedItemCount += batch.length;
          setSavedCountValues((current) => ({
            ...current,
            [stocktake.id]: {
              ...(current[stocktake.id] ?? originalCounts),
              ...Object.fromEntries(
                batch.map((item) => [item.productId, String(item.countedQuantity)]),
              ),
            },
          }));
        },
      );
      onChanged();
      reload();
      reconciliation.reload();
      setMessage(
        hasClearedSavedCounts
          ? `Đã lưu ${savedItemCount} số đếm. Ô để trống chưa được lưu; hãy nhập số đếm hợp lệ.`
          : `Đã lưu ${savedItemCount} số đếm kiểm kê.`,
      );
    } catch (error) {
      const mapped = mapErrorToState(error, 'block');
      if (savedItemCount > 0) {
        onChanged();
        reload();
        reconciliation.reload();
        setMessage(
          `Đã lưu ${savedItemCount} số đếm. Các dòng còn lại vẫn chưa lưu. ${mapped.message ?? 'Không thể tiếp tục lưu.'}`,
        );
      } else {
        setMessage(mapped.message ?? 'Không thể lưu số đếm.');
      }
    } finally {
      setBusy(false);
    }
  }

  async function approve() {
    if (!stocktake || busy || !canApprove) return;
    setBusy(true);
    setMessage('');
    try {
      await approveStockDocument(stocktake.id);
      onChanged();
      reload();
      setMessage('Đã duyệt phiếu kiểm kê.');
    } catch (error) {
      const mapped = mapErrorToState(error, 'block');
      setMessage(mapped.message ?? 'Không thể duyệt phiếu kiểm kê.');
      reconciliation.reload();
    } finally {
      setBusy(false);
    }
  }

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
        aria-label="Chi tiết phiếu kiểm kê"
        tabIndex={-1}
      >
        <header className="sd-drawer-header">
          <div>
            <p>Kho / Kiểm kê</p>
            <h2>Chi tiết phiếu kiểm kê</h2>
          </div>
          <button
            type="button"
            aria-label="Đóng chi tiết phiếu kiểm kê"
            onClick={close}
            disabled={busy}
          >
            ×
          </button>
        </header>
        <div className="sd-drawer-body">
          {message && (
            <Toast variant={message.startsWith('Đã') ? 'success' : 'error'} title={message} />
          )}
          <AsyncBlock
            isLoading={isLoading}
            error={error}
            onRetry={reload}
            skeleton={
              <div
                className="sd-detail-skeleton"
                role="status"
                aria-label="Đang tải phiếu kiểm kê"
              />
            }
          >
            {stocktake && (
              <>
                <div className="sd-detail-overview">
                  <div>
                    <span className="sd-detail-label">Mã phiếu</span>
                    <strong>{stocktake.code}</strong>
                  </div>
                  <span
                    className={`sd-badge ${stocktake.status === 'DRAFT' ? 'sd-badge--draft' : 'sd-badge--approved'}`}
                  >
                    {stocktake.status === 'DRAFT'
                      ? 'Bản nháp'
                      : stocktake.status === 'APPROVED'
                        ? 'Đã duyệt'
                        : 'Đã hủy'}
                  </span>
                </div>
                <dl className="sd-detail-grid">
                  <dt>Chi nhánh</dt>
                  <dd>{stocktake.branch.name}</dd>
                  <dt>Người lập</dt>
                  <dd>{stocktake.createdBy.fullName}</dd>
                  <dt>Thời điểm chụp tồn</dt>
                  <dd>
                    {new Intl.DateTimeFormat('vi-VN', {
                      dateStyle: 'medium',
                      timeStyle: 'short',
                    }).format(new Date(stocktake.createdAt))}
                  </dd>
                </dl>
                <StocktakeContent
                  document={stocktake}
                  counts={counts}
                  setCounts={setCounts}
                  editable={editable}
                />
                <AsyncBlock
                  isLoading={reconciliation.isLoading}
                  error={reconciliation.error}
                  onRetry={reconciliation.reload}
                  skeleton={
                    <div
                      className="sd-detail-skeleton"
                      role="status"
                      aria-label="Đang đối soát tồn"
                    />
                  }
                >
                  {reconciliation.data && <Reconciliation reconciliation={reconciliation.data} />}
                </AsyncBlock>
                {selfApproval && (
                  <p className="sd-self-approval">Bạn là người lập phiếu nên không thể tự duyệt.</p>
                )}
                <footer className="sd-detail-actions">
                  {editable && stocktake.status === 'DRAFT' && (
                    <Button
                      variant="secondary"
                      onClick={() => void saveCounts()}
                      disabled={busy || invalidCounts || !changed}
                    >
                      {busy ? 'Đang lưu…' : 'Lưu số đếm'}
                    </Button>
                  )}
                  {canApprove && (
                    <Button onClick={() => void approve()} disabled={busy}>
                      {busy ? 'Đang duyệt…' : 'Duyệt kiểm kê'}
                    </Button>
                  )}
                  {stocktake.status === 'DRAFT' &&
                    !reconciliation.isLoading &&
                    reconciliation.data?.inventoryReady === false && (
                      <p className="stocktake-approval-note">
                        Chưa thể duyệt: cần xử lý đối soát hàng đang giữ trước.
                      </p>
                    )}
                  {canApproveStockDocument(user, stocktake) && !allCounted && (
                    <p className="stocktake-approval-note">
                      Nhập đầy đủ số đếm và lưu trước khi duyệt.
                    </p>
                  )}
                </footer>
              </>
            )}
          </AsyncBlock>
        </div>
      </section>
    </div>
  );
}
