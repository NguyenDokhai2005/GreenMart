import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { Button, Table, Toast } from '../../components/ui';
import type { BranchRef, Role } from '../../api/client';
import { useActiveBranch } from './useActiveBranch';
import { APP_PATHS, getRequestedPath, getWorkspacePath } from './routePolicy';
import { useAuth } from './useAuth';
import './BranchSelectionPage.css';

const roleNames: Record<Role, string> = {
  ADMIN: 'Quản trị hệ thống',
  OWNER: 'Chủ chuỗi',
  BRANCH_MANAGER: 'Quản lý chi nhánh',
  CASHIER: 'Thu ngân',
  STOREKEEPER: 'Thủ kho',
};

interface BranchRow extends BranchRef {
  roleName: string;
  accessStatus: string;
}

export default function BranchSelectionPage() {
  const { state: authState, signOut } = useAuth();
  const user = authState.status === 'authenticated' ? authState.user : null;
  const activeBranch = useActiveBranch();
  const location = useLocation();
  const navigate = useNavigate();
  const [selectedBranchId, setSelectedBranchId] = useState<number | null>(null);

  if (!user) return null;
  const userRole = user.role;
  const userFullName = user.fullName;
  if (activeBranch.state.status === 'checking') {
    return (
      <main className="branch-page__loading" role="status" aria-live="polite">
        Đang tải danh sách chi nhánh…
      </main>
    );
  }
  if (activeBranch.state.status === 'active') {
    return (
      <Navigate
        to={getRequestedPath(location.state) ?? getWorkspacePath(userRole)}
        replace
        state={location.state}
      />
    );
  }

  const branches =
    activeBranch.state.status === 'selection-required' ? activeBranch.state.branches : [];
  const rows: BranchRow[] = branches.map((branch) => ({
    ...branch,
    roleName: roleNames[userRole],
    accessStatus: selectedBranchId === branch.id ? 'Đang chọn' : 'Được phép truy cập',
  }));
  const selectedBranch = branches.find((branch) => branch.id === selectedBranchId);

  async function handleSignOut() {
    let failedToRevoke = false;
    try {
      await signOut();
    } catch {
      failedToRevoke = true;
    }
    navigate(APP_PATHS.login, {
      replace: true,
      state: failedToRevoke ? { logoutWarning: true } : undefined,
    });
  }

  function continueWithBranch() {
    if (!selectedBranch || !activeBranch.selectBranch(selectedBranch.id)) return;
    navigate(getRequestedPath(location.state) ?? getWorkspacePath(userRole), {
      replace: true,
      state: location.state,
    });
  }

  const columns = [
    {
      key: 'name',
      title: 'Chi nhánh',
      render: (row: BranchRow) => (
        <button
          className="branch-page__row-select"
          type="button"
          aria-pressed={selectedBranchId === row.id}
          onClick={() => setSelectedBranchId(row.id)}
        >
          <strong>
            {row.code} — {row.name}
          </strong>
        </button>
      ),
    },
    { key: 'roleName', title: 'Vai trò' },
    { key: 'accessStatus', title: 'Trạng thái' },
  ];

  return (
    <div className="branch-page">
      <header className="branch-page__topbar">
        <strong>GreenMart</strong>
        <span>Chưa chọn chi nhánh</span>
        <span>
          {roleNames[userRole]} · {userFullName}
        </span>
      </header>

      <main className="branch-page__main">
        <header className="branch-page__intro">
          <h1>Bắt đầu ca làm việc</h1>
          <p>Chọn chi nhánh trong phạm vi được phân quyền.</p>
        </header>

        {activeBranch.state.status === 'no-branches' ? (
          <section className="branch-page__empty" aria-labelledby="branch-empty-title">
            <Toast
              variant="warning"
              title="Tài khoản chưa được gán chi nhánh"
              description="Liên hệ quản trị viên để được hỗ trợ."
            />
            <h2 id="branch-empty-title">Không có chi nhánh để tiếp tục</h2>
            <Button variant="secondary" onClick={() => void handleSignOut()}>
              Đăng xuất
            </Button>
          </section>
        ) : (
          <>
            <section
              className="branch-page__table-card"
              aria-label="Danh sách chi nhánh được phép truy cập"
            >
              <Table
                columns={columns}
                data={rows}
                rowKey="id"
                emptyText="Tài khoản chưa được gán chi nhánh."
              />
            </section>
            <div className="branch-page__actions">
              <Button disabled={!selectedBranch} onClick={continueWithBranch}>
                {selectedBranch ? `Tiếp tục với ${selectedBranch.code}` : 'Chọn một chi nhánh'}
              </Button>
              <Button variant="secondary" onClick={() => void handleSignOut()}>
                Đăng xuất
              </Button>
            </div>
          </>
        )}

        <p className="branch-page__footer">
          GreenMart · Chọn một chi nhánh được phân quyền để tiếp tục
        </p>
      </main>
    </div>
  );
}
