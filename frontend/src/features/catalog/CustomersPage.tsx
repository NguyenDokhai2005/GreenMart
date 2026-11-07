import { useCallback, useState } from 'react';
import type { ReactNode } from 'react';
import { Button, Input, Table, Toast } from '../../components/ui';
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

export default function CustomersPage({ navigation }: { navigation?: ReactNode }) {
  const { state } = useAuth();
  const { q, searchText, onSearch, page, size, change } = useCatalogSearch();
  const load = useCallback(
    (signal: AbortSignal) => catalogApi.customers({ q: q || undefined, page, size }, signal),
    [q, page, size],
  );
  const result = useCatalogQuery(JSON.stringify([q, page, size]), load);
  const [editing, setEditing] = useState<Customer | 'new' | null>(null);
  const [notice, setNotice] = useState('');
  const editable = state.status === 'authenticated' && canEditCustomers(state.user.role);
  const cols: TableColumn<Customer>[] = [
    { key: 'name', title: 'Khách hàng' },
    { key: 'phone', title: 'Số điện thoại' },
    { key: 'email', title: 'Email' },
    ...(editable
      ? [
          {
            key: 'edit',
            title: 'Hành động',
            render: (c: Customer) => (
              <Button
                variant="secondary"
                aria-label={`Sửa ${c.name}`}
                onClick={() => {
                  setNotice('');
                  setEditing(c);
                }}
              >
                Sửa
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
      <h1 id="catalog-customers-title">Khách hàng</h1>
      {navigation}
      {notice && <Toast variant="success" title={notice} />}
      <div className="catalog__card catalog__filters">
        <h2>Tìm kiếm &amp; bộ lọc</h2>
        <Input
          label="Tìm khách hàng"
          placeholder="Tên hoặc số điện thoại"
          value={searchText}
          onChange={(e) => onSearch(e.target.value)}
        />
      </div>
      <div className="catalog__card">
        <CatalogStates
          pending={result.pending}
          error={result.error}
          empty={result.data?.content.length === 0}
          filtered={Boolean(q)}
          retry={result.retry}
          clear={() => change({ q: null, page: '0' })}
        >
          <Table columns={cols} data={result.data?.content ?? []} rowKey="id" />
        </CatalogStates>
      </div>
      <CatalogPagination
        page={page}
        size={size}
        total={result.data?.totalElements ?? 0}
        disabled={result.pending || !!result.error}
        onPage={(n) => change({ page: String(n) })}
        onSize={(n) => change({ size: String(n), page: '0' })}
      />
      {editable && (
        <Button
          onClick={() => {
            setNotice('');
            setEditing('new');
          }}
        >
          Thêm khách hàng
        </Button>
      )}
    </section>
  );
}
