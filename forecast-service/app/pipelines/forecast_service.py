"""Dịch vụ điều phối thực thi dự báo và quản lý trạng thái run (#114).

Đáp ứng các điều chỉnh từ Prompt C:
1. POST tạo forecast_run (RUNNING) rồi trả runId ngay.
2. Nền dùng lại run_id đó, gọi trực tiếp save_forecast_results -> complete_forecast_run / fail_forecast_run.
   KHÔNG gọi persist_forecast_to_db để tránh tạo run thứ hai.
3. Chặn đồng thời bằng pg_advisory_xact_lock trong cùng transaction.
   Run RUNNING có started_at quá 30 phút sẽ được tự động đánh dấu FAILED.
4. Đọc dữ liệu từ bảng daily_sales trong DB (không đọc file parquet).
5. Nền mở kết nối riêng qua get_db_connection(); rollback và fail_forecast_run khi gặp lỗi.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
import logging
from typing import Any, Dict, Optional, Tuple

import numpy as np
import pandas as pd

from app.db.connection import get_db_connection
from app.models.evaluator import apply_short_history_fallback, evaluate_lightgbm_pipeline
from app.models.features import FeatureMatrixBuilder
from app.pipelines.forecast_runner import (
    assemble_forecast_results_dataframe,
    complete_forecast_run,
    fail_forecast_run,
    save_forecast_results,
)

logger = logging.getLogger(__name__)

# Advisory lock ID cố định cho quá trình kiểm tra và tạo forecast_run
FORECAST_ADVISORY_LOCK_ID = 1142026
STALE_RUN_TIMEOUT_MINUTES = 30
DEFAULT_MODEL_VERSION = "LightGBM-Tweedie-H7-H30"
DEFAULT_SLOW_THRESHOLD = 0.0  # #113 chọn bản thô làm số chính; hậu xử lý chỉ tham khảo, không dùng cho forecast_result



class ForecastServiceError(Exception):
    """Lớp ngoại lệ cơ sở cho forecast service."""


class ActiveRunConflictError(ForecastServiceError):
    """Ném ra khi đã có một lượt chạy đang ở trạng thái RUNNING."""

    def __init__(self, message: str, active_id: int):
        super().__init__(message)
        self.active_id = active_id


class EmptyDailySalesError(ForecastServiceError):
    """Ném ra khi bảng daily_sales trống không có dữ liệu để chạy dự báo."""


def _read_sql_df(sql: str, conn: Any, params: Optional[Any] = None) -> pd.DataFrame:
    """Đọc dữ liệu từ DB thành pandas DataFrame an toàn với cursor."""
    with conn.cursor() as cur:
        cur.execute(sql, params)
        if cur.description is None:
            return pd.DataFrame()
        columns = [col[0] for col in cur.description]
        records = cur.fetchall()
        return pd.DataFrame(records, columns=columns)


def start_forecast_run(
    conn: Any,
    model_version: str = DEFAULT_MODEL_VERSION,
    stale_timeout_minutes: int = STALE_RUN_TIMEOUT_MINUTES,
) -> Tuple[int, datetime]:
    """Kiểm tra điều kiện, xử lý run kẹt và tạo forecast_run mới với trạng thái RUNNING.

    Thực hiện toàn bộ trong cùng một transaction với pg_advisory_xact_lock để chống race condition.
    """
    with conn.cursor() as cur:
        # 1. Chặn đồng thời: Sử dụng pg_advisory_xact_lock nếu là PostgreSQL
        try:
            cur.execute("SELECT pg_advisory_xact_lock(%s);", (FORECAST_ADVISORY_LOCK_ID,))
        except Exception:
            # Cho phép bỏ qua khi test với mock hoặc DB engine khác (SQLite)
            pass

        # 2. Kiểm tra dữ liệu bảng daily_sales
        cur.execute("SELECT EXISTS (SELECT 1 FROM daily_sales LIMIT 1);")
        row = cur.fetchone()
        has_sales_data = bool(row[0]) if row else False
        if not has_sales_data:
            raise EmptyDailySalesError(
                "Bảng daily_sales hiện đang trống. Cần nạp dữ liệu bán hàng trước khi chạy dự báo."
            )

        # 3. Dọn dẹp các run RUNNING đã kẹt quá timeout (mặc định 30 phút)
        # Sử dụng started_at < clock_timestamp() - INTERVAL '...'
        cleanup_sql = f"""
            UPDATE forecast_run
            SET status = 'FAILED',
                finished_at = clock_timestamp()
            WHERE status = 'RUNNING'
              AND started_at < clock_timestamp() - INTERVAL '{stale_timeout_minutes} minutes';
        """
        cur.execute(cleanup_sql)

        # 4. Kiểm tra xem còn run nào đang RUNNING hay không
        cur.execute(
            """
            SELECT id, started_at
            FROM forecast_run
            WHERE status = 'RUNNING'
            ORDER BY id DESC
            LIMIT 1;
            """
        )
        active_row = cur.fetchone()
        if active_row:
            active_id = int(active_row[0])
            raise ActiveRunConflictError(
                f"Đã có một lượt dự báo đang chạy (run_id={active_id}). Vui lòng chờ hoàn tất.",
                active_id=active_id,
            )

        # 5. Tạo lượt chạy mới ở trạng thái RUNNING
        insert_sql = """
            INSERT INTO forecast_run (started_at, status, model_version)
            VALUES (clock_timestamp(), 'RUNNING', %s)
            RETURNING id, started_at;
        """
        cur.execute(insert_sql, (model_version,))
        new_row = cur.fetchone()
        if not new_row:
            raise RuntimeError("Không thể tạo bản ghi forecast_run mới.")

        run_id = int(new_row[0])
        started_at = new_row[1]

    if hasattr(conn, "commit"):
        conn.commit()

    return run_id, started_at


def get_forecast_run_detail(conn: Any, run_id: int) -> Optional[Dict[str, Any]]:
    """Lấy thông tin chi tiết của một forecast_run theo run_id."""
    sql = """
        SELECT id, status, started_at, finished_at, model_version, mae, wape, baseline_mae, baseline_wape
        FROM forecast_run
        WHERE id = %s;
    """
    with conn.cursor() as cur:
        cur.execute(sql, (run_id,))
        row = cur.fetchone()
        if not row:
            return None
        return {
            "run_id": int(row[0]),
            "status": str(row[1]),
            "started_at": row[2],
            "finished_at": row[3],
            "model_version": str(row[4]),
            "mae": float(row[5]) if row[5] is not None else None,
            "wape": float(row[6]) if row[6] is not None else None,
            "baseline_mae": float(row[7]) if row[7] is not None else None,
            "baseline_wape": float(row[8]) if row[8] is not None else None,
        }


def fetch_daily_sales_from_db(conn: Any) -> pd.DataFrame:
    """Đọc toàn bộ dữ liệu từ bảng daily_sales trong DB (không đọc parquet)."""
    sql = """
        SELECT sale_date, branch_id, product_id, qty_sold, qty_returned, qty_net,
               revenue, promo_flag, promo_discount_pct, stockout
        FROM daily_sales
        ORDER BY sale_date, branch_id, product_id;
    """
    df = _read_sql_df(sql, conn)
    if not df.empty and pd.api.types.is_datetime64_any_dtype(df["sale_date"]):
        df["sale_date"] = df["sale_date"].dt.date
    return df


def execute_background_forecast(
    run_id: int,
    optimal_threshold: float = DEFAULT_SLOW_THRESHOLD,
    evaluate_pipeline_fn: Optional[Any] = None,
) -> None:
    """Thực thi toàn bộ luồng dự báo chạy nền cho run_id đã được tạo trước.

    Tuyệt đối không tạo run_id mới; sử dụng đúng run_id được cấp từ POST /forecast/run.
    Mở kết nối DB riêng biệt và xử lý rollback/fail nếu phát sinh lỗi.
    """
    logger.info("Bắt đầu thực thi pipeline dự báo nền cho run_id=%d", run_id)
    eval_fn = evaluate_pipeline_fn if evaluate_pipeline_fn is not None else evaluate_lightgbm_pipeline
    try:
        with get_db_connection() as conn:
            # 1. Đọc dữ liệu từ bảng daily_sales trong PostgreSQL
            df_daily = fetch_daily_sales_from_db(conn)
            if df_daily.empty:
                raise ValueError("Bảng daily_sales rỗng khi thực thi nền.")

            # Đảm bảo sale_date là date
            if pd.api.types.is_datetime64_any_dtype(df_daily["sale_date"]):
                df_daily["sale_date"] = df_daily["sale_date"].dt.date

            # 2. Huấn luyện và đánh giá mô hình LightGBM đối đầu Baseline
            report, best_models = eval_fn(
                df_daily=df_daily,
                horizons=(7, 30),
            )

            # 3. Tạo ma trận kết quả dự báo cho 7 ngày và 30 ngày
            builder = FeatureMatrixBuilder(df_daily)
            top_sku_ids = report.baseline_report.top_sku_ids
            series_product_ids = builder.series_index.get_level_values("product_id").to_numpy()
            is_top_mask = np.isin(series_product_ids, list(top_sku_ids))

            cutoff_d = report.cutoff_date
            sale_dates = pd.to_datetime(df_daily["sale_date"]).dt.date
            df_train_only = df_daily[sale_dates <= cutoff_d]

            predictions_by_h: Dict[int, np.ndarray] = {}
            for H in (7, 30):
                test_dfs = builder.build_test_features_matrix(cutoff_d, H)
                model = best_models[H]
                pred_cols = [model.predict(sdf) for sdf in test_dfs]
                p_mat = np.column_stack(pred_cols)

                # #113 chọn bản thô làm số chính; hậu xử lý chỉ tham khảo, không dùng cho forecast_result
                th_v = optimal_threshold if optimal_threshold is not None else DEFAULT_SLOW_THRESHOLD
                if th_v > 0.0:
                    slow_cells = (~is_top_mask)[:, np.newaxis] & (p_mat < th_v)
                    p_mat = np.where(slow_cells, 0.0, p_mat)

                # Fallback cho chuỗi mới (< 28 ngày dữ liệu)
                apply_short_history_fallback(
                    df_train=df_train_only,
                    series_index=builder.series_index,
                    pred_matrix=p_mat,
                    min_history_days=28,
                )
                predictions_by_h[H] = p_mat

            results_df = assemble_forecast_results_dataframe(
                series_index=builder.series_index,
                predictions_by_horizon=predictions_by_h,
                cutoff_date=cutoff_d,
            )

            # 4. Ghi trực tiếp kết quả vào bảng forecast_result theo từng batch
            total_inserted = save_forecast_results(conn, run_id=run_id, results_df=results_df)

            # 5. Cập nhật chỉ số MAE, WAPE và baseline của tầm 7 ngày vào forecast_run
            m_h7_ov = report.lgb_results_clean[7]["lgb_tweedie"]["overall"]
            best_b_name = report.baseline_report.best_baseline[7]
            b_h7_ov = report.baseline_report.results[7][best_b_name]["overall"]

            complete_forecast_run(
                conn,
                run_id=run_id,
                mae=m_h7_ov.mae,
                wape=m_h7_ov.wape,
                baseline_mae=b_h7_ov.mae,
                baseline_wape=b_h7_ov.wape,
            )

            if hasattr(conn, "commit"):
                conn.commit()

            logger.info(
                "Hoàn tất thành công dự báo nền cho run_id=%d: đã ghi %d dòng, status=DONE",
                run_id,
                total_inserted,
            )

    except Exception as exc:
        logger.exception("Lỗi khi thực thi pipeline dự báo nền cho run_id=%d: %s", run_id, exc)
        try:
            with get_db_connection() as err_conn:
                if hasattr(err_conn, "rollback"):
                    err_conn.rollback()
                fail_forecast_run(err_conn, run_id=run_id)
                logger.info("Đã đánh dấu run_id=%d là FAILED sau lỗi.", run_id)
        except Exception as fail_err:
            logger.error("Không thể cập nhật trạng thái FAILED cho run_id=%d: %s", run_id, fail_err)
