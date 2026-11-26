import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { AsyncBlock, Dialog, Input, Select, Toast } from '../../components/ui';
import { searchProducts } from '../../api/client';
import type { components } from '../../api/schema';
import { mapErrorToState } from '../../lib/errors';
import { listInventory } from '../inventory/inventoryApi';
import { useDebouncedCallback } from '../inventory/useDebouncedCallback';
import { useRemote } from './useRemote';
import { EXPORT_REASON_OPTIONS, formatStockDocumentMoney } from './stockDocumentPresentation';
import { createStockDocument, updateStockDocument } from './stockDocumentApi';
import type { ExportReason, StockDocument, StockDocumentType } from './stockDocumentApi';
import './StockDocuments.css';

type DraftType = Extract<StockDocumentType, 'IMPORT' | 'EXPORT'>;
type DraftLine = {
  productId: number;
  productName: string;
  sku: string;
  unit: string;
  quantity: string;
  unitCost: string;
  available?: number;
};
type ProductOption = Pick<DraftLine, 'productId' | 'productName' | 'sku' | 'unit' | 'available'>;

interface StockDocumentFormDialogProps {
  type: DraftType;
  branchId?: number;
  document?: StockDocument;
  onClose: () => void;
  onSaved: (document: StockDocument) => void;
}

interface ProductPickerProps {
  type: DraftType;
  branchId: number;
  selectedIds: ReadonlySet<number>;
  disabled: boolean;
  onPick: (option: ProductOption) => void;
}

function ProductPicker({ type, branchId, selectedIds, disabled, onPick }: ProductPickerProps) {
  const [searchText, setSearchText] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const debouncedSearch = useDebouncedCallback((value: string) => setQuery(value.trim()), 300);
  const { data, error, isLoading, reload } = useRemote(async (signal) => {
    if (type === 'IMPORT') {
      const result = await searchProducts(query, signal, page - 1, 20);
      return {
        totalPages: result.totalPages,
        content: result.content.map((product): ProductOption => ({
          productId: product.id,
          productName: product.name,
          sku: product.sku,
          unit: product.unit,
        })),
      };
    }
    const result = await listInventory(
      { branchId, q: query || undefined, page: page - 1, size: 20 },
      signal,
    );
    return {
      totalPages: result.totalPages,
      content: result.content.map((item): ProductOption => ({
        productId: item.productId,
        productName: item.productName,
        sku: item.sku,
        unit: item.unit,
        available: item.available,
      })),
    };
  }, `${type}|${branchId}|${query}|${page}`);
  const visible = (data?.content ?? []).filter(
    (option) =>
      (type === 'IMPORT' || (option.available ?? 0) > 0) && !selectedIds.has(option.productId),
  );

  return (
    <section className="sd-picker" aria-label="Thêm sản phẩm vào phiếu">
      <Input
        label={type === 'IMPORT' ? 'Tìm sản phẩm nhập' : 'Tìm sản phẩm còn khả dụng'}
        type="search"
        value={searchText}
        disabled={disabled}
        placeholder="Tên sản phẩm, SKU hoặc barcode..."
        onChange={(event) => {
          setSearchText(event.target.value);
          setPage(1);
          debouncedSearch.call(event.target.value);
        }}
      />
      <AsyncBlock
        isLoading={isLoading}
        error={error}
        isEmpty={data !== undefined && visible.length === 0}
        onRetry={reload}
        empty={{
          message:
            type === 'EXPORT'
              ? 'Không tìm thấy sản phẩm còn tồn khả dụng'
              : 'Không tìm thấy sản phẩm phù hợp',
        }}
        skeleton={
          <div className="sd-picker-skeleton" role="status" aria-label="Đang tải sản phẩm" />
        }
      >
        <ul className="sd-picker-list">
          {visible.slice(0, 20).map((option) => (
            <li key={option.productId}>
              <button type="button" disabled={disabled} onClick={() => onPick(option)}>
                <span>
                  <strong>{option.productName}</strong>
                  <small>
                    {option.sku} · {option.unit}
                  </small>
                </span>
                {option.available !== undefined && (
                  <span className="sd-availability">
                    Khả dụng {option.available} {option.unit}
                  </span>
                )}
                <span className="sd-add-label">Thêm</span>
              </button>
            </li>
          ))}
        </ul>
        {data && data.totalPages > 1 && (
          <nav className="sd-picker-pager" aria-label="Phân trang sản phẩm">
            <button
              type="button"
              disabled={page <= 1}
              onClick={() => setPage((current) => current - 1)}
            >
              Trang trước
            </button>
            <span>
              {page}/{data.totalPages}
            </span>
            <button
              type="button"
              disabled={page >= data.totalPages}
              onClick={() => setPage((current) => current + 1)}
            >
              Trang sau
            </button>
          </nav>
        )}
      </AsyncBlock>
    </section>
  );
}

function readFieldErrors(error: unknown): Record<string, string> {
  const mapped = mapErrorToState(error, 'block');
  return mapped.kind === 'form' ? mapped.fieldErrors : {};
}

function validInteger(value: string, minimum: number): boolean {
  if (!value.trim()) return false;
  const number = Number(value);
  return Number.isSafeInteger(number) && number >= minimum;
}

export default function StockDocumentFormDialog({
  type,
  branchId,
  document,
  onClose,
  onSaved,
}: StockDocumentFormDialogProps) {
  const [supplier, setSupplier] = useState(document?.supplier ?? '');
  const [exportReason, setExportReason] = useState<ExportReason | ''>(document?.exportReason ?? '');
  const [note, setNote] = useState(document?.note ?? '');
  const [lines, setLines] = useState<DraftLine[]>(
    document?.items.map((item) => ({
      productId: item.productId,
      productName: item.productName,
      sku: item.sku,
      unit: item.unit,
      quantity: String(item.quantity),
      unitCost: item.unitCost == null ? '' : String(item.unitCost),
    })) ?? [],
  );
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState('');
  const [success, setSuccess] = useState('');
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const pending = useRef<AbortController | null>(null);

  useEffect(
    () => () => {
      pending.current?.abort();
    },
    [],
  );

  const title = document
    ? type === 'IMPORT'
      ? 'Sửa phiếu nhập kho'
      : 'Sửa phiếu xuất hủy'
    : type === 'IMPORT'
      ? 'Tạo phiếu nhập kho'
      : 'Tạo phiếu xuất hủy';
  const selectedIds = new Set(lines.map((line) => line.productId));
  const total = lines.reduce(
    (sum, line) => sum + Number(line.quantity || 0) * Number(line.unitCost || 0),
    0,
  );

  function close() {
    if (busy) return;
    if (dirty && !uncertain) {
      setConfirmDiscard(true);
      return;
    }
    onClose();
  }

  function addProduct(option: ProductOption) {
    setLines((current) => [
      ...current,
      {
        ...option,
        quantity: '1',
        unitCost: '',
      },
    ]);
    setDirty(true);
    setErrors({});
  }

  function updateLine(index: number, field: 'quantity' | 'unitCost', value: string) {
    setLines((current) =>
      current.map((line, currentIndex) =>
        currentIndex === index ? { ...line, [field]: value } : line,
      ),
    );
    setDirty(true);
    setErrors((current) => {
      const next = { ...current };
      delete next[`items[${index}].${field}`];
      return next;
    });
  }

  function validate(): boolean {
    const next: Record<string, string> = {};
    if (type === 'IMPORT' && !supplier.trim()) next.supplier = 'Vui lòng nhập nhà cung cấp.';
    if (type === 'EXPORT' && !exportReason) next.exportReason = 'Vui lòng chọn lý do xuất hủy.';
    if (type === 'EXPORT' && exportReason === 'OTHER' && !note.trim()) {
      next.note = 'Vui lòng ghi rõ lý do xuất hủy khác.';
    }
    if (lines.length === 0) next.items = 'Phiếu cần có ít nhất một sản phẩm.';

    lines.forEach((line, index) => {
      if (!validInteger(line.quantity, 1)) {
        next[`items[${index}].quantity`] = 'Số lượng phải là số nguyên lớn hơn 0.';
      } else if (Number(line.quantity) > 99_999) {
        next[`items[${index}].quantity`] = 'Số lượng tối đa là 99.999.';
      } else if (
        type === 'EXPORT' &&
        line.available !== undefined &&
        Number(line.quantity) > line.available
      ) {
        next[`items[${index}].quantity`] = `Không vượt quá ${line.available} sản phẩm khả dụng.`;
      }
      if (type === 'IMPORT' && !validInteger(line.unitCost, 0)) {
        next[`items[${index}].unitCost`] = 'Giá nhập phải là số nguyên không âm.';
      }
    });

    setErrors(next);
    return Object.keys(next).length === 0;
  }

  async function submit() {
    if (busy || pending.current || uncertain || !validate()) return;
    if (branchId === undefined) {
      setMessage('Chọn một chi nhánh cụ thể trước khi lập phiếu.');
      return;
    }

    const items = lines.map((line) => ({
      productId: line.productId,
      quantity: Number(line.quantity),
      ...(type === 'IMPORT' ? { unitCost: Number(line.unitCost) } : {}),
    }));
    let body: components['schemas']['StockDocumentRequest'];
    if (type === 'IMPORT') {
      body = {
        type,
        branchId,
        supplier: supplier.trim(),
        note: note.trim() || undefined,
        items,
      };
    } else {
      if (!exportReason) return;
      body = {
        type,
        branchId,
        exportReason,
        note: note.trim() || undefined,
        items,
      };
    }

    const controller = new AbortController();
    pending.current = controller;
    setBusy(true);
    setMessage('');
    setSuccess('');
    const timeout = globalThis.setTimeout(() => {
      controller.abort();
      pending.current = null;
      setBusy(false);
      setUncertain(true);
      setMessage('Chưa xác nhận được kết quả lưu. Hãy kiểm tra danh sách trước khi gửi lại.');
    }, 15_000);

    try {
      let savedDocument: StockDocument;
      if (document) {
        const updateBody: components['schemas']['StockDocumentUpdateRequest'] = {
          note: note.trim() || undefined,
          items: items.map(({ productId, quantity, unitCost }) => ({
            productId,
            quantity,
            ...(type === 'IMPORT' ? { unitCost } : {}),
          })),
        };
        if (type === 'IMPORT') {
          updateBody.supplier = supplier.trim();
        } else {
          if (!exportReason) {
            setErrors((current) => ({
              ...current,
              exportReason: 'Vui lòng chọn lý do xuất hủy.',
            }));
            return;
          }
          updateBody.exportReason = exportReason;
        }
        savedDocument = await updateStockDocument(document.id, updateBody, controller.signal);
      } else {
        savedDocument = await createStockDocument(body, controller.signal);
      }
      if (controller.signal.aborted) return;
      setSuccess(`Đã lưu phiếu nháp ${savedDocument.code}.`);
      setDirty(false);
      onSaved(savedDocument);
    } catch (error) {
      if (controller.signal.aborted) return;
      const mapped = mapErrorToState(error, 'block');
      setErrors(readFieldErrors(error));
      setMessage(mapped.message ?? 'Không thể lưu phiếu. Vui lòng thử lại.');
    } finally {
      globalThis.clearTimeout(timeout);
      if (!controller.signal.aborted) {
        pending.current = null;
        setBusy(false);
      }
    }
  }

  function onTextChange(
    setter: (value: string) => void,
    event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>,
    field: string,
  ) {
    setter(event.target.value);
    setDirty(true);
    setErrors((current) => {
      const next = { ...current };
      delete next[field];
      return next;
    });
  }

  return (
    <>
      <Dialog
        open
        title={title}
        onClose={close}
        onConfirm={() => void submit()}
        confirmText={document ? 'Lưu thay đổi' : 'Lưu phiếu nháp'}
        cancelText="Đóng"
        busy={busy}
        confirmDisabled={uncertain || branchId === undefined}
      >
        <div className="sd-editor">
          <p className="sd-editor-intro">
            {type === 'IMPORT'
              ? 'Phiếu được lưu ở trạng thái Nháp; tồn kho chỉ tăng sau khi QL chi nhánh duyệt.'
              : 'Phiếu được lưu ở trạng thái Nháp; tồn khả dụng được kiểm tra lại khi QL chi nhánh duyệt.'}
          </p>
          {message && <Toast variant={uncertain ? 'warning' : 'error'} title={message} />}
          {success && <Toast variant="success" title={success} />}
          {branchId === undefined && (
            <Toast variant="warning" title="Cần chọn chi nhánh để lập phiếu kho." />
          )}

          {type === 'IMPORT' ? (
            <Input
              label="Nhà cung cấp"
              required
              value={supplier}
              disabled={busy}
              error={errors.supplier}
              placeholder="Tên nhà cung cấp"
              onChange={(event) => onTextChange(setSupplier, event, 'supplier')}
            />
          ) : (
            <Select
              label="Lý do xuất hủy"
              required
              value={exportReason}
              disabled={busy}
              error={errors.exportReason}
              options={EXPORT_REASON_OPTIONS.map((option) => ({ ...option }))}
              onChange={(event) => {
                setExportReason(event.target.value as ExportReason | '');
                setDirty(true);
                setErrors((current) => {
                  const next = { ...current };
                  delete next.exportReason;
                  return next;
                });
              }}
            />
          )}

          <div className="sd-lines">
            <div className="sd-section-heading">
              <div>
                <h3>Sản phẩm trong phiếu</h3>
                <p>
                  Chọn từng sản phẩm rồi nhập số lượng{type === 'IMPORT' ? ' và giá nhập' : ''}.
                </p>
              </div>
              {lines.length > 0 && <span>{lines.length} mặt hàng</span>}
            </div>
            {errors.items && (
              <p className="sd-field-error" role="alert">
                {errors.items}
              </p>
            )}

            {branchId !== undefined && (
              <ProductPicker
                type={type}
                branchId={branchId}
                selectedIds={selectedIds}
                disabled={busy}
                onPick={addProduct}
              />
            )}

            {lines.length > 0 && (
              <div className="sd-table-wrap sd-lines-table-wrap">
                <table className="sd-table sd-lines-table">
                  <thead>
                    <tr>
                      <th scope="col">Sản phẩm</th>
                      {type === 'EXPORT' && <th scope="col">Khả dụng</th>}
                      <th scope="col">Số lượng</th>
                      {type === 'IMPORT' && <th scope="col">Giá nhập (VND)</th>}
                      <th scope="col">
                        <span className="sd-sr-only">Thao tác</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {lines.map((line, index) => (
                      <tr key={line.productId}>
                        <td>
                          <strong>{line.productName}</strong>
                          <small>
                            {line.sku} · {line.unit}
                          </small>
                        </td>
                        {type === 'EXPORT' && (
                          <td>
                            {line.available} {line.unit}
                          </td>
                        )}
                        <td>
                          <Input
                            type="number"
                            min={1}
                            max={99_999}
                            step={1}
                            aria-label={`Số lượng ${line.productName}`}
                            value={line.quantity}
                            disabled={busy}
                            error={errors[`items[${index}].quantity`]}
                            onChange={(event) => updateLine(index, 'quantity', event.target.value)}
                          />
                        </td>
                        {type === 'IMPORT' && (
                          <td>
                            <Input
                              type="number"
                              min={0}
                              step={1}
                              aria-label={`Giá nhập ${line.productName}`}
                              value={line.unitCost}
                              disabled={busy}
                              error={errors[`items[${index}].unitCost`]}
                              onChange={(event) =>
                                updateLine(index, 'unitCost', event.target.value)
                              }
                            />
                          </td>
                        )}
                        <td>
                          <button
                            type="button"
                            className="sd-remove-line"
                            disabled={busy}
                            aria-label={`Xóa ${line.productName} khỏi phiếu`}
                            onClick={() => {
                              setLines((current) => current.filter((_, i) => i !== index));
                              setDirty(true);
                            }}
                          >
                            Xóa
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {type === 'IMPORT' && lines.length > 0 && (
              <p className="sd-total">
                Tạm tính theo giá nhập: <strong>{formatStockDocumentMoney(total)}</strong>
              </p>
            )}
          </div>

          <label className="sd-textarea-field">
            <span>Ghi chú{type === 'EXPORT' && exportReason === 'OTHER' ? ' *' : ''}</span>
            <textarea
              className="sd-textarea"
              rows={3}
              value={note}
              disabled={busy}
              aria-invalid={errors.note ? true : undefined}
              onChange={(event) => onTextChange(setNote, event, 'note')}
              placeholder="Thông tin bổ sung (không bắt buộc)"
            />
            {errors.note && (
              <span className="sd-field-error" role="alert">
                {errors.note}
              </span>
            )}
          </label>
        </div>
      </Dialog>

      <Dialog
        open={confirmDiscard}
        title="Bỏ phiếu đang nhập?"
        onClose={() => setConfirmDiscard(false)}
        onConfirm={onClose}
        confirmText="Bỏ phiếu"
        cancelText="Tiếp tục nhập"
        destructive
      >
        Thông tin chưa lưu sẽ bị mất.
      </Dialog>
    </>
  );
}
