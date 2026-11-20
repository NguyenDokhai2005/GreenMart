import { useCallback, useMemo, useState } from 'react';
import { RevenueAreaChart, formatVnd } from '../../components/charts';
import { AsyncBlock, Select, Skeleton } from '../../components/ui';
import type { AsyncBlockProps } from '../../components/ui';
import { useCatalogQuery } from '../catalog/useCatalogQuery';
import { branchLabel } from '../../layouts/operations/adapters';
import { useOperationsScope } from '../../layouts/operations/useOperationsScope';
import { CHANNEL_OPTIONS, dashboardApi } from './dashboardApi';
import LowStockAlertsPanel from './LowStockAlertsPanel';
import './dashboard.css';
import { useLowStockAlerts } from './useLowStockAlerts';

function localDate(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function getLast30Days() {
  const to = new Date();
  const from = new Date(to);
  from.setDate(from.getDate() - 29);
  return { from: localDate(from), to: localDate(to) };
}

function Metric({ label, value, footnote }: { label: string; value: string; footnote?: string }) {
  return (
    <article className="gm-dashboard__metric">
      <span>{label}</span>
      <strong>{value}</strong>
      {footnote && <small>{footnote}</small>}
    </article>
  );
}

function DashboardPage() {
  const { branchId, branches, canPickAll, pickAll, scope } = useOperationsScope();
  const [channel, setChannel] = useState('all');
  const channelId = channel === 'all' ? undefined : Number(channel);
  const { from, to } = useMemo(() => getLast30Days(), []);
  const revenueFilter = useMemo(
    () => ({
      from,
      to,
      groupBy: 'day' as const,
      branchId,
      channelId,
    }),
    [branchId, channelId, from, to],
  );
  const loadRevenue = useCallback(
    (signal: AbortSignal) => dashboardApi.revenue(revenueFilter, signal),
    [revenueFilter],
  );
  const revenue = useCatalogQuery(
    `revenue:${from}:${to}:${branchId ?? 'all'}:${channelId ?? 'all'}`,
    loadRevenue,
  );
  const loadTopProducts = useCallback(
    (signal: AbortSignal) =>
      dashboardApi.topProducts({ from, to, limit: 8, branchId, channelId }, signal),
    [branchId, channelId, from, to],
  );
  const topProducts = useCatalogQuery(
    `top-products:${from}:${to}:${branchId ?? 'all'}:${channelId ?? 'all'}`,
    loadTopProducts,
  );
  const alerts = useLowStockAlerts(branchId);

  const branchIds = useMemo(
    () => (scope.kind === 'branch' ? [scope.branch] : branches),
    [branches, scope],
  );
  const loadBranchRevenue = useCallback(
    async (signal: AbortSignal) => {
      if (scope.kind === 'branch' || branchIds.length === 0) return [];
      const reports = await Promise.all(
        branchIds.map((branch) =>
          dashboardApi.revenue(
            { from, to, groupBy: 'day', branchId: branch.id, channelId },
            signal,
          ),
        ),
      );
      return reports.map((report, index) => ({
        id: branchIds[index]!.id,
        name: branchLabel(branchIds[index]!),
        revenue: report.summary.totalNetRevenue,
      }));
    },
    [branchIds, channelId, from, scope.kind, to],
  );
  const branchRevenueQuery = useCatalogQuery(
    `branch-revenue:${from}:${to}:${channelId ?? 'all'}:${branches.map((b) => b.id).join(',')}`,
    loadBranchRevenue,
    scope.kind === 'all',
  );
  const branchRevenue = useMemo(
    () =>
      scope.kind === 'branch'
        ? revenue.data
          ? [
              {
                id: scope.branch.id,
                name: branchLabel(scope.branch),
                revenue: revenue.data.summary.totalNetRevenue,
              },
            ]
          : undefined
        : branchRevenueQuery.data,
    [branchRevenueQuery.data, revenue.data, scope],
  );
  const clearFilters = () => {
    setChannel('all');
    if (canPickAll) pickAll();
  };
  const filtered = channelId !== undefined || (branchId !== undefined && canPickAll);
  const empty: AsyncBlockProps['empty'] = {
    message: 'Chưa có dữ liệu doanh thu trong 30 ngày qua',
    filtered,
    onClearFilters: filtered ? clearFilters : undefined,
  };
  const totalAlerts = alerts.data?.length;
  const topSelling = topProducts.data?.topSelling ?? [];
  const maxBranchRevenue = Math.max(0, ...(branchRevenue ?? []).map((item) => item.revenue));
  const maxTopProduct = Math.max(0, ...topSelling.map((item) => item.netQuantity));

  return (
    <div className="gm-dashboard">
      <header className="gm-dashboard__heading">
        <div>
          <h1>Tổng quan kinh doanh</h1>
          <p>
            {from.split('-').reverse().join('/')} – {to.split('-').reverse().join('/')} · 30 ngày
            gần nhất
          </p>
        </div>
        <div className="gm-dashboard__filters">
          <Select
            aria-label="Lọc theo kênh bán"
            value={channel}
            onChange={(event) => setChannel(event.target.value)}
            placeholder="Chọn kênh"
            options={[{ label: 'Tất cả kênh', value: 'all' }, ...CHANNEL_OPTIONS]}
          />
          {scope.kind === 'branch' && (
            <span className="gm-dashboard__scope">{branchLabel(scope.branch)}</span>
          )}
        </div>
      </header>

      <section className="gm-dashboard__metrics" aria-label="Chỉ số kinh doanh 30 ngày">
        <AsyncBlock
          isLoading={revenue.pending}
          error={revenue.error}
          onRetry={revenue.retry}
          skeleton={
            <div className="gm-dashboard__metric-skeleton" aria-label="Đang tải doanh thu" />
          }
        >
          <Metric
            label="Doanh thu thuần"
            value={revenue.data ? formatVnd(revenue.data.summary.totalNetRevenue) : '—'}
            footnote="Đã trừ tiền trả hàng"
          />
          <Metric
            label="Đơn hoàn tất"
            value={revenue.data ? String(revenue.data.summary.totalOrders) : '—'}
            footnote="Tính theo ngày hoàn tất"
          />
          <Metric
            label="Giá trị đơn trung bình"
            value={
              revenue.data
                ? formatVnd(
                    revenue.data.summary.totalOrders > 0
                      ? revenue.data.summary.totalNetRevenue / revenue.data.summary.totalOrders
                      : 0,
                  )
                : '—'
            }
            footnote="Doanh thu thuần / đơn hoàn tất"
          />
        </AsyncBlock>
        <AsyncBlock
          isLoading={alerts.pending}
          error={alerts.error}
          onRetry={alerts.retry}
          skeleton={
            <div className="gm-dashboard__metric-skeleton" aria-label="Đang tải cảnh báo" />
          }
        >
          <Metric
            label="Cảnh báo tồn kho"
            value={totalAlerts === undefined ? '—' : String(totalAlerts)}
            footnote="Đang mở tại chi nhánh"
          />
        </AsyncBlock>
      </section>

      <div className="gm-dashboard__primary-grid">
        <section className="gm-dashboard__panel" aria-labelledby="revenue-chart-title">
          <header className="gm-dashboard__panel-header">
            <div>
              <h2 id="revenue-chart-title">Doanh thu thuần 30 ngày</h2>
              <p>Ghi nhận theo ngày hoàn tất; trừ trả hàng vào ngày trả</p>
            </div>
          </header>
          <AsyncBlock
            isLoading={revenue.pending}
            error={revenue.error}
            onRetry={revenue.retry}
            isEmpty={revenue.data?.points.length === 0}
            empty={empty}
            skeleton={<Skeleton height={280} className="gm-dashboard__chart-skeleton" />}
          >
            <RevenueAreaChart data={revenue.data?.points ?? []} height={280} />
          </AsyncBlock>
        </section>
        <LowStockAlertsPanel
          branches={branches}
          compact
          alerts={alerts}
          filtered={branchId !== undefined && canPickAll}
          onClearFilters={clearFilters}
        />
      </div>

      <div
        className={`gm-dashboard__secondary-grid${canPickAll ? '' : ' gm-dashboard__secondary-grid--single'}`}
      >
        {canPickAll && (
          <section className="gm-dashboard__panel" aria-labelledby="branch-revenue-title">
            <header className="gm-dashboard__panel-header">
              <div>
                <h2 id="branch-revenue-title">Doanh thu theo chi nhánh</h2>
                <p>Doanh thu thuần trong 30 ngày</p>
              </div>
            </header>
            <AsyncBlock
              isLoading={scope.kind === 'branch' ? revenue.pending : branchRevenueQuery.pending}
              error={scope.kind === 'branch' ? revenue.error : branchRevenueQuery.error}
              onRetry={scope.kind === 'branch' ? revenue.retry : branchRevenueQuery.retry}
              isEmpty={branchRevenue?.length === 0}
              empty={empty}
              skeleton={<Skeleton lines={5} height={16} />}
            >
              <ul className="gm-dashboard__rank-list">
                {branchRevenue?.map((item) => (
                  <li key={item.id}>
                    <span className="gm-dashboard__rank-name">{item.name}</span>
                    <span className="gm-dashboard__rank-track">
                      <span
                        className="gm-dashboard__rank-bar"
                        style={{
                          width: `${maxBranchRevenue > 0 ? (item.revenue / maxBranchRevenue) * 100 : 0}%`,
                        }}
                      />
                    </span>
                    <strong>{formatVnd(item.revenue)}</strong>
                  </li>
                ))}
              </ul>
            </AsyncBlock>
          </section>
        )}

        <section className="gm-dashboard__panel" aria-labelledby="top-products-title">
          <header className="gm-dashboard__panel-header">
            <div>
              <h2 id="top-products-title">Top sản phẩm bán chạy</h2>
              <p>Theo số lượng bán ròng sau trả hàng</p>
            </div>
          </header>
          <AsyncBlock
            isLoading={topProducts.pending}
            error={topProducts.error}
            onRetry={topProducts.retry}
            isEmpty={topProducts.data?.topSelling.length === 0}
            empty={{
              message: 'Chưa có sản phẩm bán trong kỳ',
              filtered,
              onClearFilters: filtered ? clearFilters : undefined,
            }}
            skeleton={<Skeleton lines={6} height={16} />}
          >
            <ol className="gm-dashboard__rank-list gm-dashboard__product-list">
              {topSelling.map((item) => (
                <li key={item.productId}>
                  <span className="gm-dashboard__rank-name">{item.name}</span>
                  <span className="gm-dashboard__rank-track">
                    <span
                      className="gm-dashboard__rank-bar"
                      style={{
                        width: `${maxTopProduct > 0 ? (item.netQuantity / maxTopProduct) * 100 : 0}%`,
                      }}
                    />
                  </span>
                  <strong>{item.netQuantity.toLocaleString('vi-VN')}</strong>
                </li>
              ))}
            </ol>
          </AsyncBlock>
        </section>
      </div>
    </div>
  );
}

export default DashboardPage;
