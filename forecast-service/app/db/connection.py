"""Module quản lý kết nối cơ sở dữ liệu PostgreSQL cho forecast-service (psycopg 3).

Chỉ sử dụng tài khoản giới hạn của forecast (FORECAST_DB_USER / FORECAST_DB_PASSWORD),
tuyệt đối không sử dụng tài khoản chủ DB (POSTGRES_USER / POSTGRES_PASSWORD).
Đồng bộ theo đặc tả kiến trúc docs/design/architecture/README.md và PR #238.
"""

from __future__ import annotations

from contextlib import contextmanager
import os
from pathlib import Path
from typing import Generator, Optional

from dotenv import load_dotenv
import psycopg
from psycopg import Connection as PgConnection

# Nạp file .env từ thư mục gốc của repository nếu có
_SERVICE_DIR = Path(__file__).resolve().parents[2]
_REPO_ROOT = _SERVICE_DIR.parent
_ENV_PATH = _REPO_ROOT / ".env"
if _ENV_PATH.exists():
    load_dotenv(_ENV_PATH)
else:
    load_dotenv()  # Nạp mặc định theo môi trường hiện tại


def get_connection_params() -> dict[str, str | int]:
    """Lấy tham số kết nối PostgreSQL từ biến môi trường của forecast (không dùng tài khoản chủ DB)."""
    user = os.getenv("FORECAST_DB_USER")
    password = os.getenv("FORECAST_DB_PASSWORD")
    dbname = os.getenv("POSTGRES_DB", "greenmart")

    if not user:
        raise ValueError(
            "Thiếu biến môi trường FORECAST_DB_USER. "
            "Forecast service chỉ được phép dùng tài khoản riêng (forecast_ro), "
            "không được fallback sang POSTGRES_USER của chủ DB."
        )
    if password is None:
        raise ValueError(
            "Thiếu biến môi trường FORECAST_DB_PASSWORD. "
            "Forecast service chỉ được phép dùng tài khoản riêng (forecast_ro), "
            "không được fallback sang POSTGRES_PASSWORD của chủ DB."
        )

    return {
        "host": os.getenv("FORECAST_DB_HOST", "localhost"),
        "port": int(os.getenv("FORECAST_DB_PORT", "5432")),
        "dbname": dbname,
        "user": user,
        "password": password,
        "connect_timeout": int(os.getenv("FORECAST_DB_TIMEOUT", "2")),
    }


@contextmanager
def get_db_connection() -> Generator[PgConnection, None, None]:
    """Context manager cung cấp kết nối PostgreSQL (psycopg 3) và tự động đóng/rollback khi có lỗi."""
    params = get_connection_params()
    conn: Optional[PgConnection] = None
    try:
        conn = psycopg.connect(**params)
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
