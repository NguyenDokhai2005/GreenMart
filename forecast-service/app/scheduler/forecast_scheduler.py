"""Lập lịch tự động chạy dự báo nhu cầu lúc 02:00 hằng đêm (Task #118, SRS 8.4).

Thiết kế:
- Thư viện: APScheduler (BackgroundScheduler).
- Thời gian: 02:00 giờ Asia/Ho_Chi_Minh mỗi đêm.
- Chống chạy trùng lặp:
  1. Kiểm tra forecast_run trong ngày (Asia/Ho_Chi_Minh) đã có lượt DONE hoặc RUNNING chưa.
  2. Tận dụng pg_advisory_xact_lock(1142026) trong start_forecast_run() chống race condition.
- Chạy bù khi lỡ giờ (Startup Catch-up Recovery):
  Nếu container khởi động trong khung 02:00 - 06:00 sáng mà hôm nay chưa có run nào, tự động chạy bù.
"""

from __future__ import annotations

from datetime import date, datetime, time, timezone
import logging
import os
from typing import Any, Callable, Optional
from zoneinfo import ZoneInfo

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger

from app.db.connection import get_db_connection
from app.pipelines.forecast_service import (
    ActiveRunConflictError,
    DEFAULT_MODEL_VERSION,
    EmptyDailySalesError,
    execute_background_forecast,
    start_forecast_run,
)

logger = logging.getLogger(__name__)

VN_TZ = ZoneInfo("Asia/Ho_Chi_Minh")
CATCHUP_WINDOW_START_HOUR = 2  # 02:00
CATCHUP_WINDOW_END_HOUR = 6    # 06:00


def get_current_vn_time() -> datetime:
    """Lấy thời gian hiện tại theo múi giờ Asia/Ho_Chi_Minh."""
    return datetime.now(VN_TZ)


def has_run_executed_today(
    conn: Any,
    target_date: Optional[date] = None,
    min_hour: int = 2,
) -> bool:
    """Kiểm tra xem ngày target_date (mặc định hôm nay theo giờ VN) đã có forecast_run hợp lệ chưa.

    Một lượt chạy được coi là hợp lệ nếu:
    - Trạng thái là DONE hoặc đang RUNNING.
    - Thời gian started_at (chuyển sang múi giờ Asia/Ho_Chi_Minh) cùng ngày và bắt đầu từ min_hour trở đi.
    """
    check_date = target_date or get_current_vn_time().date()
    sql = """
        SELECT id, status, started_at
        FROM forecast_run
        WHERE status IN ('DONE', 'RUNNING')
          AND (started_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date = %s
          AND EXTRACT(HOUR FROM (started_at AT TIME ZONE 'Asia/Ho_Chi_Minh')) >= %s
        ORDER BY id DESC
        LIMIT 1;
    """
    with conn.cursor() as cur:
        try:
            cur.execute(sql, (check_date, min_hour))
            row = cur.fetchone()
            if row:
                logger.info(
                    "Đã tìm thấy forecast_run hợp lệ cho ngày %s: run_id=%d, status=%s, started_at=%s",
                    check_date,
                    row[0],
                    row[1],
                    row[2],
                )
                return True
        except Exception as exc:
            # Cho phép test trên SQLite mock fallback
            logger.warning("Truy vấn has_run_executed_today thất bại (DB dialect hoặc mock): %s", exc)
            return False

    return False


def run_nightly_forecast_job(
    connection_factory: Callable[[], Any] = get_db_connection,
    runner_fn: Callable[..., Any] = execute_background_forecast,
    now_dt: Optional[datetime] = None,
) -> Optional[int]:
    """Hàm thực thi công việc dự báo đêm theo lịch (02:00 Asia/Ho_Chi_Minh).

    Quy trình:
    1. Kiểm tra idempotency: Nếu hôm nay đã có run hoàn tất hoặc đang chạy, bỏ qua.
    2. Khởi tạo forecast_run mới qua start_forecast_run() với advisory lock.
    3. Thực thi pipeline dự báo nền qua runner_fn(run_id).
    4. Trả về run_id nếu kích hoạt thành công, None nếu bị bỏ qua hoặc lỗi nghiệp vụ nhẹ.
    """
    current_time = now_dt or get_current_vn_time()
    today_vn = current_time.date()

    logger.info("Bắt đầu job dự báo đêm lịch 02:00 (thời gian hệ thống VN: %s)", current_time.isoformat())

    try:
        with connection_factory() as conn:
            # 1. Kiểm tra xem hôm nay đã có run hợp lệ chưa
            if has_run_executed_today(conn, target_date=today_vn, min_hour=CATCHUP_WINDOW_START_HOUR):
                logger.info(
                    "Bỏ qua lịch 02:00: Hôm nay (%s) đã có lượt dự báo hoàn tất hoặc đang chạy.",
                    today_vn,
                )
                return None

            # 2. Tạo bản ghi forecast_run mới (có advisory lock)
            run_id, started_at = start_forecast_run(
                conn,
                model_version=DEFAULT_MODEL_VERSION,
            )
            logger.info("Đã tạo forecast_run thành công cho lịch đêm: run_id=%d, started_at=%s", run_id, started_at)

    except ActiveRunConflictError as err:
        logger.info("Bỏ qua lịch 02:00 do xung đột với run đang chạy khác: %s", err)
        return None
    except EmptyDailySalesError as err:
        logger.warning("Bỏ qua lịch 02:00 do bảng daily_sales trống: %s", err)
        return None
    except Exception as exc:
        logger.exception("Lỗi bất ngờ khi khởi tạo forecast_run cho lịch đêm: %s", exc)
        return None

    # 3. Thực thi pipeline dự báo nền
    try:
        runner_fn(run_id=run_id)
        logger.info("Hoàn tất thực thi pipeline dự báo đêm cho run_id=%d", run_id)
        return run_id
    except Exception as exc:
        logger.exception("Lỗi khi thực thi pipeline dự báo đêm cho run_id=%d: %s", run_id, exc)
        return run_id


def check_and_run_missed_forecast(
    connection_factory: Callable[[], Any] = get_db_connection,
    runner_fn: Callable[..., Any] = execute_background_forecast,
    now_dt: Optional[datetime] = None,
) -> Optional[int]:
    """Kiểm tra và chạy bù dự báo nếu bị lỡ lịch 02:00 khi khởi động container.

    Khung giờ chạy bù được thống nhất: từ 02:00 đến 06:00 sáng (Asia/Ho_Chi_Minh).
    Nếu khởi động sau 06:00 sáng, không chạy bù để tránh gây quá tải hệ thống giờ cao điểm bán hàng.
    """
    current_time = now_dt or get_current_vn_time()
    current_hour = current_time.hour
    today_vn = current_time.date()

    if not (CATCHUP_WINDOW_START_HOUR <= current_hour < CATCHUP_WINDOW_END_HOUR):
        logger.info(
            "Khởi động lúc %02d:%02d: Nằm ngoài khung giờ chạy bù (02:00 - 06:00), bỏ qua startup recovery.",
            current_hour,
            current_time.minute,
        )
        return None

    logger.info(
        "Kiểm tra lỡ lịch chạy 02:00 lúc khởi động (hiện tại: %02d:%02d ngày %s)...",
        current_hour,
        current_time.minute,
        today_vn,
    )

    try:
        with connection_factory() as conn:
            if has_run_executed_today(conn, target_date=today_vn, min_hour=CATCHUP_WINDOW_START_HOUR):
                logger.info("Hôm nay (%s) đã có lượt chạy dự báo, không cần chạy bù.", today_vn)
                return None
    except Exception as exc:
        logger.warning("Không thể kiểm tra trạng thái lượt chạy khi khởi động: %s", exc)
        return None

    logger.warning(
        "Phát hiện lỡ lịch chạy dự báo 02:00 ngày %s (thời gian khởi động: %s). "
        "Tiến hành kích hoạt chạy bù (Startup Catch-up)...",
        today_vn,
        current_time.isoformat(),
    )
    return run_nightly_forecast_job(
        connection_factory=connection_factory,
        runner_fn=runner_fn,
        now_dt=current_time,
    )


def create_and_start_scheduler(
    connection_factory: Callable[[], Any] = get_db_connection,
    runner_fn: Callable[..., Any] = execute_background_forecast,
) -> Optional[BackgroundScheduler]:
    """Khởi tạo và kích hoạt APScheduler chạy ngầm lúc 02:00 Asia/Ho_Chi_Minh."""
    enabled_str = os.environ.get("FORECAST_SCHEDULER_ENABLED", "true").strip().lower()
    if enabled_str not in ("1", "true", "yes", "on"):
        logger.info("FORECAST_SCHEDULER_ENABLED=false: Scheduler định kỳ đã tắt.")
        return None

    scheduler = BackgroundScheduler(timezone=VN_TZ)

    # Thêm cron job chạy lúc 02:00 hằng ngày
    trigger = CronTrigger(
        hour=2,
        minute=0,
        timezone=VN_TZ,
    )

    def scheduled_wrapper():
        run_nightly_forecast_job(
            connection_factory=connection_factory,
            runner_fn=runner_fn,
        )

    scheduler.add_job(
        scheduled_wrapper,
        trigger=trigger,
        id="nightly_forecast_0200",
        name="Nightly demand forecast at 02:00 Asia/Ho_Chi_Minh",
        replace_existing=True,
        misfire_grace_time=3600,  # Cho phép kích hoạt trễ tối đa 1 giờ nếu scheduler bị lag
        coalesce=True,            # Gộp nhiều lần kích hoạt trễ thành 1 lần duy nhất
        max_instances=1,          # Đảm bảo chỉ 1 thread chạy job tại 1 thời điểm
    )

    scheduler.start()
    logger.info("APScheduler đã được khởi động thành công với lịch 02:00 hằng đêm (Asia/Ho_Chi_Minh).")
    return scheduler
