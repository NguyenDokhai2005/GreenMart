import { useEffect, useRef, useState } from 'react';
import { AsyncBlock, Button, Dialog, Input, Skeleton, Toast } from '../../components/ui';
import { mapErrorToState } from '../../lib/errors';
import { catalogApi, type Customer } from '../catalog/catalogApi';
import { validateCatalogForm } from '../catalog/catalogForm';

type LookupState =
  | { status: 'idle' | 'loading' }
  | { status: 'found'; customer: Customer }
  | { status: 'missing' }
  | { status: 'error'; error: unknown };

interface PosCustomerPanelProps {
  customer: Customer | null;
  onChange: (customer: Customer | null) => void;
}

export default function PosCustomerPanel({ customer, onChange }: PosCustomerPanelProps) {
  const [phone, setPhone] = useState('');
  const [phoneError, setPhoneError] = useState('');
  const [lookupPhone, setLookupPhone] = useState<string | null>(null);
  const [lookupAttempt, setLookupAttempt] = useState(0);
  const [lookup, setLookup] = useState<LookupState>({ status: 'idle' });
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [createPhone, setCreatePhone] = useState('');
  const [formErrors, setFormErrors] = useState<Record<string, string>>({});
  const [createError, setCreateError] = useState('');
  const [duplicate, setDuplicate] = useState(false);
  const [busy, setBusy] = useState(false);
  const createController = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!lookupPhone) return;
    const controller = new AbortController();
    void catalogApi.customers({ phone: lookupPhone, page: 0, size: 1 }, controller.signal).then(
      (page) => {
        if (controller.signal.aborted) return;
        const match = page.content.find((item) => item.phone === lookupPhone);
        setLookup(match ? { status: 'found', customer: match } : { status: 'missing' });
      },
      (error: unknown) => {
        if (controller.signal.aborted) return;
        const state = mapErrorToState(error, 'block');
        if (state.kind === 'form') {
          setPhoneError(state.fieldErrors.phone || state.message);
          setLookup({ status: 'idle' });
        } else {
          setLookup({ status: 'error', error });
        }
      },
    );
    return () => controller.abort();
  }, [lookupPhone, lookupAttempt]);

  useEffect(() => () => createController.current?.abort(), []);

  function findCustomer(value = phone) {
    const candidate = value.trim();
    if (!/^0[0-9]{9,10}$/.test(candidate)) {
      setPhoneError(
        candidate
          ? 'Số điện thoại gồm 10–11 chữ số, bắt đầu bằng 0.'
          : 'Vui lòng nhập thông tin bắt buộc.',
      );
      setLookup({ status: 'idle' });
      setLookupPhone(null);
      return;
    }
    setPhone(candidate);
    setPhoneError('');
    setLookup({ status: 'loading' });
    setLookupPhone(candidate);
    setLookupAttempt((attempt) => attempt + 1);
  }

  function openCreate() {
    setName('');
    setCreatePhone(phone);
    setFormErrors({});
    setCreateError('');
    setDuplicate(false);
    setCreating(true);
  }

  function closeCreate() {
    if (busy) return;
    setCreating(false);
    setDuplicate(false);
  }

  async function createCustomer() {
    const values = { name: name.trim(), phone: createPhone.trim() };
    const errors = validateCatalogForm('customer', values);
    setFormErrors(errors);
    setCreateError('');
    if (Object.keys(errors).length > 0) return;
    const controller = new AbortController();
    createController.current = controller;
    setBusy(true);
    try {
      const created = await catalogApi.saveCustomer(undefined, values, controller.signal);
      if (controller.signal.aborted) return;
      setCreating(false);
      setDuplicate(false);
      setLookupPhone(null);
      setLookup({ status: 'idle' });
      setPhone(created.phone);
      onChange(created);
    } catch (error) {
      if (controller.signal.aborted) return;
      const state = mapErrorToState(error, 'block');
      if (state.kind === 'form') {
        setFormErrors(state.fieldErrors);
        setCreateError(state.message);
      } else if (state.kind === 'dialog' && state.code === 'DUPLICATE_PHONE') {
        setDuplicate(true);
        setCreateError(state.message);
      } else {
        setCreateError(
          state.kind === 'block' || state.kind === 'dialog'
            ? state.message
            : 'Không thể tạo khách hàng. Vui lòng thử lại.',
        );
      }
    } finally {
      if (!controller.signal.aborted) setBusy(false);
      if (createController.current === controller) createController.current = null;
    }
  }

  function changeCustomer() {
    onChange(null);
    setPhone('');
    setPhoneError('');
    setLookupPhone(null);
    setLookup({ status: 'idle' });
  }

  function findDuplicate() {
    const candidate = createPhone.trim();
    setCreating(false);
    setDuplicate(false);
    findCustomer(candidate);
  }

  function editDuplicatePhone() {
    setDuplicate(false);
    setCreateError('');
    document.getElementById('pos-customer-create-phone')?.focus();
  }

  return (
    <section className="pos-customer" aria-labelledby="pos-customer-title">
      {customer ? (
        <>
          <h3 id="pos-customer-title">Khách hàng đã gắn</h3>
          <p className="pos-customer__identity">
            {customer.name} · {customer.phone}
          </p>
          <p>Khách hàng đã được gắn vào đơn hiện tại.</p>
          <Button variant="secondary" onClick={changeCustomer}>
            Đổi khách
          </Button>
        </>
      ) : (
        <>
          <h3 id="pos-customer-title">Khách hàng</h3>
          <Input
            label="Số điện thoại"
            type="tel"
            autoComplete="tel"
            value={phone}
            error={phoneError}
            placeholder="Nhập số điện thoại…"
            onChange={(event) => {
              setPhone(event.target.value);
              setPhoneError('');
              setLookupPhone(null);
              setLookup({ status: 'idle' });
            }}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault();
                findCustomer();
              }
            }}
          />
          {lookup.status === 'idle' && (
            <>
              <Button variant="secondary" onClick={() => findCustomer()}>
                Tìm khách
              </Button>
              <p>Chưa gắn khách · Bán cho khách lẻ</p>
            </>
          )}
          {lookup.status !== 'idle' && (
            <AsyncBlock
              key={`${lookupPhone}:${lookupAttempt}`}
              isLoading={lookup.status === 'loading'}
              error={lookup.status === 'error' ? lookup.error : undefined}
              isEmpty={lookup.status === 'missing'}
              onRetry={() => findCustomer(lookupPhone ?? phone)}
              empty={{ message: 'Không tìm thấy khách hàng với SĐT này.' }}
              skeleton={<Skeleton lines={2} height={28} />}
            >
              {lookup.status === 'found' && (
                <>
                  <p className="pos-customer__identity">
                    {lookup.customer.name} · {lookup.customer.phone}
                  </p>
                  <Button onClick={() => onChange(lookup.customer)}>Gắn khách</Button>
                </>
              )}
            </AsyncBlock>
          )}
          {lookup.status === 'missing' && (
            <div className="pos-customer__actions">
              <Button onClick={openCreate}>Tạo nhanh khách</Button>
              <Button variant="secondary" onClick={() => setLookup({ status: 'idle' })}>
                Khách lẻ
              </Button>
            </div>
          )}
        </>
      )}
      <Dialog
        open={creating}
        title={duplicate ? 'Không thể tạo khách hàng' : 'Tạo nhanh khách hàng'}
        onClose={duplicate ? editDuplicatePhone : closeCreate}
        onConfirm={duplicate ? findDuplicate : () => void createCustomer()}
        cancelText={duplicate ? 'Sửa SĐT' : 'Quay lại'}
        confirmText={duplicate ? 'Tìm khách đã có' : 'Tạo và gắn khách'}
        busy={busy}
      >
        <div className="pos-customer__dialog-content">
          <p>
            {duplicate
              ? `Số điện thoại ${createPhone.trim()} đã tồn tại trong hệ thống.`
              : 'Khách chưa có trong hệ thống. Tạo mới để gắn vào giỏ hàng hiện tại.'}
          </p>
          <Input
            label="Họ và tên"
            required
            value={name}
            error={formErrors.name}
            onChange={(event) => {
              setName(event.target.value);
              setFormErrors((errors) => ({ ...errors, name: '' }));
            }}
          />
          <Input
            id="pos-customer-create-phone"
            label="Số điện thoại"
            required
            type="tel"
            value={createPhone}
            error={formErrors.phone}
            onChange={(event) => {
              setCreatePhone(event.target.value);
              setDuplicate(false);
              setCreateError('');
              setFormErrors((errors) => ({ ...errors, phone: '' }));
            }}
          />
          <p>
            {duplicate
              ? '409 DUPLICATE_PHONE · Giữ dữ liệu đã nhập; sửa SĐT hoặc tìm và gắn khách đã có.'
              : 'Giỏ hàng và báo giá hiện tại được giữ nguyên.'}
          </p>
          {createError && <Toast variant="error" title={createError} />}
        </div>
      </Dialog>
    </section>
  );
}
