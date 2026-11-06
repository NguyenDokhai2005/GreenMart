import { useState } from 'react';
import AsyncBlock from './AsyncBlock';
import EmptyState from './EmptyState';
import ErrorState from './ErrorState';
import PageLoader from './PageLoader';
import Skeleton from './Skeleton';

/** Thêm <UiStatesShowcase /> vào ComponentShowcase. */
function UiStatesShowcase() {
  const [mode, setMode] = useState<'loading' | 'empty' | 'filtered' | 'error' | 'data'>('loading');
  return (
    <section>
      <h2>Trạng thái giao diện</h2>
      <h3>Skeleton</h3>
      <Skeleton lines={3} />
      <h3>PageLoader</h3>
      <PageLoader />
      <h3>EmptyState</h3>
      <EmptyState message="Chưa có phiếu nhập nào" description="Tạo phiếu để bắt đầu theo dõi nhập kho." actionLabel="Tạo phiếu nhập" onAction={() => {}} />
      <EmptyState message="" filtered onClearFilters={() => {}} />
      <h3>ErrorState</h3>
      <ErrorState message="Không tải được danh sách phiếu nhập." traceId="abc123" onRetry={() => {}} />
      <h3>AsyncBlock</h3>
      <div role="group" aria-label="Chọn trạng thái">
        {(['loading', 'empty', 'filtered', 'error', 'data'] as const).map((m) => (
          <button key={m} type="button" onClick={() => setMode(m)} aria-pressed={mode === m}>{m}</button>
        ))}
      </div>
      <AsyncBlock
        isLoading={mode === 'loading'}
        error={mode === 'error' ? { status: 500, body: { code: 'INTERNAL', message: 'Máy chủ gặp lỗi.' } } : null}
        isEmpty={mode === 'empty' || mode === 'filtered'}
        onRetry={() => setMode('data')}
        empty={{ message: 'Chưa có dữ liệu', filtered: mode === 'filtered', onClearFilters: () => setMode('data') }}
      >
        <p>Nội dung khối đã tải.</p>
      </AsyncBlock>
    </section>
  );
}

export default UiStatesShowcase;