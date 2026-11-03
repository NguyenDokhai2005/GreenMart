"""Pytest configuration and shared fixtures for forecast-service tests."""

import sys
from pathlib import Path

# Đảm bảo thư mục gốc forecast-service nằm trong sys.path để import app và scripts
SERVICE_ROOT = Path(__file__).resolve().parent.parent
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))


def pytest_configure(config):
    """Đăng ký marker custom cho pytest."""
    config.addinivalue_line(
        "markers", "integration: mark test as integration test requiring PostgreSQL connection"
    )
