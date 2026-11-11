"""FastAPI Router for forecast service endpoints (#114).

Endpoints:
- POST /forecast/run: Kích hoạt chạy dự báo nền, trả 202 và runId ngay lập tức.
- GET /forecast/runs/{runId}: Lấy trạng thái và chỉ số MAE/WAPE của lượt chạy.
"""

from __future__ import annotations

import logging
from typing import Any, Callable

from fastapi import APIRouter, BackgroundTasks, status
from fastapi.responses import JSONResponse

from app.api.schemas import (
    ErrorResponse,
    ForecastRunCreatedResponse,
    ForecastRunDetailResponse,
)
from app.db.connection import get_db_connection
from app.pipelines.forecast_service import (
    ActiveRunConflictError,
    DEFAULT_MODEL_VERSION,
    EmptyDailySalesError,
    execute_background_forecast,
    get_forecast_run_detail,
    start_forecast_run,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/forecast", tags=["forecast"])


def create_forecast_router(
    connection_factory: Callable[[], Any] = get_db_connection,
    background_runner: Callable[..., Any] = execute_background_forecast,
) -> APIRouter:
    """Tạo APIRouter cho forecast service, hỗ trợ DI connection_factory và background_runner cho test."""
    r = APIRouter(prefix="/forecast", tags=["forecast"])

    @r.post(
        "/run",
        status_code=status.HTTP_202_ACCEPTED,
        response_model=ForecastRunCreatedResponse,
        responses={
            status.HTTP_202_ACCEPTED: {"model": ForecastRunCreatedResponse},
            status.HTTP_409_CONFLICT: {"model": ErrorResponse},
        },
    )
    def trigger_forecast_run(background_tasks: BackgroundTasks):
        """Kích hoạt lượt chạy dự báo nhu cầu nền (Background Task).

        Trả về ngay HTTP 202 kèm runId.
        Ném HTTP 409 nếu đã có một lượt chạy đang RUNNING hoặc bảng daily_sales trống.
        """
        try:
            with connection_factory() as conn:
                run_id, started_at = start_forecast_run(
                    conn,
                    model_version=DEFAULT_MODEL_VERSION,
                )
        except ActiveRunConflictError as err:
            logger.warning("Yêu cầu chạy dự báo bị từ chối do xung đột: %s", err)
            return JSONResponse(
                status_code=status.HTTP_409_CONFLICT,
                content={"code": "CONFLICT", "message": str(err)},
            )
        except EmptyDailySalesError as err:
            logger.warning("Bảng daily_sales trống: %s", err)
            return JSONResponse(
                status_code=status.HTTP_409_CONFLICT,
                content={"code": "EMPTY_DAILY_SALES", "message": str(err)},
            )
        except Exception as exc:
            logger.exception("Lỗi không xác định khi khởi tạo forecast_run: %s", exc)
            return JSONResponse(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                content={"code": "INTERNAL_ERROR", "message": "Lỗi nội bộ khi khởi tạo lượt chạy dự báo."},
            )

        # Đẩy công việc vào hàng đợi nền với đúng run_id đã tạo
        background_tasks.add_task(background_runner, run_id=run_id)

        response_dto = ForecastRunCreatedResponse(
            run_id=run_id,
            status="RUNNING",
            started_at=started_at,
        )
        return JSONResponse(
            status_code=status.HTTP_202_ACCEPTED,
            content=response_dto.model_dump(by_alias=True, mode="json"),
        )

    @r.get(
        "/runs/{runId}",
        status_code=status.HTTP_200_OK,
        response_model=ForecastRunDetailResponse,
        responses={
            status.HTTP_200_OK: {"model": ForecastRunDetailResponse},
            status.HTTP_404_NOT_FOUND: {"model": ErrorResponse},
        },
    )
    def get_run_status(runId: int):
        """Lấy thông tin tiến độ, trạng thái và độ đo sai số của lượt chạy theo runId."""
        try:
            with connection_factory() as conn:
                detail = get_forecast_run_detail(conn, run_id=runId)
        except Exception as exc:
            logger.exception("Lỗi truy vấn forecast_run id=%d: %s", runId, exc)
            return JSONResponse(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                content={"code": "INTERNAL_ERROR", "message": "Lỗi truy vấn cơ sở dữ liệu."},
            )

        if detail is None:
            return JSONResponse(
                status_code=status.HTTP_404_NOT_FOUND,
                content={"code": "NOT_FOUND", "message": f"Không tìm thấy lượt dự báo với id={runId}"},
            )

        response_dto = ForecastRunDetailResponse.model_validate(detail)
        return JSONResponse(
            status_code=status.HTTP_200_OK,
            content=response_dto.model_dump(by_alias=True, mode="json"),
        )

    return r
