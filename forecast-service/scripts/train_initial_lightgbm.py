"""Script thực thi huấn luyện và đánh giá mô hình LightGBM bản đầu tiên (Task #112).

Cách sử dụng:
    python scripts/train_initial_lightgbm.py [--data-path path/to/parquet]
"""

from __future__ import annotations

import argparse
from pathlib import Path
import sys
import time
import pandas as pd

_SCRIPT_DIR = Path(__file__).resolve().parent
_SERVICE_DIR = _SCRIPT_DIR.parent
if str(_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(_SERVICE_DIR))

from app.models.evaluator import evaluate_lightgbm_pipeline, format_full_comparison_report


def run_training_and_evaluation(
    data_path: Path | None = None,
    artifacts_dir: Path | None = None,
) -> str:
    """Nạp dữ liệu, huấn luyện mô hình LightGBM và xuất báo cáo so sánh Baseline."""
    if data_path is None:
        data_path = _SERVICE_DIR / "data" / "daily_sales.parquet"
    if artifacts_dir is None:
        artifacts_dir = _SERVICE_DIR / "artifacts"

    if not data_path.exists():
        raise FileNotFoundError(f"Không tìm thấy tệp dữ liệu tại {data_path}. Hãy chạy export_daily_sales_parquet.py trước.")

    print("=" * 80)
    print("KHỞI CHẠY HUẤN LUYỆN VÀ ĐÁNH GIÁ MÔ HÌNH LIGHTGBM BẢN ĐẦU (TASK #112)")
    print(f"- Nguồn dữ liệu: {data_path}")
    print(f"- Thư mục artifacts: {artifacts_dir}")
    print("=" * 80)

    t0 = time.time()
    print("[1/4] Đang nạp dữ liệu daily_sales...")
    df_daily = pd.read_parquet(data_path)
    print(f"      Đã nạp {len(df_daily):,} dòng dữ liệu ({time.time() - t0:.2f}s).")

    t1 = time.time()
    print("[2/4] Đang huấn luyện và đánh giá mô hình trên 2 tầm (7 ngày và 30 ngày)...")
    print("      Thử nghiệm 2 hàm mục tiêu: regression_l1 và tweedie.")
    report, best_models = evaluate_lightgbm_pipeline(
        df_daily=df_daily,
        horizons=(7, 30),
        objectives=("regression_l1", "tweedie"),
        sample_step_days=7,
    )
    print(f"      Huấn luyện và đánh giá hoàn tất trong {time.time() - t1:.2f}s.")

    t2 = time.time()
    print("[3/4] Lưu trữ model artifacts...")
    artifacts_dir.mkdir(parents=True, exist_ok=True)
    for H, model in best_models.items():
        model_file = artifacts_dir / f"lgbm_demand_h{H}.txt"
        model.save_model(model_file)
        print(f"      Đã lưu mô hình tầm {H} ngày vào: {model_file}")

    print("[4/4] Xuất báo cáo so sánh chi tiết...")
    md_report = format_full_comparison_report(report)
    report_file = artifacts_dir / "evaluation_report.md"
    report_file.write_text(md_report, encoding="utf-8")
    print(f"      Đã lưu báo cáo tại: {report_file}")
    print("=" * 80)
    print(md_report)
    print("=" * 80)
    print(f"Tổng thời gian hoàn tất: {time.time() - t0:.2f}s")
    return md_report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Huấn luyện mô hình LightGBM cho forecast-service.")
    parser.add_argument("--data-path", type=str, default=None, help="Đường dẫn file parquet daily_sales")
    args = parser.parse_args()

    p = Path(args.data_path) if args.data_path else None
    run_training_and_evaluation(data_path=p)
