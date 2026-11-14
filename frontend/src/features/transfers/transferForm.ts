/** Logic thuần của form lập/sửa phiếu chuyển kho (không phụ thuộc React). */

export interface DraftItem {
  productId: number;
  sku: string;
  productName: string;
  unit: string;
  /** Chuỗi người dùng nhập ở ô "Số lượng chuyển". */
  quantity: string;
  /** Tồn khả dụng tại kho gửi; chưa biết thì bỏ trống (backend vẫn kiểm tra lại). */
  available?: number;
}

export interface TransferFormErrors {
  toBranch?: string;
  items?: string;
  /** Lỗi theo dòng, khóa là productId. */
  rows: Record<number, string>;
}

export function validateTransferForm(
  input: { toBranchId: string; items: readonly DraftItem[] },
  availableOf: (item: DraftItem) => number | undefined,
): TransferFormErrors & { valid: boolean } {
  const errors: TransferFormErrors = { rows: {} };
  if (input.toBranchId === '') errors.toBranch = 'Chọn chi nhánh đích';
  if (input.items.length === 0) errors.items = 'Thêm ít nhất một sản phẩm cần chuyển';

  for (const item of input.items) {
    const text = item.quantity.trim();
    const n = Number(text);
    const available = availableOf(item);
    if (text === '') errors.rows[item.productId] = 'Nhập số lượng';
    else if (!Number.isInteger(n) || n < 1)
      errors.rows[item.productId] = 'Số lượng phải là số nguyên lớn hơn 0';
    else if (available !== undefined && n > available)
      errors.rows[item.productId] = `Vượt tồn khả dụng (${available})`;
  }

  const valid = !errors.toBranch && !errors.items && Object.keys(errors.rows).length === 0;
  return { ...errors, valid };
}

export function toPayloadItems(
  items: readonly DraftItem[],
): { productId: number; quantity: number }[] {
  return items.map((i) => ({ productId: i.productId, quantity: Number(i.quantity) }));
}

/** Gắn lỗi 400 của backend (`toBranchId`, `items[0].quantity`...) vào đúng ô trên form. */
export function mapBackendFields(
  fields: Record<string, string>,
  items: readonly DraftItem[],
): { toBranch?: string; rows: Record<number, string> } {
  const out: { toBranch?: string; rows: Record<number, string> } = { rows: {} };
  for (const [field, message] of Object.entries(fields)) {
    if (field === 'toBranchId') out.toBranch = message;
    const m = /^items\[(\d+)\]/.exec(field);
    const item = m ? items[Number(m[1])] : undefined;
    if (item) out.rows[item.productId] = message;
  }
  return out;
}
