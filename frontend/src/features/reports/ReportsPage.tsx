import { useState } from 'react';
import type { FormEvent } from 'react';
import { AsyncBlock, Button, Input, Select } from '../../components/ui';
import { useOperationsScope } from '../../layouts/operations/useOperationsScope';
import { branchLabel } from '../../layouts/operations/adapters';
import { useRemote } from '../stock-documents/useRemote';
import { getRevenueReport, getTopProductsReport } from './reportApi';
import './reports.css';

type GroupBy = 'day' | 'week' | 'month';

function localDate(date: Date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function defaultRange() {
  const to = new Date();
  const from = new Date(to);
  from.setDate(from.getDate() - 29);
  return { from: localDate(from), to: localDate(to) };
}

function daysBetween(from: string, to: string) {
  const start = Date.parse(`${from}T00:00:00Z`);
  const end = Date.parse(`${to}T00:00:00Z`);
  return Math.floor((end - start) / 86_400_000) + 1;
}

const MONEY = new Intl.NumberFormat('vi-VN');
const QUANTITY = new Intl.NumberFormat('vi-VN');

function money(value: number) {
  return `${MONEY.format(value)} đ`;
}

function SummaryCard({ label, value }: { label: string; value: string }) {
  return (
    <article className="reports-summary-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}

function ProductTable({
  title,
  items,
}: {
  title: string;
  items: Awaited<ReturnType<typeof getTopProductsReport>>['topSelling'];
}) {
  return (
    <section className="reports-card" aria-label={title}>
      <div className="reports-card__heading">
        <h2>{title}</h2>
        <span>{items.length} sản phẩm</span>
      </div>
      {items.length === 0 ? (
        <p className="reports-empty">Không có sản phẩm trong kỳ báo cáo.</p>
      ) : (
        <div className="reports-table-wrap">
          <table className="reports-table">
            <thead>
              <tr>
                <th scope="col">Sản phẩm</th>
                <th scope="col" className="reports-number">
                  Đã bán
                </th>
                <th scope="col" className="reports-number">
                  Đã trả
                </th>
                <th scope="col" className="reports-number">
                  Ròng
                </th>
                <th scope="col" className="reports-number">
                  Doanh thu
                </th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.productId}>
                  <td>
                    <strong>{item.name}</strong>
                    <small>
                      {item.sku}
                      {item.categoryName ? ` · ${item.categoryName}` : ''}
                    </small>
                  </td>
                  <td className="reports-number">{QUANTITY.format(item.soldQuantity)}</td>
                  <td className="reports-number">{QUANTITY.format(item.returnedQuantity)}</td>
                  <td className="reports-number">{QUANTITY.format(item.netQuantity)}</td>
                  <td className="reports-number">{money(item.revenue)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

export default function ReportsPage() {
  const { branchId, scope } = useOperationsScope();
  const defaults = defaultRange();
  const [from, setFrom] = useState(defaults.from);
  const [to, setTo] = useState(defaults.to);
  const [applied, setApplied] = useState(defaults);
  const [groupBy, setGroupBy] = useState<GroupBy>('day');
  const [filterError, setFilterError] = useState('');
  const remote = useRemote(
    async (signal) => {
      const [revenue, topProducts] = await Promise.all([
        getRevenueReport({ from: applied.from, to: applied.to, groupBy, branchId }, signal),
        getTopProductsReport({ from: applied.from, to: applied.to, limit: 10, branchId }, signal),
      ]);
      return { revenue, topProducts };
    },
    `reports|${branchId ?? 'all'}|${applied.from}|${applied.to}|${groupBy}`,
  );

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!from || !to || from > to) {
      setFilterError('Từ ngày phải trước hoặc bằng Đến ngày.');
      return;
    }
    if (daysBetween(from, to) > 366) {
      setFilterError('Khoảng báo cáo không được vượt quá 366 ngày.');
      return;
    }
    setFilterError('');
    setApplied({ from, to });
  }

  const scopeText = scope.kind === 'all' ? 'toàn hệ thống' : branchLabel(scope.branch);

  return (
    <div className="reports-page">
      <header className="reports-header">
        <div>
          <p className="reports-breadcrumb">Kinh doanh / Báo cáo</p>
          <h1>Báo cáo</h1>
          <p>Doanh thu và sản phẩm bán chạy/chậm tại {scopeText}.</p>
        </div>
      </header>

      <form className="reports-filters" onSubmit={applyFilters}>
        <Input
          label="Từ ngày"
          type="date"
          required
          value={from}
          onChange={(event) => setFrom(event.target.value)}
        />
        <Input
          label="Đến ngày"
          type="date"
          required
          value={to}
          onChange={(event) => setTo(event.target.value)}
        />
        <Select
          label="Gom doanh thu theo"
          value={groupBy}
          options={[
            { value: 'day', label: 'Ngày' },
            { value: 'week', label: 'Tuần' },
            { value: 'month', label: 'Tháng' },
          ]}
          onChange={(event) => setGroupBy(event.target.value as GroupBy)}
        />
        <Button type="submit">Xem báo cáo</Button>
        {filterError && (
          <p className="reports-filter-error" role="alert">
            {filterError}
          </p>
        )}
      </form>

      <AsyncBlock
        isLoading={remote.isLoading}
        error={remote.error}
        isEmpty={
          !!remote.data &&
          remote.data.revenue.points.length === 0 &&
          remote.data.topProducts.topSelling.length === 0 &&
          remote.data.topProducts.slowSelling.length === 0
        }
        onRetry={remote.reload}
        empty={{
          message: 'Chưa có dữ liệu báo cáo',
          description: 'Thử chọn khoảng ngày khác hoặc kiểm tra lại sau khi có giao dịch.',
        }}
        skeleton={<div className="reports-skeleton" role="status" aria-label="Đang tải báo cáo" />}
      >
        {remote.data && (
          <div className="reports-results">
            <section className="reports-summary" aria-label="Tóm tắt doanh thu">
              <SummaryCard
                label="Doanh thu thuần"
                value={money(remote.data.revenue.summary.totalNetRevenue)}
              />
              <SummaryCard
                label="Doanh thu bán"
                value={money(remote.data.revenue.summary.totalGrossRevenue)}
              />
              <SummaryCard
                label="Tiền hoàn trả"
                value={money(remote.data.revenue.summary.totalReturnAmount)}
              />
              <SummaryCard
                label="Đơn hoàn tất"
                value={String(remote.data.revenue.summary.totalOrders)}
              />
            </section>

            <section className="reports-card" aria-label="Doanh thu theo thời gian">
              <div className="reports-card__heading">
                <h2>
                  Doanh thu theo{' '}
                  {groupBy === 'day' ? 'ngày' : groupBy === 'week' ? 'tuần' : 'tháng'}
                </h2>
                <span>
                  {applied.from} – {applied.to}
                </span>
              </div>
              {remote.data.revenue.points.length === 0 ? (
                <p className="reports-empty">Không có dữ liệu doanh thu trong kỳ.</p>
              ) : (
                <div className="reports-table-wrap">
                  <table className="reports-table">
                    <thead>
                      <tr>
                        <th scope="col">Kỳ</th>
                        <th scope="col" className="reports-number">
                          Doanh thu bán
                        </th>
                        <th scope="col" className="reports-number">
                          Hoàn trả
                        </th>
                        <th scope="col" className="reports-number">
                          Doanh thu thuần
                        </th>
                        <th scope="col" className="reports-number">
                          Đơn hoàn tất
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {remote.data.revenue.points.map((point) => (
                        <tr key={point.date}>
                          <td>{point.date}</td>
                          <td className="reports-number">{money(point.grossRevenue)}</td>
                          <td className="reports-number">{money(point.returnAmount)}</td>
                          <td className="reports-number">{money(point.revenue)}</td>
                          <td className="reports-number">{point.orderCount}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>

            <div className="reports-products">
              <ProductTable
                title="Top sản phẩm bán chạy"
                items={remote.data.topProducts.topSelling}
              />
              <ProductTable
                title="Top sản phẩm bán chậm"
                items={remote.data.topProducts.slowSelling}
              />
            </div>
          </div>
        )}
      </AsyncBlock>
    </div>
  );
}
