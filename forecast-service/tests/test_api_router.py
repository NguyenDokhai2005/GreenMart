"""Unit and mock tests for forecast API endpoints (#114, #311)."""

from contextlib import contextmanager
from datetime import date, datetime, timedelta, timezone
from unittest.mock import MagicMock
from zoneinfo import ZoneInfo

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.router import create_forecast_router, register_auth_exception_handler
from app.pipelines.forecast_service import (
    ActiveRunConflictError,
    EmptyDailySalesError,
    StaleDailySalesError,
)


@pytest.fixture
def test_setup():
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur
    mock_cur.fetchone.return_value = (date(2025, 11, 1), date(2099, 1, 1))
    mock_bg_runner = MagicMock()
    mock_etl = MagicMock(return_value={"total_rows": 100})

    @contextmanager
    def mock_conn_factory():
        yield mock_conn

    app = FastAPI()
    register_auth_exception_handler(app)
    router = create_forecast_router(
        connection_factory=mock_conn_factory,
        background_runner=mock_bg_runner,
        etl_pipeline_fn=mock_etl,
    )
    app.include_router(router)
    client = TestClient(app)

    return {
        "client": client,
        "conn": mock_conn,
        "bg_runner": mock_bg_runner,
        "etl_runner": mock_etl,
    }


def test_tc_sec_01_post_run_missing_auth_header(test_setup):
    """TC-SEC-01: POST /forecast/run không có header Authorization nhận 401."""
    client = test_setup["client"]
    response = client.post("/forecast/run")

    assert response.status_code == 401
    assert response.headers.get("WWW-Authenticate") == "Bearer"
    assert response.json() == {
        "code": "UNAUTHORIZED",
        "message": "Yêu cầu header Authorization: Bearer <token> hợp lệ.",
    }


def test_tc_sec_02_get_run_missing_auth_header(test_setup):
    """TC-SEC-02: GET /forecast/runs/1 không có header Authorization nhận 401."""
    client = test_setup["client"]
    response = client.get("/forecast/runs/1")

    assert response.status_code == 401
    assert response.headers.get("WWW-Authenticate") == "Bearer"
    assert response.json() == {
        "code": "UNAUTHORIZED",
        "message": "Yêu cầu header Authorization: Bearer <token> hợp lệ.",
    }


def test_tc_sec_03_invalid_auth_scheme(test_setup):
    """TC-SEC-03: Header Authorization không dùng Bearer scheme nhận 401."""
    client = test_setup["client"]
    response = client.post(
        "/forecast/run",
        headers={"Authorization": "Basic dXNlcjpwYXNz"},
    )

    assert response.status_code == 401
    assert response.headers.get("WWW-Authenticate") == "Bearer"
    assert response.json() == {
        "code": "UNAUTHORIZED",
        "message": "Yêu cầu header Authorization: Bearer <token> hợp lệ.",
    }


def test_tc_sec_04_wrong_bearer_token(test_setup):
    """TC-SEC-04: Bearer token không khớp nhận 401."""
    client = test_setup["client"]
    response = client.post(
        "/forecast/run",
        headers={"Authorization": "Bearer wrong-secret-token"},
    )

    assert response.status_code == 401
    assert response.headers.get("WWW-Authenticate") == "Bearer"
    assert response.json() == {
        "code": "UNAUTHORIZED",
        "message": "Token xác thực nội bộ không chính xác.",
    }


def test_tc_sec_05_empty_bearer_token(test_setup):
    """TC-SEC-05: Header Bearer không có token (rỗng) nhận 401."""
    client = test_setup["client"]
    response = client.post(
        "/forecast/run",
        headers={"Authorization": "Bearer "},
    )

    assert response.status_code == 401
    assert response.headers.get("WWW-Authenticate") == "Bearer"
    assert response.json()["code"] == "UNAUTHORIZED"


def test_tc_sec_06_valid_bearer_token(test_setup, auth_headers, monkeypatch):
    """TC-SEC-06: Header Bearer token đúng vượt qua xác thực."""
    now = datetime(2026, 11, 11, 15, 0, 0, tzinfo=timezone.utc)
    monkeypatch.setattr(
        "app.api.router.start_forecast_run",
        lambda conn, model_version, expected_cutoff_date=None: (10, now),
    )
    client = test_setup["client"]
    response = client.post("/forecast/run", headers=auth_headers)
    assert response.status_code == 202
    assert response.json()["runId"] == 10


def test_tc_sec_07_fail_closed_missing_env(test_setup, auth_headers, monkeypatch):
    """TC-SEC-07: Cơ chế Fail-Closed khi FORECAST_INTERNAL_TOKEN không tồn tại."""
    monkeypatch.delenv("FORECAST_INTERNAL_TOKEN", raising=False)
    client = test_setup["client"]
    response = client.post("/forecast/run", headers=auth_headers)

    assert response.status_code == 401
    assert response.headers.get("WWW-Authenticate") == "Bearer"
    assert response.json() == {
        "code": "UNAUTHORIZED",
        "message": "Dịch vụ chưa được cấu hình token xác thực nội bộ.",
    }


def test_tc_sec_08_fail_closed_empty_env(test_setup, auth_headers, monkeypatch):
    """TC-SEC-08: Cơ chế Fail-Closed khi FORECAST_INTERNAL_TOKEN rỗng."""
    monkeypatch.setenv("FORECAST_INTERNAL_TOKEN", "   ")
    client = test_setup["client"]
    response = client.post("/forecast/run", headers=auth_headers)

    assert response.status_code == 401
    assert response.headers.get("WWW-Authenticate") == "Bearer"
    assert response.json() == {
        "code": "UNAUTHORIZED",
        "message": "Dịch vụ chưa được cấu hình token xác thực nội bộ.",
    }


def test_post_forecast_run_success(test_setup, auth_headers, monkeypatch):
    now = datetime(2026, 11, 11, 15, 0, 0, tzinfo=timezone.utc)
    today_vn = datetime.now(ZoneInfo("Asia/Ho_Chi_Minh")).date()
    expected_cutoff = today_vn - timedelta(days=1)

    # Mock start_forecast_run returning (55, now)
    monkeypatch.setattr(
        "app.api.router.start_forecast_run",
        lambda conn, model_version, expected_cutoff_date=None: (55, now),
    )

    client = test_setup["client"]
    bg_runner = test_setup["bg_runner"]

    response = client.post("/forecast/run", headers=auth_headers)

    assert response.status_code == 202
    data = response.json()
    assert data["runId"] == 55
    assert data["status"] == "RUNNING"
    assert "startedAt" in data
    # Verify background runner was queued with run_id 55, expected_cutoff_date, run_etl_before=True
    bg_runner.assert_called_once_with(
        run_id=55,
        expected_cutoff_date=expected_cutoff,
        run_etl_before=True,
    )


def test_post_forecast_run_conflict_409(test_setup, auth_headers, monkeypatch):
    def raise_conflict(*args, **kwargs):
        raise ActiveRunConflictError("Đã có lượt chạy run_id=41 đang chạy.", active_id=41)

    monkeypatch.setattr("app.api.router.start_forecast_run", raise_conflict)

    client = test_setup["client"]
    bg_runner = test_setup["bg_runner"]

    response = client.post("/forecast/run", headers=auth_headers)

    assert response.status_code == 409
    data = response.json()
    assert data["code"] == "CONFLICT"
    assert "run_id=41" in data["message"]
    bg_runner.assert_not_called()


def test_post_forecast_run_empty_daily_sales_409(test_setup, auth_headers, monkeypatch):
    def raise_empty(*args, **kwargs):
        raise EmptyDailySalesError("Bảng daily_sales hiện đang trống.")

    monkeypatch.setattr("app.api.router.ensure_fresh_daily_sales", raise_empty)

    client = test_setup["client"]
    bg_runner = test_setup["bg_runner"]

    response = client.post("/forecast/run", headers=auth_headers)

    assert response.status_code == 409
    data = response.json()
    assert data["code"] == "EMPTY_DAILY_SALES"
    assert "daily_sales" in data["message"]
    bg_runner.assert_not_called()


def test_post_forecast_run_stale_daily_sales_409(test_setup, auth_headers, monkeypatch):
    """Issue #417: Khi dữ liệu daily_sales bị cũ, API trả 409 EMPTY_DAILY_SALES để backend client ánh xạ chuẩn."""
    def raise_stale(*args, **kwargs):
        raise StaleDailySalesError("Dữ liệu daily_sales chỉ mới tới 2026-11-20, chưa đủ mới tới cutoff 2026-11-22.")

    monkeypatch.setattr("app.api.router.ensure_fresh_daily_sales", raise_stale)

    client = test_setup["client"]
    bg_runner = test_setup["bg_runner"]

    response = client.post("/forecast/run", headers=auth_headers)

    assert response.status_code == 409
    data = response.json()
    assert data["code"] == "EMPTY_DAILY_SALES"
    assert "2026-11-20" in data["message"]
    bg_runner.assert_not_called()


def test_post_forecast_run_etl_fails_to_reach_cutoff_returns_409(test_setup, auth_headers, monkeypatch):
    """Mục 2 (Issue #417): Khi ETL nạp xong nhưng vẫn không đạt T - 1, API trả về 409 EMPTY_DAILY_SALES."""
    monkeypatch.setattr(
        "app.api.router.ensure_fresh_daily_sales",
        MagicMock(side_effect=StaleDailySalesError("Dữ liệu daily_sales sau ETL vẫn bị cũ: cutoff 2026-11-20 < 2026-11-22.")),
    )
    client = test_setup["client"]
    bg_runner = test_setup["bg_runner"]

    response = client.post("/forecast/run", headers=auth_headers)

    assert response.status_code == 409
    data = response.json()
    assert data["code"] == "EMPTY_DAILY_SALES"
    assert "vẫn bị cũ" in data["message"]
    bg_runner.assert_not_called()


def test_get_forecast_run_detail_success(test_setup, auth_headers, monkeypatch):
    now = datetime(2026, 11, 11, 15, 0, 0, tzinfo=timezone.utc)
    mock_detail = {
        "run_id": 55,
        "status": "DONE",
        "started_at": now,
        "finished_at": now,
        "model_version": "LightGBM-Tweedie-H7-H30",
        "mae": 0.256,
        "wape": 0.150,
        "baseline_mae": 0.2743,
        "baseline_wape": 0.1726,
    }
    monkeypatch.setattr("app.api.router.get_forecast_run_detail", lambda conn, run_id: mock_detail)

    client = test_setup["client"]
    response = client.get("/forecast/runs/55", headers=auth_headers)

    assert response.status_code == 200
    data = response.json()
    assert data["runId"] == 55
    assert data["status"] == "DONE"
    assert data["modelVersion"] == "LightGBM-Tweedie-H7-H30"
    assert data["mae"] == 0.256
    assert data["wape"] == 0.150
    assert data["baselineMae"] == 0.2743
    assert data["baselineWape"] == 0.1726


def test_get_forecast_run_detail_not_found(test_setup, auth_headers, monkeypatch):
    monkeypatch.setattr("app.api.router.get_forecast_run_detail", lambda conn, run_id: None)

    client = test_setup["client"]
    response = client.get("/forecast/runs/999", headers=auth_headers)

    assert response.status_code == 404
    data = response.json()
    assert data["code"] == "NOT_FOUND"
    assert "999" in data["message"]
