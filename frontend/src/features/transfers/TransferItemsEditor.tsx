import { useState } from 'react';
import EmptyState from '../../components/ui/tsx/EmptyState';
import type { InventoryItem } from '../inventory/inventoryApi';
import ProductPicker from './ProductPicker';
import type { DraftItem } from './transferForm';

interface TransferItemsEditorProps {
  sourceId: number;
  items: readonly DraftItem[];
  availableOf: (item: DraftItem) => number | undefined;
  /** Lỗi theo dòng (khóa productId) và lỗi chung của danh sách. */
  rowErrors: Record<number, string>;
  listError?: string;
  onAdd: (item: InventoryItem) => void;
  onQuantityChange: (productId: number, quantity: string) => void;
  onRemove: (productId: number) => void;
}

export default function TransferItemsEditor({
  sourceId,
  items,
  availableOf,
  rowErrors,
  listError,
  onAdd,
  onQuantityChange,
  onRemove,
}: TransferItemsEditorProps) {
  const [pickerOpen, setPickerOpen] = useState(false);
  const excluded = new Set(items.map((i) => i.productId));

  return (
    <section className="ck-card ck-items" aria-labelledby="ck-items-title">
      <h2 id="ck-items-title">Danh sách sản phẩm chuyển</h2>

      {items.length === 0 ? (
        <EmptyState
          message="Chưa có sản phẩm chuyển"
          description="Thêm sản phẩm cần chuyển để lập phiếu."
          actionLabel="+ Thêm sản phẩm"
          onAction={() => setPickerOpen(true)}
        />
      ) : (
        <div className="ck-table-wrap">
          <table className="ck-table ck-table--compact">
            <thead>
              <tr>
                <th scope="col">Sản phẩm</th>
                <th scope="col">Đơn vị</th>
                <th scope="col" className="ck-num">
                  Tồn khả dụng
                </th>
                <th scope="col">Số lượng chuyển</th>
                <th scope="col">
                  <span className="ck-sr-only">Xóa</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => {
                const available = availableOf(item);
                const error = rowErrors[item.productId];
                const errorId = `ck-qty-err-${item.productId}`;
                return (
                  <tr key={item.productId}>
                    <td>
                      <span className="ck-product">
                        <b>{item.productName}</b>
                        <small>{item.sku}</small>
                      </span>
                    </td>
                    <td>{item.unit}</td>
                    <td className="ck-num">{available ?? '—'}</td>
                    <td>
                      <input
                        type="number"
                        className={`ck-qty${error ? ' ck-qty--error' : ''}`}
                        min={1}
                        max={available}
                        inputMode="numeric"
                        aria-label={`Số lượng chuyển ${item.productName}`}
                        aria-invalid={error ? true : undefined}
                        aria-describedby={error ? errorId : undefined}
                        value={item.quantity}
                        onChange={(e) => onQuantityChange(item.productId, e.target.value)}
                      />
                      {error && (
                        <p id={errorId} className="ck-field-error" role="alert">
                          {error}
                        </p>
                      )}
                    </td>
                    <td>
                      <button
                        type="button"
                        className="ck-icon-button"
                        aria-label={`Xóa ${item.productName} khỏi phiếu`}
                        onClick={() => onRemove(item.productId)}
                      >
                        <svg
                          width="16"
                          height="16"
                          viewBox="0 0 24 24"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth="2"
                          strokeLinecap="round"
                          aria-hidden="true"
                        >
                          <path d="M6 6l12 12M18 6L6 18" />
                        </svg>
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {listError && (
        <p className="ck-field-error" role="alert">
          {listError}
        </p>
      )}

      {pickerOpen ? (
        <ProductPicker
          sourceId={sourceId}
          excludeIds={excluded}
          onPick={(item) => {
            onAdd(item);
            setPickerOpen(false);
          }}
          onClose={() => setPickerOpen(false)}
        />
      ) : (
        items.length > 0 && (
          <button
            type="button"
            className="ck-link-button ck-add"
            onClick={() => setPickerOpen(true)}
          >
            + Thêm sản phẩm
          </button>
        )
      )}
    </section>
  );
}
