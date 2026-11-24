from unittest.mock import MagicMock

import pytest
from fastapi.testclient import TestClient

from app.main import create_app


@pytest.mark.parametrize("value", [None, "false", "anything"])
def test_runtime_docs_are_disabled_by_default(monkeypatch, value):
    if value is None:
        monkeypatch.delenv("FORECAST_DOCS_ENABLED", raising=False)
    else:
        monkeypatch.setenv("FORECAST_DOCS_ENABLED", value)
    client = TestClient(create_app(MagicMock()))
    for path in ("/openapi.json", "/docs", "/redoc"):
        assert client.get(path).status_code == 404
    assert client.get("/forecast/runs/1").status_code == 401
    assert client.get("/livez").json() == {"status": "UP"}


def test_development_can_explicitly_enable_docs(monkeypatch):
    monkeypatch.setenv("FORECAST_DOCS_ENABLED", "true")
    client = TestClient(create_app(MagicMock()))
    assert client.get("/openapi.json").status_code == 200
    assert client.get("/openapi.json").json()["paths"]
    assert client.get("/docs").status_code == 200
    assert client.get("/redoc").status_code == 200
    assert client.get("/forecast/runs/1").status_code == 401
