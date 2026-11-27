import { useState } from 'react';
import { Link, Outlet, useNavigate } from 'react-router';
import type { CurrentUser } from '../../api/client';
import { AsyncBlock, Button, Toast } from '../../components/ui';
import { useOperationsScope } from '../../layouts/operations/useOperationsScope';
import { branchLabel } from '../../layouts/operations/adapters';
import { useAuth } from '../auth/useAuth';
import Pagination from '../inventory/PaginationControl';
import { useRemote } from '../stock-documents/useRemote';
import { canCreateStockDocument } from '../stock-documents/stockDocumentPermissions';
import { createStockDocument, listStockDocuments } from '../stock-documents/stockDocumentApi';
import '../stock-documents/StockDocuments.css';
import './stocktake.css';

const PAGE_SIZE = 20;

export interface StocktakeOutletContext {
  user: CurrentUser;
  onChanged: () => void;
}

function StocktakeSkeleton() {
  return (
    <div className="sd-table-wrap" role="status" aria-label="Đang tải phiếu kiểm kê">
      <table className="sd-table">
        <tbody>
          {Array.from({ length: 5 }, (_, row) => (
            <tr key={row}>
              {Array.from({ length: 4 }, (_, cell) => (
                <td key={cell}>
                  <span className="gm-skeleton stocktake-skeleton-cell" />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function StocktakePage() {
  const auth = useAuth();
  const user = auth.state.status === 'authenticated' ? auth.state.user : null;
  const { branchId, scope } = useOperationsScope();
  const navigate = useNavigate();
  const [page, setPage] = useState(1);
  const [creating, setCreating] = useState(false);
  const [message, setMessage] = useState('');
  const [errorMessage, setErrorMessage] = useState('');
  const remote = useRemote(
    (signal) =>
      listStockDocuments({ branchId, type: 'ADJUST', page: page - 1, size: PAGE_SIZE }, signal),
    `stocktake|${branchId ?? 'missing'}|${page}`,
  );
  if (!user) return null;

  const canCreate = canCreateStockDocument(user, branchId);
  const currentBranch =
    scope.kind === 'branch' ? branchLabel(scope.branch) : 'Chi nhánh chưa được chọn';

  async function createStocktake() {
    if (creating || branchId === undefined) return;
    setCreating(true);
    setErrorMessage('');
    try {
      const document = await createStockDocument({
        type: 'ADJUST',
        branchId,
        allProducts: true,
      });
      setMessage(`Đã tạo phiếu kiểm kê ${document.code}.`);
      remote.reload();
      navigate(`/operations/stocktake/${document.id}`);
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : 'Không thể tạo phiếu kiểm kê.');
    } finally {
      setCreating(false);
    }
  }

  return (
    <div className="sd-page stocktake-page">
      <header className="sd-header">
        <div>
          <p className="sd-breadcrumb">Kho / Kiểm kê</p>
          <h1>Kiểm kê tồn kho</h1>
          <p>
            Tạo phiếu chụp tồn, nhập số đếm thực tế và đối chiếu chênh lệch tại {currentBranch}.
          </p>
        </div>
        {canCreate && (
          <div className="sd-header-actions">
            <Button onClick={() => void createStocktake()} disabled={creating}>
              {creating ? 'Đang tạo…' : '+ Tạo phiếu kiểm kê'}
            </Button>
          </div>
        )}
      </header>

      {message && <Toast variant="success" title={message} onClose={() => setMessage('')} />}
      {errorMessage && <Toast variant="error" title={errorMessage} />}

      <section
        className="sd-card"
        aria-label="Danh sách phiếu kiểm kê"
        aria-busy={remote.isLoading}
      >
        <div className="sd-card-heading">
          <h2>Phiếu kiểm kê</h2>
          {remote.data && (
            <span className="sd-result-count">{remote.data.totalElements} phiếu</span>
          )}
        </div>
        <AsyncBlock
          isLoading={remote.isLoading}
          error={remote.error}
          isEmpty={!!remote.data && remote.data.content.length === 0}
          onRetry={remote.reload}
          empty={{
            message: 'Chưa có phiếu kiểm kê',
            description: 'Tạo phiếu để chụp tồn hiện tại của chi nhánh và bắt đầu nhập số đếm.',
            ...(canCreate
              ? { actionLabel: '+ Tạo phiếu kiểm kê', onAction: () => void createStocktake() }
              : {}),
          }}
          skeleton={<StocktakeSkeleton />}
        >
          {remote.data && remote.data.content.length > 0 && (
            <div className="sd-table-wrap">
              <table className="sd-table">
                <thead>
                  <tr>
                    <th scope="col">Mã phiếu</th>
                    <th scope="col">Chi nhánh</th>
                    <th scope="col">Mặt hàng</th>
                    <th scope="col">Trạng thái</th>
                    <th scope="col">Ngày tạo</th>
                  </tr>
                </thead>
                <tbody>
                  {remote.data.content.map((document) => (
                    <tr key={document.id}>
                      <td>
                        <Link className="sd-code" to={`/operations/stocktake/${document.id}`}>
                          {document.code}
                        </Link>
                      </td>
                      <td>{document.branch.name}</td>
                      <td>{document.itemCount}</td>
                      <td>
                        <span
                          className={`sd-badge ${document.status === 'DRAFT' ? 'sd-badge--draft' : 'sd-badge--approved'}`}
                        >
                          {document.status === 'DRAFT'
                            ? 'Bản nháp'
                            : document.status === 'APPROVED'
                              ? 'Đã duyệt'
                              : 'Đã hủy'}
                        </span>
                      </td>
                      <td>
                        {new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium' }).format(
                          new Date(document.createdAt),
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </AsyncBlock>
        {!remote.error && remote.data && remote.data.totalPages > 1 && (
          <div className="sd-card-footer">
            <Pagination page={page} totalPages={remote.data.totalPages} onChange={setPage} />
          </div>
        )}
      </section>

      <Outlet context={{ user, onChanged: remote.reload } satisfies StocktakeOutletContext} />
    </div>
  );
}
