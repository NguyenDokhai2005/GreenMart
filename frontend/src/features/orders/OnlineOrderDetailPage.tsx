import { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { cancelOnlineOrder, completeOnlineOrder, getOrder, type PosOrder } from '../../api/client';
import type { components } from '../../api/schema';
import {
  AsyncBlock,
  Button,
  Dialog,
  OrderStatusBadge,
  Select,
  Skeleton,
  Toast,
} from '../../components/ui';
import { mapErrorToState } from '../../lib/errors';
import { useAuth } from '../auth/useAuth';
import { useActiveBranch } from '../auth/useActiveBranch';
import ForbiddenPage from '../../pages/ForbiddenPage';
import NotFoundPage from '../../pages/NotFoundPage';
import OfflinePage from '../../pages/OfflinePage';
import ServerErrorPage from '../../pages/ServerErrorPage';
import SessionExpiredPage from '../../pages/SessionExpiredPage';
import './onlineOrderDetail.css';

type CancelReason = components['schemas']['CancelOrderRequest']['reason'];
type Action = 'complete' | 'cancel' | null;

const cancelReasons: { value: CancelReason; label: string }[] = [
  { value: 'CUSTOMER_REQUEST', label: 'Khách yêu cầu hủy' },
  { value: 'OUT_OF_STOCK_ACTUAL', label: 'Hết hàng thực tế' },
  { value: 'WRONG_INFO', label: 'Sai thông tin đơn' },
  { value: 'OTHER', label: 'Lý do khác' },
];
const money = (value: number) => `${new Intl.NumberFormat('vi-VN').format(value)} đ`;
const dateTime = new Intl.DateTimeFormat('vi-VN', {
  timeZone: 'Asia/Ho_Chi_Minh',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

function formatDate(value?: string | null) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : dateTime.format(date);
}

function useOrderDetail(orderId: number) {
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{ key: string; data?: PosOrder; error?: unknown } | null>(
    null,
  );
  const key = `${orderId}:${attempt}`;
  useEffect(() => {
    if (!Number.isSafeInteger(orderId) || orderId < 1) return;
    const controller = new AbortController();
    void getOrder(orderId, controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setResult({ key, data });
      },
      (error: unknown) => {
        if (!controller.signal.aborted) setResult({ key, error });
      },
    );
    return () => controller.abort();
  }, [key, orderId]);
  return {
    data: result?.data,
    error: result?.key === key ? result.error : undefined,
    isLoading: result?.key !== key,
    reload: () => setAttempt((value) => value + 1),
    setData: (data: PosOrder) => setResult({ key, data }),
  };
}

export default function OnlineOrderDetailPage() {
  const orderId = Number(useParams().orderId);
  const navigate = useNavigate();
  const location = useLocation();
  const { state } = useAuth();
  const branch = useActiveBranch();
  const { data, error, isLoading, reload, setData } = useOrderDetail(orderId);
  const [action, setAction] = useState<Action>(null);
  const [busy, setBusy] = useState(false);
  const [reason, setReason] = useState<CancelReason | ''>('');
  const [note, setNote] = useState('');
  const [actionError, setActionError] = useState<unknown>(null);
  const [notice, setNotice] = useState('');
  const role = state.status === 'authenticated' ? state.user.role : undefined;
  const isOnline = data?.channel === 'WEB' || data?.channel === 'MARKET';
  const canComplete =
    isOnline &&
    data?.status === 'CONFIRMED' &&
    (role === 'BRANCH_MANAGER' || role === 'STOREKEEPER');
  const canCancel =
    isOnline &&
    (data?.status === 'RESERVED' || data?.status === 'CONFIRMED') &&
    role === 'BRANCH_MANAGER';
  const mappedError = actionError ? mapErrorToState(actionError, 'block') : null;
  const fieldErrors = mappedError?.kind === 'form' ? mappedError.fieldErrors : {};
  const missingReason = action === 'cancel' && !reason;
  const missingNote = action === 'cancel' && reason === 'OTHER' && !note.trim();

  const closeAction = useCallback(() => {
    if (busy) return;
    setAction(null);
    setActionError(null);
  }, [busy]);

  async function submitAction() {
    if (!data || !action || busy || missingReason || missingNote) return;
    setBusy(true);
    setActionError(null);
    try {
      const updated =
        action === 'complete'
          ? await completeOnlineOrder(data.id)
          : await cancelOnlineOrder(data.id, {
              reason: reason as CancelReason,
              ...(note.trim() ? { note: note.trim() } : {}),
            });
      setData(updated);
      setAction(null);
      setNotice(action === 'complete' ? 'Đơn đã hoàn tất.' : 'Đơn đã hủy.');
    } catch (failure) {
      setActionError(failure);
      if (
        typeof failure === 'object' &&
        failure !== null &&
        'status' in failure &&
        failure.status === 409
      )
        reload();
    } finally {
      setBusy(false);
    }
  }

  if (!Number.isSafeInteger(orderId) || orderId < 1) {
    return <NotFoundPage onHome={() => navigate('/operations/orders')} />;
  }

  const pageError = error ? mapErrorToState(error, 'page') : null;
  if (pageError?.kind === 'page') {
    if (pageError.page === 'not-found')
      return <NotFoundPage onHome={() => navigate('/operations/orders')} />;
    if (pageError.page === 'forbidden')
      return (
        <ForbiddenPage
          onHome={() => navigate('/operations')}
          onSwitchBranch={() => {
            branch.clearBranch();
            navigate('/select-branch');
          }}
          message={pageError.message}
        />
      );
    if (pageError.page === 'offline') return <OfflinePage onReconnect={reload} />;
    if (pageError.page === 'session-expired')
      return (
        <SessionExpiredPage onLogin={() => navigate('/login', { state: { from: location } })} />
      );
    return <ServerErrorPage traceId={pageError.traceId} onRetry={reload} />;
  }

  return (
    <section className="online-order" aria-label="Chi tiết đơn hàng">
      <AsyncBlock
        isLoading={isLoading}
        error={error}
        onRetry={reload}
        skeleton={
          <div className="online-order__columns">
            <Skeleton lines={8} />
            <Skeleton lines={6} />
          </div>
        }
      >
        {data && (
          <>
            <header className="online-order__header">
              <div>
                <Link className="online-order__back" to="/operations/orders">
                  ‹ Danh sách đơn
                </Link>
                <h1>
                  Đơn {data.code} <OrderStatusBadge status={data.status} />
                </h1>
                <p>
                  Kênh {data.channel} · {data.branch.code} - {data.branch.name} ·{' '}
                  {formatDate(data.createdAt)}
                </p>
              </div>
            </header>
            <div className="online-order__columns">
              <div className="online-order__main">
                <section className="online-order__card" aria-labelledby="order-items-heading">
                  <h2 id="order-items-heading">Sản phẩm trong đơn</h2>
                  <div className="online-order__table-wrap">
                    <table>
                      <thead>
                        <tr>
                          <th>SKU</th>
                          <th>Sản phẩm</th>
                          <th>ĐVT</th>
                          <th>SL</th>
                          <th>Đơn giá</th>
                          <th>Giảm</th>
                          <th>Thành tiền</th>
                        </tr>
                      </thead>
                      <tbody>
                        {data.items.map((item) => (
                          <tr key={item.id}>
                            <td>{item.sku}</td>
                            <td>{item.name}</td>
                            <td>{item.unit}</td>
                            <td>{item.quantity}</td>
                            <td>{money(item.unitPrice)}</td>
                            <td>{item.discount ? money(item.discount) : '—'}</td>
                            <td>{money(item.lineTotal)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <dl className="online-order__totals">
                    <div>
                      <dt>Tạm tính</dt>
                      <dd>{money(data.subtotal)}</dd>
                    </div>
                    <div>
                      <dt>Giảm giá sản phẩm</dt>
                      <dd>{money(data.itemDiscount)}</dd>
                    </div>
                    <div>
                      <dt>Giảm giá đơn hàng</dt>
                      <dd>{money(data.orderDiscount)}</dd>
                    </div>
                    <div>
                      <dt>Tổng giảm giá</dt>
                      <dd>{money(data.discount)}</dd>
                    </div>
                    <div className="online-order__total">
                      <dt>Tổng thanh toán</dt>
                      <dd>{money(data.total)}</dd>
                    </div>
                  </dl>
                </section>
                <section className="online-order__card" aria-labelledby="order-payment-heading">
                  <h2 id="order-payment-heading">Thanh toán</h2>
                  {data.payments.length ? (
                    data.payments.map((payment) => (
                      <div className="online-order__payment" key={payment.id}>
                        <strong>
                          {payment.method} · {money(payment.amount)}
                        </strong>
                        <span>
                          {payment.status === 'REFUNDED'
                            ? 'Đã hoàn tiền'
                            : payment.status === 'PAID'
                              ? 'Đã thanh toán'
                              : payment.status}
                        </span>
                        <small>
                          {formatDate(payment.paidAt)}{' '}
                          {payment.providerRef && `· ${payment.providerRef}`}
                        </small>
                      </div>
                    ))
                  ) : (
                    <p>Chưa có giao dịch thanh toán.</p>
                  )}
                </section>
              </div>
              <aside className="online-order__sidebar">
                <section className="online-order__card" aria-labelledby="order-info-heading">
                  <h2 id="order-info-heading">Thông tin đơn</h2>
                  <dl className="online-order__facts">
                    <div>
                      <dt>Mã đơn</dt>
                      <dd>{data.code}</dd>
                    </div>
                    <div>
                      <dt>Mã đơn kênh</dt>
                      <dd>{data.externalOrderId || '—'}</dd>
                    </div>
                    <div>
                      <dt>Kênh</dt>
                      <dd>{data.channel}</dd>
                    </div>
                    <div>
                      <dt>Chi nhánh</dt>
                      <dd>
                        {data.branch.code} - {data.branch.name}
                      </dd>
                    </div>
                    <div>
                      <dt>Ngày tạo</dt>
                      <dd>{formatDate(data.createdAt)}</dd>
                    </div>
                    {data.completedAt && (
                      <div>
                        <dt>Hoàn tất lúc</dt>
                        <dd>{formatDate(data.completedAt)}</dd>
                      </div>
                    )}
                    {data.statusReason && (
                      <div>
                        <dt>Lý do hủy</dt>
                        <dd>
                          {cancelReasons.find((entry) => entry.value === data.statusReason)
                            ?.label ?? data.statusReason}
                        </dd>
                      </div>
                    )}
                    {data.statusNote && (
                      <div>
                        <dt>Ghi chú</dt>
                        <dd>{data.statusNote}</dd>
                      </div>
                    )}
                  </dl>
                </section>
                <section className="online-order__card" aria-labelledby="order-customer-heading">
                  <h2 id="order-customer-heading">Khách hàng</h2>
                  <p>{data.customer?.name ?? 'Khách lẻ'}</p>
                  {data.customer?.phone && <p>{data.customer.phone}</p>}
                </section>
                <section
                  className="online-order__card online-order__actions"
                  aria-labelledby="order-actions-heading"
                >
                  <h2 id="order-actions-heading">Xử lý đơn</h2>
                  {canComplete || canCancel ? (
                    <>
                      <p>
                        {data.status === 'CONFIRMED'
                          ? 'Đơn đã xác nhận, chờ bàn giao cho khách.'
                          : 'Đơn đang giữ hàng, chờ xác nhận.'}
                      </p>
                      {canComplete && (
                        <Button onClick={() => setAction('complete')}>Hoàn tất đơn</Button>
                      )}
                      {canCancel && (
                        <Button
                          variant="destructive"
                          onClick={() => {
                            setReason('');
                            setNote('');
                            setAction('cancel');
                          }}
                        >
                          Hủy đơn
                        </Button>
                      )}
                    </>
                  ) : (
                    <p>
                      {data.status === 'COMPLETED'
                        ? 'Đơn đã hoàn tất, không thể hủy.'
                        : data.status === 'CANCELLED'
                          ? 'Đơn đã hủy, không thể xử lý thêm.'
                          : 'Không có thao tác phù hợp với trạng thái hoặc quyền hiện tại.'}
                    </p>
                  )}
                </section>
              </aside>
            </div>
          </>
        )}
      </AsyncBlock>
      {notice && <Toast variant="success" title={notice} onClose={() => setNotice('')} />}
      {action && data && (
        <Dialog
          open
          title={action === 'complete' ? `Hoàn tất đơn ${data.code}?` : `Hủy đơn ${data.code}?`}
          onClose={closeAction}
          onConfirm={() => void submitAction()}
          confirmText={action === 'complete' ? 'Hoàn tất đơn' : 'Hủy đơn'}
          cancelText="Quay lại"
          destructive={action === 'cancel'}
          busy={busy}
          confirmDisabled={missingReason || missingNote}
        >
          {action === 'cancel' ? (
            <div className="online-order__cancel-form">
              <p>
                Hàng giữ chỗ sẽ được giải phóng. Đơn đã thanh toán sẽ được hoàn tiền theo phương
                thức gốc.
              </p>
              <Select
                label="Lý do hủy"
                value={reason}
                options={cancelReasons}
                placeholder="Chọn lý do hủy"
                error={fieldErrors.reason}
                onChange={(event) => {
                  setReason(event.target.value as CancelReason);
                  setActionError(null);
                }}
              />
              <label htmlFor="order-cancel-note">Ghi chú</label>
              <textarea
                id="order-cancel-note"
                value={note}
                maxLength={255}
                aria-invalid={!!fieldErrors.note}
                onChange={(event) => {
                  setNote(event.target.value);
                  setActionError(null);
                }}
              />
              {fieldErrors.note && <p role="alert">{fieldErrors.note}</p>}
              <small>Ghi chú bắt buộc khi chọn “Lý do khác”, tối đa 255 ký tự.</small>
            </div>
          ) : (
            <p>
              Xác nhận đơn đã bàn giao cho khách. Sau khi hoàn tất, trạng thái sẽ chuyển sang Hoàn
              tất.
            </p>
          )}
          {mappedError && (
            <p className="online-order__action-error" role="alert">
              {mappedError.message}
            </p>
          )}
        </Dialog>
      )}
    </section>
  );
}
