"""Unit tests for RabbitMQ event publisher and error resilience (#118)."""

from datetime import date, datetime, timezone
import json
from unittest.mock import MagicMock, call, patch

import pika
import pytest

from app.events.publisher import (
    DEFAULT_EXCHANGE,
    DEFAULT_ROUTING_KEY,
    build_forecast_completed_payload,
    publish_forecast_completed,
)
from app.pipelines.forecast_service import (
    DEFAULT_MODEL_VERSION,
    execute_background_forecast,
)


def test_build_forecast_completed_payload():
    """Kiểm tra cấu trúc và các trường trong payload sự kiện forecast.completed."""
    payload = build_forecast_completed_payload(
        run_id=42,
        status="DONE",
        mae=0.25,
        wape=0.18,
        baseline_mae=0.30,
        baseline_wape=0.22,
        cutoff_date="2026-11-17",
        model_version="Test-Model-v1",
        event_id="test-event-uuid",
        timestamp="2026-11-18T02:00:00+07:00",
    )

    assert payload["eventId"] == "test-event-uuid"
    assert payload["eventType"] == "forecast.completed"
    assert payload["timestamp"] == "2026-11-18T02:00:00+07:00"
    assert payload["runId"] == 42
    assert payload["status"] == "DONE"
    assert payload["modelVersion"] == "Test-Model-v1"
    assert payload["mae"] == 0.25
    assert payload["wape"] == 0.18
    assert payload["baselineMae"] == 0.30
    assert payload["baselineWape"] == 0.22
    assert payload["cutoffDate"] == "2026-11-17"


def test_publish_forecast_completed_success():
    """TC-EVENT-01: Phát sự kiện thành công tới topic exchange greenmart.events."""
    mock_conn = MagicMock()
    mock_channel = MagicMock()
    mock_conn.channel.return_value = mock_channel

    mock_conn_factory = MagicMock(return_value=mock_conn)
    mock_sleep = MagicMock()

    success = publish_forecast_completed(
        run_id=101,
        status="DONE",
        mae=0.15,
        wape=0.12,
        baseline_mae=0.20,
        baseline_wape=0.18,
        cutoff_date="2026-11-17",
        connection_factory=mock_conn_factory,
        sleep_fn=mock_sleep,
    )

    assert success is True
    assert mock_conn_factory.call_count == 1
    mock_conn.channel.assert_called_once()

    # Kiểm tra khai báo exchange topic durable
    mock_channel.exchange_declare.assert_called_once_with(
        exchange=DEFAULT_EXCHANGE,
        exchange_type="topic",
        durable=True,
    )

    # Kiểm tra basic_publish
    mock_channel.basic_publish.assert_called_once()
    pub_args = mock_channel.basic_publish.call_args[1]
    assert pub_args["exchange"] == DEFAULT_EXCHANGE
    assert pub_args["routing_key"] == DEFAULT_ROUTING_KEY

    # Kiểm tra body JSON
    sent_payload = json.loads(pub_args["body"])
    assert sent_payload["runId"] == 101
    assert sent_payload["status"] == "DONE"
    assert sent_payload["mae"] == 0.15
    assert sent_payload["wape"] == 0.12
    assert sent_payload["cutoffDate"] == "2026-11-17"

    # Kiểm tra thuộc tính persistent
    props = pub_args["properties"]
    assert props.delivery_mode == 2
    assert props.content_type == "application/json"
    assert props.message_id == sent_payload["eventId"]

    mock_conn.close.assert_called_once()
    mock_sleep.assert_not_called()


def test_publish_forecast_completed_retry_then_success():
    """TC-EVENT-04: Kết nối chập chờn, lần 1 lỗi, lần 2 thành công (retry backoff)."""
    mock_conn = MagicMock()
    mock_channel = MagicMock()
    mock_conn.channel.return_value = mock_channel

    # Lần 1 ném exception, lần 2 trả mock_conn
    mock_conn_factory = MagicMock(side_effect=[Exception("Broker connection refused"), mock_conn])
    mock_sleep = MagicMock()

    success = publish_forecast_completed(
        run_id=102,
        connection_factory=mock_conn_factory,
        sleep_fn=mock_sleep,
        max_retries=3,
        retry_backoff_factor=1.0,
    )

    assert success is True
    assert mock_conn_factory.call_count == 2
    mock_sleep.assert_called_once_with(1.0)  # 1.0 * (2 ** 0)


def test_publish_forecast_completed_all_retries_fail_returns_false_no_crash():
    """TC-EVENT-03: RabbitMQ sập hoàn toàn -> thử 3 lần thất bại, trả False, không crash."""
    mock_conn_factory = MagicMock(side_effect=Exception("Connection timed out"))
    mock_sleep = MagicMock()

    success = publish_forecast_completed(
        run_id=103,
        connection_factory=mock_conn_factory,
        sleep_fn=mock_sleep,
        max_retries=3,
        retry_backoff_factor=1.0,
    )

    assert success is False
    assert mock_conn_factory.call_count == 3
    assert mock_sleep.call_count == 2
    assert mock_sleep.call_args_list == [call(1.0), call(2.0)]


@patch("app.pipelines.forecast_service.get_db_connection")
@patch("app.pipelines.forecast_service.fetch_daily_sales_from_db")
@patch("app.pipelines.forecast_service.save_forecast_results")
@patch("app.pipelines.forecast_service.complete_forecast_run")
def test_execute_background_forecast_calls_publisher_on_completion(
    mock_complete,
    mock_save,
    mock_fetch,
    mock_get_conn,
):
    """Pipeline dự báo chạy xong thành công thì tự động gọi publisher phát sự kiện."""
    mock_conn = MagicMock()
    mock_get_conn.return_value.__enter__.return_value = mock_conn

    # Tạo dummy sales df
    import pandas as pd
    import numpy as np

    dates = pd.date_range("2026-01-01", periods=35, freq="D").date
    sales_data = []
    for d in dates:
        sales_data.append({
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
    mock_fetch.return_value = pd.DataFrame(sales_data)

    mock_save.return_value = 70

    # Mock evaluate pipeline report
    mock_report = MagicMock()
    mock_lgb_metric = MagicMock(mae=0.22, wape=0.14)
    mock_base_metric = MagicMock(mae=0.28, wape=0.19)
    mock_report.lgb_results_clean = {7: {"lgb_tweedie": {"overall": mock_lgb_metric}}}
    mock_report.baseline_report.best_baseline = {7: "naive"}
    mock_report.baseline_report.results = {7: {"naive": {"overall": mock_base_metric}}}
    mock_report.baseline_report.top_sku_ids = {10}

    mock_eval = MagicMock(return_value=(mock_report, {7: MagicMock(), 30: MagicMock()}))
    mock_retrain = MagicMock(return_value={7: MagicMock(predict=lambda df: np.ones(len(df))), 30: MagicMock(predict=lambda df: np.ones(len(df)))})

    mock_publisher = MagicMock(return_value=True)

    execute_background_forecast(
        run_id=200,
        evaluate_pipeline_fn=mock_eval,
        retrain_models_fn=mock_retrain,
        event_publisher_fn=mock_publisher,
    )

    # Xác nhận complete_forecast_run được gọi
    mock_complete.assert_called_once_with(
        mock_conn,
        run_id=200,
        mae=0.22,
        wape=0.14,
        baseline_mae=0.28,
        baseline_wape=0.19,
    )
    mock_conn.commit.assert_called()

    # Xác nhận publisher được gọi với đúng thông tin
    mock_publisher.assert_called_once_with(
        run_id=200,
        status="DONE",
        mae=0.22,
        wape=0.14,
        baseline_mae=0.28,
        baseline_wape=0.19,
        cutoff_date=str(dates[-1]),
        model_version=DEFAULT_MODEL_VERSION,
    )


@patch("app.pipelines.forecast_service.get_db_connection")
@patch("app.pipelines.forecast_service.fetch_daily_sales_from_db")
@patch("app.pipelines.forecast_service.save_forecast_results")
@patch("app.pipelines.forecast_service.complete_forecast_run")
@patch("app.pipelines.forecast_service.fail_forecast_run")
def test_execute_background_forecast_survives_publisher_failure(
    mock_fail,
    mock_complete,
    mock_save,
    mock_fetch,
    mock_get_conn,
):
    """Khi publisher ném ngoại lệ, pipeline không crash, run vẫn DONE và KHÔNG bị đánh dấu FAILED."""
    mock_conn = MagicMock()
    mock_get_conn.return_value.__enter__.return_value = mock_conn

    import pandas as pd
    import numpy as np

    dates = pd.date_range("2026-01-01", periods=35, freq="D").date
    sales_data = [{"sale_date": d, "branch_id": 1, "product_id": 10, "qty_sold": 5, "qty_returned": 0, "qty_net": 5, "revenue": 50000, "promo_flag": False, "promo_discount_pct": 0.0, "stockout": False} for d in dates]
    mock_fetch.return_value = pd.DataFrame(sales_data)
    mock_save.return_value = 70

    mock_report = MagicMock()
    mock_lgb_metric = MagicMock(mae=0.22, wape=0.14)
    mock_base_metric = MagicMock(mae=0.28, wape=0.19)
    mock_report.lgb_results_clean = {7: {"lgb_tweedie": {"overall": mock_lgb_metric}}}
    mock_report.baseline_report.best_baseline = {7: "naive"}
    mock_report.baseline_report.results = {7: {"naive": {"overall": mock_base_metric}}}
    mock_report.baseline_report.top_sku_ids = {10}

    mock_eval = MagicMock(return_value=(mock_report, {7: MagicMock(), 30: MagicMock()}))
    mock_retrain = MagicMock(return_value={7: MagicMock(predict=lambda df: np.ones(len(df))), 30: MagicMock(predict=lambda df: np.ones(len(df)))})

    # Giả lập publisher ném ngoại lệ nghiêm trọng
    broken_publisher = MagicMock(side_effect=RuntimeError("RabbitMQ broker permanently crashed"))

    execute_background_forecast(
        run_id=201,
        evaluate_pipeline_fn=mock_eval,
        retrain_models_fn=mock_retrain,
        event_publisher_fn=broken_publisher,
    )

    # Run vẫn được complete_forecast_run và commit
    mock_complete.assert_called_once()
    mock_conn.commit.assert_called()

    # Tuyệt đối KHÔNG được gọi fail_forecast_run
    mock_fail.assert_not_called()


def test_build_forecast_completed_payload_with_standardized_metadata():
    """Sự kiện forecast.completed mang metadata tóm tắt gọn (#423), không nhúng blob metricsBreakdown."""
    payload = build_forecast_completed_payload(
        run_id=300,
        status="DONE",
        mae=0.8757,
        wape=0.7913,
        baseline_mae=1.0706,
        baseline_wape=0.9674,
        cutoff_date="2026-10-31",
        model_version=DEFAULT_MODEL_VERSION,
        metric_horizon=7,
        aggregation="HORIZON_AGGREGATE_POSTPROC",
        postprocess_version="slow-th-h7_0.4-h30_0.4-sh28-v1",
        metric_protocol_version="ac05-v2-h7-h30-postproc-20261125",
        evaluation_cutoff_date="2026-10-01",
        test_start_date="2026-10-02",
        test_end_date="2026-10-31",
        stockout_mode="EXCLUDED_CLEAN",
        baseline_name="rolling_mean_28",
    )

    assert payload["metricHorizon"] == 7
    assert payload["aggregation"] == "HORIZON_AGGREGATE_POSTPROC"
    assert payload["postprocessVersion"] == "slow-th-h7_0.4-h30_0.4-sh28-v1"
    assert payload["evaluationCutoffDate"] == "2026-10-01"
    assert payload["cutoffDate"] == "2026-10-31"
    assert payload["baselineName"] == "rolling_mean_28"
    assert "metricsBreakdown" not in payload
