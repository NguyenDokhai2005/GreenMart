"""Script trích xuất số liệu đánh giá chi tiết theo phân nhóm (Chi nhánh, Ngành hàng, Pareto)
phục vụ báo cáo đánh giá mô hình cuối cùng AC-05 (Task #120).

Tuân thủ chặt chẽ:
- Giao thức thực nghiệm của evaluator.py (Cutoff: 2026-10-01, Test: 02/10 - 31/10/2026).
- Lọc bỏ ngày hết hàng (stockout = True).
- Nạp trực tiếp mô hình LightGBM artifacts (H7 & H30), áp dụng ngưỡng 0.4 cho slow-moving.
- Tính toán đầy đủ cho cả Mô hình và Baseline tốt nhất (Rolling mean 28 ngày), kèm Weekly Naive & Zero.
- Phân rã theo 3 Chi nhánh và toàn bộ các Ngành hàng thực tế trong dữ liệu (đếm động).
"""

from __future__ import annotations

from datetime import date, timedelta
import json
import math
from pathlib import Path
import sys
from typing import Any, Dict, List, Set

import numpy as np
import pandas as pd

# Thiết lập đường dẫn import
_SCRIPT_DIR = Path(__file__).resolve().parent
_SERVICE_DIR = _SCRIPT_DIR.parent
if str(_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(_SERVICE_DIR))

from app.models.evaluator import (
    aggregate_by_weekly_windows,
    apply_short_history_fallback,
    calculate_mae,
    calculate_wape,
    calculate_zero_ratio,
)
from app.models.features import FeatureMatrixBuilder
from app.models.lightgbm_model import LightGBMDemandModel


def run_segment_evaluation(
    data_path: Path | None = None,
    artifacts_dir: Path | None = None,
    output_json_path: Path | None = None,
) -> Dict[str, Any]:
    if data_path is None:
        data_path = _SERVICE_DIR / "data" / "daily_sales.parquet"
    if artifacts_dir is None:
        artifacts_dir = _SERVICE_DIR / "artifacts"
    if output_json_path is None:
        output_json_path = artifacts_dir / "segment_metrics.json"

    print("=" * 80)
    print("TRÍCH XUẤT SỐ LIỆU ĐÁNH GIÁ MÔ HÌNH THEO PHÂN NHÓM (TASK #120 - AC-05)")
    print(f"- Dữ liệu: {data_path}")
    print(f"- Thư mục artifacts: {artifacts_dir}")
    print("=" * 80)

    if not data_path.exists():
        raise FileNotFoundError(f"Không tìm thấy file dữ liệu tại {data_path}")

    # 1. Nạp dữ liệu
    print("[1/4] Đang nạp daily_sales...")
    df_daily = pd.read_parquet(data_path)
    if pd.api.types.is_datetime64_any_dtype(df_daily["sale_date"]):
        df_daily["sale_date"] = pd.to_datetime(df_daily["sale_date"]).dt.date

    builder = FeatureMatrixBuilder(df_daily)
    unique_dates = builder.unique_dates
    cutoff_date = date(2026, 10, 1)

    print(f"      Tổng số dòng: {len(df_daily):,}, Chuỗi: {builder.n_series:,}, Ngày chốt: {cutoff_date}")

    # 2. Xác định Top 200 SKU trên tập dữ liệu train (<= cutoff_date)
    df_train = builder.df[builder.df["sale_date"] <= cutoff_date]
    top_sku_ids: Set[int] = set(
        df_train.groupby("product_id")["qty_net"].sum().nlargest(200).index
    )
    product_ids_series = builder.product_ids
    branch_ids_series = builder.branch_ids
    is_top_mask = np.isin(product_ids_series, list(top_sku_ids))

    # Lấy thông tin chi nhánh thực tế
    branches_df = df_daily[["branch_id", "branch_code", "branch_name"]].drop_duplicates().sort_values("branch_id")
    branch_info = branches_df.to_dict("records")
    print(f"      Số chi nhánh thực tế: {len(branch_info)}")

    # Lấy thông tin ngành hàng thực tế
    cat_df = df_daily[["category_id", "category_name"]].drop_duplicates().sort_values("category_id")
    cat_info = cat_df.to_dict("records")
    print(f"      Số ngành hàng thực tế: {len(cat_info)}")

    # Ánh xạ product_id -> category_id
    prod_to_cat = df_daily.drop_duplicates("product_id").set_index("product_id")["category_id"].to_dict()
    category_ids_series = np.array([prod_to_cat.get(pid, -1) for pid in product_ids_series])

    train_dates = [d for d in unique_dates if d <= cutoff_date]
    history_qty_matrix = builder.qty_matrix[:, : len(train_dates)]

    # 3. Nạp mô hình LightGBM cho 2 tầm 7 và 30 ngày
    print("[2/4] Nạp mô hình LightGBM từ artifacts...")
    horizons = (7, 30)
    models: Dict[int, LightGBMDemandModel] = {}
    for H in horizons:
        model_file = artifacts_dir / f"lgbm_demand_h{H}.txt"
        if not model_file.exists():
            raise FileNotFoundError(f"Không tìm thấy file mô hình {model_file}!")
        m = LightGBMDemandModel(horizon=H, objective="tweedie")
        m.load_model(model_file)
        models[H] = m
        print(f"      Đã nạp thành công mô hình tầm {H} ngày từ {model_file.name}")

    # 4. Dự báo và đánh giá
    print("[3/4] Dự báo và tính toán MAE/WAPE trên tập kiểm tra (02/10/2026 - 31/10/2026)...")

    results: Dict[str, Any] = {
        "cutoff_date": str(cutoff_date),
        "test_start_date": "2026-10-02",
        "test_end_date": "2026-10-31",
        "slow_moving_threshold": 0.4,
        "branches": branch_info,
        "categories": cat_info,
        "metrics": {},
    }

    for H in horizons:
        test_dates_H = [cutoff_date + timedelta(days=i) for i in range(1, H + 1)]
        test_indices = [builder.date_to_idx[d] for d in test_dates_H]
        actual_matrix_H = builder.qty_matrix[:, test_indices]
        stockout_matrix_H = builder.stockout_matrix[:, test_indices]

        # Sinh dự báo cho các Baseline
        # Baseline 1: Naive theo tuần
        last_7_train = history_qty_matrix[:, -7:]
        reps = int(np.ceil(H / 7))
        pred_naive = np.tile(last_7_train, (1, reps))[:, :H]

        # Baseline 2: TB trượt 28 ngày
        w_size = min(28, history_qty_matrix.shape[1])
        last_28_train = history_qty_matrix[:, -w_size:]
        mean_28 = np.mean(last_28_train, axis=1, keepdims=True)
        pred_ma28 = np.repeat(mean_28, H, axis=1)

        # Baseline tham chiếu: Zero
        pred_zero = np.zeros_like(actual_matrix_H, dtype=float)

        # Mô hình LightGBM Tweedie
        test_feature_dfs = builder.build_test_features_matrix(cutoff_date=cutoff_date, horizon=H)
        step_preds = [models[H].predict(sdf) for sdf in test_feature_dfs]
        pred_lgb_raw = np.column_stack(step_preds)

        # Áp dụng hậu xử lý ngưỡng 0.4 cho slow-moving
        slow_cells = (~is_top_mask)[:, np.newaxis] & (pred_lgb_raw < 0.4)
        pred_lgb_postproc = np.where(slow_cells, 0.0, pred_lgb_raw)

        # Áp dụng fallback chuỗi mới
        apply_short_history_fallback(df_train, builder.series_index, pred_naive, 28)
        apply_short_history_fallback(df_train, builder.series_index, pred_ma28, 28)
        apply_short_history_fallback(df_train, builder.series_index, pred_zero, 28)
        apply_short_history_fallback(df_train, builder.series_index, pred_lgb_postproc, 28)

        # Chuẩn bị ma trận đã lọc stockout
        act_clean = np.where(stockout_matrix_H, 0.0, actual_matrix_H)
        pred_naive_clean = np.where(stockout_matrix_H, 0.0, pred_naive)
        pred_ma28_clean = np.where(stockout_matrix_H, 0.0, pred_ma28)
        pred_zero_clean = np.where(stockout_matrix_H, 0.0, pred_zero)
        pred_lgb_clean = np.where(stockout_matrix_H, 0.0, pred_lgb_postproc)

        # Weekly aggregate
        act_w_clean = aggregate_by_weekly_windows(act_clean, H)
        pred_naive_w_clean = aggregate_by_weekly_windows(pred_naive_clean, H)
        pred_ma28_w_clean = aggregate_by_weekly_windows(pred_ma28_clean, H)
        pred_zero_w_clean = aggregate_by_weekly_windows(pred_zero_clean, H)
        pred_lgb_w_clean = aggregate_by_weekly_windows(pred_lgb_clean, H)

        # Horizon aggregate
        act_agg_clean = np.sum(act_clean, axis=1)
        pred_naive_agg_clean = np.sum(pred_naive_clean, axis=1)
        pred_ma28_agg_clean = np.sum(pred_ma28_clean, axis=1)
        pred_zero_agg_clean = np.sum(pred_zero_clean, axis=1)
        pred_lgb_agg_clean = np.sum(pred_lgb_clean, axis=1)

        # Định nghĩa các phân nhóm cần đánh giá
        segment_masks: Dict[str, np.ndarray] = {
            "overall": np.ones(builder.n_series, dtype=bool),
            "top_200": is_top_mask,
            "slow_moving": ~is_top_mask,
        }

        # Bổ sung các nhóm chi nhánh
        for b in branch_info:
            b_id = b["branch_id"]
            segment_masks[f"branch_{b_id}"] = (branch_ids_series == b_id)

        # Bổ sung các nhóm ngành hàng
        for c in cat_info:
            c_id = c["category_id"]
            segment_masks[f"category_{c_id}"] = (category_ids_series == c_id)

        results["metrics"][str(H)] = {}

        for seg_key, mask in segment_masks.items():
            if not np.any(mask):
                continue

            # 1. Từng ô ngày (Daily cell)
            y_act = actual_matrix_H[mask, :].ravel()
            s_mask = stockout_matrix_H[mask, :].ravel()
            valid = ~s_mask

            y_act_v = y_act[valid]
            tot_act_daily = float(np.sum(y_act_v))
            n_daily = int(len(y_act_v))

            pred_ma28_v = pred_ma28[mask, :].ravel()[valid]
            pred_naive_v = pred_naive[mask, :].ravel()[valid]
            pred_lgb_v = pred_lgb_postproc[mask, :].ravel()[valid]

            mae_ma28_d = calculate_mae(y_act_v, pred_ma28_v)
            wape_ma28_d = calculate_wape(y_act_v, pred_ma28_v, as_percent=False)

            mae_naive_d = calculate_mae(y_act_v, pred_naive_v)
            wape_naive_d = calculate_wape(y_act_v, pred_naive_v, as_percent=False)

            mae_lgb_d = calculate_mae(y_act_v, pred_lgb_v)
            wape_lgb_d = calculate_wape(y_act_v, pred_lgb_v, as_percent=False)
            zero_ratio_d = calculate_zero_ratio(pred_lgb_v)

            # 2. Gộp theo tuần (Weekly aggregate)
            y_w_act = act_w_clean[mask, :].ravel()
            y_w_ma28 = pred_ma28_w_clean[mask, :].ravel()
            y_w_naive = pred_naive_w_clean[mask, :].ravel()
            y_w_lgb = pred_lgb_w_clean[mask, :].ravel()

            mae_ma28_w = calculate_mae(y_w_act, y_w_ma28)
            wape_ma28_w = calculate_wape(y_w_act, y_w_ma28, as_percent=False)

            mae_naive_w = calculate_mae(y_w_act, y_w_naive)
            wape_naive_w = calculate_wape(y_w_act, y_w_naive, as_percent=False)

            mae_lgb_w = calculate_mae(y_w_act, y_w_lgb)
            wape_lgb_w = calculate_wape(y_w_act, y_w_lgb, as_percent=False)
            zero_ratio_w = calculate_zero_ratio(y_w_lgb)

            # 3. Tổng cả tầm (Horizon aggregate)
            y_agg_act = act_agg_clean[mask]
            y_agg_ma28 = pred_ma28_agg_clean[mask]
            y_agg_naive = pred_naive_agg_clean[mask]
            y_agg_lgb = pred_lgb_agg_clean[mask]

            mae_ma28_agg = calculate_mae(y_agg_act, y_agg_ma28)
            wape_ma28_agg = calculate_wape(y_agg_act, y_agg_ma28, as_percent=False)

            mae_naive_agg = calculate_mae(y_agg_act, y_agg_naive)
            wape_naive_agg = calculate_wape(y_agg_act, y_agg_naive, as_percent=False)

            mae_lgb_agg = calculate_mae(y_agg_act, y_agg_lgb)
            wape_lgb_agg = calculate_wape(y_agg_act, y_agg_lgb, as_percent=False)
            zero_ratio_agg = calculate_zero_ratio(y_agg_lgb)

            # Đánh giá thắng/thua so với Baseline tốt nhất (Rolling mean 28)
            win_daily = (wape_lgb_d < wape_ma28_d) if (wape_lgb_d is not None and wape_ma28_d is not None) else False
            win_weekly = (wape_lgb_w < wape_ma28_w) if (wape_lgb_w is not None and wape_ma28_w is not None) else False
            win_horizon = (wape_lgb_agg < wape_ma28_agg) if (wape_lgb_agg is not None and wape_ma28_agg is not None) else False

            results["metrics"][str(H)][seg_key] = {
                "sample_count_series": int(np.sum(mask)),
                "total_actual_clean": tot_act_daily,
                "daily_cell": {
                    "sample_count": n_daily,
                    "model": {"mae": mae_lgb_d, "wape": wape_lgb_d, "zero_pred_ratio": zero_ratio_d},
                    "baseline_rolling_mean_28": {"mae": mae_ma28_d, "wape": wape_ma28_d},
                    "baseline_weekly_naive": {"mae": mae_naive_d, "wape": wape_naive_d},
                    "wape_diff": (wape_lgb_d - wape_ma28_d) if (wape_lgb_d and wape_ma28_d) else None,
                    "win": win_daily,
                },
                "weekly_aggregate": {
                    "sample_count": len(y_w_act),
                    "model": {"mae": mae_lgb_w, "wape": wape_lgb_w, "zero_pred_ratio": zero_ratio_w},
                    "baseline_rolling_mean_28": {"mae": mae_ma28_w, "wape": wape_ma28_w},
                    "baseline_weekly_naive": {"mae": mae_naive_w, "wape": wape_naive_w},
                    "wape_diff": (wape_lgb_w - wape_ma28_w) if (wape_lgb_w and wape_ma28_w) else None,
                    "win": win_weekly,
                },
                "horizon_aggregate": {
                    "sample_count": len(y_agg_act),
                    "model": {"mae": mae_lgb_agg, "wape": wape_lgb_agg, "zero_pred_ratio": zero_ratio_agg},
                    "baseline_rolling_mean_28": {"mae": mae_ma28_agg, "wape": wape_ma28_agg},
                    "baseline_weekly_naive": {"mae": mae_naive_agg, "wape": wape_naive_agg},
                    "wape_diff": (wape_lgb_agg - wape_ma28_agg) if (wape_lgb_agg and wape_ma28_agg) else None,
                    "win": win_horizon,
                },
            }

    # 5. Lưu ra JSON
    print(f"[4/4] Ghi kết quả vào {output_json_path}...")
    output_json_path.parent.mkdir(parents=True, exist_ok=True)
    output_json_path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"      Đã lưu metrics phân nhóm thành công!")

    # In tóm tắt kết quả
    print("\n" + "=" * 80)
    print("BẢNG TỔNG HỢP THEO CHI NHÁNH (CÁCH ĐO GỘP TUẦN - WEEKLY AGGREGATE):")
    print(f"{'Tầm':<8} | {'Chi nhánh':<24} | {'Thực tế':<8} | {'BL2 WAPE':<10} | {'Mô hình WAPE':<12} | {'Chênh lệch':<11} | {'Kết quả':<8}")
    print("-" * 90)
    for H in (7, 30):
        for b in branch_info:
            b_id = b["branch_id"]
            b_name = f"{b['branch_code']} ({b['branch_name']})"
            m = results["metrics"][str(H)][f"branch_{b_id}"]["weekly_aggregate"]
            bl_w = m["baseline_rolling_mean_28"]["wape"] * 100
            md_w = m["model"]["wape"] * 100
            diff = (m["wape_diff"]) * 100
            res = "Thắng" if m["win"] else "Thua"
            tot = int(results["metrics"][str(H)][f"branch_{b_id}"]["total_actual_clean"])
            print(f"{H} ngày   | {b_name:<24} | {tot:<8} | {bl_w:>8.2f}% | {md_w:>10.2f}% | {diff:>+9.2f}% | {res:<8}")

    print("\n" + "=" * 80)
    print("BẢNG TỔNG HỢP THEO NGÀNH HÀNG (CÁCH ĐO GỘP TUẦN - WEEKLY AGGREGATE):")
    print(f"{'Tầm':<8} | {'Ngành hàng':<32} | {'Thực tế':<8} | {'BL2 WAPE':<10} | {'Mô hình WAPE':<12} | {'Chênh lệch':<11} | {'Kết quả':<8}")
    print("-" * 100)
    for H in (7, 30):
        for c in cat_info:
            c_id = c["category_id"]
            c_name = f"[{c_id}] {c['category_name']}"
            m = results["metrics"][str(H)][f"category_{c_id}"]["weekly_aggregate"]
            bl_w = m["baseline_rolling_mean_28"]["wape"] * 100
            md_w = m["model"]["wape"] * 100
            diff = (m["wape_diff"]) * 100
            res = "Thắng" if m["win"] else "Thua"
            tot = int(results["metrics"][str(H)][f"category_{c_id}"]["total_actual_clean"])
            print(f"{H} ngày   | {c_name:<32} | {tot:<8} | {bl_w:>8.2f}% | {md_w:>10.2f}% | {diff:>+9.2f}% | {res:<8}")

    return results


if __name__ == "__main__":
    run_segment_evaluation()
