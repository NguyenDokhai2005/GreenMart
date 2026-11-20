import { useLocation, useNavigate, useParams } from 'react-router';
import { getOrder } from '../../api/client';
import type { components } from '../../api/schema';
import { AsyncBlock, Dialog, OrderStatusBadge } from '../../components/ui';
import { APP_PATHS } from '../auth/routePolicy';
import { useRemote } from '../transfers/useRemote';
import './InventoryLedger.css';

type Order = components['schemas']['Order'];

function formatMoney(value: number): string {
  return new Intl.NumberFormat('vi-VN', {
    style: 'currency',
    currency: 'VND',
    maximumFractionDigits: 0,
  }).format(value);
}

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? '—'
    : new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

function OrderContent({ order }: { order: Order }) {
  return (
    <div className="ledger-order">
      <dl>
        <dt>Mã đơn</dt>
        <dd>{order.code}</dd>
        <dt>Chi nhánh</dt>
        <dd>
          {order.branch.name} ({order.branch.code})
        </dd>
        <dt>Trạng thái</dt>
        <dd>
          <OrderStatusBadge status={order.status} />
        </dd>
        <dt>Ngày tạo</dt>
        <dd>{formatDate(order.createdAt)}</dd>
      </dl>
      <h3>Sản phẩm</h3>
      <div className="ledger-table-wrap">
        <table className="ledger-table">
          <thead>
            <tr>
              <th scope="col">Sản phẩm</th>
              <th scope="col">SKU</th>
              <th scope="col" className="ledger-number">
                Số lượng
              </th>
              <th scope="col" className="ledger-number">
                Thành tiền
              </th>
            </tr>
          </thead>
          <tbody>
            {order.items.map((item) => (
              <tr key={item.id}>
                <td>{item.name}</td>
                <td>{item.sku}</td>
                <td className="ledger-number">{item.quantity}</td>
                <td className="ledger-number">{formatMoney(item.lineTotal)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="ledger-order-total">
        Tổng đơn: <strong>{formatMoney(order.total)}</strong>
      </p>
    </div>
  );
}

export default function LedgerOrderDetailDialog() {
  const { id } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const orderId = Number(id);
  const validId = Number.isSafeInteger(orderId) && orderId > 0;
  const { data, error, isLoading, reload } = useRemote(
    (signal) =>
      validId ? getOrder(orderId, signal) : Promise.reject(new Error('Mã đơn hàng không hợp lệ.')),
    validId ? `ledger-order|${orderId}` : 'ledger-order|invalid',
  );

  return (
    <Dialog
      open
      title="Chi tiết đơn hàng"
      onClose={() => navigate({ pathname: APP_PATHS.ledger, search: location.search })}
      cancelText="Đóng"
    >
      <AsyncBlock
        isLoading={isLoading}
        error={error}
        onRetry={reload}
        skeleton={
          <div className="ledger-order-skeleton" role="status" aria-label="Đang tải đơn hàng" />
        }
      >
        {data ? <OrderContent order={data} /> : null}
      </AsyncBlock>
    </Dialog>
  );
}
