"""Module điều phối thực thi dự báo và lưu trữ kết quả vào PostgreSQL (Issue #113).

Tuân thủ:
1. Tài khoản phân quyền: Sử dụng tài khoản forecast_ro (SELECT bảng nghiệp vụ,
   INSERT/UPDATE/DELETE 3 bảng daily_sales, forecast_run, forecast_result).
2. Ràng buộc CSDL (V1__init_schema.sql):
   - forecast_run: status IN ('RUNNING', 'DONE', 'FAILED').
   - forecast_result: horizon IN (7, 30), UNIQUE (run_id, branch_id, product_id, horizon, target_date).
3. Độc lập và có thể kiểm thử (Mock-friendly):
   - Nhận connection từ ngoài vào để hỗ trợ Unit Test với SQLite/Mock Connection.
   - Hỗ trợ lưu trữ theo lô (batch insert/copy) để nạp 111.000 bản ghi siêu tốc.
"""

from __future__ import annotations

from datetime import date, datetime
from typing import Any, Dict, List, Optional, Sequence, Tuple, Union
import numpy as np
import pandas as pd

from app.db.connection import get_db_connection


def create_forecast_run(
    conn: Any,
    model_version: str = "LightGBM-Tweedie-v1",
) -> int:
    """Tạo một bản ghi forecast_run mới với trạng thái RUNNING.

    Trả về:
        run_id (int) vừa được tạo.
    """
    sql = """
        INSERT INTO forecast_run (started_at, status, model_version)
        VALUES (NOW(), 'RUNNING', %s)
        RETURNING id;
    """
    with conn.cursor() as cur:
        cur.execute(sql, (model_version,))
        row = cur.fetchone()
        if row is None:
            raise RuntimeError("Không thể tạo bản ghi forecast_run.")
        run_id = int(row[0])
    return run_id


def save_forecast_results(
    conn: Any,
    run_id: int,
    results_df: pd.DataFrame,
    batch_size: int = 10_000,
) -> int:
    """Lưu danh sách kết quả dự báo vào bảng forecast_result theo từng khối (batch).

    Tham số:
        conn: Kết nối CSDL (psycopg hoặc mock/sqlite adapter).
        run_id: ID của lượt chạy dự báo.
        results_df: DataFrame chứa các cột:
            ['branch_id', 'product_id', 'horizon', 'target_date', 'predicted_qty']
        batch_size: Kích thước mỗi khối INSERT để tối ưu bộ nhớ và tốc độ.

    Trả về:
        Tổng số dòng đã ghi thành công.
    """
    required_cols = {"branch_id", "product_id", "horizon", "target_date", "predicted_qty"}
    if not required_cols.issubset(results_df.columns):
        missing = required_cols - set(results_df.columns)
        raise ValueError(f"results_df thiếu các cột bắt buộc: {missing}")

    # Kiểm tra ràng buộc horizon chỉ nhận 7 hoặc 30
    invalid_horizons = set(results_df["horizon"].unique()) - {7, 30}
    if invalid_horizons:
        raise ValueError(f"Giá trị horizon không hợp lệ (chỉ chấp nhận 7 hoặc 30): {invalid_horizons}")

    # Kiểm tra tính duy nhất của bộ khóa
    key_cols = ["branch_id", "product_id", "horizon", "target_date"]
    dup_mask = results_df.duplicated(subset=key_cols)
    if dup_mask.any():
        raise ValueError(f"Phát hiện {dup_mask.sum()} dòng trùng lặp bộ khóa (branch, product, horizon, target_date).")

    # Chuẩn bị dữ liệu ghi
    df = results_df.copy()
    df["run_id"] = run_id
    # Đảm bảo predicted_qty không âm và làm tròn 4 chữ số thập phân
    df["predicted_qty"] = np.clip(df["predicted_qty"].astype(float), 0.0, None).round(4)

    # Chuẩn hóa target_date về string YYYY-MM-DD hoặc date
    if pd.api.types.is_datetime64_any_dtype(df["target_date"]):
        df["target_date"] = df["target_date"].dt.date

    records = df[["run_id", "branch_id", "product_id", "horizon", "target_date", "predicted_qty"]].values.tolist()

    insert_sql = """
        INSERT INTO forecast_result (run_id, branch_id, product_id, horizon, target_date, predicted_qty)
        VALUES (%s, %s, %s, %s, %s, %s);
    """

    total_inserted = 0
    with conn.cursor() as cur:
        for i in range(0, len(records), batch_size):
            chunk = records[i : i + batch_size]
            cur.executemany(insert_sql, chunk)
            total_inserted += len(chunk)

    return total_inserted


def complete_forecast_run(
    conn: Any,
    run_id: int,
    mae: Optional[float] = None,
    wape: Optional[float] = None,
    baseline_mae: Optional[float] = None,
    baseline_wape: Optional[float] = None,
) -> None:
    """Cập nhật trạng thái forecast_run thành DONE kèm các chỉ số đánh giá."""
    sql = """
        UPDATE forecast_run
        SET finished_at = NOW(),
            status = 'DONE',
            mae = %s,
            wape = %s,
            baseline_mae = %s,
            baseline_wape = %s
        WHERE id = %s;
    """
    with conn.cursor() as cur:
        cur.execute(
            sql,
            (
                round(mae, 4) if mae is not None else None,
                round(wape, 4) if wape is not None else None,
                round(baseline_mae, 4) if baseline_mae is not None else None,
                round(baseline_wape, 4) if baseline_wape is not None else None,
                run_id,
            ),
        )


def fail_forecast_run(
    conn: Any,
    run_id: int,
) -> None:
    """Đánh dấu lượt chạy forecast_run thất bại (FAILED)."""
    sql = """
        UPDATE forecast_run
        SET finished_at = NOW(),
            status = 'FAILED'
        WHERE id = %s;
    """
    with conn.cursor() as cur:
        cur.execute(sql, (run_id,))


def assemble_forecast_results_dataframe(
    series_index: pd.MultiIndex,
    predictions_by_horizon: Dict[int, np.ndarray],
    cutoff_date: date,
) -> pd.DataFrame:
    """Tạo DataFrame kết quả dự báo chuẩn hóa để nạp vào forecast_result.

    Tham số:
        series_index: MultiIndex gồm (branch_id, product_id).
        predictions_by_horizon: Dict[horizon, matrix (N_series, horizon)].
        cutoff_date: Ngày gốc của đợt dự báo.

    Trả về:
        DataFrame có đúng 5 cột yêu cầu.
    """
    dfs: List[pd.DataFrame] = []
    from datetime import timedelta

    branch_ids = series_index.get_level_values("branch_id").to_numpy()
    product_ids = series_index.get_level_values("product_id").to_numpy()
    n_series = len(series_index)

    for H, pred_mat in predictions_by_horizon.items():
        if H not in (7, 30):
            continue
        for step in range(1, H + 1):
            t_date = cutoff_date + timedelta(days=step)
            step_preds = pred_mat[:, step - 1]
            chunk_df = pd.DataFrame({
                "branch_id": branch_ids,
                "product_id": product_ids,
                "horizon": np.full(n_series, H, dtype=int),
                "target_date": t_date,
                "predicted_qty": np.clip(step_preds, 0.0, None).round(4),
            })
            dfs.append(chunk_df)

    if not dfs:
        return pd.DataFrame(columns=["branch_id", "product_id", "horizon", "target_date", "predicted_qty"])

    return pd.concat(dfs, ignore_index=True)
