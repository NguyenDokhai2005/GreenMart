import { useState } from 'react';
import AsyncBlock from '../../components/ui/tsx/AsyncBlock';
import Input from '../../components/ui/tsx/Input';
import Skeleton from '../../components/ui/tsx/Skeleton';
import { listInventory } from '../inventory/inventoryApi';
import type { InventoryItem } from '../inventory/inventoryApi';
import { useDebouncedCallback } from '../inventory/useDebouncedCallback';
import { useRemote } from './useRemote';

interface ProductPickerProps {
  /** Kho gửi: chỉ gợi ý sản phẩm còn tồn khả dụng tại đây. */
  sourceId: number;
  excludeIds: ReadonlySet<number>;
  onPick: (item: InventoryItem) => void;
  onClose: () => void;
}

/** Tìm sản phẩm trong tồn kho của chi nhánh gửi để thêm vào phiếu. */
export default function ProductPicker({
  sourceId,
  excludeIds,
  onPick,
  onClose,
}: ProductPickerProps) {
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  const debounced = useDebouncedCallback((value: string) => setQ(value.trim()), 300);

  const { data, error, isLoading, reload } = useRemote(
    (signal) => listInventory({ branchId: sourceId, q: q || undefined, page: 0, size: 10 }, signal),
    `picker|${sourceId}|${q}`,
  );
  const visible = (data?.content ?? []).filter(
    (i) => i.available > 0 && !excludeIds.has(i.productId),
  );

  return (
    <div className="ck-picker" role="group" aria-label="Thêm sản phẩm vào phiếu">
      <div className="ck-picker__head">
        <Input
          type="search"
          aria-label="Tìm sản phẩm trong kho gửi"
          placeholder="Nhập tên sản phẩm, SKU hoặc barcode..."
          value={text}
          onChange={(e) => {
            setText(e.target.value);
            debounced.call(e.target.value);
          }}
          autoFocus
        />
        <button type="button" className="ck-link-button" onClick={onClose}>
          Đóng
        </button>
      </div>

      <AsyncBlock
        isLoading={isLoading}
        error={error}
        isEmpty={!!data && visible.length === 0}
        onRetry={reload}
        empty={{
          message: q
            ? 'Không tìm thấy sản phẩm còn tồn'
            : 'Kho gửi chưa có sản phẩm còn tồn để chuyển',
        }}
        skeleton={<Skeleton lines={3} height={36} />}
      >
        <ul className="ck-picker__list">
          {visible.map((item) => (
            <li key={item.productId}>
              <button type="button" onClick={() => onPick(item)}>
                <span>
                  <b>{item.productName}</b>
                  <small>{item.sku}</small>
                </span>
                <span className="ck-picker__stock">
                  Khả dụng {item.available} {item.unit}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </AsyncBlock>
    </div>
  );
}
