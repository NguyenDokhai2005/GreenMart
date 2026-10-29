import type { ReactNode } from "react";
import "../css/Table.css";

export interface TableColumn<T> {
  key: keyof T | string;
  title: string;
  render?: (row: T, index: number) => ReactNode;
  width?: string;
  align?: "left" | "center" | "right";
}

export interface TableProps<T extends Record<string, unknown>> {
  columns: TableColumn<T>[];
  data: T[];
  rowKey: keyof T | ((row: T, index: number) => string | number);
  caption?: string;
  emptyText?: string;
  className?: string;
}

export default function Table<T extends Record<string, unknown>>({
  columns,
  data,
  rowKey,
  caption,
  emptyText = "Không có dữ liệu.",
  className = "",
}: TableProps<T>) {
  const getRowKey = (row: T, index: number) =>
    typeof rowKey === "function"
      ? rowKey(row, index)
      : String(row[rowKey] ?? index);

  return (
    <div className={`gm-table-wrap ${className}`.trim()}>
      <table className="gm-table">
        {caption && (
          <caption className="gm-table__caption">
            {caption}
          </caption>
        )}

        <thead>
          <tr>
            {columns.map((column) => (
              <th
                key={String(column.key)}
                scope="col"
                style={{
                  width: column.width,
                  textAlign: column.align ?? "left",
                }}
              >
                {column.title}
              </th>
            ))}
          </tr>
        </thead>

        <tbody>
          {data.length === 0 ? (
            <tr>
              <td
                className="gm-table__empty"
                colSpan={columns.length || 1}
              >
                {emptyText}
              </td>
            </tr>
          ) : (
            data.map((row, index) => (
              <tr key={getRowKey(row, index)}>
                {columns.map((column) => {
                  const value = row[String(column.key)];

                  return (
                    <td
                      key={String(column.key)}
                      style={{
                        textAlign: column.align ?? "left",
                      }}
                    >
                      {column.render
                        ? column.render(row, index)
                        : String(value ?? "—")}
                    </td>
                  );
                })}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}