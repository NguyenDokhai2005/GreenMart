import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Button, Checkbox, Dialog, Input, Select, Toast } from '../../components/ui';
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
    // A timed-out write may have reached the server: never replay it automatically.
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
  const input = (field: string, label: string, required = true, type = 'text') => (
    <Input
      label={label}
      required={required}
      type={type}
      value={values[field] ?? ''}
      error={errors[field]}
      disabled={busy}
      onChange={(e) => change(field, e.target.value)}
    />
  );
  return (
    <section className="catalog__editor" aria-label={title}>
      <h2>{title}</h2>
      {message && <Toast variant="error" title={message} />}
      {saved && <Toast variant="success" title="Đã lưu thành công" />}
      <form noValidate onSubmit={(e) => void submit(e)}>
        <fieldset disabled={busy}>
          <legend>Thông tin {props.kind === 'product' ? 'sản phẩm' : 'khách hàng'}</legend>
          {props.kind === 'product' && input('sku', 'SKU')}
          {input('name', props.kind === 'product' ? 'Tên sản phẩm' : 'Tên khách hàng')}
          {props.kind === 'product' ? (
            <>
              {input('barcode', 'Barcode', false)}
              {input('unit', 'Đơn vị')}
              <Select
                label="Danh mục"
                required
                value={values.categoryId}
                error={errors.categoryId}
                options={props.categories.map((c) => ({ value: String(c.id), label: c.name }))}
                onChange={(e) => change('categoryId', e.target.value)}
              />
              {input('salePrice', 'Giá bán (đ)', true, 'number')}
              {input('costPrice', 'Giá vốn (đ)', true, 'number')}
              <Checkbox
                label="Đang bán"
                checked={values.active === 'true'}
                onChange={(e) => change('active', String(e.target.checked))}
              />
            </>
          ) : (
            <>
              {input('phone', 'Số điện thoại', true, 'tel')}
              {input('email', 'Email', false, 'email')}
            </>
          )}
        </fieldset>
        <div className="catalog__actions">
          <Button type="button" variant="secondary" disabled={busy} onClick={close}>
            Quay lại
          </Button>
          <Button type="submit" disabled={busy || saved || uncertain}>
            {busy ? 'Đang xử lý…' : 'Lưu'}
          </Button>
        </div>
      </form>
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
