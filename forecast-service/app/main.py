"""Container health, DB permissions and forecasting pipeline API (#114)."""
import logging
import os
from collections.abc import Callable
from contextlib import asynccontextmanager
from typing import Optional

import psycopg
from fastapi import FastAPI
from fastapi.responses import JSONResponse

from app.api.router import create_forecast_router, register_auth_exception_handler
from app.scheduler.forecast_scheduler import (
    check_and_run_missed_forecast,
    create_and_start_scheduler,
)

log = logging.getLogger(__name__)


def connect():
    """Use only forecast credentials, never POSTGRES_USER/password of the owner."""
    return psycopg.connect(
        host=os.environ.get("FORECAST_DB_HOST", "postgres"),
        port=int(os.environ.get("FORECAST_DB_PORT", "5432")),
        dbname=os.environ["POSTGRES_DB"],
        user=os.environ["FORECAST_DB_USER"],
        password=os.environ["FORECAST_DB_PASSWORD"],
        connect_timeout=3,
        options="-c statement_timeout=3000",
    )


def database_ready(connection_factory: Callable = connect) -> bool:
    with connection_factory() as connection:
        with connection.cursor() as cursor:
            cursor.execute("""
                SELECT current_user = %s
                  AND NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user)
                  AND has_table_privilege(current_user, 'public.sales_order', 'SELECT')
                  AND has_table_privilege(current_user, 'public.product', 'SELECT')
                  AND NOT EXISTS (
                    SELECT 1 FROM (VALUES ('daily_sales'), ('forecast_run'), ('forecast_result')) AS t(name)
                    CROSS JOIN (VALUES ('INSERT'), ('UPDATE'), ('DELETE')) AS p(privilege)
                    WHERE NOT has_table_privilege(current_user, 'public.' || t.name, p.privilege)
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
                      AND c.relname NOT IN ('daily_sales', 'forecast_run', 'forecast_result')
                      AND has_table_privilege(current_user, c.oid, 'INSERT,UPDATE,DELETE,TRUNCATE')
                  )
            """, (os.environ["FORECAST_DB_USER"],))
            return cursor.fetchone()[0] is True


def create_app(connection_factory: Callable = connect) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        scheduler = None
        try:
            scheduler = create_and_start_scheduler(connection_factory=connection_factory)
            # Kiểm tra chạy bù nếu lỡ lịch 02:00 khi khởi động container
            check_and_run_missed_forecast(connection_factory=connection_factory)
        except Exception as exc:
            log.warning("Khởi động scheduler hoặc kiểm tra chạy bù thất bại: %s", exc)

        yield

        if scheduler is not None:
            try:
                scheduler.shutdown(wait=False)
                log.info("APScheduler đã dừng an toàn.")
            except Exception as shutdown_err:
                log.warning("Lỗi khi dừng APScheduler: %s", shutdown_err)

    application = FastAPI(
        title="GreenMart Forecast Service",
        docs_url=None,
        redoc_url=None,
        lifespan=lifespan,
    )
    register_auth_exception_handler(application)

    @application.get("/healthz")
    def health():
        try:
            ready = database_ready(connection_factory)
        except Exception:
            # Do not log exception text/DSNs, which can contain credentials or host details.
            log.warning("Forecast database readiness failed")
            ready = False
        return JSONResponse({"status": "UP" if ready else "DOWN"}, status_code=200 if ready else 503)

    @application.get("/livez")
    def live():
        return {"status": "UP"}

    forecast_router = create_forecast_router(connection_factory=connection_factory)
    application.include_router(forecast_router)

    return application


app = create_app()
