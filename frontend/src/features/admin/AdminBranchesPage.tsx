import { useCallback } from 'react';
import { listBranches } from '../../api/client';
import type { components } from '../../api/schema';
import { AsyncBlock, Skeleton } from '../../components/ui';
import { useCatalogQuery } from '../catalog/useCatalogQuery';
import './admin.css';

type Branch = components['schemas']['Branch'];

export default function AdminBranchesPage() {
  const load = useCallback((signal: AbortSignal) => listBranches(true, signal), []);
  const query = useCatalogQuery<Branch[]>('admin-branches:active', load);

  return (
    <div className="admin-page">
      <header className="admin-page__heading">
        <div>
          <h1>Chi nhánh</h1>
          <p>Danh sách chi nhánh đang hoạt động trong hệ thống.</p>
        </div>
      </header>

      <section className="admin-card" aria-labelledby="admin-branches-title">
        <div className="admin-card__head">
          <h2 id="admin-branches-title">Danh sách chi nhánh</h2>
        </div>
        <AsyncBlock
          isLoading={query.pending}
          error={query.error}
          onRetry={query.retry}
          isEmpty={query.data?.length === 0}
          empty={{ message: 'Chưa có chi nhánh đang hoạt động' }}
          skeleton={<Skeleton lines={6} height={22} />}
        >
          <div className="admin-branches">
            {query.data?.map((branch) => (
              <article className="admin-branch" key={branch.id}>
                <div className="admin-branch__code">{branch.code}</div>
                <div className="admin-branch__info">
                  <strong>{branch.name}</strong>
                  <span>{branch.address || 'Chưa cập nhật địa chỉ'}</span>
                </div>
                <span className="admin-status admin-status--active">Đang hoạt động</span>
              </article>
            ))}
          </div>
        </AsyncBlock>
      </section>
    </div>
  );
}
