import type { ReactNode } from 'react';
import Skeleton from '../../components/ui/tsx/Skeleton';
import { getStockItem } from './transferApi';
import type { TransferDoc } from './transferApi';
import { formatDifference } from './receiveCalc';
import type { ReceiveRowResult } from './receiveCalc';
import { useRemote } from './useRemote';

const Product = ({ name, sku }: { name: string; sku: string }) => (
  <span className="ck-product">
    <b>{name}</b>
    <small>{sku}</small>
  </span>
);

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="ck-card ck-items">
      <h2>{title}</h2>
      <div className="ck-table-wrap">{children}</div>
    </section>
  );
}

/** Bước 2 (phiếu Nháp): kiểm đếm trước khi giao vận chuyển, kèm tồn khả dụng hiện tại của kho gửi. */
export function DraftItemsCard({ doc }: { doc: TransferDoc }) {
  const ids = doc.items.map((i) => i.productId).join(',');
  const stock = useRemote(
    async (signal) =>
      new Map(
        await Promise.all(
          doc.items.map((i) =>
            getStockItem(doc.branch.id, i.productId, signal).then(
              (r) => [i.productId, r.available] as const,
              () => [i.productId, undefined] as const,
            ),
          ),
        ),
      ),
    `stock|${doc.branch.id}|${ids}`,
  );

  return (
    <Card title="Kiểm đếm thực tế trước khi giao vận chuyển">
      <table className="ck-table ck-table--compact">
        <thead>
          <tr>
            <th scope="col">Sản phẩm</th>
            <th scope="col">Đơn vị</th>
            <th scope="col" className="ck-num">
              SL yêu cầu
            </th>
            <th scope="col" className="ck-num">
              Tồn khả dụng {doc.branch.code}
            </th>
          </tr>
        </thead>
        <tbody>
          {doc.items.map((it) => {
            const available = stock.data?.get(it.productId);
            const short = available !== undefined && it.quantity > available;
            return (
              <tr key={it.id}>
                <td>
                  <Product name={it.productName} sku={it.sku} />
                </td>
                <td>{it.unit}</td>
                <td className="ck-num">{it.quantity}</td>
                <td className={`ck-num${short ? ' ck-diff--out' : ''}`}>
                  {stock.isLoading ? <Skeleton width={32} height={14} /> : (available ?? '—')}
                  {short && <span className="ck-sr-only"> (không đủ tồn)</span>}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </Card>
  );
}

interface ReceiveCardProps {
  doc: TransferDoc;
  /** true: người dùng thuộc kho nhận, nhập được số thực nhận. */
  editable: boolean;
  values: Record<number, string>;
  onChange: (itemId: number, text: string) => void;
  rows: ReceiveRowResult[];
}

/** Bước 3 (Đang vận chuyển): đối soát số thực nhận với số thực xuất. */
export function ReceiveCard({ doc, editable, values, onChange, rows }: ReceiveCardProps) {
  return (
    <Card title="Đối soát nhận hàng">
      <table className="ck-table ck-table--compact">
        <thead>
          <tr>
            <th scope="col">Sản phẩm</th>
            <th scope="col">Đơn vị</th>
            <th scope="col" className="ck-num">
              SL thực xuất
            </th>
            <th scope="col">SL thực nhận</th>
            <th scope="col" className="ck-num">
              Chênh lệch
            </th>
          </tr>
        </thead>
        <tbody>
          {doc.items.map((it, index) => {
            const row = rows[index];
            const diff = row?.difference ?? null;
            const short = diff !== null && diff < 0;
            const errorId = `ck-recv-err-${it.id}`;
            return (
              <tr key={it.id}>
                <td>
                  <Product name={it.productName} sku={it.sku} />
                </td>
                <td>{it.unit}</td>
                <td className="ck-num">{it.quantity}</td>
                <td>
                  {editable ? (
                    <>
                      <input
                        type="number"
                        min={0}
                        max={it.quantity}
                        inputMode="numeric"
                        className={`ck-qty${short ? ' ck-qty--short' : ''}${row?.error ? ' ck-qty--error' : ''}`}
                        aria-label={`SL thực nhận ${it.productName}`}
                        aria-invalid={row?.error ? true : undefined}
                        aria-describedby={row?.error ? errorId : undefined}
                        value={values[it.id] ?? ''}
                        onChange={(e) => onChange(it.id, e.target.value)}
                      />
                      {row?.error && (
                        <p id={errorId} className="ck-field-error" role="alert">
                          {row.error}
                        </p>
                      )}
                    </>
                  ) : (
                    '—'
                  )}
                </td>
                <td className={`ck-num ck-diff ${short ? 'ck-diff--entry-short' : 'ck-diff--ok'}`}>
                  {diff === null ? '—' : formatDifference(diff)}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </Card>
  );
}

/** Phiếu đã nhận: số thực nhận và chênh lệch; chênh lệch âm màu cam. */
export function ReceivedCard({ doc }: { doc: TransferDoc }) {
  return (
    <Card title="Kết quả nhận hàng">
      <table className="ck-table ck-table--compact">
        <thead>
          <tr>
            <th scope="col">Sản phẩm</th>
            <th scope="col">Đơn vị</th>
            <th scope="col" className="ck-num">
              SL thực xuất
            </th>
            <th scope="col" className="ck-num">
              SL thực nhận
            </th>
            <th scope="col" className="ck-num">
              Chênh lệch
            </th>
          </tr>
        </thead>
        <tbody>
          {doc.items.map((it) => {
            const received = it.receivedQuantity ?? null;
            const diff = it.difference ?? (received === null ? null : received - it.quantity);
            const short = diff !== null && diff < 0;
            return (
              <tr key={it.id}>
                <td>
                  <Product name={it.productName} sku={it.sku} />
                </td>
                <td>{it.unit}</td>
                <td className="ck-num">{it.quantity}</td>
                <td className="ck-num">{received ?? '—'}</td>
                <td className={`ck-num ck-diff ${short ? 'ck-diff--short' : 'ck-diff--ok'}`}>
                  {diff === null ? '—' : formatDifference(diff)}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </Card>
  );
}

/** Phiếu đã hủy: chỉ liệt kê số lượng yêu cầu. */
export function CancelledItemsCard({ doc }: { doc: TransferDoc }) {
  return (
    <Card title="Sản phẩm trên phiếu">
      <table className="ck-table ck-table--compact">
        <thead>
          <tr>
            <th scope="col">Sản phẩm</th>
            <th scope="col">Đơn vị</th>
            <th scope="col" className="ck-num">
              SL yêu cầu
            </th>
          </tr>
        </thead>
        <tbody>
          {doc.items.map((it) => (
            <tr key={it.id}>
              <td>
                <Product name={it.productName} sku={it.sku} />
              </td>
              <td>{it.unit}</td>
              <td className="ck-num">{it.quantity}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}
