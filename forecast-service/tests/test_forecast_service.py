"""Unit tests for forecast_service business logic (#114)."""

from datetime import datetime, timezone
from unittest.mock import MagicMock, patch
import numpy as np
import pandas as pd
import pytest

from app.pipelines.forecast_service import (
    ActiveRunConflictError,
    EmptyDailySalesError,
    execute_background_forecast,
    get_forecast_run_detail,
    start_forecast_run,
)


def test_start_forecast_run_raises_when_daily_sales_empty():
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur

    # SELECT EXISTS (...) returns False (no rows in daily_sales)
    mock_cur.fetchone.return_value = (False,)

    with pytest.raises(EmptyDailySalesError, match="Bảng daily_sales hiện đang trống"):
        start_forecast_run(mock_conn)


def test_start_forecast_run_raises_when_active_run_running():
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur

    # 1. EXISTS daily_sales -> True
    # 2. SELECT active run -> (41, started_at)
    mock_cur.fetchone.side_effect = [
        (True,),  # has sales
        (41, datetime.now(timezone.utc)),  # active run
    ]

    with pytest.raises(ActiveRunConflictError) as exc_info:
        start_forecast_run(mock_conn)

    assert exc_info.value.active_id == 41
    assert "Đã có một lượt dự báo đang chạy (run_id=41)" in str(exc_info.value)


def test_start_forecast_run_cleans_stale_run_and_creates_new():
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur

    now = datetime.now(timezone.utc)
    mock_cur.fetchone.side_effect = [
        (True,),  # has sales
        None,     # no active run after cleanup
        (105, now),  # INSERT returning id, started_at
    ]

    run_id, started_at = start_forecast_run(mock_conn, model_version="LightGBM-Test")
    assert run_id == 105
    assert started_at == now
    assert mock_conn.commit.called

    # Check cleanup query was executed
    executed_queries = [call[0][0] for call in mock_cur.execute.call_args_list]
    assert any("UPDATE forecast_run" in q and "FAILED" in q and "30 minutes" in q for q in executed_queries)
    assert any("INSERT INTO forecast_run" in q and "RUNNING" in q for q in executed_queries)


def test_get_forecast_run_detail_returns_dict_or_none():
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur

    # Found
    now = datetime.now(timezone.utc)
    mock_cur.fetchone.return_value = (
        1, "DONE", now, now, "LightGBM-Tweedie-H7-H30", 0.25, 0.15, 0.28, 0.18
    )
    detail = get_forecast_run_detail(mock_conn, 1)
    assert detail is not None
    assert detail["run_id"] == 1
    assert detail["status"] == "DONE"
    assert detail["mae"] == 0.25
    assert detail["wape"] == 0.15
    assert detail["baseline_mae"] == 0.28
    assert detail["baseline_wape"] == 0.18

    # Not found
    mock_cur.fetchone.return_value = None
    assert get_forecast_run_detail(mock_conn, 999) is None


def test_execute_background_forecast_success(monkeypatch):
    """Verify background task reuses run_id and does NOT call create_forecast_run."""
    mock_conn = MagicMock()
    monkeypatch.setattr("app.pipelines.forecast_service.get_db_connection", lambda: mock_conn)
    mock_conn.__enter__.return_value = mock_conn

    # Mock daily_sales dataframe
    from datetime import timedelta
    cutoff = datetime(2026, 10, 1).date()
    start_date = cutoff - timedelta(days=35)
    end_date = cutoff + timedelta(days=30)
    days_count = (end_date - start_date).days + 1
    dates = [start_date + timedelta(days=i) for i in range(days_count)]
    records = []
    for d in dates:
        records.append({
            "sale_date": d,
            "branch_id": 1,
            "product_id": 10,
            "qty_sold": 5,
            "qty_returned": 0,
            "qty_net": 5,
            "revenue": 50000,
            "promo_flag": False,
            "promo_discount_pct": 0.0,
            "stockout": False,
        })
    df_dummy = pd.DataFrame(records)
    monkeypatch.setattr(
        "app.pipelines.forecast_service.fetch_daily_sales_from_db",
        lambda conn: df_dummy,
    )

    mock_metrics = MagicMock(mae=0.20, wape=0.12)
    mock_report = MagicMock()
    mock_report.cutoff_date = cutoff
    mock_report.optimal_slow_thresholds = {7: 0.4, 30: 0.4}
    mock_report.baseline_report.top_sku_ids = {10}
    mock_report.baseline_report.best_baseline = {7: "rolling_mean_28"}
    mock_report.baseline_report.results = {7: {"rolling_mean_28": {"overall": mock_metrics}}}
    mock_report.lgb_results_clean = {
        7: {
            "lgb_tweedie": {"overall": mock_metrics},
        }
    }

    class DummyModel:
        def predict(self, df):
            return np.ones(len(df))

    dummy_evaluator = lambda df_daily, horizons: (mock_report, {7: DummyModel(), 30: DummyModel()})

    saved_runs = []
    completed_runs = []

    monkeypatch.setattr(
        "app.pipelines.forecast_service.save_forecast_results",
        lambda conn, run_id, results_df: saved_runs.append(run_id) or len(results_df),
    )
    monkeypatch.setattr(
        "app.pipelines.forecast_service.complete_forecast_run",
        lambda conn, run_id, mae, wape, baseline_mae, baseline_wape: completed_runs.append((run_id, mae, wape)),
    )

    # Execute background task for existing run_id 88
    execute_background_forecast(run_id=88, evaluate_pipeline_fn=dummy_evaluator)

    # Verify run_id 88 is reused
    assert saved_runs == [88]
    assert len(completed_runs) == 1
    assert completed_runs[0][0] == 88
    assert completed_runs[0][1] == 0.20
    assert completed_runs[0][2] == 0.12
    assert mock_conn.commit.called


def test_execute_background_forecast_failure_marks_failed(monkeypatch):
    """Verify background task marks run as FAILED and rollbacks on error."""
    mock_conn = MagicMock()
    monkeypatch.setattr("app.pipelines.forecast_service.get_db_connection", lambda: mock_conn)
    mock_conn.__enter__.return_value = mock_conn

    # Make fetch fail
    monkeypatch.setattr(
        "app.pipelines.forecast_service.fetch_daily_sales_from_db",
        MagicMock(side_effect=RuntimeError("Database failure")),
    )

    failed_runs = []
    monkeypatch.setattr(
        "app.pipelines.forecast_service.fail_forecast_run",
        lambda conn, run_id: failed_runs.append(run_id),
    )

    execute_background_forecast(run_id=77)

    assert failed_runs == [77]


def test_execute_background_forecast_default_raw_model_no_threshold(monkeypatch):
    """Kiểm tra khi chạy mặc định, không có dự báo nào bị ép về 0 bởi ngưỡng (#114)."""
    mock_conn = MagicMock()
    monkeypatch.setattr("app.pipelines.forecast_service.get_db_connection", lambda: mock_conn)
    mock_conn.__enter__.return_value = mock_conn

    from datetime import timedelta
    cutoff = datetime(2026, 10, 1).date()
    start_date = cutoff - timedelta(days=35)
    end_date = cutoff + timedelta(days=30)
    dates = [start_date + timedelta(days=i) for i in range((end_date - start_date).days + 1)]
    records = []
    for d in dates:
        # SKU 20 là slow-moving (không nằm trong top_sku_ids)
        records.append({
            "sale_date": d,
            "branch_id": 1,
            "product_id": 20,
            "qty_sold": 1,
            "qty_returned": 0,
            "qty_net": 1,
            "revenue": 10000,
            "promo_flag": False,
            "promo_discount_pct": 0.0,
            "stockout": False,
        })
    df_dummy = pd.DataFrame(records)
    monkeypatch.setattr("app.pipelines.forecast_service.fetch_daily_sales_from_db", lambda conn: df_dummy)

    mock_metrics = MagicMock(mae=0.20, wape=0.12)
    mock_report = MagicMock()
    mock_report.cutoff_date = cutoff
    mock_report.optimal_slow_thresholds = {7: 0.4, 30: 0.4}
    mock_report.baseline_report.top_sku_ids = set()  # Không có SKU nào là Top, SKU 20 là slow-moving
    mock_report.baseline_report.best_baseline = {7: "rolling_mean_28"}
    mock_report.baseline_report.results = {7: {"rolling_mean_28": {"overall": mock_metrics}}}
    mock_report.lgb_results_clean = {7: {"lgb_tweedie": {"overall": mock_metrics}}}

    # Model trả về 0.15 (< ngưỡng 0.4 cũ)
    class DummySlowModel:
        def predict(self, df):
            return np.full(len(df), 0.15)

    dummy_evaluator = lambda df_daily, horizons: (mock_report, {7: DummySlowModel(), 30: DummySlowModel()})

    captured_results = []
    monkeypatch.setattr(
        "app.pipelines.forecast_service.save_forecast_results",
        lambda conn, run_id, results_df: captured_results.append(results_df.copy()) or len(results_df),
    )
    monkeypatch.setattr(
        "app.pipelines.forecast_service.complete_forecast_run",
        lambda conn, run_id, mae, wape, baseline_mae, baseline_wape: None,
    )

    # Chạy mặc định không truyền optimal_threshold
    execute_background_forecast(run_id=99, evaluate_pipeline_fn=dummy_evaluator)

    assert len(captured_results) == 1
    res_df = captured_results[0]
    # Kiểm tra rằng giá trị 0.15 KHÔNG bị ép về 0.0
    assert (res_df["predicted_qty"] == 0.15).all(), "Kỳ vọng dự báo thô 0.15 được giữ nguyên, không bị ép về 0"
    assert (res_df["predicted_qty"] == 0.0).sum() == 0, "Không có dòng nào bị ép về 0"

