import { Link } from 'react-router';
import type { BranchRef } from '../../api/client';
import { AsyncBlock, Skeleton } from '../../components/ui';
import type { AsyncBlockProps } from '../../components/ui';
import { branchLabel } from '../../layouts/operations/adapters';
import { useLowStockAlerts } from './useLowStockAlerts';

interface LowStockAlertsPanelProps {
  branches: readonly BranchRef[];
  compact?: boolean;
  alerts: ReturnType<typeof useLowStockAlerts>;
  filtered?: boolean;
  onClearFilters?: () => void;
}

export default function LowStockAlertsPanel({
  branches,
  compact = false,
  alerts,
  filtered = false,
  onClearFilters,
}: LowStockAlertsPanelProps) {
  const visibleAlerts = compact ? alerts.data?.slice(0, 5) : alerts.data;
  const empty: AsyncBlockProps['empty'] = {
    message: 'Chưa có cảnh báo tồn kho',
    description: 'Các mặt hàng hết hàng hoặc chạm điểm đặt lại sẽ xuất hiện tại đây.',
    filtered,
    onClearFilters,
  };
  const branchNames = new Map(branches.map((branch) => [branch.id, branchLabel(branch)]));

  return (
    <section
      className="gm-dashboard__panel gm-dashboard__alerts"
      aria-labelledby="stock-alerts-title"
    >
      <header className="gm-dashboard__panel-header">
        <div>
          <h2 id="stock-alerts-title">Cảnh báo tồn kho</h2>
          <p>Hết hàng trước, tồn thấp sau</p>
        </div>
        {compact && (
          <Link className="gm-dashboard__text-link" to="/operations/alerts">
            Xem tất cả
          </Link>
        )}
      </header>
      <AsyncBlock
        isLoading={alerts.pending}
        error={alerts.error}
        isEmpty={alerts.data?.length === 0}
        onRetry={alerts.retry}
        empty={empty}
        skeleton={<Skeleton lines={5} height={48} />}
      >
        <ul className="gm-dashboard__alert-list">
          {visibleAlerts?.map((alert) => (
            <li
              className={`gm-dashboard__alert gm-dashboard__alert--${alert.severity.toLowerCase()}`}
              key={alert.id}
            >
              <span className="gm-dashboard__alert-indicator" aria-hidden="true" />
              <div className="gm-dashboard__alert-info">
                <strong>{alert.productName}</strong>
                <span>
                  {alert.sku}
                  {branchNames.has(alert.branchId) ? ` · ${branchNames.get(alert.branchId)}` : ''}
                </span>
              </div>
              <div className="gm-dashboard__alert-stock">
                <span>{alert.severity === 'OUT' ? 'Hết hàng' : 'Tồn thấp'}</span>
                <strong>{alert.available}</strong>
              </div>
            </li>
          ))}
        </ul>
      </AsyncBlock>
    </section>
  );
}
