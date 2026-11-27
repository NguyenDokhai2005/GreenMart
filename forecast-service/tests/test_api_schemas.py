"""Unit tests for forecast API Pydantic schemas (#114)."""

from datetime import datetime, timezone

from app.api.schemas import (
    ErrorResponse,
    ForecastRunCreatedResponse,
    ForecastRunDetailResponse,
)


def test_forecast_run_created_response_camel_case():
    now = datetime.now(timezone.utc)
    res = ForecastRunCreatedResponse(run_id=42, status="RUNNING", started_at=now)
    data = res.model_dump(by_alias=True)

    assert data == {
        "runId": 42,
        "status": "RUNNING",
        "startedAt": now,
    }
    assert "run_id" not in data
    assert "started_at" not in data


def test_forecast_run_detail_response_camel_case():
    now = datetime.now(timezone.utc)
    res = ForecastRunDetailResponse(
        run_id=42,
        status="DONE",
        started_at=now,
        finished_at=now,
        model_version="LightGBM-Tweedie-H7-H30",
        mae=0.256,
        wape=0.15,
        baseline_mae=0.2743,
        baseline_wape=0.1726,
    )
    data = res.model_dump(by_alias=True)

    assert data["runId"] == 42
    assert data["status"] == "DONE"
    assert data["startedAt"] == now
    assert data["finishedAt"] == now
    assert data["modelVersion"] == "LightGBM-Tweedie-H7-H30"
    assert data["mae"] == 0.256
    assert data["wape"] == 0.15
    assert data["baselineMae"] == 0.2743
    assert data["baselineWape"] == 0.1726

    # Also verify population by alias works
    from_alias = ForecastRunDetailResponse.model_validate({
        "runId": 99,
        "status": "FAILED",
        "startedAt": now.isoformat(),
        "modelVersion": "LightGBM-v1",
    })
    assert from_alias.run_id == 99
    assert from_alias.status == "FAILED"


def test_error_response_structure():
    err = ErrorResponse(code="CONFLICT", message="A forecast run is already running.")
    data = err.model_dump()
    assert data == {
        "code": "CONFLICT",
        "message": "A forecast run is already running.",
    }


def test_forecast_run_detail_response_legacy_and_running_null_safety():
    now = datetime.now(timezone.utc)
    legacy_run = ForecastRunDetailResponse(
        run_id=12,
        status="DONE",
        started_at=now,
        finished_at=now,
        model_version="LightGBM-Tweedie-H7-H30",
        mae=0.2574,
        wape=1.6252,
        baseline_mae=0.2575,
        baseline_wape=1.6261,
        legacy=True,
    )
    legacy_dump = legacy_run.model_dump(by_alias=True)
    assert legacy_dump["legacy"] is True
    assert legacy_dump["metricHorizon"] is None
    assert legacy_dump["aggregation"] is None
    assert legacy_dump["metricsBreakdown"] is None

    running_run = ForecastRunDetailResponse(
        run_id=13,
        status="RUNNING",
        started_at=now,
        model_version="LightGBM-Tweedie-H7-H30",
    )
    running_dump = running_run.model_dump(by_alias=True)
    assert running_dump["mae"] is None
    assert running_dump["wape"] is None
    assert running_dump["legacy"] is False
    assert running_dump["metricsBreakdown"] is None
