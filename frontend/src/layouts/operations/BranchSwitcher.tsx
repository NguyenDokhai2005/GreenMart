import { useEffect, useMemo, useRef, useState } from 'react';
import { EmptyState, Input } from '../../components/ui';
import { branchLabel } from './adapters';
import { Icon } from './Icon';
import { useOperationsScope } from './useOperationsScope';

export function BranchSwitcher() {
  const { scope, branches, canPickAll, locked, pickAll, pickBranch } = useOperationsScope();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const filtered = useMemo(() => {
    const k = q.trim().toLowerCase();
    return k ? branches.filter((b) => branchLabel(b).toLowerCase().includes(k)) : branches;
  }, [branches, q]);

  const current = scope.kind === 'all' ? 'Tất cả chi nhánh' : branchLabel(scope.branch);

  // Chi nhánh cố định: chỉ hiển thị, không có dropdown (tiêu chí 2).
  if (locked) {
    return (
      <div className="ops-branch ops-branch--locked" aria-label="Chi nhánh được giao">
        <Icon name="store" />
        <span className="ops-branch__text">
          <small>Chi nhánh được giao</small>
          <b>{current}</b>
        </span>
        <Icon name="lock" size={14} />
      </div>
    );
  }

  const close = () => {
    setOpen(false);
    setQ('');
  };

  return (
    <div className="ops-branchwrap" ref={root}>
      <button
        type="button"
        className="ops-branch"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <Icon name="warehouse" />
        <span className="ops-branch__text">
          <small>Phạm vi đang xem</small>
          <b>{current}</b>
        </span>
        <Icon name="chevron" size={14} className={open ? 'ops-flip' : undefined} />
      </button>

      {open && (
        <div className="ops-pop" role="dialog" aria-label="Chọn phạm vi chi nhánh">
          <div className="ops-pop__head">
            <strong>Chọn phạm vi chi nhánh</strong>
            <span className="ops-chip">{branches.length} chi nhánh</span>
          </div>
          <div className="ops-search">
            <Icon name="search" size={16} />
            <Input
              autoFocus
              aria-label="Tìm chi nhánh"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="Tìm theo tên hoặc mã chi nhánh..."
            />
          </div>

          {branches.length === 0 && !canPickAll ? (
            <EmptyState message="Chưa có chi nhánh nào" />
          ) : (
            <ul role="listbox" className="ops-list">
              {canPickAll && (
                <li>
                  <button
                    type="button"
                    role="option"
                    aria-selected={scope.kind === 'all'}
                    className={`ops-opt ops-opt--all${scope.kind === 'all' ? ' is-on' : ''}`}
                    onClick={() => {
                      pickAll();
                      close();
                    }}
                  >
                    <Icon name="warehouse" />
                    <span>
                      <b>Tất cả chi nhánh</b>
                      <small>Xem dữ liệu tổng hợp toàn hệ thống</small>
                    </span>
                    {scope.kind === 'all' && <Icon name="check" size={16} />}
                  </button>
                </li>
              )}
              {filtered.map((b) => {
                const on = scope.kind === 'branch' && scope.branch.id === b.id;
                return (
                  <li key={b.id}>
                    <button
                      type="button"
                      role="option"
                      aria-selected={on}
                      className={`ops-opt${on ? ' is-on' : ''}`}
                      onClick={() => {
                        pickBranch(b.id);
                        close();
                      }}
                    >
                      <Icon name="store" />
                      <span>{branchLabel(b)}</span>
                      {on && <Icon name="check" size={16} />}
                    </button>
                  </li>
                );
              })}
              {q && filtered.length === 0 && (
                <li>
                  <EmptyState message="" filtered onClearFilters={() => setQ('')} />
                </li>
              )}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
