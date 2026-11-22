import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { getOrder, listOrders, type PosOrder } from '../../api/client';
import type { components } from '../../api/schema';
import { AsyncBlock, Button, EmptyState, ErrorState, Skeleton } from '../../components/ui';
import { useActiveBranch } from '../auth/useActiveBranch';
import type { PosPageProps } from '../../layouts/pos/PosPlaceholderPage';
import { PosPageError } from '../../layouts/pos/PosRouteContent';
import { discountMoney, money } from './posMoney';
import './posReceipts.css';

type SummaryPage = components['schemas']['OrderSummaryPage'];
type LoadState<T> =
  | { status: 'loading'; key: string }
  | { status: 'ready'; key: string; data: T }
  | { status: 'error'; key: string; error: unknown };

const dateTime = (value: string) =>
  new Intl.DateTimeFormat('vi-VN', {
    timeZone: 'Asia/Ho_Chi_Minh',
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(new Date(value));

function Receipt({ order, onBack }: { order: PosOrder; onBack: () => void }) {
  const payment = order.payments.find((item) => item.status === 'PAID');

  return (
    <>
      <div className="pos-receipts__actions">
        <Button variant="secondary" onClick={onBack}>
          Danh sách hóa đơn
        </Button>
        <Button onClick={() => window.print()}>In hóa đơn</Button>
      </div>
      <article className="pos-receipts__paper" aria-label={`Hóa đơn ${order.code}`}>
        <header className="pos-receipts__paper-header">
          <strong>GreenMart</strong>
          <h2>HÓA ĐƠN BÁN HÀNG</h2>
          <p>{order.code}</p>
          <small>{dateTime(order.completedAt ?? order.createdAt)}</small>
        </header>
        <dl className="pos-receipts__identity">
          <div>
            <dt>Chi nhánh</dt>
            <dd>
              {order.branch.code} · {order.branch.name}
            </dd>
          </div>
          <div>
            <dt>Thu ngân</dt>
            <dd>{order.createdBy?.fullName ?? '—'}</dd>
          </div>
          {order.customer && (
            <div>
              <dt>Khách hàng</dt>
              <dd>
                {order.customer.name} · {order.customer.phone}
              </dd>
            </div>
          )}
        </dl>
        <table className="pos-receipts__items">
          <thead>
            <tr>
              <th scope="col">Sản phẩm</th>
              <th scope="col">SL</th>
              <th scope="col">Đơn giá</th>
              <th scope="col">Giảm</th>
              <th scope="col">Thành tiền</th>
            </tr>
          </thead>
          <tbody>
            {order.items.map((item) => (
              <tr key={item.id}>
                <td>
                  <strong>{item.name}</strong>
                  <small>
                    {item.sku} · {item.unit}
                    {item.promotion && ` · ${item.promotion.code}`}
                  </small>
                </td>
                <td>{item.quantity}</td>
                <td>{money(item.unitPrice)}</td>
                <td>{item.discount ? `−${money(item.discount)}` : '—'}</td>
                <td>{money(item.lineTotal)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <dl className="pos-receipts__totals">
          <div>
            <dt>Tạm tính</dt>
            <dd>{money(order.subtotal)}</dd>
          </div>
          <div>
            <dt>Giảm theo dòng</dt>
            <dd>{discountMoney(order.itemDiscount)}</dd>
          </div>
          <div>
            <dt>Giảm giá đơn hàng{order.orderPromotion && ` · ${order.orderPromotion.code}`}</dt>
            <dd>{discountMoney(order.orderDiscount)}</dd>
          </div>
          {order.manualDiscount > 0 && (
            <div>
              <dt>Giảm thủ công</dt>
              <dd>−{money(order.manualDiscount)}</dd>
            </div>
          )}
          <div>
            <dt>Tổng giảm giá</dt>
            <dd>{discountMoney(order.discount)}</dd>
          </div>
          <div className="pos-receipts__total">
            <dt>Tổng thanh toán</dt>
            <dd>{money(order.total)}</dd>
          </div>
          {payment && (
            <div>
              <dt>Phương thức</dt>
              <dd>
                {payment.method === 'CASH' ? 'Tiền mặt' : payment.method === 'CARD' ? 'Thẻ' : 'QR'}
              </dd>
            </div>
          )}
          {payment?.method === 'CASH' && payment.cashReceived != null && (
            <div>
              <dt>Tiền khách đưa</dt>
              <dd>{money(payment.cashReceived)}</dd>
            </div>
          )}
          {payment?.method === 'CASH' && payment.change != null && (
            <div>
              <dt>Tiền thối</dt>
              <dd>{money(payment.change)}</dd>
            </div>
          )}
        </dl>
        <p className="pos-receipts__thanks">Cảm ơn quý khách đã mua sắm tại GreenMart!</p>
      </article>
    </>
  );
}

function ReceiptDetail({
  orderId,
  branchId,
  onBack,
}: {
  orderId: number;
  branchId: number;
  onBack: () => void;
}) {
  const [state, setState] = useState<LoadState<PosOrder>>({ status: 'loading', key: '' });
  const [attempt, setAttempt] = useState(0);
  const key = `${orderId}:${attempt}`;
  const loading = state.key !== key || state.status === 'loading';

  useEffect(() => {
    const controller = new AbortController();
    void getOrder(orderId, controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setState({ status: 'ready', key, data });
      },
      (error: unknown) => {
        if (!controller.signal.aborted) setState({ status: 'error', key, error });
      },
    );
    return () => controller.abort();
  }, [orderId, key]);

  if (!loading && state.status === 'error') {
    return <PosPageError error={state.error} onRetry={() => setAttempt((value) => value + 1)} />;
  }
  if (
    !loading &&
    state.status === 'ready' &&
    (state.data.branch.id !== branchId ||
      state.data.channel !== 'POS' ||
      state.data.status !== 'COMPLETED')
  ) {
    return (
      <ErrorState message="Không thể in đơn này tại chi nhánh POS đang chọn." onRetry={onBack} />
    );
  }
  return (
    <AsyncBlock
      key={key}
      isLoading={loading}
      onRetry={() => setAttempt((value) => value + 1)}
      skeleton={<Skeleton lines={8} />}
    >
      {!loading && state.status === 'ready' && <Receipt order={state.data} onBack={onBack} />}
    </AsyncBlock>
  );
}

function ReceiptList({ branchId, onOpen }: { branchId: number; onOpen: (id: number) => void }) {
  const [state, setState] = useState<LoadState<SummaryPage>>({ status: 'loading', key: '' });
  const [page, setPage] = useState(0);
  const [attempt, setAttempt] = useState(0);
  const key = `${branchId}:${page}:${attempt}`;
  const loading = state.key !== key || state.status === 'loading';

  useEffect(() => {
    const controller = new AbortController();
    void listOrders({ branchId, channel: 'POS', page, size: 20 }, controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setState({ status: 'ready', key, data });
      },
      (error: unknown) => {
        if (!controller.signal.aborted) setState({ status: 'error', key, error });
      },
    );
    return () => controller.abort();
  }, [branchId, key, page]);

  if (!loading && state.status === 'error') {
    return <PosPageError error={state.error} onRetry={() => setAttempt((value) => value + 1)} />;
  }
  return (
    <AsyncBlock
      key={key}
      isLoading={loading}
      onRetry={() => setAttempt((value) => value + 1)}
      skeleton={<Skeleton lines={6} />}
    >
      {!loading &&
        state.status === 'ready' &&
        (state.data.content.length === 0 ? (
          <EmptyState
            message="Chưa có hóa đơn"
            description="Hóa đơn sẽ xuất hiện sau khi hoàn tất đơn bán tại quầy."
          />
        ) : (
          <>
            <ul className="pos-receipts__list">
              {state.data.content.map((order) => (
                <li key={order.id}>
                  <button type="button" onClick={() => onOpen(order.id)}>
                    <span>
                      <strong>{order.code}</strong>
                      <small>{dateTime(order.completedAt ?? order.createdAt)}</small>
                    </span>
                    <span>{money(order.total)}</span>
                  </button>
                </li>
              ))}
            </ul>
            {state.data.totalPages > 1 && (
              <nav className="pos-receipts__pagination" aria-label="Phân trang hóa đơn">
                <Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
                  Trước
                </Button>
                <span>
                  Trang {page + 1}/{state.data.totalPages}
                </span>
                <Button
                  variant="secondary"
                  disabled={page + 1 >= state.data.totalPages}
                  onClick={() => setPage(page + 1)}
                >
                  Sau
                </Button>
              </nav>
            )}
          </>
        ))}
    </AsyncBlock>
  );
}

export default function PosReceiptsPage({ item }: PosPageProps) {
  const branch = useActiveBranch();
  const [params, setParams] = useSearchParams();
  const rawOrderId = params.get('orderId');
  const orderId = rawOrderId && /^\d+$/.test(rawOrderId) ? Number(rawOrderId) : null;
  const branchId = branch.state.status === 'active' ? branch.state.branch.id : 0;

  return (
    <section className="pos-receipts" aria-label={item.title}>
      <h1 data-pos-heading tabIndex={-1}>
        Hóa đơn
      </h1>
      {!branchId ? (
        <ErrorState message="Chưa chọn chi nhánh." />
      ) : rawOrderId && (!orderId || !Number.isSafeInteger(orderId)) ? (
        <ErrorState message="Mã hóa đơn không hợp lệ." />
      ) : orderId ? (
        <ReceiptDetail orderId={orderId} branchId={branchId} onBack={() => setParams({})} />
      ) : (
        <ReceiptList branchId={branchId} onOpen={(id) => setParams({ orderId: String(id) })} />
      )}
    </section>
  );
}
