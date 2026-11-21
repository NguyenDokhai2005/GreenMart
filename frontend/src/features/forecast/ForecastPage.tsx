import { useCallback, useState } from 'react';
import type { components } from '../../api/schema';
import { AsyncBlock, EmptyState, Input, Select, Skeleton } from '../../components/ui';
import type { AsyncBlockProps } from '../../components/ui';
import Pagination from '../inventory/PaginationControl';
import { getReplenishment, listInventory } from '../inventory/inventoryApi';
import type { InventoryItem, Replenishment, StockStatus } from '../inventory/inventoryApi';
import { branchLabel } from '../../layouts/operations/adapters';
import { useOperationsScope } from '../../layouts/operations/useOperationsScope';
import { useCatalogQuery } from '../catalog/useCatalogQuery';
import { getForecast } from './forecastApi';
import type { ForecastResponse } from './forecastApi';
import ForecastAreaChart from '../../components/charts/ForecastAreaChart';
import type { ForecastChartPoint } from '../../components/charts/ForecastAreaChart';
import { formatDayFull } from '../../components/charts/format';
import './forecast.css';

type Horizon = 7 | 30;
type InventoryPageData = components['schemas']['InventoryPage'];
type ForecastInventoryRow = { item: InventoryItem; replenishment: Replenishment };
type ForecastInventoryResult = InventoryPageData & { rows: ForecastInventoryRow[] };
type StockFilter = 'ALL' | StockStatus;

const PAGE_SIZE = 10;
const HORIZONS: Horizon[] = [7, 30];
const QTY = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 1 });

function formatQuantity(value: number | null | undefined): string {
  return value == null ? '—' : QTY.format(value);
}

function rowKey(item: InventoryItem): string {
  return `${item.branch.id}:${item.productId}`;
}

function forecastHorizon(response: ForecastResponse | undefined, horizon: Horizon) {
  return response?.horizons.find((item) => item.horizon === horizon);
}

function sortRows(rows: ForecastInventoryRow[]): ForecastInventoryRow[] {
  return rows.sort((a, b) => {
    const quantity =
      (b.replenishment.suggestedQuantity ?? -1) - (a.replenishment.suggestedQuantity ?? -1);
    if (quantity !== 0) return quantity;
    const priority = { OUT: 0, LOW: 1, OK: 2 } as const;
    return priority[a.item.stockStatus] - priority[b.item.stockStatus];
  });
}

function ForecastTableSkeleton({ showBranch }: { showBranch: boolean }) {
  return (
    <div className="forecast-table-wrap" role="status" aria-busy="true" aria-label="Đang tải">
      <table className="forecast-table">
        <thead>
          <tr>
            <th>Sản phẩm</th>
            {showBranch && <th>Chi nhánh</th>}
            <th>Khả dụng</th>
            <th>Nhu cầu ROP</th>
            <th>ROP</th>
            <th>An toàn</th>
            <th>Gợi ý nhập</th>
          </tr>
        </thead>
        <tbody>
          {Array.from({ length: PAGE_SIZE }, (_, row) => (
            <tr key={row}>
              {Array.from({ length: showBranch ? 7 : 6 }, (_, cell) => (
                <td key={cell}>
                  <span className="gm-skeleton forecast-table__skeleton-cell" />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ForecastPage() {
  const { branchId, canPickAll, pickAll, scope } = useOperationsScope();
  const [searchText, setSearchText] = useState('');
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<StockFilter>('ALL');
  const [page, setPage] = useState(1);
  const [horizon, setHorizon] = useState<Horizon>(7);
  const [selectedProductKey, setSelectedProductKey] = useState<string>();

  const stockStatus = status === 'ALL' ? undefined : status;
  const loadInventory = useCallback(
    async (signal: AbortSignal): Promise<ForecastInventoryResult> => {
      const inventory = await listInventory(
        { branchId, q: search || undefined, stockStatus, page: page - 1, size: PAGE_SIZE },
        signal,
      );
      const rows = await Promise.all(
        inventory.content.map(async (item) => ({
          item,
          replenishment: await getReplenishment(item.branch.id, item.productId, signal),
        })),
      );
      return { ...inventory, rows: sortRows(rows) };
    },
    [branchId, page, search, stockStatus],
  );
  const inventory = useCatalogQuery(
    `forecast-items:${branchId ?? 'all'}:${search}:${status}:${page}`,
    loadInventory,
  );

  const rows = inventory.data?.rows;
  const eligibleRows = rows?.filter((row) => row.replenishment.forecastDemand != null);
  const selectedRow =
    rows?.find((row) => rowKey(row.item) === selectedProductKey) ?? eligibleRows?.[0] ?? rows?.[0];
  const selectedHasForecast = selectedRow?.replenishment.forecastDemand != null;
  const loadForecast = useCallback(
    (signal: AbortSignal) => {
      if (!selectedRow || !selectedHasForecast) {
        return Promise.reject(new Error('Sản phẩm chưa có dữ liệu dự báo.'));
      }
      return getForecast(
        { branchId: selectedRow.item.branch.id, productId: selectedRow.item.productId },
        signal,
      );
    },
    [selectedHasForecast, selectedRow],
  );
  const forecast = useCatalogQuery(
    `forecast-series:${selectedRow ? rowKey(selectedRow.item) : 'none'}`,
    loadForecast,
    selectedHasForecast,
  );
  const selectedForecast = forecastHorizon(forecast.data, horizon);
  const chartData: ForecastChartPoint[] =
    selectedForecast?.points.map((point) => ({
      date: point.date,
      predictedQty: point.predictedQty,
      actualQty: point.actualQty ?? null,
    })) ?? [];

  const hasFilters = search !== '' || status !== 'ALL' || (branchId !== undefined && canPickAll);
  const clearFilters = () => {
    setSearchText('');
    setSearch('');
    setStatus('ALL');
    setPage(1);
    if (canPickAll) pickAll();
  };
  const empty: AsyncBlockProps['empty'] = {
    message: 'Chưa có sản phẩm phù hợp',
    description: 'Sản phẩm và gợi ý nhập sẽ xuất hiện khi có dữ liệu tồn kho.',
    filtered: hasFilters,
    onClearFilters: clearFilters,
  };
  const rowCount = inventory.data?.rows.length ?? 0;
  const forecastCount =
    inventory.data?.rows.filter((row) => row.replenishment.forecastDemand != null).length ?? 0;
  const demandTotal =
    inventory.data?.rows.reduce((sum, row) => sum + (row.replenishment.forecastDemand ?? 0), 0) ??
    0;
  const suggestedTotal =
    inventory.data?.rows.reduce(
      (sum, row) => sum + (row.replenishment.suggestedQuantity ?? 0),
      0,
    ) ?? 0;
  const chartEmpty =
    !!inventory.data &&
    (inventory.data.rows.length === 0 ||
      !selectedHasForecast ||
      selectedForecast?.points.length === 0);

  return (
    <div className="forecast-page">
      <header className="forecast-page__header">
        <div>
          <h1>Dự báo và gợi ý nhập hàng</h1>
          <p>
            {scope.kind === 'all'
              ? 'Dự báo nhu cầu và lượng nhập theo các chi nhánh được phép xem.'
              : `Dự báo và gợi ý nhập tại ${branchLabel(scope.branch)}.`}
          </p>
        </div>
      </header>

      <section className="forecast-page__summary" aria-label="Tóm tắt dự báo">
        <AsyncBlock
          isLoading={inventory.pending}
          error={inventory.error}
          onRetry={inventory.retry}
          skeleton={
            <div className="forecast-page__summary">
              <Skeleton height={92} />
              <Skeleton height={92} />
              <Skeleton height={92} />
            </div>
          }
        >
          {inventory.pending ? null : (
            <>
              <Metric label="Có dự báo (trang)" value={String(forecastCount)} />
              <Metric label="Nhu cầu ROP (trang)" value={formatQuantity(demandTotal)} />
              <Metric label="Gợi ý nhập (trang)" value={formatQuantity(suggestedTotal)} />
            </>
          )}
        </AsyncBlock>
      </section>

      <section className="forecast-page__chart-panel" aria-labelledby="forecast-chart-title">
        <header className="forecast-page__section-header">
          <div>
            <h2 id="forecast-chart-title">
              {selectedRow ? selectedRow.item.productName : 'Dự báo theo ngày'}
            </h2>
            <p>
              {forecast.data
                ? `SKU ${forecast.data.sku} · Model ${forecast.data.modelVersion} · Dữ liệu đến ${formatDayFull(forecast.data.cutoffDate)}`
                : 'Chọn sản phẩm trong bảng để xem đường dự báo theo ngày.'}
            </p>
          </div>
          <div className="forecast-page__horizons" role="group" aria-label="Khoảng dự báo">
            {HORIZONS.map((value) => (
              <button
                key={value}
                type="button"
                aria-pressed={horizon === value}
                onClick={() => setHorizon(value)}
              >
                {value} ngày
              </button>
            ))}
          </div>
        </header>
        <AsyncBlock
          isLoading={inventory.pending || forecast.pending}
          error={inventory.error ?? forecast.error}
          onRetry={inventory.error ? inventory.retry : forecast.retry}
          isEmpty={chartEmpty}
          empty={{
            message: selectedRow
              ? 'Chưa có dữ liệu dự báo cho sản phẩm này'
              : 'Chưa có dữ liệu dự báo',
            description: 'Dữ liệu sẽ hiển thị sau khi có lần chạy dự báo hoàn tất.',
          }}
          skeleton={<Skeleton height={280} />}
        >
          {inventory.pending || forecast.pending ? null : selectedForecast ? (
            <ForecastAreaChart data={chartData} horizon={horizon} />
          ) : (
            <EmptyState message="Chọn một sản phẩm có dự báo ở bảng bên dưới" />
          )}
        </AsyncBlock>
      </section>

      <section className="forecast-page__table-panel" aria-labelledby="forecast-table-title">
        <header className="forecast-page__section-header forecast-page__table-heading">
          <div>
            <h2 id="forecast-table-title">Gợi ý nhập hàng</h2>
            <p>Ưu tiên mặt hàng thiếu nhiều; số lượng đã tính theo quy cách đóng gói.</p>
          </div>
        </header>
        <form
          className="forecast-page__filters"
          onSubmit={(event) => {
            event.preventDefault();
            setSearch(searchText.trim());
            setPage(1);
          }}
        >
          <Input
            label="Tìm sản phẩm"
            type="search"
            value={searchText}
            onChange={(event) => setSearchText(event.target.value)}
            placeholder="Tên, SKU hoặc barcode"
          />
          <Select
            label="Trạng thái tồn"
            value={status}
            onChange={(event) => {
              const value = event.target.value;
              if (value === 'ALL' || value === 'OUT' || value === 'LOW' || value === 'OK') {
                setStatus(value);
              }
              setPage(1);
            }}
            options={[
              { label: 'Tất cả', value: 'ALL' },
              { label: 'Hết hàng', value: 'OUT' },
              { label: 'Tồn thấp', value: 'LOW' },
              { label: 'Bình thường', value: 'OK' },
            ]}
          />
          <button type="submit" className="forecast-page__filter-button">
            Tìm kiếm
          </button>
        </form>

        <AsyncBlock
          isLoading={inventory.pending}
          error={inventory.error}
          isEmpty={!!inventory.data && inventory.data.rows.length === 0}
          onRetry={inventory.retry}
          empty={empty}
          skeleton={<ForecastTableSkeleton showBranch={branchId === undefined} />}
        >
          {inventory.pending ? null : (
            <>
              <ForecastTable
                rows={inventory.data?.rows ?? []}
                showBranch={branchId === undefined}
                selectedKey={selectedRow ? rowKey(selectedRow.item) : undefined}
                onSelect={(item) => setSelectedProductKey(rowKey(item))}
              />
              {inventory.data && inventory.data.totalPages > 1 && (
                <Pagination page={page} totalPages={inventory.data.totalPages} onChange={setPage} />
              )}
              {rowCount > 0 && (
                <p className="forecast-page__page-note">
                  Đang hiển thị {rowCount} sản phẩm · {forecastCount} có dự báo
                </p>
              )}
            </>
          )}
        </AsyncBlock>
      </section>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <article className="forecast-page__metric">
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}

function ForecastTable({
  rows,
  showBranch,
  selectedKey,
  onSelect,
}: {
  rows: ForecastInventoryRow[];
  showBranch: boolean;
  selectedKey?: string;
  onSelect: (item: InventoryItem) => void;
}) {
  return (
    <div className="forecast-table-wrap">
      <table className="forecast-table">
        <thead>
          <tr>
            <th scope="col">Sản phẩm</th>
            {showBranch && <th scope="col">Chi nhánh</th>}
            <th scope="col">Khả dụng</th>
            <th scope="col">Nhu cầu ROP</th>
            <th scope="col">ROP</th>
            <th scope="col">An toàn</th>
            <th scope="col">Gợi ý nhập</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(({ item, replenishment }) => {
            const selected = rowKey(item) === selectedKey;
            return (
              <tr key={rowKey(item)} className={selected ? 'is-selected' : undefined}>
                <td>
                  <button
                    className="forecast-table__product"
                    type="button"
                    aria-pressed={selected}
                    onClick={() => onSelect(item)}
                  >
                    <strong>{item.productName}</strong>
                    <small>{item.sku}</small>
                  </button>
                </td>
                {showBranch && <td>{item.branch.code}</td>}
                <td>{formatQuantity(replenishment.available)}</td>
                <td>{formatQuantity(replenishment.forecastDemand)}</td>
                <td>{formatQuantity(replenishment.reorderPoint)}</td>
                <td>{formatQuantity(replenishment.safetyStock)}</td>
                <td className="forecast-table__suggestion">
                  {replenishment.suggestedQuantity == null
                    ? 'Chưa có gợi ý'
                    : formatQuantity(replenishment.suggestedQuantity)}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export default ForecastPage;
