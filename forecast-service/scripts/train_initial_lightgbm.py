"""Script thực thi huấn luyện và đánh giá mô hình LightGBM cho Issue #113.

Cách sử dụng:
    python scripts/train_initial_lightgbm.py [--data-path path/to/parquet] [--save-db]
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import time
import numpy as np
import pandas as pd

_SCRIPT_DIR = Path(__file__).resolve().parent
_SERVICE_DIR = _SCRIPT_DIR.parent
if str(_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(_SERVICE_DIR))

from app.models.evaluator import (
    DEFAULT_SLOW_THRESHOLD,
    _metric_to_dict,
    build_metrics_breakdown_payload,
    build_standardized_evaluation_summary,
    compute_canonical_data_hash,
    evaluate_lightgbm_pipeline,
    format_full_comparison_report,
)
from app.pipelines.forecast_runner import (
    assemble_forecast_results_dataframe,
    complete_forecast_run,
    create_forecast_run,
    fail_forecast_run,
    persist_forecast_to_db,
    save_forecast_results,
)


def run_training_and_evaluation(
    data_path: Path | None = None,
    artifacts_dir: Path | None = None,
    save_db: bool = False,
    slow_threshold: float | None = DEFAULT_SLOW_THRESHOLD,
    tune_slow_threshold: bool = False,
) -> str:
    """Nạp dữ liệu, huấn luyện mô hình LightGBM và xuất báo cáo so sánh Baseline đối đầu."""
    if data_path is None:
        data_path = _SERVICE_DIR / "data" / "daily_sales.parquet"
    if artifacts_dir is None:
        artifacts_dir = _SERVICE_DIR / "artifacts"

    if not data_path.exists():
        raise FileNotFoundError(f"Không tìm thấy tệp dữ liệu tại {data_path}. Hãy chạy export_daily_sales_parquet.py trước.")

    fixed_thresholds: dict[int, float] | None = None
    if not tune_slow_threshold:
        th = DEFAULT_SLOW_THRESHOLD if slow_threshold is None else float(slow_threshold)
        fixed_thresholds = {7: th, 30: th}

    print("=" * 80)
    print("KHỞI CHẠY HUẤN LUYỆN VÀ ĐÁNH GIÁ MÔ HÌNH LIGHTGBM (ISSUE #113 & #423)")
    print(f"- Nguồn dữ liệu: {data_path}")
    print(f"- Thư mục artifacts: {artifacts_dir}")
    print(f"- Chính sách ngưỡng slow-moving: {'tune Validation' if fixed_thresholds is None else f'cố định {fixed_thresholds[7]}'}")
    print(f"- Ghi CSDL (forecast_run / forecast_result): {save_db}")
    print("=" * 80)

    t0 = time.time()
    print("[1/5] Đang nạp dữ liệu daily_sales...")
    parquet_sha256 = hashlib.sha256(data_path.read_bytes()).hexdigest()
    df_daily = pd.read_parquet(data_path)
    if pd.api.types.is_datetime64_any_dtype(df_daily["sale_date"]):
        df_daily["sale_date"] = pd.to_datetime(df_daily["sale_date"]).dt.date
    canonical_hash = compute_canonical_data_hash(df_daily)
    print(f"      Đã nạp {len(df_daily):,} dòng dữ liệu ({time.time() - t0:.2f}s).")
    print(f"      SHA-256 file: {parquet_sha256}")
    print(f"      SHA-256 dữ liệu chuẩn hóa: {canonical_hash}")

    t1 = time.time()
    print("[2/5] Đang huấn luyện và đánh giá đối đầu trên 2 tầm (7 ngày và 30 ngày)...")
    if fixed_thresholds is None:
        print("      Thử nghiệm 2 hàm mục tiêu: regression_l1 và tweedie; tối ưu ngưỡng bán chậm trên Validation.")
    else:
        print(f"      Thử nghiệm 2 hàm mục tiêu: regression_l1 và tweedie; khóa ngưỡng bán chậm v1 = {fixed_thresholds[7]}.")
    report, best_models = evaluate_lightgbm_pipeline(
        df_daily=df_daily,
        horizons=(7, 30),
        objectives=("regression_l1", "tweedie"),
        sample_step_days=7,
        fixed_slow_thresholds=fixed_thresholds,
        data_source=data_path.name,
        data_source_hash=canonical_hash,
    )
    print(f"      Huấn luyện và đánh giá hoàn tất trong {time.time() - t1:.2f}s.")

    t2 = time.time()
    print("[3/5] Lưu trữ model artifacts...")
    artifacts_dir.mkdir(parents=True, exist_ok=True)
    for H, model in best_models.items():
        model_file = artifacts_dir / f"lgbm_demand_h{H}.txt"
        model.save_model(model_file)
        print(f"      Đã lưu mô hình tầm {H} ngày vào: {model_file}")

    print("[4/5] Xuất báo cáo so sánh chi tiết và metrics JSON...")
    md_report = format_full_comparison_report(report)
    report_file = artifacts_dir / "evaluation_report.md"
    report_file.write_text(md_report, encoding="utf-8")
    print(f"      Đã lưu báo cáo tại: {report_file}")

    def _serialize_view_results(results_by_h: dict) -> dict:
        return {
            str(H): {
                m_name: {
                    s_name: _metric_to_dict(m)
                    for s_name, m in s_dict.items()
                }
                for m_name, s_dict in results_by_h[H].items()
            }
            for H in report.horizons
        }

    standardized = build_standardized_evaluation_summary(report)
    metrics_breakdown = build_metrics_breakdown_payload(report)
    # Xuất metrics JSON tổng hợp (giữ đủ các khóa cũ + bổ sung metadata chuẩn hóa #423)
    metrics_summary = {
        "schema_version": standardized["schema_version"],
        "metric_protocol_version": standardized["metric_protocol_version"],
        "cutoff_date": str(report.cutoff_date),
        "test_start_date": str(report.test_start_date),
        "test_end_date": str(report.test_end_date),
        "model_version": standardized["model_version"],
        "postprocess_version": standardized["postprocess_version"],
        "data_source": data_path.name,
        "data_source_hash": canonical_hash,
        "parquet_sha256": parquet_sha256,
        "optimal_slow_thresholds": standardized["optimal_slow_thresholds"],
        "short_history_series_count": report.short_history_series_count,
        "best_baseline_by_view": standardized["best_baseline_by_view"],
        "standardized_summary": standardized["horizons"],
        "metrics_breakdown": metrics_breakdown,
        "benchmark_limitations": standardized["benchmark_limitations"],
        "results_clean_daily": _serialize_view_results(report.lgb_results_clean),
        "results_raw_daily": _serialize_view_results(report.lgb_results_raw),
        "results_clean_weekly": _serialize_view_results(report.weekly_results_clean),
        "results_clean_aggregate": _serialize_view_results(report.aggregated_results_clean),
    }
    json_file = artifacts_dir / "evaluation_metrics.json"
    json_file.write_text(json.dumps(metrics_summary, indent=2, ensure_ascii=False, allow_nan=False), encoding="utf-8")
    print(f"      Đã lưu metrics JSON tại: {json_file}")

    if save_db:
        print("[5/5] Đang retrain tới ngày D và ghi dự báo phục vụ vào PostgreSQL (forecast_run và forecast_result)...")
        from app.db.connection import get_db_connection

        with get_db_connection() as conn:
            persist_forecast_to_db(
                conn=conn,
                report=report,
                best_models=best_models,
                df_daily=df_daily,
                model_version="LightGBM-Tweedie-H7-H30",
                serving_mode=True,
            )

    print("=" * 80)
    print(md_report)
    print("=" * 80)
    print(f"Tổng thời gian hoàn tất: {time.time() - t0:.2f}s")
    return md_report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Huấn luyện mô hình LightGBM cho forecast-service.")
    parser.add_argument("--data-path", type=str, default=None, help="Đường dẫn file parquet daily_sales")
    parser.add_argument("--save-db", action="store_true", help="Ghi kết quả phục vụ vào PostgreSQL")
    parser.add_argument(
        "--slow-threshold",
        type=float,
        default=DEFAULT_SLOW_THRESHOLD,
        help="Ngưỡng slow-moving cố định (mặc định 0.4 theo policy v1 #423)",
    )
    parser.add_argument(
        "--tune-slow-threshold",
        action="store_true",
        help="Chế độ nghiên cứu: tối ưu ngưỡng trên Validation thay vì khóa ngưỡng cố định",
    )
    args = parser.parse_args()

    p = Path(args.data_path) if args.data_path else None
    run_training_and_evaluation(
        data_path=p,
        save_db=args.save_db,
        slow_threshold=args.slow_threshold,
        tune_slow_threshold=args.tune_slow_threshold,
    )
