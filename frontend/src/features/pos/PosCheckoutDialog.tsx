import { useCallback, useRef, useState } from 'react';
import { completePosOrder, type PaymentMethod, type PosOrder, type Quote } from '../../api/client';
import type { components } from '../../api/schema';
import { Dialog, Input, Toast } from '../../components/ui';
import { mapErrorToState, type UiErrorState } from '../../lib/errors';
import type { CartLine } from './posCart';
import { readChangedQuote, readStockShortages, type StockShortage } from './posErrorDetails';
import { discountMoney, money } from './posMoney';
import './posCheckout.css';

type PosOrderRequest = components['schemas']['PosOrderRequest'];
type Attempt = { key: string; request: PosOrderRequest };
type CheckoutState =
  | { status: 'editing' }
  | { status: 'submitting' }
  | {
      status: 'error';
      error: UiErrorState;
      uncertain: boolean;
      code?: string;
      changedQuote?: Quote;
    }
  | { status: 'success'; order: PosOrder };

interface PosCheckoutDialogProps {
  branchId: number;
  customerId?: number;
  lines: CartLine[];
  quote: Quote;
  manualDiscount?: components['schemas']['ManualDiscount'];
  onClose: () => void;
  onCompleted: () => void;
  onRequote: () => void;
  onQuoteChanged: (quote: Quote) => void;
  onStockConflict: (shortages: StockShortage[], message: string) => void;
}

const methods: { id: PaymentMethod; label: string }[] = [
  { id: 'CASH', label: 'Tiền mặt' },
  { id: 'CARD', label: 'Thẻ' },
  { id: 'QR', label: 'QR' },
];

function cashValue(input: string): number | null {
  const normalized = input.trim();
  if (!/^\d+$/.test(normalized) && !/^\d{1,3}(\.\d{3})+$/.test(normalized)) return null;
  const value = Number(normalized.replaceAll('.', ''));
  return Number.isSafeInteger(value) ? value : null;
}

function responseCode(error: unknown): string | undefined {
  if (typeof error !== 'object' || error === null || !('code' in error)) return undefined;
  return typeof error.code === 'string' ? error.code : undefined;
}

function isUncertain(error: unknown): boolean {
  if (typeof error !== 'object' || error === null || !('status' in error)) return true;
  const status = error.status;
  return (
    typeof status !== 'number' || status >= 500 || responseCode(error) === 'IDEMPOTENCY_CONFLICT'
  );
}

export default function PosCheckoutDialog({
  branchId,
  customerId,
  lines,
  quote,
  manualDiscount,
  onClose,
  onCompleted,
  onRequote,
  onQuoteChanged,
  onStockConflict,
}: PosCheckoutDialogProps) {
  const [method, setMethod] = useState<PaymentMethod>('CASH');
  const [cashReceived, setCashReceived] = useState('');
  const [state, setState] = useState<CheckoutState>({ status: 'editing' });
  const attemptRef = useRef<Attempt | null>(null);
  const inFlightRef = useRef(false);
  const received = cashValue(cashReceived);
  const cashError =
    method === 'CASH' && cashReceived !== ''
      ? received === null
        ? 'Nhập số tiền nguyên hợp lệ.'
        : received < quote.total
          ? 'Tiền khách đưa nhỏ hơn tổng thanh toán.'
          : undefined
      : undefined;
  const backendCashError =
    state.status === 'error' && state.error.kind === 'form'
      ? state.error.fieldErrors['payment.cashReceived']
      : undefined;
  const busy = state.status === 'submitting';
  const uncertain = state.status === 'error' && state.uncertain;
  const locked =
    busy ||
    uncertain ||
    state.status === 'success' ||
    (state.status === 'error' && state.code === 'TOTAL_CHANGED');
  const validCash = method !== 'CASH' || (received !== null && received >= quote.total);
  const requoteOnClose =
    state.status === 'error' &&
    ['TOTAL_CHANGED', 'INSUFFICIENT_STOCK', 'PRODUCT_INACTIVE'].includes(state.code ?? '');

  const handleClose = useCallback(() => {
    if (busy || uncertain) return;
    if (requoteOnClose) onRequote();
    onClose();
  }, [busy, onClose, onRequote, requoteOnClose, uncertain]);

  async function handleConfirm() {
    if (inFlightRef.current || !validCash || state.status === 'success') return;
    // An ambiguous response keeps both the key and the exact request body for every retry.
    const attempt =
      attemptRef.current ??
      ({
        key: crypto.randomUUID(),
        request: {
          branchId,
          ...(customerId === undefined ? {} : { customerId }),
          items: lines.map(({ product, quantity }) => ({ productId: product.id, quantity })),
          ...(manualDiscount ? { manualDiscount } : {}),
          payment: method === 'CASH' ? { method, cashReceived: received! } : { method },
          expectedTotal: quote.total,
        },
      } satisfies Attempt);
    attemptRef.current = attempt;
    inFlightRef.current = true;
    setState({ status: 'submitting' });
    try {
      const order = await completePosOrder(attempt.request, attempt.key);
      attemptRef.current = null;
      setState({ status: 'success', order });
      onCompleted();
    } catch (error) {
      const code = responseCode(error);
      const uncertain = isUncertain(error);
      const mapped = mapErrorToState(error, 'block');
      const details = mapped.kind === 'dialog' ? mapped.details : undefined;
      if (code === 'INSUFFICIENT_STOCK') {
        attemptRef.current = null;
        onStockConflict(
          readStockShortages(details, lines),
          mapped.message ?? 'Không đủ tồn kho khả dụng.',
        );
        return;
      }
      if (!uncertain && code !== 'PAYMENT_FAILED' && code !== 'PAYMENT_TIMEOUT') {
        attemptRef.current = null;
      }
      setState({
        status: 'error',
        error: mapped,
        uncertain,
        code,
        changedQuote:
          code === 'TOTAL_CHANGED'
            ? (readChangedQuote(details, branchId, lines) ?? undefined)
            : undefined,
      });
    } finally {
      inFlightRef.current = false;
    }
  }

  function selectMethod(next: PaymentMethod) {
    if (locked || next === method) return;
    attemptRef.current = null;
    setMethod(next);
    setState({ status: 'editing' });
  }

  function acceptChangedQuote() {
    if (state.status !== 'error' || state.code !== 'TOTAL_CHANGED') return;
    if (!state.changedQuote) {
      handleClose();
      return;
    }
    onQuoteChanged(state.changedQuote);
    setState({ status: 'editing' });
    if (!state.changedQuote.canComplete) onClose();
  }

  const error = state.status === 'error' ? state.error : null;
  const errorCode = state.status === 'error' ? state.code : undefined;
  const changedQuote = state.status === 'error' ? state.changedQuote : undefined;
  const needsQuoteConfirmation = errorCode === 'TOTAL_CHANGED';
  const paymentFailed = errorCode === 'PAYMENT_FAILED' || errorCode === 'PAYMENT_TIMEOUT';
  const errorMessage = error?.message ?? 'Thanh toán chưa hoàn tất.';
  const itemCount = lines.reduce((sum, line) => sum + line.quantity, 0);
  const paid =
    state.status === 'success'
      ? state.order.payments.find((payment) => payment.status === 'PAID')
      : null;

  return (
    <Dialog
      open
      title={state.status === 'success' ? 'Thanh toán thành công' : 'Thanh toán đơn hàng'}
      onClose={handleClose}
      onConfirm={
        state.status === 'success'
          ? handleClose
          : needsQuoteConfirmation
            ? acceptChangedQuote
            : () => void handleConfirm()
      }
      onCancel={state.status === 'success' ? () => window.print() : undefined}
      confirmText={
        state.status === 'success'
          ? 'Đơn hàng mới'
          : needsQuoteConfirmation
            ? changedQuote
              ? 'Xác nhận tổng mới'
              : 'Xem lại báo giá'
            : uncertain || paymentFailed
              ? 'Thử lại'
              : 'Hoàn tất'
      }
      cancelText={state.status === 'success' ? 'In hóa đơn' : 'Quay lại'}
      busy={busy}
      closeDisabled={uncertain}
      confirmDisabled={!needsQuoteConfirmation && !validCash}
    >
      {state.status === 'success' ? (
        <div className="pos-checkout__success" role="status">
          <p className="pos-checkout__receipt-label">HÓA ĐƠN BÁN HÀNG</p>
          <p className="pos-checkout__receipt-meta">
            {state.order.code} · {state.order.branch.code} · Thu ngân{' '}
            {state.order.createdBy?.fullName ?? '—'}
            {state.order.customer && ` · Khách ${state.order.customer.name}`}
          </p>
          <ul className="pos-checkout__receipt-lines">
            {state.order.items.map((item) => (
              <li key={item.id}>
                {item.name} · {item.quantity} × {money(item.unitPrice)}
                {item.discount > 0 &&
                  ` − ${item.promotion?.code ?? 'Giảm giá'} ${money(item.discount)}`}{' '}
                = {money(item.lineTotal)}
              </li>
            ))}
          </ul>
          <dl className="pos-checkout__receipt-totals">
            <div>
              <dt>Tạm tính</dt>
              <dd>{money(state.order.subtotal)}</dd>
            </div>
            <div>
              <dt>Giảm giá đơn hàng</dt>
              <dd>{discountMoney(state.order.orderDiscount)}</dd>
            </div>
            <div>
              <dt>Tổng giảm giá</dt>
              <dd>{discountMoney(state.order.discount)}</dd>
            </div>
            <div className="pos-checkout__receipt-total">
              <dt>Tổng thanh toán</dt>
              <dd>{money(state.order.total)}</dd>
            </div>
          </dl>
          <p className="pos-checkout__receipt-payment">
            {paid?.method === 'CASH'
              ? `Tiền mặt · Khách đưa ${money(paid.cashReceived ?? state.order.total)} · Tiền thừa ${money(paid.change ?? 0)}`
              : `Thanh toán ${paid?.method === 'CARD' ? 'thẻ' : 'QR'} · ${money(state.order.total)}`}
          </p>
          <p className="pos-checkout__receipt-note">
            Đã ghi nhận một đơn hàng. Chỉ bắt đầu giỏ mới sau khi thanh toán thành công.
          </p>
        </div>
      ) : (
        <div className="pos-checkout">
          <p className="pos-checkout__caption">
            {itemCount} mặt hàng · Báo giá {money(quote.total)}
          </p>
          <div className="pos-checkout__methods" role="group" aria-label="Phương thức thanh toán">
            {methods.map((option) => (
              <button
                key={option.id}
                type="button"
                aria-pressed={method === option.id}
                className={`pos-checkout__method${method === option.id ? ' pos-checkout__method--selected' : ''}`}
                disabled={locked}
                onClick={() => selectMethod(option.id)}
              >
                {option.label}
              </button>
            ))}
          </div>
          <dl className="pos-checkout__totals">
            <div>
              <dt>Tạm tính</dt>
              <dd>{money(quote.subtotal)}</dd>
            </div>
            <div>
              <dt>Giảm giá đơn hàng</dt>
              <dd>{discountMoney(quote.orderDiscount)}</dd>
            </div>
            <div>
              <dt>Tổng giảm giá</dt>
              <dd>{discountMoney(quote.discount)}</dd>
            </div>
            <div className="pos-checkout__total">
              <dt>Tổng thanh toán</dt>
              <dd>{money(quote.total)}</dd>
            </div>
          </dl>
          {method === 'CASH' ? (
            <div className="pos-checkout__payment">
              <Input
                label="Tiền khách đưa (VND)"
                value={cashReceived}
                inputMode="numeric"
                helperText={
                  received === null
                    ? 'Ví dụ: 62000 hoặc 62.000'
                    : `Số tiền ghi nhận: ${money(received)}`
                }
                autoComplete="off"
                disabled={locked}
                onChange={(event) => {
                  attemptRef.current = null;
                  setCashReceived(event.target.value);
                  setState({ status: 'editing' });
                }}
                error={cashError ?? backendCashError}
              />
              <p className="pos-checkout__change" role="status">
                Tiền thối:{' '}
                <strong>
                  {received !== null && received >= quote.total
                    ? money(received - quote.total)
                    : '—'}
                </strong>
              </p>
            </div>
          ) : (
            <div className="pos-checkout__payment">
              <strong>Thanh toán bằng {method === 'CARD' ? 'thẻ' : 'QR'}</strong>
              <p>Mô phỏng thanh toán {method === 'CARD' ? 'thẻ' : 'QR'}. Tối đa 60 giây.</p>
            </div>
          )}
          {busy && (
            <p className="pos-checkout__pending" role="status">
              Đang xử lý thanh toán, vui lòng chờ…
            </p>
          )}
          {error && (
            <Toast
              variant="error"
              title={errorMessage}
              description={
                uncertain
                  ? 'Chưa xác định được kết quả. Thử lại với cùng yêu cầu để tránh tạo đơn trùng.'
                  : paymentFailed
                    ? 'Giỏ hàng vẫn được giữ nguyên. Thử lại hoặc chọn phương thức khác.'
                    : needsQuoteConfirmation
                      ? 'Xác nhận tổng mới trước khi thử thanh toán lại. Giỏ hàng vẫn được giữ nguyên.'
                      : requoteOnClose
                        ? 'Quay lại giỏ hàng để cập nhật báo giá.'
                        : 'Giỏ hàng vẫn được giữ nguyên.'
              }
            />
          )}
          {changedQuote && (
            <div className="pos-checkout__changed-total" role="status">
              <span>Tổng trước: {money(quote.total)}</span>
              <strong>Tổng mới: {money(changedQuote.total)}</strong>
            </div>
          )}
        </div>
      )}
    </Dialog>
  );
}
