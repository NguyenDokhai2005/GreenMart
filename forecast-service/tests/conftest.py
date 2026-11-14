"""Pytest configuration and shared fixtures for forecast-service tests."""

import os
import sys
from pathlib import Path

import pytest

# Đảm bảo thư mục gốc forecast-service nằm trong sys.path để import app và scripts
SERVICE_ROOT = Path(__file__).resolve().parent.parent
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

TEST_INTERNAL_TOKEN = "test-internal-token-123"
os.environ.setdefault("FORECAST_INTERNAL_TOKEN", TEST_INTERNAL_TOKEN)


def pytest_configure(config):
    """Đăng ký marker custom cho pytest."""
    config.addinivalue_line(
        "markers", "integration: mark test as integration test requiring PostgreSQL connection"
    )


@pytest.fixture(autouse=True)
def default_forecast_internal_token(monkeypatch):
    """Mặc định thiết lập FORECAST_INTERNAL_TOKEN cho test suite (#311)."""
    monkeypatch.setenv("FORECAST_INTERNAL_TOKEN", TEST_INTERNAL_TOKEN)


@pytest.fixture
def auth_headers():
    """Header xác thực Bearer token hợp lệ cho các request test (#311)."""
    return {"Authorization": f"Bearer {TEST_INTERNAL_TOKEN}"}
