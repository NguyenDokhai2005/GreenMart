import { useNavigate } from 'react-router';
import { Button } from '../../components/ui';
import { useOperationsScope } from '../../layouts/operations/useOperationsScope';
import LowStockAlertsPanel from './LowStockAlertsPanel';
import { useLowStockAlerts } from './useLowStockAlerts';
import './dashboard.css';

export default function StockAlertsPage() {
  const navigate = useNavigate();
  const { branchId, branches, canPickAll, pickAll } = useOperationsScope();
  const alerts = useLowStockAlerts(branchId);
  const filtered = branchId !== undefined && canPickAll;
  return (
    <div className="gm-dashboard gm-dashboard--alerts-page">
      <header className="gm-dashboard__heading">
        <div>
          <h1>Cảnh báo tồn kho</h1>
          <p>Danh sách cảnh báo đang mở theo phạm vi chi nhánh hiện tại.</p>
        </div>
        <Button variant="secondary" onClick={() => navigate('/operations')}>
          Quay lại tổng quan
        </Button>
      </header>
      <LowStockAlertsPanel
        branches={branches}
        alerts={alerts}
        filtered={filtered}
        onClearFilters={filtered ? pickAll : undefined}
      />
    </div>
  );
}
