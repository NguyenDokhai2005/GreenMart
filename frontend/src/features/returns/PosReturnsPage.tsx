import { useEffect, useRef, useState, type FormEvent } from 'react';
import { getOrder, listOrders, type PosOrder } from '../../api/client';
import type { components } from '../../api/schema';
import { AsyncBlock, Button, Dialog, Input, Skeleton, Toast } from '../../components/ui';
import { mapErrorToState } from '../../lib/errors';
import { useActiveBranch } from '../auth/useActiveBranch';
import { useAuth } from '../auth/useAuth';
import {
  approveReturn,
  cancelReturn,
  createReturn,
  listReturnsByOrder,
  type ReturnDocument,
} from './returnsApi';
import './posReturns.css';

type OrderSummary = components['schemas']['OrderSummary'];
type ReturnItemRequest = components['schemas']['ReturnItemRequest'];
const money = (value: number) => `${new Intl.NumberFormat('vi-VN').format(value)} ₫`;

export default function PosReturnsPage() {
  const branch = useActiveBranch();
  const auth = useAuth();
  const branchId = branch.state.status === 'active' ? branch.state.branch.id : undefined;
  const user = auth.state.status === 'authenticated' ? auth.state.user : undefined;
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [orders, setOrders] = useState<OrderSummary[]>([]);
  const [order, setOrder] = useState<PosOrder | null>(null);
  const [selectedOrderId, setSelectedOrderId] = useState<number | null>(null);
  const [returns, setReturns] = useState<ReturnDocument[]>([]);
  const [loadKey, setLoadKey] = useState('');
  const [loadError, setLoadError] = useState<unknown>();
  const [detailsLoading, setDetailsLoading] = useState(false);
  const [detailsError, setDetailsError] = useState<unknown>();
  const [reason, setReason] = useState('');
  const [items, setItems] = useState<Record<number, ReturnItemRequest>>({});
  const [action, setAction] = useState<{ kind: 'approve' | 'cancel'; document: ReturnDocument }>();
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [formError, setFormError] = useState('');
  const [reasonError, setReasonError] = useState('');
  const [itemErrors, setItemErrors] = useState<Record<number, string>>({});
  const [revision, setRevision] = useState(0);
  const detailRequest = useRef(0);
  const requestKey = `${branchId}:${query}:${revision}`;

  useEffect(() => {
    if (!query) return;
    const controller = new AbortController();
    void listOrders({ q: query, page: 0, size: 20 }, controller.signal).then(
      (page) => {
        if (!controller.signal.aborted) {
          setOrders(page.content);
          setLoadError(undefined);
          setLoadKey(requestKey);
        }
      },
      (error: unknown) => {
        if (!controller.signal.aborted) {
          setLoadError(error);
          setLoadKey(requestKey);
        }
      },
    );
    return () => controller.abort();
  }, [branchId, query, revision, requestKey]);

  async function openOrder(id: number) {
    const request = ++detailRequest.current;
    setSelectedOrderId(id);
    setDetailsLoading(true);
    setDetailsError(undefined);
    setOrder(null);
    try {
      const [nextOrder, documents] = await Promise.all([getOrder(id), listReturnsByOrder(id)]);
      if (request !== detailRequest.current) return;
      setOrder(nextOrder);
      setReturns(documents);
      setItems({});
      setReason('');
    } catch (error) {
      if (request === detailRequest.current) setDetailsError(error);
    } finally {
      if (request === detailRequest.current) setDetailsLoading(false);
    }
  }

  function submitSearch(event: FormEvent) {
    event.preventDefault();
    detailRequest.current += 1;
    setQuery(search.trim());
    setOrder(null);
    setSelectedOrderId(null);
    setReturns([]);
    setDetailsError(undefined);
    setDetailsLoading(false);
  }

  function changeItem(
    orderItemId: number,
    quantity: number,
    condition: ReturnItemRequest['condition'],
  ) {
    setItems((current) => ({ ...current, [orderItemId]: { orderItemId, quantity, condition } }));
    setItemErrors((current) => {
      const next = { ...current };
      delete next[orderItemId];
      return next;
    });
  }

  async function saveDraft() {
    if (!order || !branchId) return;
    const selected = Object.values(items).filter((item) => item.quantity > 0);
    setReasonError('');
    setItemErrors({});
    setFormError('');
    if (reason.trim().length < 3 || reason.trim().length > 255) {
      setReasonError('Lý do trả hàng phải dài từ 3 đến 255 ký tự.');
      return;
    }
    if (selected.length === 0) {
      setFormError('Chọn ít nhất một sản phẩm với số lượng trả lớn hơn 0.');
      return;
    }
    if (selected.some((item) => !Number.isInteger(item.quantity) || item.quantity < 1)) {
      setFormError('Số lượng trả phải là số nguyên dương.');
      return;
    }
    if (
      selected.some(
        (item) =>
          item.quantity >
          available(
            item.orderItemId,
            order.items.find((line) => line.id === item.orderItemId)?.quantity ?? 0,
          ),
      )
    ) {
      setFormError('Số lượng trả vượt số lượng còn có thể trả.');
      return;
    }
    setBusy(true);
    setFormError('');
    try {
      const created = await createReturn({
        orderId: order.id,
        branchId,
        reason: reason.trim(),
        items: selected,
      });
      setReturns((current) => [created, ...current]);
      setItems({});
      setReason('');
      setNotice(`Đã tạo phiếu nháp ${created.code}. Quản lý chi nhánh cần duyệt.`);
    } catch (error) {
      const state = mapErrorToState(error);
      if (state.kind === 'form') {
        setReasonError(state.fieldErrors.reason ?? '');
        const nextItemErrors: Record<number, string> = {};
        selected.forEach((item, index) => {
          const message = state.fieldErrors[`items[${index}].quantity`];
          if (message) nextItemErrors[item.orderItemId] = message;
        });
        setItemErrors(nextItemErrors);
        if (!state.fieldErrors.reason && Object.keys(nextItemErrors).length === 0)
          setFormError(state.message);
      } else {
        setFormError(state.message ?? 'Không thể tạo phiếu trả hàng.');
      }
    } finally {
      setBusy(false);
    }
  }

  async function runAction() {
    if (!action || !order) return;
    setBusy(true);
    try {
      const updated =
        action.kind === 'approve'
          ? await approveReturn(action.document.id)
          : await cancelReturn(action.document.id);
      setReturns((current) => current.map((item) => (item.id === updated.id ? updated : item)));
      setNotice(action.kind === 'approve' ? 'Đã duyệt phiếu trả hàng.' : 'Đã hủy phiếu nháp.');
      setAction(undefined);
      const refreshed = await getOrder(order.id);
      setOrder(refreshed);
      setRevision((value) => value + 1);
    } catch (error) {
      setFormError(mapErrorToState(error).message ?? 'Không thể xử lý phiếu trả hàng.');
      setAction(undefined);
    } finally {
      setBusy(false);
    }
  }

  const available = (id: number, purchased: number) =>
    Math.max(
      0,
      purchased -
        returns
          .filter((document) => document.status === 'DRAFT' || document.status === 'APPROVED')
          .flatMap((document) => document.items)
          .filter((item) => item.orderItemId === id)
          .reduce((sum, item) => sum + item.quantity, 0),
    );
  const mayApprove = user?.role === 'BRANCH_MANAGER' || user?.role === 'OWNER';
  const returnable = order?.status === 'COMPLETED';

  return (
    <section className="pos-returns">
      <h1>Trả hàng</h1>
      <p>Tìm đơn đã hoàn tất theo mã đơn hoặc số điện thoại khách hàng.</p>
      {notice && <Toast title={notice} variant="success" />}
      <form className="pos-returns__search" onSubmit={submitSearch}>
        <Input
          label="Mã đơn hoặc số điện thoại"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <Button type="submit" disabled={!search.trim()}>
          Tìm đơn
        </Button>
      </form>
      {query && (
        <AsyncBlock
          isLoading={loadKey !== requestKey}
          error={loadKey === requestKey ? loadError : undefined}
          onRetry={() => setRevision((value) => value + 1)}
          skeleton={<Skeleton lines={4} />}
          isEmpty={loadKey === requestKey && !loadError && orders.length === 0}
          empty={{ message: 'Không tìm thấy đơn phù hợp' }}
        >
          <div className="pos-returns__orders">
            {orders.map((summary) => (
              <Button
                key={summary.id}
                variant="secondary"
                onClick={() => void openOrder(summary.id)}
              >
                {summary.code} · {summary.status} · {money(summary.total)}
              </Button>
            ))}
          </div>
        </AsyncBlock>
      )}
      {detailsLoading && <Skeleton lines={6} />}
      {detailsError !== undefined && (
        <AsyncBlock
          isLoading={false}
          error={detailsError}
          onRetry={() => {
            if (selectedOrderId !== null) void openOrder(selectedOrderId);
          }}
        >
          <span />
        </AsyncBlock>
      )}
      {order && (
        <section className="pos-returns__detail">
          <h2>Đơn {order.code}</h2>
          <p>
            Trạng thái: {order.status} · Tổng đã thanh toán: {money(order.total)}
          </p>
          {!returnable && <p>Đơn này không còn ở trạng thái cho phép lập phiếu trả hàng.</p>}
          {returnable && (
            <>
              <p>
                Số tiền hoàn chính xác được hệ thống tính khi tạo phiếu, sau khi phân bổ khuyến mại.
              </p>
              <table>
                <thead>
                  <tr>
                    <th>Sản phẩm</th>
                    <th>Đã mua</th>
                    <th>Còn có thể trả</th>
                    <th>Số lượng trả</th>
                    <th>Tình trạng</th>
                  </tr>
                </thead>
                <tbody>
                  {order.items.map((item) => (
                    <tr key={item.id}>
                      <td>
                        {item.name} · {item.sku}
                      </td>
                      <td>{item.quantity}</td>
                      <td>{available(item.id, item.quantity)}</td>
                      <td>
                        <input
                          aria-label={`Số lượng trả ${item.name}`}
                          type="number"
                          min="0"
                          max={available(item.id, item.quantity)}
                          step="1"
                          value={items[item.id]?.quantity ?? 0}
                          onChange={(event) =>
                            changeItem(
                              item.id,
                              Number(event.target.value),
                              items[item.id]?.condition ?? 'RESELLABLE',
                            )
                          }
                          aria-invalid={itemErrors[item.id] ? true : undefined}
                        />
                        {itemErrors[item.id] && <small role="alert">{itemErrors[item.id]}</small>}
                      </td>
                      <td>
                        <select
                          aria-label={`Tình trạng ${item.name}`}
                          value={items[item.id]?.condition ?? 'RESELLABLE'}
                          onChange={(event) =>
                            changeItem(
                              item.id,
                              items[item.id]?.quantity ?? 0,
                              event.target.value as ReturnItemRequest['condition'],
                            )
                          }
                        >
                          <option value="RESELLABLE">Bán lại được</option>
                          <option value="DAMAGED">Hỏng</option>
                        </select>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <Input
                label="Lý do trả hàng"
                value={reason}
                onChange={(event) => {
                  setReason(event.target.value);
                  setReasonError('');
                }}
                error={reasonError}
              />
              <Button disabled={busy} onClick={() => void saveDraft()}>
                Tạo phiếu trả hàng
              </Button>
            </>
          )}
          {formError && <Toast variant="error" title={formError} />}
          <h3>Phiếu trả hàng của đơn</h3>
          {returns.length === 0 ? (
            <p>Chưa có phiếu trả hàng.</p>
          ) : (
            <div className="pos-returns__documents">
              {returns.map((document) => (
                <article key={document.id}>
                  <strong>{document.code}</strong> · {document.status} · Hoàn{' '}
                  {money(document.refundAmount)}
                  <p>Lý do: {document.reason}</p>
                  <ul>
                    {document.items.map((item) => (
                      <li key={item.id}>
                        {item.name}: {item.quantity} ·{' '}
                        {item.condition === 'DAMAGED' ? 'Hỏng' : 'Bán lại được'} ·{' '}
                        {money(item.refundAmount)}
                      </li>
                    ))}
                  </ul>
                  {mayApprove &&
                    document.status === 'DRAFT' &&
                    document.createdBy.id !== user.id && (
                      <div className="pos-returns__actions">
                        <Button onClick={() => setAction({ kind: 'approve', document })}>
                          Duyệt
                        </Button>
                        <Button
                          variant="secondary"
                          onClick={() => setAction({ kind: 'cancel', document })}
                        >
                          Hủy phiếu
                        </Button>
                      </div>
                    )}
                </article>
              ))}
            </div>
          )}
        </section>
      )}
      <Dialog
        open={!!action}
        title={action?.kind === 'approve' ? 'Duyệt phiếu trả hàng' : 'Hủy phiếu nháp'}
        onClose={() => setAction(undefined)}
        onConfirm={() => void runAction()}
        busy={busy}
      >
        <p>
          Xác nhận {action?.kind === 'approve' ? 'duyệt' : 'hủy'} phiếu {action?.document.code}?
        </p>
      </Dialog>
    </section>
  );
}
