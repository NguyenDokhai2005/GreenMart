"""Module quản lý kết nối cơ sở dữ liệu PostgreSQL cho forecast-service.

Đọc cấu hình từ biến môi trường (.env) và hỗ trợ quản lý transaction,
tự động đóng kết nối sau khi sử dụng.
"""

from __future__ import annotations

from contextlib import contextmanager
import os
from pathlib import Path
from typing import Generator, Optional

from dotenv import load_dotenv
import psycopg2
from psycopg2.extensions import connection as PgConnection

# Nạp file .env từ thư mục gốc của repository nếu có
_SERVICE_DIR = Path(__file__).resolve().parents[2]
_REPO_ROOT = _SERVICE_DIR.parent
_ENV_PATH = _REPO_ROOT / ".env"
if _ENV_PATH.exists():
    load_dotenv(_ENV_PATH)
else:
    load_dotenv()  # Nạp mặc định theo môi trường hiện tại


def get_connection_params() -> dict[str, str | int]:
    """Lấy tham số kết nối PostgreSQL từ biến môi trường (không in secrets)."""
    return {
        "host": os.getenv("POSTGRES_HOST", "localhost"),
        "port": int(os.getenv("POSTGRES_PORT", "5432")),
        "dbname": os.getenv("POSTGRES_DB", "greenmart"),
        "user": os.getenv("FORECAST_DB_USER", os.getenv("POSTGRES_USER", "forecast_ro")),
        "password": os.getenv("FORECAST_DB_PASSWORD", os.getenv("POSTGRES_PASSWORD", "")),
    }


@contextmanager
def get_db_connection() -> Generator[PgConnection, None, None]:
    """Context manager cung cấp kết nối PostgreSQL và tự động đóng/rollback khi có lỗi."""
    params = get_connection_params()
    conn: Optional[PgConnection] = None
    try:
        conn = psycopg2.connect(**params)
        yield conn
    except Exception:
        if conn and not conn.closed:
            conn.rollback()
        raise
    finally:
        if conn and not conn.closed:
            conn.close()


def test_connection() -> bool:
    """Kiểm tra kết nối tới cơ sở dữ liệu PostgreSQL (trả về True nếu thành công)."""
    try:
        with get_db_connection() as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT 1;")
                res = cur.fetchone()
                return res is not None and res[0] == 1
    except Exception:
        return False
