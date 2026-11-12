import { useCallback, useState } from 'react';
import { useSearchParams } from 'react-router';
import { Button, Input, Select, Table, Toast } from '../../components/ui';
import type { TableColumn } from '../../components/ui/tsx/Table';
import { useAuth } from '../auth/useAuth';
import { catalogApi } from './catalogApi';
import type { Customer } from './catalogApi';
import { canEditCustomers } from './catalogPolicy';
import CatalogEditor from './CatalogEditor';
import CatalogPagination from './CatalogPagination';
import CatalogStates from './CatalogStates';
import { useCatalogQuery } from './useCatalogQuery';
import { useCatalogSearch } from './useCatalogSearch';
import './catalog.css';

export default function CustomersPage() {
  const { state } = useAuth();
  const { q, searchText, onSearch, page, size, change } = useCatalogSearch();
  const [params] = useSearchParams();
  const isCashier = state.status === 'authenticated' && state.user.role === 'CASHIER';
  const exactMode = isCashier && params.get('mode') === 'phone';
  const phone = exactMode ? (params.get('phone') ?? '') : '';
  const validPhone = /^0[0-9]{9,10}$/.test(phone);
  const [phoneInput, setPhoneInput] = useState(phone);
  const [phoneError, setPhoneError] = useState('');
  const load = useCallback(
    (signal: AbortSignal) =>
      catalogApi.customers(
        exactMode ? { phone, page, size } : { q: q || undefined, page, size },
        signal,
      ),
    [exactMode, phone, q, page, size],
  );
  const result = useCatalogQuery(
    JSON.stringify([exactMode, exactMode ? phone : q, page, size]),
    load,
    !exactMode || validPhone,
  );
  const [editing, setEditing] = useState<Customer | 'new' | null>(null);
  const [notice, setNotice] = useState('');
  const editable = state.status === 'authenticated' && canEditCustomers(state.user.role);
  const cols: TableColumn<Customer>[] = [
    { key: 'name', title: 'Họ và tên' },
    { key: 'phone', title: 'Số điện thoại' },
    { key: 'email', title: 'Email', render: (c) => c.email || '—' },
    ...(editable
      ? [
          {
            key: 'edit',
            title: 'Thao tác',
            render: (c: Customer) => (
              <Button
                className="catalog__text-action"
                variant="secondary"
                aria-label={`Sửa ${c.name}`}
                onClick={() => {
                  setNotice('');
                  setEditing(c);
                }}
              >
                Chỉnh sửa
              </Button>
            ),
          },
        ]
      : []),
  ];
  if (editing && editable)
    return (
      <CatalogEditor
        kind="customer"
        initial={editing === 'new' ? undefined : editing}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          setNotice('Đã lưu khách hàng thành công');
          result.retry();
        }}
      />
    );
  return (
    <section className="catalog-page" aria-labelledby="catalog-customers-title">
      <header className="catalog__page-header">
        <div>
          <p className="catalog__breadcrumb">Danh mục / Khách hàng</p>
          <h1 id="catalog-customers-title" tabIndex={-1} data-pos-heading>
            {isCashier ? 'Tra cứu khách hàng' : 'Khách hàng'}
          </h1>
          <p>Tìm kiếm và cập nhật thông tin liên hệ của khách hàng.</p>
        </div>
        {editable && (
          <Button
            onClick={() => {
              setNotice('');
              setEditing('new');
            }}
          >
            + Thêm khách hàng
          </Button>
        )}
      </header>
      {notice && <Toast variant="success" title={notice} />}
      <div
        className={`catalog__filters ${exactMode ? 'catalog__filters--exact' : 'catalog__filters--customers'}`}
      >
        <Input
          label={exactMode ? 'Số điện thoại chính xác' : 'Tìm khách hàng'}
          placeholder={
            exactMode ? 'Nhập đầy đủ số điện thoại...' : 'Nhập tên hoặc số điện thoại...'
          }
          value={exactMode ? phoneInput : searchText}
          error={exactMode ? phoneError : undefined}
          onChange={(e) => {
            if (exactMode) {
              setPhoneInput(e.target.value);
              setPhoneError('');
            } else onSearch(e.target.value);
          }}
        />
        {isCashier && (
          <Select
            label="Chế độ tìm kiếm"
            value={exactMode ? 'phone' : 'partial'}
            options={[
              { value: 'partial', label: 'Tên / một phần SĐT' },
              { value: 'phone', label: 'SĐT chính xác' },
            ]}
            onChange={(e) => {
              setPhoneError('');
              setPhoneInput('');
              change({
                mode: e.target.value === 'phone' ? 'phone' : null,
                q: null,
                phone: null,
                page: '0',
              });
            }}
          />
        )}
        <Button
          variant="secondary"
          onClick={() => {
            if (exactMode) {
              const candidate = phoneInput.trim();
              if (!/^0[0-9]{9,10}$/.test(candidate)) {
                setPhoneError('Nhập đầy đủ số điện thoại 10–11 chữ số, bắt đầu bằng 0.');
                return;
              }
              change({ phone: candidate, page: '0' });
            } else change({ q: searchText, page: '0' });
          }}
        >
          {exactMode ? 'Tra cứu' : 'Tìm kiếm'}
        </Button>
      </div>
      <div className="catalog__results">
        {exactMode && !validPhone ? (
          <p className="catalog__lookup-prompt">Nhập đầy đủ số điện thoại để tra cứu khách hàng.</p>
        ) : (
          <CatalogStates
            pending={result.pending}
            error={result.error}
            empty={result.data?.content.length === 0}
            filtered={Boolean(exactMode ? phone : q)}
            retry={result.retry}
            clear={() => {
              if (exactMode) {
                setPhoneInput('');
                setPhoneError('');
              }
              change(exactMode ? { phone: null, page: '0' } : { q: null, page: '0' });
            }}
          >
            <Table
              className="catalog__table"
              columns={cols}
              data={result.data?.content ?? []}
              rowKey="id"
            />
          </CatalogStates>
        )}
        {(!exactMode || validPhone) && (
          <CatalogPagination
            page={page}
            size={size}
            total={result.data?.totalElements ?? 0}
            disabled={result.pending || !!result.error}
            onPage={(n) => change({ page: String(n) })}
            onSize={(n) => change({ size: String(n), page: '0' })}
          />
        )}
      </div>
    </section>
  );
}
