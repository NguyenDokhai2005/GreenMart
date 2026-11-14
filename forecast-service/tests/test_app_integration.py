"""Tests for FastAPI app mounting forecast routes (#114, #311)."""

from unittest.mock import MagicMock
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
