"""Unit tests chuẩn hóa báo cáo metric theo horizon, aggregation và postprocess (Issue #423 - Giai đoạn 1)."""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
import json
import math
from pathlib import Path
from unittest.mock import MagicMock

import numpy as np
import pandas as pd
import pytest

from app.api.schemas import ForecastMetricsBreakdown, ForecastRunDetailResponse
from app.models.evaluator import (
    DEFAULT_METRIC_PROTOCOL_VERSION,
    DEFAULT_MODEL_VERSION,
    ModelComparisonMetrics,
    _apply_serving_postprocess_pipeline,
    build_metrics_breakdown_payload,
    build_postprocess_version,
    build_standardized_evaluation_summary,
    compute_canonical_data_hash,
    evaluate_lightgbm_pipeline,
    format_full_comparison_report,
    select_best_baseline_for_metrics,
)
from app.pipelines.forecast_runner import complete_forecast_run, extract_run_metric_summary
from app.pipelines.forecast_service import get_forecast_run_detail
from scripts.evaluate_segments import _compute_view_comparison, run_segment_evaluation
from scripts.train_initial_lightgbm import run_training_and_evaluation


def _make_sparse_sales_with_zero_demand(n_days: int = 65) -> pd.DataFrame:
    """Tạo dữ liệu tổng hợp có cả SKU bán đều và SKU zero-demand trên cửa sổ test."""
    start = date(2026, 8, 1)
    records = []
    for offset in range(n_days):
        d = start + timedelta(days=offset)
        for branch_id in (1, 2):
            for product_id in (101, 102, 103, 104):
                if product_id == 101:
                    qty = float(5 + (offset % 4))
                elif product_id == 102:
                    qty = 1.0 if offset % 5 == 0 else 0.0
                elif product_id == 103:
                    # Chỉ phát sinh bán ở giai đoạn đầu (train), bằng 0 hoàn toàn trên 30 ngày cuối (test)
                    qty = 1.0 if offset < 20 else 0.0
                else:
                    qty = 0.0
                records.append({
                    "sale_date": d,
                    "branch_id": branch_id,
                    "product_id": product_id,
                    "category_id": 1 if product_id in (101, 102) else 2,
                    "category_name": "Fast" if product_id in (101, 102) else "Slow",
                    "branch_code": f"GM-00{branch_id}",
                    "branch_name": f"Branch {branch_id}",
                    "qty_sold": int(qty),
                    "qty_returned": 0,
                    "qty_net": qty,
                    "revenue": qty * 10000.0,
                    "promo_flag": False,
                    "promo_discount_pct": 0.0,
                    "stockout": False,
                })
    return pd.DataFrame(records)


def test_build_postprocess_version_formats_thresholds_deterministically():
    assert build_postprocess_version({30: 0.35, 7: 0.4}, min_history_days=28) == "slow-th-h7_0.4-h30_0.35-sh28-v1"
    assert build_postprocess_version({}, min_history_days=28) == "no-th-sh28-v1"


def test_select_best_baseline_for_metrics_respects_wape_mae_tiebreak():
    view_results = {
        "weekly_naive": {
            "overall": ModelComparisonMetrics(mae=0.8, wape=0.45, sample_count=10, total_actual=20.0, zero_pred_ratio=0.1)
        },
        "rolling_mean_28": {
            "overall": ModelComparisonMetrics(mae=0.6, wape=0.52, sample_count=10, total_actual=20.0, zero_pred_ratio=0.0)
        },
    }
    assert select_best_baseline_for_metrics(view_results, "overall") == "weekly_naive"

    tie_wape_results = {
        "weekly_naive": {
            "overall": ModelComparisonMetrics(mae=0.7, wape=0.50, sample_count=10, total_actual=20.0, zero_pred_ratio=0.1)
        },
        "rolling_mean_28": {
            "overall": ModelComparisonMetrics(mae=0.5, wape=0.50, sample_count=10, total_actual=20.0, zero_pred_ratio=0.0)
        },
    }
    assert select_best_baseline_for_metrics(tie_wape_results, "overall") == "rolling_mean_28"


def test_standardized_summary_and_report_separate_serving_from_raw_daily():
    df_daily = _make_sparse_sales_with_zero_demand(n_days=65)
    report, _ = evaluate_lightgbm_pipeline(
        df_daily=df_daily,
        horizons=(7, 30),
        objectives=("regression_l1", "tweedie"),
        sample_step_days=5,
    )

    assert report.model_version == DEFAULT_MODEL_VERSION
    assert report.postprocess_version.startswith("slow-th-")
    base_views = {
        "daily_clean",
        "daily_raw",
        "weekly_clean",
        "weekly_raw",
        "horizon_aggregate_clean",
        "horizon_aggregate_raw",
    }
    for h in (7, 30):
        assert h in report.best_baseline_by_view
        expected_views = base_views | {"rolling_h7_aggregate_clean"} if h == 7 else base_views
        assert set(report.best_baseline_by_view[h].keys()) == expected_views
        assert "zero_demand" in report.aggregated_results_clean[h]["lgb_tweedie_postproc"]
        assert "non_zero_demand" in report.aggregated_results_clean[h]["lgb_tweedie_postproc"]

    summary = build_standardized_evaluation_summary(report)
    assert summary["schema_version"] == "1.0"
    assert summary["model_version"] == DEFAULT_MODEL_VERSION
    assert summary["postprocess_version"] == report.postprocess_version
    assert summary["cutoff_date"] == str(report.cutoff_date)
    assert len(summary["benchmark_limitations"]) >= 3

    for h_str in ("7", "30"):
        h_views = summary["horizons"][h_str]
        serving_view = h_views["serving_aggregate_postproc"]
        raw_daily_view = h_views["raw_daily_clean"]

        assert serving_view["aggregation"] == "horizon_aggregate_postproc"
        assert serving_view["postprocess_version"] == report.postprocess_version
        assert serving_view["model_key"] == "lgb_tweedie_postproc"
        assert serving_view["best_baseline_name"] == report.best_baseline_by_view[int(h_str)]["horizon_aggregate_clean"]
        assert "zero_demand" in serving_view["segments"]
        assert "non_zero_demand" in serving_view["segments"]

        assert raw_daily_view["aggregation"] == "daily_cell_raw_model"
        assert raw_daily_view["postprocess_version"] == "none"
        assert raw_daily_view["model_key"] == "lgb_tweedie"
        assert raw_daily_view["best_baseline_name"] == report.best_baseline_by_view[int(h_str)]["daily_clean"]

    md = format_full_comparison_report(report)
    assert "modelVersion" in md
    assert "postprocessVersion" in md
    assert "Zero-demand" in md
    assert "Chờ Lead @NguyenDokhai2005 chốt cách đo" in md
    assert "ĐẠT AC-05" not in md


def test_compute_view_comparison_handles_zero_wape_without_truthiness_bug():
    y_act = np.array([10.0, 20.0])
    y_perfect = np.array([10.0, 20.0])
    y_raw = np.array([11.0, 19.0])
    y_ma28 = np.array([12.0, 18.0])
    y_naive = np.array([15.0, 15.0])

    comp = _compute_view_comparison(
        y_act=y_act,
        y_postproc=y_perfect,
        y_raw=y_raw,
        y_ma28=y_ma28,
        y_naive=y_naive,
        horizon=7,
        aggregation="horizon_aggregate",
        stockout_excluded=True,
    )
    assert comp["model"]["wape"] == 0.0
    assert comp["best_baseline_name"] == "rolling_mean_28"
    assert comp["wape_diff"] == pytest.approx(0.0 - comp["baseline_rolling_mean_28"]["wape"])
    assert comp["win"] is True


def test_scripts_train_and_segment_evaluation_end_to_end_metadata(tmp_path: Path):
    df_daily = _make_sparse_sales_with_zero_demand(n_days=65)
    parquet_path = tmp_path / "daily_sales.parquet"
    artifacts_dir = tmp_path / "artifacts"
    df_daily.to_parquet(parquet_path, index=False)

    run_training_and_evaluation(
        data_path=parquet_path,
        artifacts_dir=artifacts_dir,
        save_db=False,
    )

    eval_json = json.loads((artifacts_dir / "evaluation_metrics.json").read_text(encoding="utf-8"))
    assert eval_json["model_version"] == DEFAULT_MODEL_VERSION
    assert eval_json["postprocess_version"] == "slow-th-h7_0.4-h30_0.4-sh28-v1"
    assert eval_json["optimal_slow_thresholds"] == {"7": 0.4, "30": 0.4}
    assert "standardized_summary" in eval_json
    assert "results_raw_daily" in eval_json
    assert "zero_demand" in eval_json["results_clean_aggregate"]["7"]["lgb_tweedie_postproc"]

    seg_json = run_segment_evaluation(
        data_path=parquet_path,
        artifacts_dir=artifacts_dir,
        output_json_path=artifacts_dir / "segment_metrics.json",
    )
    assert seg_json["cutoff_date"] == eval_json["cutoff_date"]
    assert seg_json["postprocess_version"] == eval_json["postprocess_version"]
    assert "zero_demand" in seg_json["metrics"]["7"]
    assert "model_raw" in seg_json["metrics"]["7"]["overall"]["horizon_aggregate"]
    assert "best_baseline_name" in seg_json["metrics"]["7"]["overall"]["horizon_aggregate"]


def test_fixed_threshold_strict_less_than_and_postprocess_order():
    """Ngưỡng 0.4 xóa giá trị < 0.4 trên slow-moving, giữ nguyên == 0.4, rồi fallback < 28 ngày và clip >= 0."""
    idx = pd.MultiIndex.from_tuples([(1, 101), (1, 102), (1, 103)], names=["branch_id", "product_id"])
    is_top_mask = np.array([True, False, False])
    # 101 (top): 0.2 -> giữ nguyên 0.2; 102 (slow): 0.3999 -> 0.0, 0.4 -> giữ nguyên 0.4; 103 (short history): fallback > 0
    pred_mat = np.array([
        [0.2, -0.5],
        [0.3999, 0.4],
        [0.1, 0.1],
    ])
    records = []
    for d in range(30):
        s_date = date(2026, 9, 1) + timedelta(days=d)
        records.append({"branch_id": 1, "product_id": 101, "category_id": 1, "sale_date": s_date, "qty_net": 5.0})
        records.append({"branch_id": 1, "product_id": 102, "category_id": 1, "sale_date": s_date, "qty_net": 1.0})
    for d in range(10):
        s_date = date(2026, 9, 21) + timedelta(days=d)
        records.append({"branch_id": 1, "product_id": 103, "category_id": 1, "sale_date": s_date, "qty_net": 2.0})
    df_hist = pd.DataFrame(records)

    out, fb_cnt = _apply_serving_postprocess_pipeline(
        pred_matrix=pred_mat,
        is_top_mask=is_top_mask,
        slow_threshold=0.4,
        df_history=df_hist,
        series_index=idx,
        min_history_days=28,
    )
    assert fb_cnt == 1
    assert out[0, 0] == pytest.approx(0.2)
    assert out[0, 1] == 0.0  # clipped >= 0
    assert out[1, 0] == 0.0  # 0.3999 < 0.4
    assert out[1, 1] == pytest.approx(0.4)  # == 0.4 preserved
    assert out[2, 0] > 0.0  # short history fallback overrides 0.1


def test_canonical_hash_rolling_h7_and_typed_breakdown_contract():
    df_daily = _make_sparse_sales_with_zero_demand(n_days=65)
    # Shuffle row order & use Timestamp to verify deterministic canonical hash
    df_shuffled = df_daily.sample(frac=1.0, random_state=42).copy()
    df_shuffled["sale_date"] = pd.to_datetime(df_shuffled["sale_date"])
    h1 = compute_canonical_data_hash(df_daily)
    h2 = compute_canonical_data_hash(df_shuffled)
    assert h1 == h2

    # Inject 2 stockout cells inside test window to test coverage tracking
    cutoff = sorted(df_daily["sale_date"].unique())[-31]
    df_daily.loc[(df_daily["sale_date"] == cutoff + timedelta(days=2)) & (df_daily["product_id"] == 101), "stockout"] = True

    report, _ = evaluate_lightgbm_pipeline(
        df_daily=df_daily,
        horizons=(7, 30),
        objectives=("regression_l1", "tweedie"),
        sample_step_days=5,
        fixed_slow_thresholds={7: 0.4, 30: 0.4},
        data_source="postgresql.daily_sales",
        data_source_hash=h1,
        serving_cutoff_date=df_daily["sale_date"].max(),
        include_rolling_h7=True,
    )

    assert report.postprocess_version == "slow-th-h7_0.4-h30_0.4-sh28-v1"
    assert len(report.rolling_h7_origins) == 24
    assert report.rolling_h7_origins[0] == cutoff
    assert report.rolling_h7_origins[-1] == report.test_end_date - timedelta(days=7)

    breakdown = build_metrics_breakdown_payload(report)
    parsed = ForecastMetricsBreakdown.model_validate(breakdown)
    assert parsed.schema_version == "2.0"
    assert parsed.metric_protocol_version == DEFAULT_METRIC_PROTOCOL_VERSION
    assert parsed.cutoff_date == df_daily["sale_date"].max()
    assert parsed.evaluation_cutoff_date == cutoff
    assert parsed.h7 is not None
    assert parsed.h30 is not None
    assert parsed.h7.rolling_h7_aggregate_postproc is not None
    assert len(parsed.h7.rolling_h7_aggregate_postproc.evaluation_origins) == 24
    assert parsed.h30.rolling_h7_aggregate_postproc is None
    assert parsed.h7.serving_aggregate_postproc.segments.overall.excluded_stockout_count == 2
    assert parsed.h7.serving_aggregate_postproc.segments.overall.coverage_ratio < 1.0
    # Zero-demand segment has totalActual <= 0 -> WAPE must be None
    assert parsed.h7.serving_aggregate_postproc.segments.zero_demand.total_actual <= 0.0
    assert parsed.h7.serving_aggregate_postproc.segments.zero_demand.model.wape is None
    # Serialized JSON must contain zero NaN/Infinity
    raw_json = json.dumps(breakdown, allow_nan=False)
    assert "NaN" not in raw_json and "Infinity" not in raw_json

    mae_val, wape_val, b_mae_val, b_wape_val, meta = extract_run_metric_summary(report)
    assert meta is not None
    assert meta["metric_horizon"] == 7
    assert meta["aggregation"] == "HORIZON_AGGREGATE_POSTPROC"
    assert meta["baseline_name"] == parsed.h7.serving_aggregate_postproc.segments.overall.best_baseline_name
    assert mae_val == pytest.approx(parsed.h7.serving_aggregate_postproc.segments.overall.model.mae, abs=1e-4)
    assert b_mae_val == pytest.approx(parsed.h7.serving_aggregate_postproc.segments.overall.best_baseline.mae, abs=1e-4)

    # Verify ForecastRunDetailResponse + get_forecast_run_detail handle standardized, legacy, and RUNNING rows
    now = datetime.now(timezone.utc)
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur

    complete_forecast_run(
        mock_conn,
        run_id=77,
        mae=mae_val,
        wape=wape_val,
        baseline_mae=b_mae_val,
        baseline_wape=b_wape_val,
        metric_metadata=meta,
    )
    executed_sql = mock_cur.execute.call_args[0][0]
    assert "metrics_breakdown = %s::jsonb" in executed_sql

    mock_cur.fetchone.return_value = (
        77, "DONE", now, now, DEFAULT_MODEL_VERSION,
        mae_val, wape_val, b_mae_val, b_wape_val,
        meta["metric_horizon"], meta["aggregation"], meta["postprocess_version"], meta["metric_protocol_version"],
        meta["cutoff_date"], meta["evaluation_cutoff_date"], meta["test_start_date"], meta["test_end_date"],
        meta["stockout_mode"], meta["baseline_name"], meta["data_source"], meta["data_source_hash"],
        json.dumps(breakdown),
    )
    detail = get_forecast_run_detail(mock_conn, 77)
    assert detail is not None
    assert detail["legacy"] is False
    dto = ForecastRunDetailResponse.model_validate(detail)
    dumped = dto.model_dump(by_alias=True, mode="json")
    assert dumped["metricHorizon"] == 7
    assert dumped["aggregation"] == "HORIZON_AGGREGATE_POSTPROC"
    assert dumped["cutoffDate"] == str(meta["cutoff_date"])
    assert dumped["evaluationCutoffDate"] == str(meta["evaluation_cutoff_date"])
    assert dumped["metricsBreakdown"]["h7"]["rollingH7AggregatePostproc"] is not None

    # Non-DONE row must return NULL for all metrics and metadata
    mock_cur.fetchone.return_value = (
        78, "FAILED", now, now, DEFAULT_MODEL_VERSION,
        0.5, 0.5, 0.5, 0.5, 7, "HORIZON_AGGREGATE_POSTPROC", "v1", "p1",
        meta["cutoff_date"], meta["evaluation_cutoff_date"], meta["test_start_date"], meta["test_end_date"],
        "EXCLUDED_CLEAN", "rolling_mean_28", "ds", "hash", json.dumps(breakdown),
    )
    failed_detail = get_forecast_run_detail(mock_conn, 78)
    assert failed_detail is not None
    assert failed_detail["legacy"] is False
    assert failed_detail["mae"] is None
    assert failed_detail["metric_horizon"] is None
    assert failed_detail["metrics_breakdown"] is None


def test_serialize_eval_view_preserves_zero_coverage_ratio():
    """Coverage = 0.0 (toàn bộ ngày bị stockout) không được fallback thành 1.0 (#423 QA)."""
    from app.api.schemas import ForecastEvaluationView
    from app.models.evaluator import _build_openapi_evaluation_view

    full_stockout_seg = ModelComparisonMetrics(None, None, 0, 0.0, None, 7, 7, 0.0)
    half_cov_seg = ModelComparisonMetrics(1.0, 0.5, 4, 8.0, 0.25, 4, 8, 0.5)
    full_cov_seg = ModelComparisonMetrics(0.5, 0.2, 7, 14.0, 0.0, 0, 7, 1.0)
    naive_seg = ModelComparisonMetrics(1.2, 0.6, 4, 8.0, 0.5, 4, 8, 0.5)
    zero_seg = ModelComparisonMetrics(2.0, 1.0, 4, 8.0, 1.0, 4, 8, 0.5)

    stub_report = MagicMock()
    stub_report.best_baseline_by_view = {7: {"daily_clean": "rolling_mean_28"}}

    view = _build_openapi_evaluation_view(
        report=stub_report,
        horizon=7,
        aggregation="DAILY_CELL_RAW",
        stockout_mode="EXCLUDED_CLEAN",
        res_h={
            "lgb_tweedie": {
                "overall": full_stockout_seg,
                "top_200": half_cov_seg,
                "slow_moving": full_cov_seg,
                "zero_demand": full_stockout_seg,
                "non_zero_demand": full_cov_seg,
            },
            "rolling_mean_28": {
                "overall": full_stockout_seg,
                "top_200": half_cov_seg,
                "slow_moving": full_cov_seg,
                "zero_demand": full_stockout_seg,
                "non_zero_demand": full_cov_seg,
            },
            "weekly_naive": {
                "overall": full_stockout_seg,
                "top_200": naive_seg,
                "slow_moving": full_cov_seg,
                "zero_demand": full_stockout_seg,
                "non_zero_demand": full_cov_seg,
            },
            "zero_forecast": {
                "overall": full_stockout_seg,
                "top_200": zero_seg,
                "slow_moving": full_cov_seg,
                "zero_demand": full_stockout_seg,
                "non_zero_demand": full_cov_seg,
            },
        },
        model_key="lgb_tweedie",
        bb_view_key="daily_clean",
        evaluation_origins=[date(2026, 10, 1)],
        target_start=date(2026, 10, 2),
        target_end=date(2026, 10, 8),
        postproc_ver="none",
    )

    parsed_view = ForecastEvaluationView.model_validate(view)
    assert view["segments"]["overall"]["coverageRatio"] == 0.0
    assert parsed_view.segments.overall.coverage_ratio == 0.0
    assert view["segments"]["overall"]["excludedStockoutCount"] == 7
    assert view["segments"]["overall"]["totalDailyCellsBeforeFilter"] == 7
    assert view["segments"]["overall"]["sampleCount"] == 0
    assert view["segments"]["top200"]["coverageRatio"] == 0.5
    assert view["segments"]["slowMoving"]["coverageRatio"] == 1.0


def test_compute_view_comparison_wape_none_for_zero_negative_and_empty():
    """WAPE phải là None/N/A khi total_actual <= 0 (all-zero, trả hàng) hoặc tập rỗng (#423 QA)."""
    zeros = np.zeros(7, dtype=float)
    comp_zero = _compute_view_comparison(
        y_act=zeros,
        y_postproc=zeros,
        y_raw=zeros,
        y_ma28=zeros,
        y_naive=zeros,
        horizon=7,
        aggregation="horizon_aggregate",
        stockout_excluded=True,
    )
    assert comp_zero["sample_count"] == 7
    assert comp_zero["model"]["mae"] == 0.0
    assert comp_zero["model"]["wape"] is None
    assert comp_zero["model"]["zero_pred_ratio"] == 1.0
    assert comp_zero["model_raw"]["wape"] is None
    assert comp_zero["baseline_rolling_mean_28"]["wape"] is None
    assert comp_zero["baseline_weekly_naive"]["wape"] is None
    assert comp_zero["wape_diff"] is None
    assert comp_zero["win"] is False

    returns_neg = np.array([-3.0, 1.0, 1.0], dtype=float)  # total_actual = -1 <= 0
    pred_neg = np.zeros(3, dtype=float)
    comp_neg = _compute_view_comparison(
        y_act=returns_neg,
        y_postproc=pred_neg,
        y_raw=pred_neg,
        y_ma28=pred_neg,
        y_naive=pred_neg,
        horizon=7,
        aggregation="horizon_aggregate",
        stockout_excluded=True,
    )
    assert comp_neg["model"]["mae"] == pytest.approx(5.0 / 3.0)
    assert comp_neg["model"]["wape"] is None
    assert comp_neg["baseline_rolling_mean_28"]["wape"] is None
    assert comp_neg["win"] is False

    empty = np.array([], dtype=float)
    comp_empty = _compute_view_comparison(
        y_act=empty,
        y_postproc=empty,
        y_raw=empty,
        y_ma28=empty,
        y_naive=empty,
        horizon=7,
        aggregation="horizon_aggregate",
        stockout_excluded=True,
    )
    assert comp_empty["sample_count"] == 0
    assert comp_empty["model"]["mae"] is None
    assert comp_empty["model"]["wape"] is None
    assert comp_empty["model"]["zero_pred_ratio"] is None
    assert comp_empty["win"] is False

