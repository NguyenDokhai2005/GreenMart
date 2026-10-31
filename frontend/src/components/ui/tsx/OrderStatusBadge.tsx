import '../css/OrderStatusBadge.css';

export type OrderStatus =
  | 'NEW'
  | 'RESERVED'
  | 'CONFIRMED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'EXPIRED'
  | 'REJECTED'
  | 'RETURNED';

export interface OrderStatusBadgeProps {
  status: OrderStatus;
}

const statusLabels: Record<OrderStatus, string> = {
  NEW: 'Mới',
  RESERVED: 'Đang giữ hàng',
  CONFIRMED: 'Đã xác nhận',
  COMPLETED: 'Hoàn tất',
  CANCELLED: 'Đã hủy',
  EXPIRED: 'Hết hạn',
  REJECTED: 'Từ chối',
  RETURNED: 'Đã trả hàng',
};

export default function OrderStatusBadge({ status }: OrderStatusBadgeProps) {
  return (
    <span className={`gm-order-status gm-order-status--${status.toLowerCase()}`}>
      {statusLabels[status]}
    </span>
  );
}
