import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Button, Dialog, Input, Select, Toast } from '../../components/ui';
import { mapErrorToState } from '../../lib/errors';
import { validateCatalogForm } from './catalogForm';
import { catalogApi } from './catalogApi';
import type { Customer, CustomerInput, Product, ProductInput } from './catalogApi';
import type { components } from '../../api/schema';

type Category = components['schemas']['Category'];
type EditorProps = { onClose: () => void; onSaved: () => void } & (
  | { kind: 'product'; initial?: Product; categories: Category[] }
  | { kind: 'customer'; initial?: Customer }
);
type Values = Record<string, string>;

function initialValues(props: EditorProps): Values {
  if (props.kind === 'customer')
    return {
      name: props.initial?.name ?? '',
      phone: props.initial?.phone ?? '',
      email: props.initial?.email ?? '',
    };
  const p = props.initial;
  return {
    sku: p?.sku ?? '',
    barcode: p?.barcode ?? '',
    name: p?.name ?? '',
    unit: p?.unit ?? '',
    categoryId: p ? String(p.categoryId) : '',
    salePrice: p ? String(p.salePrice) : '',
    costPrice: p?.costPrice == null ? '' : String(p.costPrice),
    active: String(p?.active ?? true),
  };
}

export default function CatalogEditor(props: EditorProps) {
  const [values, setValues] = useState(() => initialValues(props));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [saved, setSaved] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [dirty, setDirty] = useState(false);
  const pending = useRef<AbortController | null>(null);
  const timeout = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(
    () => () => {
      pending.current?.abort();
      clearTimeout(timeout.current);
    },
    [],
  );
  useEffect(() => {
    if (!dirty || saved) return;
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty, saved]);
  const close = () => (dirty && !saved ? setConfirmDiscard(true) : props.onClose());
  function change(field: string, value: string) {
    setValues((old) => ({ ...old, [field]: value }));
    setDirty(true);
    setSaved(false);
    setErrors((old) => ({ ...old, [field]: '' }));
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy || pending.current || uncertain) return;
    const nextErrors = validateCatalogForm(props.kind, values);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length) return;
    const controller = new AbortController();
    pending.current = controller;
    setBusy(true);
    setMessage('');
    // Lệnh ghi quá hạn có thể đã đến máy chủ; không tự gửi lại.
    timeout.current = setTimeout(() => {
      controller.abort();
      pending.current = null;
      setBusy(false);
      setUncertain(true);
      setMessage('Chưa xác nhận được kết quả lưu. Hãy kiểm tra lại danh sách trước khi gửi lại.');
    }, 15_000);
    try {
      if (props.kind === 'product') {
        const input: ProductInput = {
          sku: values.sku.trim(),
          barcode: values.barcode.trim() || null,
          name: values.name.trim(),
          unit: values.unit.trim(),
          categoryId: Number(values.categoryId),
          salePrice: Number(values.salePrice),
          costPrice: Number(values.costPrice),
          active: values.active === 'true',
        };
        await catalogApi.saveProduct(props.initial?.id, input, controller.signal);
      } else {
        const input: CustomerInput = {
          name: values.name.trim(),
          phone: values.phone.trim(),
          email: values.email.trim() || null,
        };
        await catalogApi.saveCustomer(props.initial?.id, input, controller.signal);
      }
      if (controller.signal.aborted) return;
      setSaved(true);
      setDirty(false);
      props.onSaved();
    } catch (error) {
      if (controller.signal.aborted) return;
      const mapped = mapErrorToState(error, 'block');
      if (mapped.kind === 'form') setErrors(mapped.fieldErrors);
      if (mapped.kind === 'page' || error instanceof TypeError) setUncertain(true);
      setMessage(mapped.message ?? 'Không thể lưu. Vui lòng thử lại.');
    } finally {
      clearTimeout(timeout.current);
      if (!controller.signal.aborted) {
        pending.current = null;
        setBusy(false);
      }
    }
  }
  const title = `${props.initial ? 'Sửa' : 'Thêm'} ${props.kind === 'product' ? 'sản phẩm' : 'khách hàng'}`;
  const isProduct = props.kind === 'product';
  const input = (field: string, label: string, required = true, type = 'text') => (
    <Input
      label={label}
      required={required}
      type={type}
      value={values[field] ?? ''}
      error={errors[field]}
      disabled={busy}
      helperText={
        field === 'sku'
          ? '1–40 ký tự; chữ in hoa, số và dấu gạch nối.'
          : field === 'barcode'
            ? 'Tối đa 40 ký tự; có thể để trống.'
            : field === 'unit'
              ? '1–20 ký tự; ví dụ: Hộp, Chai, Kg.'
              : field === 'phone'
                ? '10–11 chữ số, bắt đầu bằng 0.'
                : field === 'email'
                  ? 'Email hợp lệ, tối đa 120 ký tự. Có thể để trống.'
                  : field === 'name'
                    ? isProduct
                      ? '1–200 ký tự.'
                      : '1–120 ký tự.'
                    : field === 'salePrice' || field === 'costPrice'
                      ? 'Số nguyên không âm, đơn vị đồng.'
                      : undefined
      }
      onChange={(e) => change(field, e.target.value)}
    />
  );
  return (
    <section className="catalog__editor" aria-label={title}>
      <header className="catalog__page-header">
        <div>
          <p className="catalog__breadcrumb">Danh mục / {isProduct ? 'Sản phẩm' : 'Khách hàng'}</p>
          <h1>
            {props.initial ? 'Chỉnh sửa' : 'Thêm'} {isProduct ? 'sản phẩm' : 'khách hàng'}
          </h1>
          <p>
            {props.initial
              ? isProduct
                ? `${props.initial.sku} · ${props.initial.name}`
                : `${props.initial.name} · ${props.initial.phone}`
              : `Nhập thông tin ${isProduct ? 'sản phẩm' : 'khách hàng'} mới.`}
          </p>
        </div>
      </header>
      {message && <Toast variant="error" title={message} />}
      {saved && <Toast variant="success" title="Đã lưu thành công" />}
      <div className="catalog__editor-layout">
        <form noValidate onSubmit={(e) => void submit(e)}>
          <fieldset className="catalog__fields" disabled={busy}>
            {isProduct ? (
              <>
                <section className="catalog__editor-card">
                  <h2>Thông tin sản phẩm</h2>
                  <div className="catalog__editor-grid">
                    {input('sku', 'SKU')}
                    {input('barcode', 'Mã vạch', false)}
                    <div className="catalog__full-width">{input('name', 'Tên sản phẩm')}</div>
                    {input('unit', 'Đơn vị tính')}
                    <Select
                      label="Danh mục"
                      required
                      value={values.categoryId}
                      error={errors.categoryId}
                      helperText="Chọn một danh mục trong hệ thống."
                      options={props.categories.map((c) => ({
                        value: String(c.id),
                        label: c.name,
                      }))}
                      onChange={(e) => change('categoryId', e.target.value)}
                    />
                  </div>
                </section>
                <section className="catalog__editor-card">
                  <h2>Giá &amp; trạng thái bán</h2>
                  <div className="catalog__editor-grid">
                    {input('costPrice', 'Giá vốn (VND)', true, 'number')}
                    {input('salePrice', 'Giá bán (VND)', true, 'number')}
                    <div className="catalog__full-width">
                      <Select
                        label="Trạng thái bán"
                        required
                        value={values.active}
                        helperText="Ngừng bán để tạm dừng kinh doanh sản phẩm."
                        options={[
                          { value: 'true', label: 'Đang bán' },
                          { value: 'false', label: 'Ngừng bán' },
                        ]}
                        onChange={(e) => change('active', e.target.value)}
                      />
                    </div>
                  </div>
                </section>
              </>
            ) : (
              <section className="catalog__editor-card">
                <h2>Thông tin liên hệ</h2>
                <div className="catalog__editor-grid catalog__editor-grid--single">
                  {input('name', 'Họ và tên')}
                  {input('phone', 'Số điện thoại', true, 'tel')}
                  {input('email', 'Email (không bắt buộc)', false, 'email')}
                </div>
              </section>
            )}
          </fieldset>
          <div className="catalog__actions">
            <span>
              {props.initial
                ? 'Thông tin đang chỉnh sửa chưa được lưu.'
                : 'Trường có dấu * là bắt buộc.'}
            </span>
            <Button type="button" variant="secondary" disabled={busy} onClick={close}>
              Hủy
            </Button>
            <Button type="submit" disabled={busy || saved || uncertain}>
              {busy ? 'Đang lưu…' : props.initial ? 'Lưu thay đổi' : 'Thêm mới'}
            </Button>
          </div>
        </form>
        <aside className="catalog__editor-help">
          <h2>{isProduct ? 'Lưu ý khi cập nhật' : 'Thông tin cần biết'}</h2>
          <p>Trường có dấu * là bắt buộc.</p>
          {isProduct ? (
            <>
              <p>Chỉ chủ cửa hàng (OWNER) được thêm, sửa và thay đổi giá sản phẩm.</p>
              <p>Mọi thay đổi giá được ghi nhật ký. Hóa đơn đã tạo vẫn giữ nguyên giá cũ.</p>
              <p>Ngừng bán để tạm dừng kinh doanh sản phẩm, không xóa dữ liệu.</p>
            </>
          ) : (
            <>
              <p>
                Số điện thoại dùng để tra cứu chính xác khách hàng tại POS. Mỗi khách hàng dùng một
                số riêng.
              </p>
              <p>Chủ cửa hàng, quản lý chi nhánh và thu ngân được thêm, sửa khách hàng.</p>
              <p>Quản trị viên và thủ kho chỉ được xem thông tin.</p>
            </>
          )}
        </aside>
      </div>
      <Dialog
        open={confirmDiscard}
        title="Hủy thay đổi?"
        onClose={() => setConfirmDiscard(false)}
        onConfirm={props.onClose}
        confirmText="Hủy thay đổi"
        cancelText="Tiếp tục nhập"
        destructive
      >
        Thông tin chưa lưu sẽ bị mất.
      </Dialog>
    </section>
  );
}
