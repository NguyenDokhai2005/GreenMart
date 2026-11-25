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
import json
import math
from typing import Any, Dict, List, Optional, Sequence, Tuple, Union
import numpy as np
import pandas as pd

from app.db.connection import get_db_connection
from app.models.evaluator import (
    DEFAULT_METRIC_PROTOCOL_VERSION,
    DEFAULT_MIN_HISTORY_DAYS,
    DEFAULT_MODEL_VERSION,
    _resolve_best_baseline_name,
    build_metrics_breakdown_payload,
    build_postprocess_version,
    compute_canonical_data_hash,
)


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
    if hasattr(conn, "commit"):
        conn.commit()
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


def _clean_metric_float(value: Optional[float], digits: int = 4) -> Optional[float]:
    if value is None:
        return None
    f = float(value)
    if not math.isfinite(f):
        return None
    return round(f, digits)


def extract_run_metric_summary(
    report: Any,
    serving_cutoff_date: Optional[date] = None,
    data_source: Optional[str] = None,
    data_source_hash: Optional[str] = None,
) -> Tuple[Optional[float], Optional[float], Optional[float], Optional[float], Optional[Dict[str, Any]]]:
    """Trích xuất chỉ số đại diện H7 postprocessed aggregate + metadata/breakdown từ báo cáo đánh giá.

    - Với `FullEvaluationReport` chuẩn #423 (có `aggregated_results_clean[7]`):
      Lấy tầm 7 ngày, nhóm overall, phép đo `HORIZON_AGGREGATE_POSTPROC`, `EXCLUDED_CLEAN`,
      và baseline tốt nhất trên đúng cùng góc đo/mask/dữ liệu/origin/fallback.
    - Với mock report legacy chỉ cung cấp `lgb_results_clean`: giữ tương thích ngược (`metric_metadata=None`).
    """
    agg_dict = getattr(report, "aggregated_results_clean", None)
    if isinstance(agg_dict, dict) and 7 in agg_dict:
        if serving_cutoff_date is not None:
            report.serving_cutoff_date = serving_cutoff_date
        if data_source is not None:
            report.data_source = data_source
        if data_source_hash is not None:
            report.data_source_hash = data_source_hash

        best_obj_map = getattr(report, "best_objective", None)
        obj_name = best_obj_map.get(7, "tweedie") if isinstance(best_obj_map, dict) else "tweedie"
        post_key = f"lgb_{obj_name}_postproc"
        agg_h7 = agg_dict[7]
        model_key = post_key if post_key in agg_h7 else f"lgb_{obj_name}"

        m_h7_ov = agg_h7[model_key]["overall"]
        best_b_name = _resolve_best_baseline_name(report, 7, "horizon_aggregate_clean", agg_h7)
        b_h7_ov = agg_h7[best_b_name]["overall"]

        m_wape = None if m_h7_ov.total_actual <= 0.0 else m_h7_ov.wape
        b_wape = None if b_h7_ov.total_actual <= 0.0 else b_h7_ov.wape

        breakdown = build_metrics_breakdown_payload(report)
        postproc_ver = (
            getattr(report, "postprocess_version", "")
            or build_postprocess_version(
                getattr(report, "optimal_slow_thresholds", {}),
                min_history_days=DEFAULT_MIN_HISTORY_DAYS,
            )
        )
        protocol_ver = getattr(report, "metric_protocol_version", "") or DEFAULT_METRIC_PROTOCOL_VERSION
        s_cutoff = getattr(report, "serving_cutoff_date", None) or serving_cutoff_date or getattr(report, "test_end_date", None)

        metadata = {
            "metric_horizon": 7,
            "aggregation": "HORIZON_AGGREGATE_POSTPROC",
            "postprocess_version": postproc_ver,
            "metric_protocol_version": protocol_ver,
            "cutoff_date": s_cutoff,
            "evaluation_cutoff_date": getattr(report, "cutoff_date", None),
            "test_start_date": getattr(report, "test_start_date", None),
            "test_end_date": getattr(report, "test_end_date", None),
            "stockout_mode": "EXCLUDED_CLEAN",
            "baseline_name": best_b_name,
            "data_source": getattr(report, "data_source", None) or data_source or "daily_sales",
            "data_source_hash": getattr(report, "data_source_hash", None) or data_source_hash or "",
            "metrics_breakdown": breakdown,
        }
        return (
            _clean_metric_float(m_h7_ov.mae, 4),
            _clean_metric_float(m_wape, 4),
            _clean_metric_float(b_h7_ov.mae, 4),
            _clean_metric_float(b_wape, 4),
            metadata,
        )

    # Fallback tương thích ngược cho mock report cũ trong unit test
    m_h7_ov = report.lgb_results_clean[7]["lgb_tweedie"]["overall"]
    baseline_report = getattr(report, "baseline_report", None)
    if baseline_report is not None and isinstance(getattr(baseline_report, "best_baseline", None), dict) and 7 in baseline_report.best_baseline:
        best_b_name = baseline_report.best_baseline[7]
        b_h7_ov = baseline_report.results[7][best_b_name]["overall"]
    else:
        b_h7_ov = report.lgb_results_clean[7]["rolling_mean_28"]["overall"]
    return (m_h7_ov.mae, m_h7_ov.wape, b_h7_ov.mae, b_h7_ov.wape, None)


def complete_forecast_run(
    conn: Any,
    run_id: int,
    mae: Optional[float] = None,
    wape: Optional[float] = None,
    baseline_mae: Optional[float] = None,
    baseline_wape: Optional[float] = None,
    metric_metadata: Optional[Dict[str, Any]] = None,
) -> None:
    """Cập nhật trạng thái forecast_run thành DONE kèm các chỉ số đánh giá và metadata #423."""
    if metric_metadata is None:
        sql = """
            UPDATE forecast_run
            SET finished_at = clock_timestamp(),
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
                    _clean_metric_float(mae, 4),
                    _clean_metric_float(wape, 4),
                    _clean_metric_float(baseline_mae, 4),
                    _clean_metric_float(baseline_wape, 4),
                    run_id,
                ),
            )
        return

    sql = """
        UPDATE forecast_run
        SET finished_at = clock_timestamp(),
            status = 'DONE',
            mae = %s,
            wape = %s,
            baseline_mae = %s,
            baseline_wape = %s,
            metric_horizon = %s,
            aggregation = %s,
            postprocess_version = %s,
            metric_protocol_version = %s,
            cutoff_date = %s,
            evaluation_cutoff_date = %s,
            test_start_date = %s,
            test_end_date = %s,
            stockout_mode = %s,
            baseline_name = %s,
            data_source = %s,
            data_source_hash = %s,
            metrics_breakdown = %s::jsonb
        WHERE id = %s;
    """
    breakdown = metric_metadata.get("metrics_breakdown")
    breakdown_json = json.dumps(breakdown, ensure_ascii=False, allow_nan=False) if breakdown is not None else None
    with conn.cursor() as cur:
        cur.execute(
            sql,
            (
                _clean_metric_float(mae, 4),
                _clean_metric_float(wape, 4),
                _clean_metric_float(baseline_mae, 4),
                _clean_metric_float(baseline_wape, 4),
                metric_metadata.get("metric_horizon"),
                metric_metadata.get("aggregation"),
                metric_metadata.get("postprocess_version"),
                metric_metadata.get("metric_protocol_version"),
                metric_metadata.get("cutoff_date"),
                metric_metadata.get("evaluation_cutoff_date"),
                metric_metadata.get("test_start_date"),
                metric_metadata.get("test_end_date"),
                metric_metadata.get("stockout_mode"),
                metric_metadata.get("baseline_name"),
                metric_metadata.get("data_source"),
                metric_metadata.get("data_source_hash"),
                breakdown_json,
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
        SET finished_at = clock_timestamp(),
            status = 'FAILED'
        WHERE id = %s;
    """
    with conn.cursor() as cur:
        cur.execute(sql, (run_id,))
    if hasattr(conn, "commit"):
        conn.commit()


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


def persist_forecast_to_db(
    conn: Any,
    report: Any,
    best_models: Optional[Dict[int, Any]] = None,
    df_daily: Optional[pd.DataFrame] = None,
    results_df: Optional[pd.DataFrame] = None,
    model_version: str = "LightGBM-Tweedie-H7-H30",
) -> Tuple[int, int]:
    """Thực thi toàn bộ luồng tạo kết quả dự báo và ghi vào CSDL theo cờ --save-db.

    Quy trình:
    1. Lấy danh sách Top 200 SKU từ baseline_report (tái dùng mask từ baselines.py).
    2. Vector hóa: Áp ngưỡng hậu xử lý tối ưu cho nhóm bán chậm (không dùng vòng lặp for).
    3. Áp dụng fallback chuỗi mới (< 28 ngày) bằng trung bình danh mục.
    4. Ghép nối thành results_df (37 ngày x N chuỗi).
    5. Quản lý transaction:
       - create_forecast_run: Commit ngay (status = 'RUNNING').
       - try: save_forecast_results + complete_forecast_run -> conn.commit().
       - except: conn.rollback() -> fail_forecast_run (commit) -> raise err.
       - Chỉ in thông báo "Đã nạp ... dòng" và "DONE" SAU KHI commit thành công.

    Trả về:
        (run_id, total_inserted)
    """
    if results_df is None:
        if df_daily is None or best_models is None:
            raise ValueError("Cần cung cấp df_daily và best_models nếu results_df chưa được tính sẵn.")

        # Chuẩn hóa cột sale_date về datetime.date để đồng nhất mọi phép so sánh với cutoff_date
        # (ngăn chặn triệt để TypeError: Invalid comparison between dtype=datetime64 and date khi đọc từ parquet)
        df_daily = df_daily.copy()
        sale_dates = pd.to_datetime(df_daily["sale_date"]).dt.date
        df_daily["sale_date"] = sale_dates

        from app.models.features import FeatureMatrixBuilder
        from app.models.evaluator import apply_short_history_fallback

        builder = FeatureMatrixBuilder(df_daily)
        # Tái dùng đúng mask/danh sách Top 200 SKU từ baseline_report
        top_sku_ids = report.baseline_report.top_sku_ids
        series_product_ids = builder.series_index.get_level_values("product_id").to_numpy()
        is_top_mask = np.isin(series_product_ids, list(top_sku_ids))

        predictions_by_h: Dict[int, np.ndarray] = {}
        df_train_only = df_daily[sale_dates <= report.cutoff_date]

        for H in (7, 30):
            test_dfs = builder.build_test_features_matrix(report.cutoff_date, H)
            model = best_models[H]
            pred_cols = [model.predict(sdf) for sdf in test_dfs]
            p_mat = np.column_stack(pred_cols)

            th_v = report.optimal_slow_thresholds.get(H, 0.0)
            if th_v > 0.0:
                # Vector hóa: Áp ngưỡng hậu xử lý chỉ cho chuỗi bán chậm (không thuộc top 200)
                slow_cells = (~is_top_mask)[:, np.newaxis] & (p_mat < th_v)
                p_mat = np.where(slow_cells, 0.0, p_mat)

            # Áp dụng quy tắc fallback chuỗi mới (< 28 ngày)
            apply_short_history_fallback(
                df_train=df_train_only,
                series_index=builder.series_index,
                pred_matrix=p_mat,
                min_history_days=28,
            )
            predictions_by_h[H] = np.clip(p_mat, 0.0, None)

        results_df = assemble_forecast_results_dataframe(
            series_index=builder.series_index,
            predictions_by_horizon=predictions_by_h,
            cutoff_date=report.cutoff_date,
        )

    # Bắt đầu transaction quản lý CSDL
    run_id = create_forecast_run(conn, model_version=model_version)
    print(f"      Đã tạo forecast_run với ID: {run_id}")

    try:
        cnt = save_forecast_results(conn, run_id=run_id, results_df=results_df)

        serving_cutoff = getattr(report, "serving_cutoff_date", None) or getattr(report, "cutoff_date", None)
        data_hash = getattr(report, "data_source_hash", None) or (
            compute_canonical_data_hash(df_daily) if df_daily is not None else None
        )
        mae_val, wape_val, b_mae_val, b_wape_val, metric_metadata = extract_run_metric_summary(
            report,
            serving_cutoff_date=serving_cutoff,
            data_source=getattr(report, "data_source", None) or "daily_sales",
            data_source_hash=data_hash,
        )
        if metric_metadata is not None:
            complete_forecast_run(
                conn,
                run_id=run_id,
                mae=mae_val,
                wape=wape_val,
                baseline_mae=b_mae_val,
                baseline_wape=b_wape_val,
                metric_metadata=metric_metadata,
            )
        else:
            complete_forecast_run(
                conn,
                run_id=run_id,
                mae=mae_val,
                wape=wape_val,
                baseline_mae=b_mae_val,
                baseline_wape=b_wape_val,
            )
        if hasattr(conn, "commit"):
            conn.commit()

        # CHỈ IN SAU KHI ĐÃ COMMIT THÀNH CÔNG:
        print(f"      Đã nạp {cnt:,} dòng vào forecast_result.")
        print(
            "      [Chuẩn #423] Chỉ số top-level forecast_run: H7 overall HORIZON_AGGREGATE_POSTPROC "
            "(chi tiết H7/H30 lưu tại metrics_breakdown)."
        )
        print(f"      Đã cập nhật forecast_run ID {run_id} sang trạng thái DONE.")
        return run_id, cnt
    except Exception as err:
        if hasattr(conn, "rollback"):
            conn.rollback()
        try:
            fail_forecast_run(conn, run_id=run_id)
        except Exception as fail_err:
            print(f"      [CẢNH BÁO] Không thể cập nhật trạng thái FAILED: {fail_err}")
        raise err

