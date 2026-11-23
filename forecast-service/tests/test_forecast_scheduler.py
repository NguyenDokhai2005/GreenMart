"""Unit tests for nightly forecast scheduler (02:00 Asia/Ho_Chi_Minh) and startup recovery (#118)."""

from datetime import date, datetime, timezone
from unittest.mock import MagicMock, call, patch
from zoneinfo import ZoneInfo

import pytest

from app.pipelines.forecast_service import (
    ActiveRunConflictError,
    EmptyDailySalesError,
    StaleDailySalesError,
)
from app.scheduler.forecast_scheduler import (
    CATCHUP_WINDOW_END_HOUR,
    CATCHUP_WINDOW_START_HOUR,
    VN_TZ,
    check_and_run_missed_forecast,
    create_and_start_scheduler,
    has_run_executed_today,
    run_nightly_forecast_job,
)


@pytest.fixture
def mock_db():
    conn = MagicMock()
    conn.__enter__.return_value = conn
    cur = MagicMock()
    conn.cursor.return_value.__enter__.return_value = cur
    cur.fetchone.return_value = (date(2025, 11, 1), date(2026, 11, 17))

    def conn_factory():
        return conn

    return {
        "conn": conn,
        "cur": cur,
        "factory": conn_factory,
    }


def test_has_run_executed_today_returns_true_when_record_exists(mock_db):
    """Kiểm tra has_run_executed_today trả về True khi đã có run DONE/RUNNING từ 02:00."""
    cur = mock_db["cur"]
    today = date(2026, 11, 18)
    cur.fetchone.return_value = (55, "DONE", datetime(2026, 11, 18, 2, 5, tzinfo=VN_TZ))

    result = has_run_executed_today(mock_db["conn"], target_date=today, min_hour=2)
    assert result is True
    cur.execute.assert_called_once()
    assert cur.execute.call_args[0][1] == (today, 2)


def test_has_run_executed_today_returns_false_when_no_record(mock_db):
    """Kiểm tra has_run_executed_today trả về False khi chưa có run nào hôm nay."""
    cur = mock_db["cur"]
    cur.fetchone.return_value = None

    result = has_run_executed_today(mock_db["conn"], target_date=date(2026, 11, 18), min_hour=2)
    assert result is False


def test_has_run_executed_today_returns_false_when_done_run_has_stale_cutoff(mock_db):
    """Mục 1 (Issue #417): Khi lượt chạy hôm nay DONE nhưng MIN(target_date) <= expected_cutoff (train trên cutoff cũ 31/10), coi như chưa có run hợp lệ."""
    cur = mock_db["cur"]
    today = date(2026, 11, 20)
    expected_cutoff = date(2026, 11, 19)

    # 1. SELECT id, status, started_at -> (55, "DONE", ...)
    # 2. SELECT MIN(target_date) -> 2026-11-01 (từ cutoff 31/10)
    cur.fetchone.side_effect = [
        (55, "DONE", datetime(2026, 11, 20, 2, 5, tzinfo=VN_TZ)),
        (date(2026, 11, 1),),
    ]

    result = has_run_executed_today(
        mock_db["conn"],
        target_date=today,
        min_hour=2,
        expected_cutoff_date=expected_cutoff,
    )
    assert result is False


def test_has_run_executed_today_returns_true_when_done_run_has_fresh_cutoff(mock_db):
    """Mục 1 (Issue #417): Khi lượt chạy hôm nay DONE và MIN(target_date) > expected_cutoff (được train trên cutoff mới >= 19/11), trả về True."""
    cur = mock_db["cur"]
    today = date(2026, 11, 20)
    expected_cutoff = date(2026, 11, 19)

    # 1. SELECT id, status, started_at -> (55, "DONE", ...)
    # 2. SELECT MIN(target_date) -> 2026-11-20 (target sớm nhất = cutoff + 1 > 19/11)
    cur.fetchone.side_effect = [
        (55, "DONE", datetime(2026, 11, 20, 2, 5, tzinfo=VN_TZ)),
        (date(2026, 11, 20),),
    ]

    result = has_run_executed_today(
        mock_db["conn"],
        target_date=today,
        min_hour=2,
        expected_cutoff_date=expected_cutoff,
    )
    assert result is True


@patch("app.scheduler.forecast_scheduler.start_forecast_run")
@patch("app.scheduler.forecast_scheduler.has_run_executed_today")
def test_tc_sched_01_run_nightly_forecast_job_success(mock_has_run, mock_start, mock_db):
    """TC-SCHED-01: Đến 02:00, job kích hoạt tạo forecast_run mới và gọi runner_fn thành công."""
    mock_has_run.return_value = False
    mock_start.return_value = (88, datetime(2026, 11, 18, 2, 0, tzinfo=VN_TZ))
    mock_db["cur"].fetchone.return_value = (date(2025, 11, 1), date(2026, 11, 17))
    mock_runner = MagicMock()
    mock_etl_pipeline = MagicMock(return_value={"total_rows": 100})

    test_time = datetime(2026, 11, 18, 2, 0, 5, tzinfo=VN_TZ)

    run_id = run_nightly_forecast_job(
        connection_factory=mock_db["factory"],
        runner_fn=mock_runner,
        now_dt=test_time,
        etl_pipeline_fn=mock_etl_pipeline,
    )

    assert run_id == 88
    mock_has_run.assert_called_once_with(mock_db["conn"], target_date=test_time.date(), min_hour=2)
    mock_start.assert_called_once()
    mock_runner.assert_called_once_with(run_id=88, expected_cutoff_date=date(2026, 11, 17))


@patch("app.scheduler.forecast_scheduler.start_forecast_run")
@patch("app.scheduler.forecast_scheduler.has_run_executed_today")
def test_tc_sched_02_run_nightly_job_skips_when_active_run_conflict(mock_has_run, mock_start, mock_db):
    """TC-SCHED-02: Bỏ qua an toàn khi có xung đột run khác đang RUNNING (ActiveRunConflictError)."""
    mock_has_run.return_value = False
    mock_start.side_effect = ActiveRunConflictError("Run 77 đang chạy", active_id=77)
    mock_runner = MagicMock()

    run_id = run_nightly_forecast_job(
        connection_factory=mock_db["factory"],
        runner_fn=mock_runner,
        now_dt=datetime(2026, 11, 18, 2, 0, 0, tzinfo=VN_TZ),
    )

    assert run_id is None
    mock_runner.assert_not_called()


@patch("app.scheduler.forecast_scheduler.has_run_executed_today")
def test_tc_sched_03_run_nightly_job_skips_when_already_executed_today(mock_has_run, mock_db):
    """TC-SCHED-03: Bỏ qua khi hôm nay đã có lượt chạy hoàn tất (idempotent guard)."""
    mock_has_run.return_value = True
    mock_runner = MagicMock()

    run_id = run_nightly_forecast_job(
        connection_factory=mock_db["factory"],
        runner_fn=mock_runner,
        now_dt=datetime(2026, 11, 18, 2, 0, 0, tzinfo=VN_TZ),
    )

    assert run_id is None
    mock_runner.assert_not_called()


@patch("app.scheduler.forecast_scheduler.start_forecast_run")
@patch("app.scheduler.forecast_scheduler.has_run_executed_today")
def test_tc_sched_04_run_nightly_job_skips_when_daily_sales_empty(mock_has_run, mock_start, mock_db):
    """TC-SCHED-04: Bỏ qua an toàn khi bảng daily_sales trống."""
    mock_has_run.return_value = False
    mock_start.side_effect = EmptyDailySalesError("daily_sales trống")
    mock_runner = MagicMock()

    run_id = run_nightly_forecast_job(
        connection_factory=mock_db["factory"],
        runner_fn=mock_runner,
        now_dt=datetime(2026, 11, 18, 2, 0, 0, tzinfo=VN_TZ),
    )

    assert run_id is None
    mock_runner.assert_not_called()


@patch("app.scheduler.forecast_scheduler.run_nightly_forecast_job")
@patch("app.scheduler.forecast_scheduler.has_run_executed_today")
def test_tc_sched_05_startup_recovery_triggers_in_catchup_window_with_scheduler(mock_has_run, mock_run_job, mock_db):
    """TC-SCHED-05: Khởi động container lúc 02:15 sáng (trong khung 02:00-06:00) chưa có run -> lập lịch chạy bù qua scheduler."""
    mock_has_run.return_value = False
    mock_scheduler = MagicMock()
    mock_scheduler.add_job.return_value = "catchup_job_instance"

    startup_time = datetime(2026, 11, 18, 2, 15, tzinfo=VN_TZ)

    result = check_and_run_missed_forecast(
        connection_factory=mock_db["factory"],
        now_dt=startup_time,
        scheduler=mock_scheduler,
    )

    assert result == "catchup_job_instance"
    mock_has_run.assert_called_once_with(
        mock_db["conn"],
        target_date=startup_time.date(),
        min_hour=2,
        expected_cutoff_date=date(2026, 11, 17),
    )
    mock_scheduler.add_job.assert_called_once()
    job_kwargs = mock_scheduler.add_job.call_args[1]
    assert job_kwargs["trigger"] == "date"
    assert job_kwargs["run_date"] == startup_time
    assert job_kwargs["id"] == "catchup_forecast"
    assert job_kwargs["max_instances"] == 1
    assert job_kwargs["coalesce"] is True
    assert job_kwargs["replace_existing"] is True
    # run_job không bị gọi đồng bộ ngay lúc schedule
    mock_run_job.assert_not_called()


@patch("app.scheduler.forecast_scheduler.threading.Thread")
@patch("app.scheduler.forecast_scheduler.has_run_executed_today")
def test_tc_sched_05_startup_recovery_triggers_fallback_thread_when_no_scheduler(mock_has_run, mock_thread_cls, mock_db):
    """TC-SCHED-05: Khi scheduler=None, khởi tạo daemon thread chạy bù nền."""
    mock_has_run.return_value = False
    mock_thread_instance = MagicMock()
    mock_thread_cls.return_value = mock_thread_instance

    startup_time = datetime(2026, 11, 18, 2, 15, tzinfo=VN_TZ)

    result = check_and_run_missed_forecast(
        connection_factory=mock_db["factory"],
        now_dt=startup_time,
        scheduler=None,
    )

    assert result == mock_thread_instance
    mock_thread_instance.start.assert_called_once()
    assert mock_thread_cls.call_args[1]["daemon"] is True


@patch("app.scheduler.forecast_scheduler.run_nightly_forecast_job")
def test_tc_sched_05_startup_recovery_skips_before_0200(mock_run_job, mock_db):
    """TC-SCHED-05: Khởi động lúc 01:45 sáng (trước 02:00) -> không kích hoạt chạy bù."""
    startup_time = datetime(2026, 11, 18, 1, 45, tzinfo=VN_TZ)

    result = check_and_run_missed_forecast(
        connection_factory=mock_db["factory"],
        now_dt=startup_time,
    )

    assert result is None
    mock_run_job.assert_not_called()


@patch("app.scheduler.forecast_scheduler.has_run_executed_today")
def test_tc_sched_05_startup_recovery_triggers_after_0600_when_no_run_today(mock_has_run, mock_db):
    """TC-SCHED-05 (Issue #417): Khởi động lúc 07:30 sáng (sau 06:00) chưa có run -> vẫn kích hoạt chạy bù."""
    mock_has_run.return_value = False
    mock_scheduler = MagicMock()
    mock_scheduler.add_job.return_value = "catchup_job_after_0600"
    startup_time = datetime(2026, 11, 18, 7, 30, tzinfo=VN_TZ)

    result = check_and_run_missed_forecast(
        connection_factory=mock_db["factory"],
        now_dt=startup_time,
        scheduler=mock_scheduler,
    )

    assert result == "catchup_job_after_0600"
    mock_has_run.assert_called_once_with(
        mock_db["conn"],
        target_date=startup_time.date(),
        min_hour=2,
        expected_cutoff_date=date(2026, 11, 17),
    )


@patch("app.scheduler.forecast_scheduler.ensure_fresh_daily_sales")
@patch("app.scheduler.forecast_scheduler.has_run_executed_today")
def test_tc_sched_07_run_nightly_job_aborts_when_etl_stale(mock_has_run, mock_ensure_fresh, mock_db):
    """TC-SCHED-07 (Issue #417): Job đêm dừng lại và không tạo run khi ETL báo dữ liệu cũ (StaleDailySalesError)."""
    mock_has_run.return_value = False
    mock_ensure_fresh.side_effect = StaleDailySalesError("Dữ liệu daily_sales chỉ tới 2026-11-15, cũ hơn cutoff 2026-11-17")
    mock_runner = MagicMock()

    run_id = run_nightly_forecast_job(
        connection_factory=mock_db["factory"],
        runner_fn=mock_runner,
        now_dt=datetime(2026, 11, 18, 2, 0, 0, tzinfo=VN_TZ),
    )

    assert run_id is None
    mock_runner.assert_not_called()


@patch("app.scheduler.forecast_scheduler.run_nightly_forecast_job")
@patch("app.scheduler.forecast_scheduler.has_run_executed_today")
def test_tc_sched_05_startup_recovery_skips_if_already_ran_today(mock_has_run, mock_run_job, mock_db):
    """TC-SCHED-05: Khởi động lúc 03:00 sáng nhưng hôm nay đã có run hoàn tất -> không chạy bù."""
    mock_has_run.return_value = True
    startup_time = datetime(2026, 11, 18, 3, 0, tzinfo=VN_TZ)

    result = check_and_run_missed_forecast(
        connection_factory=mock_db["factory"],
        now_dt=startup_time,
    )

    assert result is None
    mock_run_job.assert_not_called()


def test_tc_sched_06_scheduler_configuration(monkeypatch, mock_db):
    """TC-SCHED-06: Kiểm tra APScheduler khởi tạo đúng trigger 02:00 giờ Asia/Ho_Chi_Minh."""
    monkeypatch.setenv("FORECAST_SCHEDULER_ENABLED", "true")

    scheduler = create_and_start_scheduler(connection_factory=mock_db["factory"])

    try:
        assert scheduler is not None
        assert scheduler.running is True

        jobs = scheduler.get_jobs()
        assert len(jobs) == 1
        job = jobs[0]

        assert job.id == "nightly_forecast_0200"
        assert job.misfire_grace_time == 3600
        assert job.coalesce is True
        assert job.max_instances == 1

        # Kiểm tra CronTrigger
        fields = {f.name: str(f) for f in job.trigger.fields}
        assert fields["hour"] == "2"
        assert fields["minute"] == "0"
        assert str(job.trigger.timezone) == "Asia/Ho_Chi_Minh"
    finally:
        if scheduler and scheduler.running:
            scheduler.shutdown(wait=False)


def test_scheduler_disabled_when_env_false(monkeypatch, mock_db):
    """Kiểm tra scheduler không chạy khi FORECAST_SCHEDULER_ENABLED=false."""
    monkeypatch.setenv("FORECAST_SCHEDULER_ENABLED", "false")

    scheduler = create_and_start_scheduler(connection_factory=mock_db["factory"])
    assert scheduler is None
