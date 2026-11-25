"""Unit tests for forecast_service business logic (#114)."""

from datetime import date, datetime, timezone
from unittest.mock import MagicMock, patch
import numpy as np
import pandas as pd
import pytest

from app.pipelines.forecast_service import (
    ActiveRunConflictError,
    DEFAULT_SLOW_THRESHOLD,
    EmptyDailySalesError,
    StaleDailySalesError,
    ensure_fresh_daily_sales,
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


def test_start_forecast_run_raises_when_daily_sales_stale():
    """Issue #417: start_forecast_run ném StaleDailySalesError khi MAX(sale_date) < expected_cutoff_date."""
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur

    # SELECT MIN(sale_date), MAX(sale_date) -> 2026-11-20
    mock_cur.fetchone.return_value = (date(2025, 11, 1), date(2026, 11, 20))

    with pytest.raises(StaleDailySalesError, match="bị cũ: cutoff hiện tại là"):
        start_forecast_run(mock_conn, expected_cutoff_date=date(2026, 11, 22))


def test_ensure_fresh_daily_sales_already_fresh():
    """Issue #417: Khi MAX(sale_date) >= expected_cutoff_date, không cần chạy pipeline."""
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur
    mock_cur.fetchone.return_value = (date(2025, 11, 1), date(2026, 11, 22))

    res = ensure_fresh_daily_sales(mock_conn, expected_cutoff_date=date(2026, 11, 22))
    assert res["status"] == "ALREADY_FRESH"
    assert res["cutoff_date"] == date(2026, 11, 22)


def test_ensure_fresh_daily_sales_runs_pipeline_and_succeeds():
    """Issue #417: Khi dữ liệu thiếu, gọi pipeline_fn và kiểm tra lại thành công."""
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur
    # Lần 1 (trước ETL): 2026-11-20 < 2026-11-22
    # Lần 2 (sau ETL): 2026-11-22 >= 2026-11-22
    mock_cur.fetchone.side_effect = [
        (date(2025, 11, 1), date(2026, 11, 20)),
        (date(2025, 11, 1), date(2026, 11, 22)),
    ]

    pipeline_called = []
    def dummy_pipeline(start_date, end_date, conn):
        pipeline_called.append((start_date, end_date))
        return {"total_rows": 100}

    res = ensure_fresh_daily_sales(
        mock_conn,
        expected_cutoff_date=date(2026, 11, 22),
        pipeline_fn=dummy_pipeline,
    )
    assert res["status"] == "ETL_LOADED"
    assert res["cutoff_date"] == date(2026, 11, 22)
    assert len(pipeline_called) == 1
    assert pipeline_called[0][1] == date(2026, 11, 22)


def test_ensure_fresh_daily_sales_raises_stale_when_etl_fails_to_reach_cutoff():
    """Issue #417: Khi pipeline chạy xong nhưng MAX(sale_date) vẫn không tới cutoff, ném StaleDailySalesError."""
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur
    # Trước và sau ETL đều chỉ tới 2026-11-20
    mock_cur.fetchone.side_effect = [
        (date(2025, 11, 1), date(2026, 11, 20)),
        (date(2025, 11, 1), date(2026, 11, 20)),
    ]

    def dummy_pipeline(start_date, end_date, conn):
        return {"total_rows": 0}

    with pytest.raises(StaleDailySalesError, match="vẫn bị cũ"):
        ensure_fresh_daily_sales(
            mock_conn,
            expected_cutoff_date=date(2026, 11, 22),
            pipeline_fn=dummy_pipeline,
        )


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


def _build_dummy_sales(product_id: int = 20) -> pd.DataFrame:
    """Tạo DataFrame daily_sales giả lập đủ 65 ngày lịch sử cho 1 series."""
    from datetime import timedelta
    cutoff = datetime(2026, 10, 1).date()
    start_date = cutoff - timedelta(days=35)
    end_date = cutoff + timedelta(days=30)
    dates = [start_date + timedelta(days=i) for i in range((end_date - start_date).days + 1)]
    records = []
    for d in dates:
        records.append({
            "sale_date": d,
            "branch_id": 1,
            "product_id": product_id,
            "qty_sold": 1,
            "qty_returned": 0,
            "qty_net": 1,
            "revenue": 10000,
            "promo_flag": False,
            "promo_discount_pct": 0.0,
            "stockout": False,
        })
    return pd.DataFrame(records)


def test_default_slow_threshold_is_0_4():
    """1. Đảm bảo cấu hình mặc định DEFAULT_SLOW_THRESHOLD thực sự là 0.4 (Quyết định Lead 13/11/2026)."""
    assert DEFAULT_SLOW_THRESHOLD == 0.4


def test_execute_background_forecast_default_applies_slow_threshold_0_4(monkeypatch):
    """2. Kiểm tra khi chạy mặc định, SKU bán chậm có dự báo < 0.4 bị ép về 0.0 theo quyết định Lead 13/11/2026."""
    mock_conn = MagicMock()
    monkeypatch.setattr("app.pipelines.forecast_service.get_db_connection", lambda: mock_conn)
    mock_conn.__enter__.return_value = mock_conn

    cutoff = datetime(2026, 10, 1).date()
    df_dummy = _build_dummy_sales(product_id=20)
    monkeypatch.setattr("app.pipelines.forecast_service.fetch_daily_sales_from_db", lambda conn: df_dummy)

    mock_metrics = MagicMock(mae=0.20, wape=0.12)
    mock_report = MagicMock()
    mock_report.cutoff_date = cutoff
    mock_report.optimal_slow_thresholds = {7: 0.4, 30: 0.4}
    mock_report.baseline_report.top_sku_ids = set()  # SKU 20 là slow-moving
    mock_report.baseline_report.best_baseline = {7: "rolling_mean_28"}
    mock_report.baseline_report.results = {7: {"rolling_mean_28": {"overall": mock_metrics}}}
    mock_report.lgb_results_clean = {7: {"lgb_tweedie": {"overall": mock_metrics}}}

    # Model trả về 0.15 (< ngưỡng 0.4)
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

    # Chạy mặc định không truyền optimal_threshold (sử dụng DEFAULT_SLOW_THRESHOLD = 0.4)
    execute_background_forecast(run_id=99, evaluate_pipeline_fn=dummy_evaluator)

    assert len(captured_results) == 1
    res_df = captured_results[0]
    # Kiểm tra rằng giá trị 0.15 < 0.4 của SKU bán chậm bị ép về 0.0
    assert (res_df["predicted_qty"] == 0.0).all(), "Kỳ vọng dự báo cho SKU bán chậm có giá trị 0.15 < 0.4 bị ép về 0.0"


def test_execute_background_forecast_slow_sku_above_threshold_preserved(monkeypatch):
    """Kiểm tra SKU bán chậm có dự báo >= 0.4 vẫn được giữ nguyên, không bị ép về 0."""
    mock_conn = MagicMock()
    monkeypatch.setattr("app.pipelines.forecast_service.get_db_connection", lambda: mock_conn)
    mock_conn.__enter__.return_value = mock_conn

    cutoff = datetime(2026, 10, 1).date()
    df_dummy = _build_dummy_sales(product_id=20)
    monkeypatch.setattr("app.pipelines.forecast_service.fetch_daily_sales_from_db", lambda conn: df_dummy)

    mock_metrics = MagicMock(mae=0.20, wape=0.12)
    mock_report = MagicMock()
    mock_report.cutoff_date = cutoff
    mock_report.optimal_slow_thresholds = {7: 0.4, 30: 0.4}
    mock_report.baseline_report.top_sku_ids = set()  # SKU 20 là slow-moving
    mock_report.baseline_report.best_baseline = {7: "rolling_mean_28"}
    mock_report.baseline_report.results = {7: {"rolling_mean_28": {"overall": mock_metrics}}}
    mock_report.lgb_results_clean = {7: {"lgb_tweedie": {"overall": mock_metrics}}}

    # Model trả về 0.50 (>= ngưỡng 0.4)
    class DummySlowModelAboveThreshold:
        def predict(self, df):
            return np.full(len(df), 0.50)

    dummy_evaluator = lambda df_daily, horizons: (mock_report, {7: DummySlowModelAboveThreshold(), 30: DummySlowModelAboveThreshold()})

    captured_results = []
    monkeypatch.setattr(
        "app.pipelines.forecast_service.save_forecast_results",
        lambda conn, run_id, results_df: captured_results.append(results_df.copy()) or len(results_df),
    )
    monkeypatch.setattr(
        "app.pipelines.forecast_service.complete_forecast_run",
        lambda conn, run_id, mae, wape, baseline_mae, baseline_wape: None,
    )

    execute_background_forecast(run_id=100, evaluate_pipeline_fn=dummy_evaluator)

    assert len(captured_results) == 1
    res_df = captured_results[0]
    assert (res_df["predicted_qty"] == 0.50).all(), "Kỳ vọng dự báo >= 0.4 được giữ nguyên"


def test_execute_background_forecast_fast_moving_skus_not_affected(monkeypatch):
    """3. Nhóm 200 SKU bán chạy không bị ảnh hưởng bởi ngưỡng 0.4 ngay cả khi giá trị < 0.4."""
    mock_conn = MagicMock()
    monkeypatch.setattr("app.pipelines.forecast_service.get_db_connection", lambda: mock_conn)
    mock_conn.__enter__.return_value = mock_conn

    cutoff = datetime(2026, 10, 1).date()
    df_dummy = _build_dummy_sales(product_id=10)
    monkeypatch.setattr("app.pipelines.forecast_service.fetch_daily_sales_from_db", lambda conn: df_dummy)

    mock_metrics = MagicMock(mae=0.20, wape=0.12)
    mock_report = MagicMock()
    mock_report.cutoff_date = cutoff
    mock_report.optimal_slow_thresholds = {7: 0.4, 30: 0.4}
    mock_report.baseline_report.top_sku_ids = {10}  # SKU 10 là top SKU (bán chạy)
    mock_report.baseline_report.best_baseline = {7: "rolling_mean_28"}
    mock_report.baseline_report.results = {7: {"rolling_mean_28": {"overall": mock_metrics}}}
    mock_report.lgb_results_clean = {7: {"lgb_tweedie": {"overall": mock_metrics}}}

    # Model trả về 0.15 (< ngưỡng 0.4) cho Top SKU
    class DummyTopModel:
        def predict(self, df):
            return np.full(len(df), 0.15)

    dummy_evaluator = lambda df_daily, horizons: (mock_report, {7: DummyTopModel(), 30: DummyTopModel()})

    captured_results = []
    monkeypatch.setattr(
        "app.pipelines.forecast_service.save_forecast_results",
        lambda conn, run_id, results_df: captured_results.append(results_df.copy()) or len(results_df),
    )
    monkeypatch.setattr(
        "app.pipelines.forecast_service.complete_forecast_run",
        lambda conn, run_id, mae, wape, baseline_mae, baseline_wape: None,
    )

    execute_background_forecast(run_id=101, evaluate_pipeline_fn=dummy_evaluator)

    assert len(captured_results) == 1
    res_df = captured_results[0]
    # SKU 10 là top SKU nên không bị ép về 0.0, giữ nguyên 0.15
    assert (res_df["predicted_qty"] == 0.15).all(), "Kỳ vọng Top SKU giữ nguyên 0.15, không bị ép về 0"


def test_execute_background_forecast_predictions_non_negative(monkeypatch):
    """4. Giá trị dự báo không âm: các giá trị âm từ mô hình phải được chặn dưới tại 0.0."""
    mock_conn = MagicMock()
    monkeypatch.setattr("app.pipelines.forecast_service.get_db_connection", lambda: mock_conn)
    mock_conn.__enter__.return_value = mock_conn

    cutoff = datetime(2026, 10, 1).date()
    df_dummy = _build_dummy_sales(product_id=10)
    monkeypatch.setattr("app.pipelines.forecast_service.fetch_daily_sales_from_db", lambda conn: df_dummy)

    mock_metrics = MagicMock(mae=0.20, wape=0.12)
    mock_report = MagicMock()
    mock_report.cutoff_date = cutoff
    mock_report.optimal_slow_thresholds = {7: 0.4, 30: 0.4}
    mock_report.baseline_report.top_sku_ids = {10}
    mock_report.baseline_report.best_baseline = {7: "rolling_mean_28"}
    mock_report.baseline_report.results = {7: {"rolling_mean_28": {"overall": mock_metrics}}}
    mock_report.lgb_results_clean = {7: {"lgb_tweedie": {"overall": mock_metrics}}}

    # Model trả về giá trị âm -1.5
    class DummyNegativeModel:
        def predict(self, df):
            return np.full(len(df), -1.5)

    dummy_evaluator = lambda df_daily, horizons: (mock_report, {7: DummyNegativeModel(), 30: DummyNegativeModel()})

    captured_results = []
    monkeypatch.setattr(
        "app.pipelines.forecast_service.save_forecast_results",
        lambda conn, run_id, results_df: captured_results.append(results_df.copy()) or len(results_df),
    )
    monkeypatch.setattr(
        "app.pipelines.forecast_service.complete_forecast_run",
        lambda conn, run_id, mae, wape, baseline_mae, baseline_wape: None,
    )

    execute_background_forecast(run_id=102, evaluate_pipeline_fn=dummy_evaluator)

    assert len(captured_results) == 1
    res_df = captured_results[0]
    assert (res_df["predicted_qty"] >= 0.0).all(), "Tất cả dự báo phải không âm"
    assert (res_df["predicted_qty"] == 0.0).all(), "Giá trị âm phải được clip về 0.0"


def test_execute_background_forecast_explicit_threshold_override(monkeypatch):
    """Kiểm tra khi truyền tường minh optimal_threshold=0.0 thì không áp dụng ngưỡng."""
    mock_conn = MagicMock()
    monkeypatch.setattr("app.pipelines.forecast_service.get_db_connection", lambda: mock_conn)
    mock_conn.__enter__.return_value = mock_conn

    cutoff = datetime(2026, 10, 1).date()
    df_dummy = _build_dummy_sales(product_id=20)
    monkeypatch.setattr("app.pipelines.forecast_service.fetch_daily_sales_from_db", lambda conn: df_dummy)

    mock_metrics = MagicMock(mae=0.20, wape=0.12)
    mock_report = MagicMock()
    mock_report.cutoff_date = cutoff
    mock_report.optimal_slow_thresholds = {7: 0.4, 30: 0.4}
    mock_report.baseline_report.top_sku_ids = set()
    mock_report.baseline_report.best_baseline = {7: "rolling_mean_28"}
    mock_report.baseline_report.results = {7: {"rolling_mean_28": {"overall": mock_metrics}}}
    mock_report.lgb_results_clean = {7: {"lgb_tweedie": {"overall": mock_metrics}}}

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

    # Truyền tường minh optimal_threshold=0.0
    execute_background_forecast(run_id=103, optimal_threshold=0.0, evaluate_pipeline_fn=dummy_evaluator)

    assert len(captured_results) == 1
    res_df = captured_results[0]
    assert (res_df["predicted_qty"] == 0.15).all(), "Kỳ vọng giữ nguyên giá trị 0.15 khi optimal_threshold=0.0"


def test_execute_background_forecast_generates_future_target_dates_D1_to_D30(monkeypatch):
    """Test AC-01 & AC-02 của Issue #298:
    Dữ liệu tới ngày D -> kết quả forecast_result có đúng target_date từ D+1 đến D+30,
    đủ cả 2 tầm horizon 7 và 30, không có giá trị âm.
    """
    mock_conn = MagicMock()
    monkeypatch.setattr("app.pipelines.forecast_service.get_db_connection", lambda: mock_conn)
    mock_conn.__enter__.return_value = mock_conn

    # Tạo dummy sales kết thúc tại ngày D = 2026-10-31
    product_id = 10
    df_dummy = _build_dummy_sales(product_id=product_id)
    last_date = df_dummy["sale_date"].max()
    assert last_date == date(2026, 10, 31)

    monkeypatch.setattr("app.pipelines.forecast_service.fetch_daily_sales_from_db", lambda conn: df_dummy)

    mock_metrics = MagicMock(mae=0.22, wape=0.14)
    mock_report = MagicMock()
    mock_report.cutoff_date = date(2026, 10, 1)  # Cutoff backtest trong quá khứ
    mock_report.optimal_slow_thresholds = {7: 0.4, 30: 0.4}
    mock_report.baseline_report.top_sku_ids = {10}
    mock_report.baseline_report.best_baseline = {7: "rolling_mean_28"}
    mock_report.baseline_report.results = {7: {"rolling_mean_28": {"overall": mock_metrics}}}
    mock_report.lgb_results_clean = {7: {"lgb_tweedie": {"overall": mock_metrics}}}
    mock_report.best_objective = {7: "tweedie", 30: "tweedie"}

    class DummyProdModel:
        def __init__(self, val=2.5):
            self.val = val
        def predict(self, df):
            return np.full(len(df), self.val)

    dummy_evaluator = lambda df_daily, horizons: (mock_report, {7: DummyProdModel(1.0), 30: DummyProdModel(1.0)})

    captured_results = []
    completed_runs = []
    monkeypatch.setattr(
        "app.pipelines.forecast_service.save_forecast_results",
        lambda conn, run_id, results_df: captured_results.append(results_df.copy()) or len(results_df),
    )
    monkeypatch.setattr(
        "app.pipelines.forecast_service.complete_forecast_run",
        lambda conn, run_id, mae, wape, baseline_mae, baseline_wape: completed_runs.append((run_id, mae, wape)),
    )

    # Chạy background forecast
    execute_background_forecast(
        run_id=298,
        evaluate_pipeline_fn=dummy_evaluator,
        retrain_models_fn=lambda builder, horizons: {7: DummyProdModel(3.0), 30: DummyProdModel(3.0)},
    )

    assert len(captured_results) == 1
    res_df = captured_results[0]

    # 1. Kiểm tra khoảng target_date từ D+1 (2026-11-01) đến D+30 (2026-11-30)
    min_target = res_df["target_date"].min()
    max_target = res_df["target_date"].max()
    assert min_target == date(2026, 11, 1), f"target_date bắt đầu từ {min_target}, kỳ vọng 2026-11-01"
    assert max_target == date(2026, 11, 30), f"target_date kết thúc tại {max_target}, kỳ vọng 2026-11-30"

    # 2. Kiểm tra các dòng horizon = 7: 7 ngày (D+1..D+7)
    df_h7 = res_df[res_df["horizon"] == 7]
    assert len(df_h7) == 7 * 1  # 1 series x 7 ngày
    assert df_h7["target_date"].min() == date(2026, 11, 1)
    assert df_h7["target_date"].max() == date(2026, 11, 7)

    # 3. Kiểm tra các dòng horizon = 30: 30 ngày (D+1..D+30)
    df_h30 = res_df[res_df["horizon"] == 30]
    assert len(df_h30) == 30 * 1  # 1 series x 30 ngày
    assert df_h30["target_date"].min() == date(2026, 11, 1)
    assert df_h30["target_date"].max() == date(2026, 11, 30)

    # 4. Tổng số dòng = 37 dòng
    assert len(res_df) == 37

    # 5. Không có giá trị âm
    assert (res_df["predicted_qty"] >= 0.0).all()

    # 6. forecast_run vẫn lưu MAE/WAPE của đánh giá lùi
    assert len(completed_runs) == 1
    assert completed_runs[0] == (298, 0.22, 0.14)


def test_execute_background_forecast_supports_two_arg_monkeypatched_evaluator(monkeypatch):
    """Đảm bảo monkeypatch evaluate_lightgbm_pipeline với chữ ký 2 tham số (df_daily, horizons) không lỗi TypeError (#423)."""
    mock_conn = MagicMock()
    monkeypatch.setattr("app.pipelines.forecast_service.get_db_connection", lambda: mock_conn)
    mock_conn.__enter__.return_value = mock_conn

    df_dummy = _build_dummy_sales(product_id=10)
    monkeypatch.setattr("app.pipelines.forecast_service.fetch_daily_sales_from_db", lambda conn: df_dummy)

    mock_metrics = MagicMock(mae=0.25, wape=0.15)
    mock_report = MagicMock()
    mock_report.cutoff_date = date(2026, 10, 1)
    mock_report.optimal_slow_thresholds = {7: 0.4, 30: 0.4}
    mock_report.baseline_report.top_sku_ids = {10}
    mock_report.baseline_report.best_baseline = {7: "rolling_mean_28"}
    mock_report.baseline_report.results = {7: {"rolling_mean_28": {"overall": mock_metrics}}}
    mock_report.lgb_results_clean = {7: {"lgb_tweedie": {"overall": mock_metrics}}}
    mock_report.best_objective = {7: "tweedie", 30: "tweedie"}

    class DummyProdModel:
        def predict(self, df):
            return np.full(len(df), 2.0)

    monkeypatch.setattr(
        "app.pipelines.forecast_service.evaluate_lightgbm_pipeline",
        lambda df_daily, horizons: (mock_report, {7: DummyProdModel(), 30: DummyProdModel()}),
    )

    completed_runs = []
    monkeypatch.setattr(
        "app.pipelines.forecast_service.save_forecast_results",
        lambda conn, run_id, results_df: len(results_df),
    )
    monkeypatch.setattr(
        "app.pipelines.forecast_service.complete_forecast_run",
        lambda conn, run_id, mae, wape, baseline_mae, baseline_wape: completed_runs.append((run_id, mae, wape)),
    )

    execute_background_forecast(
        run_id=423,
        retrain_models_fn=lambda builder, horizons: {7: DummyProdModel(), 30: DummyProdModel()},
        event_publisher_fn=lambda **kwargs: True,
    )
    assert completed_runs == [(423, 0.25, 0.15)]




