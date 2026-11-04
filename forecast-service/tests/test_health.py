"""Readiness must fail closed and never expose DB exception details."""
import os
import sys
from pathlib import Path
from unittest.mock import MagicMock

from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.main import create_app, database_ready  # noqa: E402


def connection_returning(value):
    factory = MagicMock()
    cursor = factory.return_value.__enter__.return_value.cursor.return_value.__enter__.return_value
    cursor.fetchone.return_value = (value,)
    return factory, cursor


def test_healthy_requires_restricted_role_and_db_access(monkeypatch):
    monkeypatch.setenv("FORECAST_DB_USER", "forecast_ro")
    factory, cursor = connection_returning(True)
    response = TestClient(create_app(factory)).get("/healthz")
    assert response.status_code == 200
    assert response.json() == {"status": "UP"}
    sql, params = cursor.execute.call_args.args
    assert params == ("forecast_ro",)
    assert "rolsuper" in sql and "TRUNCATE" in sql and "has_table_privilege" in sql


def test_wrong_or_overprivileged_role_is_unhealthy(monkeypatch):
    monkeypatch.setenv("FORECAST_DB_USER", "forecast_ro")
    factory, _ = connection_returning(False)
    response = TestClient(create_app(factory)).get("/healthz")
    assert response.status_code == 503
    assert response.json() == {"status": "DOWN"}


def test_db_failure_does_not_leak_secret_and_liveness_still_works(monkeypatch):
    monkeypatch.setenv("FORECAST_DB_USER", "forecast_ro")
    def broken():
        raise RuntimeError("password=must-never-appear")
    client = TestClient(create_app(broken))
    response = client.get("/healthz")
    assert response.status_code == 503
    assert "must-never-appear" not in response.text
    assert client.get("/livez").status_code == 200
