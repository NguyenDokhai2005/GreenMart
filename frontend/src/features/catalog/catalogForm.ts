export function validateCatalogForm(
  kind: 'product' | 'customer',
  values: Record<string, string>,
): Record<string, string> {
  const errors: Record<string, string> = {};
  const required =
    kind === 'product'
      ? ['sku', 'name', 'unit', 'categoryId', 'salePrice', 'costPrice']
      : ['name', 'phone'];
  for (const field of required)
    if (!values[field]?.trim()) errors[field] = 'Vui lòng nhập thông tin bắt buộc.';
  const max =
    kind === 'product' ? { sku: 40, barcode: 40, name: 200, unit: 20 } : { name: 120, email: 120 };
  for (const [field, length] of Object.entries(max))
    if ((values[field]?.trim().length ?? 0) > length) errors[field] = `Tối đa ${length} ký tự.`;
  if (kind === 'product') {
    if (values.sku && !/^[A-Z0-9-]+$/.test(values.sku.trim()))
      errors.sku = 'SKU chỉ gồm chữ in hoa, số và dấu gạch ngang.';
    for (const field of ['salePrice', 'costPrice']) {
      if (
        values[field] &&
        (!/^\d+$/.test(values[field]) || !Number.isSafeInteger(Number(values[field])))
      )
        errors[field] = 'Nhập số nguyên đồng không âm.';
    }
    if (values.categoryId && !Number.isSafeInteger(Number(values.categoryId)))
      errors.categoryId = 'Danh mục không hợp lệ.';
  } else {
    if (values.phone && !/^0[0-9]{9,10}$/.test(values.phone.trim()))
      errors.phone = 'Số điện thoại gồm 10–11 chữ số, bắt đầu bằng 0.';
    if (values.email?.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(values.email.trim()))
      errors.email = 'Email không hợp lệ.';
  }
  return errors;
}
