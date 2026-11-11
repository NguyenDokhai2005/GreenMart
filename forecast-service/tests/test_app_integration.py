"""Tests for FastAPI app mounting forecast routes (#114)."""

from unittest.mock import MagicMock
from fastapi.testclient import TestClient

from app.main import create_app


def test_app_includes_forecast_endpoints():
    mock_conn = MagicMock()
    mock_cur = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cur
    mock_cur.fetchone.return_value = None

    mock_factory = MagicMock()
    mock_factory.return_value.__enter__.return_value = mock_conn

    application = create_app(mock_factory)
    client = TestClient(application)

    # Verify endpoints respond properly through client
    res_get = client.get("/forecast/runs/999")
    # When record not found, router returns 404
    assert res_get.status_code == 404
    assert res_get.json()["code"] == "NOT_FOUND"
