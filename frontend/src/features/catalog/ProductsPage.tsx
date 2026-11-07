import { useCallback, useState } from 'react';
import type { ReactNode } from 'react';
import { useSearchParams } from 'react-router';
import { Button, ErrorState, Input, Select, Table, Toast } from '../../components/ui';
import type { TableColumn } from '../../components/ui/tsx/Table';
import { useAuth } from '../auth/useAuth';
import { catalogApi } from './catalogApi';
import type { Product } from './catalogApi';
import { canEditProducts, money } from './catalogPolicy';
import CatalogEditor from './CatalogEditor';
import CatalogPagination from './CatalogPagination';
import CatalogStates from './CatalogStates';
import { useCatalogQuery } from './useCatalogQuery';
import { useCatalogSearch } from './useCatalogSearch';
import './catalog.css';

export default function ProductsPage({ navigation }: { navigation?: ReactNode }) {
  const { state } = useAuth();
  const [params] = useSearchParams();
  const { q, searchText, onSearch, page, size, change } = useCatalogSearch();
  const active =
    params.get('active') === 'true' ? true : params.get('active') === 'false' ? false : undefined;
  const category = Number(params.get('categoryId'));
  const categoryId = Number.isSafeInteger(category) && category > 0 ? category : undefined;
  const load = useCallback(
    (signal: AbortSignal) =>
      catalogApi.products({ q: q || undefined, page, size, active, categoryId }, signal),
    [q, page, size, active, categoryId],
  );
  const result = useCatalogQuery(JSON.stringify([q, page, size, active, categoryId]), load);
  const loadCategories = useCallback((signal: AbortSignal) => catalogApi.categories(signal), []);
  const categories = useCatalogQuery('categories', loadCategories);
  const [editing, setEditing] = useState<Product | 'new' | null>(null);
  const [notice, setNotice] = useState('');
  const editable = state.status === 'authenticated' && canEditProducts(state.user.role);
  const cols: TableColumn<Product>[] = [
    { key: 'name', title: 'Tên sản phẩm' },
    { key: 'sku', title: 'SKU' },
    { key: 'barcode', title: 'Barcode' },
    { key: 'categoryName', title: 'Danh mục' },
    { key: 'salePrice', title: 'Giá bán', align: 'right', render: (p) => money(p.salePrice) },
    {
      key: 'active',
      title: 'Trạng thái',
      render: (p) => (
        <span className={`catalog__badge${p.active ? '' : ' catalog__badge--inactive'}`}>
          {p.active ? 'Đang bán' : 'Ngừng bán'}
        </span>
      ),
    },
    ...(editable
      ? [
          {
            key: 'edit',
            title: 'Hành động',
            render: (p: Product) => (
              <Button
                variant="secondary"
                disabled={categories.pending || !!categories.error}
                onClick={() => {
                  setNotice('');
                  setEditing(p);
                }}
                aria-label={`Sửa ${p.name}`}
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
        kind="product"
        initial={editing === 'new' ? undefined : editing}
        categories={categories.data ?? []}
        onClose={() => setEditing(null)}
        onSaved={() => {
          setEditing(null);
          setNotice('Đã lưu sản phẩm thành công');
          result.retry();
        }}
      />
    );
  return (
    <section className="catalog-page" aria-labelledby="catalog-products-title">
      <h1 id="catalog-products-title">Sản phẩm</h1>
      <p>Danh mục hàng bán tại GreenMart</p>
      {navigation}
      {notice && <Toast variant="success" title={notice} />}
      <div className="catalog__card catalog__filters">
        <h2>Tìm kiếm &amp; bộ lọc</h2>
        <Input
          label="Tìm sản phẩm"
          placeholder="Tên, SKU hoặc barcode"
          value={searchText}
          onChange={(e) => onSearch(e.target.value)}
        />
        <div className="catalog__filter-row">
          <Select
            label="Danh mục"
            value={categoryId ? String(categoryId) : 'all'}
            options={[
              { value: 'all', label: 'Tất cả danh mục' },
              ...(categories.data ?? []).map((c) => ({ value: String(c.id), label: c.name })),
            ]}
            onChange={(e) =>
              change({ categoryId: e.target.value === 'all' ? null : e.target.value, page: '0' })
            }
          />
          <Select
            label="Trạng thái"
            value={active === undefined ? 'all' : String(active)}
            options={[
              { value: 'all', label: 'Tất cả trạng thái' },
              { value: 'true', label: 'Đang bán' },
              { value: 'false', label: 'Ngừng bán' },
            ]}
            onChange={(e) =>
              change({ active: e.target.value === 'all' ? null : e.target.value, page: '0' })
            }
          />
        </div>
        {categories.error !== undefined && (
          <ErrorState
            message="Không tải được danh mục. Bạn vẫn có thể tra cứu sản phẩm."
            onRetry={categories.retry}
          />
        )}
      </div>
      <div className="catalog__card">
        <CatalogStates
          pending={result.pending}
          error={result.error}
          empty={result.data?.content.length === 0}
          filtered={Boolean(q || categoryId || active !== undefined)}
          retry={result.retry}
          clear={() => change({ q: null, categoryId: null, active: null, page: '0' })}
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
          disabled={categories.pending || !!categories.error}
          onClick={() => {
            setNotice('');
            setEditing('new');
          }}
        >
          Thêm sản phẩm
        </Button>
      )}
    </section>
  );
}
