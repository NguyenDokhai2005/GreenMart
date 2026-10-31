import type { ReactNode } from 'react';
import Button from './Button';
import '../css/Table.css';

export interface TableColumn<T> {
  key: keyof T | string;
  title: string;
  render?: (row: T, index: number) => ReactNode;
  width?: string;
  align?: 'left' | 'center' | 'right';
}

export interface TableProps<T extends object> {
  columns: TableColumn<T>[];
  data: T[];
  rowKey: keyof T | ((row: T, index: number) => string | number);
  caption?: string;
  emptyText?: string;
  loading?: boolean;
  error?: string | null;
  onRetry?: () => void;
  className?: string;
}

export default function Table<T extends object>({
  columns,
  data,
  rowKey,
  caption,
  emptyText = 'Không có dữ liệu.',
  loading = false,
  error = null,
  onRetry,
  className = '',
}: TableProps<T>) {
  const getRowKey = (row: T, index: number) =>
    typeof rowKey === 'function' ? rowKey(row, index) : String(row[rowKey] ?? index);

  const renderState = () => {
    if (loading) {
      return (
        <tr>
          <td className="gm-table__state" colSpan={columns.length || 1}>
            <span role="status" aria-live="polite">
              Đang tải dữ liệu...
            </span>
          </td>
        </tr>
      );
    }

    if (error) {
      return (
        <tr>
          <td className="gm-table__state gm-table__state--error" colSpan={columns.length || 1}>
            <div role="alert">
              <p className="gm-table__error-message">{error}</p>

              {onRetry && (
                <Button variant="secondary" onClick={onRetry}>
                  Thử lại
                </Button>
              )}
            </div>
          </td>
        </tr>
      );
    }

    if (data.length === 0) {
      return (
        <tr>
          <td className="gm-table__empty" colSpan={columns.length || 1}>
            {emptyText}
          </td>
        </tr>
      );
    }

    return data.map((row, index) => (
      <tr key={getRowKey(row, index)}>
        {columns.map((column) => {
          const value = (row as Record<string, unknown>)[String(column.key)];

          return (
            <td
              key={String(column.key)}
              style={{
                textAlign: column.align ?? 'left',
              }}
            >
              {column.render ? column.render(row, index) : String(value ?? '—')}
            </td>
          );
        })}
      </tr>
    ));
  };

  return (
    <div className={`gm-table-wrap ${className}`.trim()}>
      <table className="gm-table">
        {caption && <caption className="gm-table__caption">{caption}</caption>}

        <thead>
          <tr>
            {columns.map((column) => (
              <th
                key={String(column.key)}
                scope="col"
                style={{
                  width: column.width,
                  textAlign: column.align ?? 'left',
                }}
              >
                {column.title}
              </th>
            ))}
          </tr>
        </thead>

        <tbody>{renderState()}</tbody>
      </table>
    </div>
  );
}
