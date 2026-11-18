"""Tests for FastAPI app mounting forecast routes (#114, #311)."""

from datetime import datetime
from unittest.mock import MagicMock, patch
from zoneinfo import ZoneInfo
from fastapi.testclient import TestClient

from app.main import create_app


def test_app_includes_forecast_endpoints(auth_headers):
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur
    mock_cur.fetchone.return_value = None

    mock_factory = MagicMock()
    mock_factory.return_value.__enter__.return_value = mock_conn

    application = create_app(mock_factory)
    client = TestClient(application)

    # 1. Health & Live endpoints remain public (no token needed)
    res_live = client.get("/livez")
    assert res_live.status_code == 200
    assert res_live.json() == {"status": "UP"}

    # 2. Endpoint /forecast/runs/{id} without auth header returns 401
    res_unauthorized = client.get("/forecast/runs/999")
    assert res_unauthorized.status_code == 401
    assert res_unauthorized.headers.get("WWW-Authenticate") == "Bearer"
    assert res_unauthorized.json()["code"] == "UNAUTHORIZED"

    # 3. With valid Bearer token, router proceeds and returns 404 when not found
    res_get = client.get("/forecast/runs/999", headers=auth_headers)
    assert res_get.status_code == 404
    assert res_get.json()["code"] == "NOT_FOUND"


@patch("app.scheduler.forecast_scheduler.get_current_vn_time")
@patch("app.scheduler.forecast_scheduler.has_run_executed_today")
def test_lifespan_startup_completes_without_synchronous_runner_call(
    mock_has_run,
    mock_get_time,
    monkeypatch,
):
    """Kiểm tra lifespan khởi động xong mà runner_fn chưa bị gọi đồng bộ (#118).

    Khi container khởi động trong khung 02:00 - 06:00 mà hôm nay chưa có run:
    - Lifespan khởi động và yield ngay lập tức.
    - runner_fn KHÔNG bị gọi đồng bộ trước khi yield.
    - /healthz và /livez sẵn sàng phục vụ HTTP 200 ngay.
    """
    monkeypatch.setenv("FORECAST_SCHEDULER_ENABLED", "true")
    monkeypatch.setenv("FORECAST_DB_USER", "forecast_ro")

    # Giả lập thời gian 02:15 AM (nằm trong khung chạy bù 02:00-06:00)
    mock_get_time.return_value = datetime(2026, 11, 18, 2, 15, tzinfo=ZoneInfo("Asia/Ho_Chi_Minh"))
    mock_has_run.return_value = False

    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur
    # database_ready returns True
    mock_cur.fetchone.return_value = (True,)

    mock_factory = MagicMock()
    mock_factory.return_value.__enter__.return_value = mock_conn

    lifespan_has_yielded = False
    runner_called_before_yield = False

    def runner_fn_spy(*args, **kwargs):
        nonlocal runner_called_before_yield
        if not lifespan_has_yielded:
            runner_called_before_yield = True

    app = create_app(connection_factory=mock_factory, runner_fn=runner_fn_spy)

    with TestClient(app) as client:
        # Tại thời điểm TestClient bước vào context (sau khi lifespan đã yield):
        # runner_fn tuyệt đối không được gọi đồng bộ trong quá trình startup
        assert runner_called_before_yield is False
        lifespan_has_yielded = True

        # Endpoints phản hồi ngay lập tức, không bị treo
        res_live = client.get("/livez")
        assert res_live.status_code == 200
        assert res_live.json() == {"status": "UP"}

        res_health = client.get("/healthz")
        assert res_health.status_code == 200
        assert res_health.json() == {"status": "UP"}
