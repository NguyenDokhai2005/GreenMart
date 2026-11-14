"""Kiểm thử tích hợp CSDL PostgreSQL thật cho forecast_runner và persist_forecast_to_db (Issue #113).

Yêu cầu nghiệm thu (AC):
1. Chạy trên PostgreSQL 16 thật thông qua Testcontainers.
2. Áp dụng đầy đủ migration V1-V5 từ backend/src/main/resources/db/migration.
3. Ca thành công:
   - Đóng kết nối cũ và mở kết nối mới.
   - forecast_run.status = 'DONE', finished_at không NULL.
   - forecast_result có đúng số dòng (N_series x (7 + 30)), không có dòng nào predicted_qty < 0.
   - Khớp báo cáo: Tỷ lệ predicted_qty = 0 của nhóm bán chậm trong DB khớp với số trong evaluation_report (sai lệch <= 0.5 điểm %).
4. Ca lỗi:
   - Monkeypatch save_forecast_results ghi dở dang rồi ném ngoại lệ.
   - Kiểm tra rollback: forecast_run.status = 'FAILED' và forecast_result có đúng 0 dòng.
5. Chỉ skip khi Docker daemon không khả dụng.
"""

from __future__ import annotations

from datetime import date, timedelta
from pathlib import Path
from typing import Any, Dict, Generator, List, Set, Tuple
import numpy as np
import pandas as pd
import pytest

from app.pipelines.forecast_runner import (
    assemble_forecast_results_dataframe,
    persist_forecast_to_db,
    save_forecast_results,
)


def _check_docker() -> bool:
    """Kiểm tra Docker daemon có đang chạy hay không."""
    try:
        import docker

        client = docker.from_env()
        return bool(client.ping())
    except Exception:
        return False


HAS_DOCKER = _check_docker()


@pytest.fixture(scope="module")
def postgres_container() -> Generator[Any, None, None]:
    """Khởi tạo container PostgreSQL 16 và áp dụng các migration V1-V5."""
    if not HAS_DOCKER:
        pytest.skip(
            "Docker daemon không khả dụng trên môi trường này. "
            "Để chạy test tích hợp PostgreSQL: hãy khởi động Docker Desktop rồi chạy: "
            "pytest tests/test_forecast_db_integration.py -v"
        )

    try:
        from testcontainers.community.postgres import PostgresContainer
    except ImportError:
        from testcontainers.postgres import PostgresContainer
    import psycopg

    container = PostgresContainer("postgres:16-alpine")
    container.start()

    # Áp dụng migration V1-V5
    migrations_dir = Path(__file__).resolve().parents[2] / "backend" / "src" / "main" / "resources" / "db" / "migration"
    migration_files = [
        "V1__init_schema.sql",
        "V2__grant_forecast.sql",
        "V3__reference_data.sql",
        "V4__document_timestamps.sql",
        "V5__inventory_transaction_append_only.sql",
    ]

    with psycopg.connect(
        host=container.get_container_host_ip(),
        port=container.get_exposed_port(5432),
        user=container.username,
        password=container.password,
        dbname=container.dbname,
        autocommit=True,
    ) as conn:
        with conn.cursor() as cur:
            for mf in migration_files:
                fpath = migrations_dir / mf
                if fpath.exists():
                    sql = fpath.read_text(encoding="utf-8")
                    if mf == "V2__grant_forecast.sql":
                        sql = sql.replace("${forecast_user}", "forecast_user")
                    cur.execute(sql)

            # Tạo dữ liệu cha (foreign keys) phục vụ kiểm thử
            cur.execute("""
                INSERT INTO branch (id, code, name, is_active)
                VALUES (1, 'GM-001', 'Chi nhánh 1', TRUE),
                       (2, 'GM-002', 'Chi nhánh 2', TRUE)
                ON CONFLICT (id) DO NOTHING;
            """)
            cur.execute("""
                INSERT INTO category (id, name)
                VALUES (1, 'Rau củ quả')
                ON CONFLICT (id) DO NOTHING;
            """)
            cur.execute("""
                INSERT INTO product (id, sku, barcode, name, unit, category_id, sale_price, cost_price, is_active)
                VALUES 
                    (101, 'SKU-TOP-1', 'BAR-101', 'Gạo ST25 5KG', 'TUI', 1, 150000, 120000, TRUE),
                    (102, 'SKU-SLOW-1', 'BAR-102', 'Hành tây tím', 'KG', 1, 25000, 18000, TRUE),
                    (103, 'SKU-SLOW-2', 'BAR-103', 'Tỏi Lý Sơn', 'TUI', 1, 45000, 30000, TRUE),
                    (104, 'SKU-SLOW-3', 'BAR-104', 'Gừng gió', 'KG', 1, 35000, 22000, TRUE)
                ON CONFLICT (id) DO NOTHING;
            """)

    yield container

    container.stop()


def _get_pg_connection(container: Any):
    """Mở kết nối psycopg 3 độc lập tới container."""
    import psycopg

    return psycopg.connect(
        host=container.get_container_host_ip(),
        port=container.get_exposed_port(5432),
        user=container.username,
        password=container.password,
        dbname=container.dbname,
        autocommit=False,
    )


class DummyModel:
    """Mô hình dự báo giả định phục vụ kiểm thử."""

    def __init__(self, pred_values: Dict[int, float]):
        self.pred_values = pred_values

    def predict(self, feature_df: pd.DataFrame) -> np.ndarray:
        p_ids = feature_df["product_id"].to_numpy()
        return np.array([self.pred_values.get(pid, 0.1) for pid in p_ids], dtype=float)


class DummyMetrics:
    def __init__(self, mae: float, wape: float, zero_pred_ratio: float = 0.0):
        self.mae = mae
        self.wape = wape
        self.zero_pred_ratio = zero_pred_ratio


class DummyReport:
    def __init__(self, cutoff_date: date, top_sku_ids: Set[int], optimal_thresholds: Dict[int, float]):
        self.cutoff_date = cutoff_date
        self.horizons = [7, 30]
        baseline_metrics_h7 = DummyMetrics(mae=0.30, wape=0.20)
        baseline_metrics_h30 = DummyMetrics(mae=0.35, wape=0.22)
        self.baseline_report = type(
            "BaselineReport",
            (),
            {
                "top_sku_ids": top_sku_ids,
                "best_baseline": {7: "rolling_mean_28", 30: "rolling_mean_28"},
                "results": {
                    7: {"rolling_mean_28": {"overall": baseline_metrics_h7}},
                    30: {"rolling_mean_28": {"overall": baseline_metrics_h30}},
                },
            },
        )()
        self.optimal_slow_thresholds = optimal_thresholds

        # Tỷ lệ zero mong đợi của nhóm bán chậm khi áp ngưỡng 0.4
        # (3 SKU bán chậm: 102 dự báo 0.1, 103 dự báo 0.15, 104 dự báo 0.1 => 100% bị gán về 0.0)
        self.lgb_results_clean = {
            7: {
                "lgb_tweedie": {
                    "overall": DummyMetrics(mae=0.25, wape=0.15),
                    "slow_moving": DummyMetrics(mae=0.10, wape=0.9985, zero_pred_ratio=1.00),
                },
                "rolling_mean_28": {"overall": DummyMetrics(mae=0.30, wape=0.20)},
            },
            30: {
                "lgb_tweedie": {
                    "overall": DummyMetrics(mae=0.28, wape=0.18),
                    "slow_moving": DummyMetrics(mae=0.12, wape=0.9985, zero_pred_ratio=1.00),
                },
                "rolling_mean_28": {"overall": DummyMetrics(mae=0.35, wape=0.22)},
            },
        }


def _make_dummy_daily_sales(cutoff_date: date) -> pd.DataFrame:
    """Tạo chuỗi 35 ngày lịch sử + 30 ngày test window cho 2 chi nhánh x 4 sản phẩm = 8 chuỗi."""
    start_date = cutoff_date - timedelta(days=35)
    end_date = cutoff_date + timedelta(days=30)
    days_count = (end_date - start_date).days + 1
    dates = [start_date + timedelta(days=i) for i in range(days_count)]
    records = []
    for d in dates:
        for b in [1, 2]:
            for p in [101, 102, 103, 104]:
                qty = 50.0 if p == 101 else (1.0 if d.day % 3 == 0 else 0.0)
                records.append({
                    "branch_id": b,
                    "product_id": p,
                    "sale_date": d,
                    "qty_net": qty,
                    "stockout": False,
                })
    return pd.DataFrame(records)


def test_persist_forecast_to_db_postgres_success(postgres_container):
    """Ca thành công: Ghi kết quả vào Postgres 16 thật, kiểm tra status DONE, đúng số dòng và khớp báo cáo."""
    cutoff = date(2026, 10, 1)
    top_skus = {101}  # SKU 101 là Top, 102, 103, 104 là bán chậm
    report = DummyReport(cutoff_date=cutoff, top_sku_ids=top_skus, optimal_thresholds={7: 0.4, 30: 0.4})
    df_daily = _make_dummy_daily_sales(cutoff)

    # Dự báo: SKU 101 cho 10.5 (không bị clip), các SKU bán chậm cho 0.15 (< 0.4 => clip thành 0.0)
    pred_values = {101: 10.5, 102: 0.15, 103: 0.12, 104: 0.08}
    models = {7: DummyModel(pred_values), 30: DummyModel(pred_values)}

    # Ghi dữ liệu bằng kết nối conn1
    conn1 = _get_pg_connection(postgres_container)
    try:
        run_id, total_inserted = persist_forecast_to_db(
            conn=conn1,
            report=report,
            best_models=models,
            df_daily=df_daily,
            model_version="LightGBM-Tweedie-H7-H30-Test",
        )
    finally:
        conn1.close()

    # 1. Đóng conn1 và mở kết nối hoàn toàn mới conn2 để kiểm chứng tính bền vững (Persistence)
    conn2 = _get_pg_connection(postgres_container)
    try:
        with conn2.cursor() as cur:
            # Kiểm tra trạng thái forecast_run
            cur.execute(
                "SELECT status, model_version, mae, wape, baseline_mae, baseline_wape, finished_at "
                "FROM forecast_run WHERE id = %s;",
                (run_id,),
            )
            run_row = cur.fetchone()
            assert run_row is not None
            status, m_ver, mae, wape, b_mae, b_wape, finished_at = run_row
            assert status == "DONE"
            assert finished_at is not None
            assert m_ver == "LightGBM-Tweedie-H7-H30-Test"
            assert float(mae) == 0.25
            assert float(wape) == 0.15
            assert float(b_mae) == 0.30
            assert float(b_wape) == 0.20

            # 2. Kiểm tra forecast_result: Đúng số dòng (8 chuỗi x (7 + 30) = 296 dòng)
            expected_rows = 8 * (7 + 30)
            assert total_inserted == expected_rows

            cur.execute("SELECT COUNT(*), MIN(predicted_qty) FROM forecast_result WHERE run_id = %s;", (run_id,))
            cnt_res, min_pred = cur.fetchone()
            assert cnt_res == expected_rows
            assert float(min_pred) >= 0.0, "Không được có predicted_qty âm"

            # 3. Kiểm tra khớp báo cáo: Tỷ lệ predicted_qty = 0 ở nhóm bán chậm trong DB ≈ số trong report
            # Nhóm bán chậm gồm 102, 103, 104; tất cả dự báo đều < 0.4 nên sau hậu xử lý 100% bằng 0.0
            cur.execute(
                """
                SELECT 
                    COUNT(*) FILTER (WHERE predicted_qty = 0) * 1.0 / COUNT(*) AS zero_ratio
                FROM forecast_result
                WHERE run_id = %s AND horizon = 7 AND product_id IN (102, 103, 104);
                """,
                (run_id,),
            )
            db_zero_ratio = float(cur.fetchone()[0])
            expected_zero_ratio = report.lgb_results_clean[7]["lgb_tweedie"]["slow_moving"].zero_pred_ratio
            # Sai lệch tối đa không vượt quá 0.5 điểm % (0.005)
            assert abs(db_zero_ratio - expected_zero_ratio) <= 0.005, (
                f"Tỷ lệ zero trong DB ({db_zero_ratio:.4f}) lệch so với báo cáo ({expected_zero_ratio:.4f})"
            )
    finally:
        conn2.close()


def test_persist_forecast_to_db_postgres_failure_rollback(postgres_container, monkeypatch):
    """Ca lỗi: save_forecast_results ghi dở dang rồi ném lỗi; kiểm tra status = FAILED và 0 dòng kết quả."""
    cutoff = date(2026, 10, 1)
    report = DummyReport(cutoff_date=cutoff, top_sku_ids={101}, optimal_thresholds={7: 0.4, 30: 0.4})
    df_daily = _make_dummy_daily_sales(cutoff)
    models = {7: DummyModel({101: 5.0}), 30: DummyModel({101: 5.0})}

    # Monkeypatch hàm save_forecast_results: ghi một phần dữ liệu rồi quăng Exception
    import app.pipelines.forecast_runner as fr_mod
    original_save = fr_mod.save_forecast_results

    def faulty_save(conn, run_id, results_df, batch_size=10_000):
        # Ghi thử vài dòng vào DB nhưng không commit
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO forecast_result (run_id, branch_id, product_id, horizon, target_date, predicted_qty) "
                "VALUES (%s, 1, 101, 7, '2026-10-02', 12.34);",
                (run_id,),
            )
        # Ném lỗi mô phỏng mất kết nối DB hoặc vi phạm dữ liệu
        raise RuntimeError("Mô phỏng sự cố mạng ngắt kết nối giữa chừng!")

    monkeypatch.setattr(fr_mod, "save_forecast_results", faulty_save)

    conn1 = _get_pg_connection(postgres_container)
    caught_run_id = None
    try:
        with pytest.raises(RuntimeError, match="Mô phỏng sự cố mạng"):
            # Lấy run_id từ lần chạy bị lỗi
            persist_forecast_to_db(
                conn=conn1,
                report=report,
                best_models=models,
                df_daily=df_daily,
                model_version="LightGBM-Faulty-Test",
            )
    finally:
        conn1.close()

    # Mở kết nối mới conn2 để kiểm tra trạng thái trong PostgreSQL
    conn2 = _get_pg_connection(postgres_container)
    try:
        with conn2.cursor() as cur:
            # Tìm lượt chạy LightGBM-Faulty-Test mới nhất
            cur.execute(
                "SELECT id, status, finished_at FROM forecast_run WHERE model_version = 'LightGBM-Faulty-Test' "
                "ORDER BY id DESC LIMIT 1;"
            )
            row = cur.fetchone()
            assert row is not None, "forecast_run phải được ghi nhận (RUNNING -> FAILED)"
            run_id, status, finished_at = row
            assert status == "FAILED", f"Trạng thái thực tế là {status}, kỳ vọng FAILED"
            assert finished_at is not None

            # KIỂM TRA ROLLBACK: Bảng forecast_result phải có ĐÚNG 0 dòng thuộc run_id này
            cur.execute("SELECT COUNT(*) FROM forecast_result WHERE run_id = %s;", (run_id,))
            cnt = cur.fetchone()[0]
            assert cnt == 0, f"Rollback thất bại: Vẫn còn {cnt} dòng trong forecast_result!"
    finally:
        conn2.close()


def test_persist_forecast_to_db_postgres_datetime64_parquet_format(postgres_container):
    """Kiểm tra persist_forecast_to_db chạy trọn vẹn với sale_date kiểu datetime64[ms] (đúng dạng đọc từ parquet)."""
    cutoff = date(2026, 10, 1)
    report = DummyReport(cutoff_date=cutoff, top_sku_ids={101}, optimal_thresholds={7: 0.4, 30: 0.4})
    df_daily = _make_dummy_daily_sales(cutoff)

    # Ép kiểu sale_date sang datetime64[ms] mô phỏng dữ liệu đọc từ parquet
    df_daily["sale_date"] = pd.to_datetime(df_daily["sale_date"]).astype("datetime64[ms]")

    pred_values = {101: 8.0, 102: 0.2, 103: 0.15, 104: 0.1}
    models = {7: DummyModel(pred_values), 30: DummyModel(pred_values)}

    conn1 = _get_pg_connection(postgres_container)
    try:
        run_id, total_inserted = persist_forecast_to_db(
            conn=conn1,
            report=report,
            best_models=models,
            df_daily=df_daily,
            model_version="LightGBM-Parquet-Datetime64-Test",
        )
    finally:
        conn1.close()

    conn2 = _get_pg_connection(postgres_container)
    try:
        with conn2.cursor() as cur:
            cur.execute(
                "SELECT status, finished_at FROM forecast_run WHERE id = %s;",
                (run_id,),
            )
            status, finished_at = cur.fetchone()
            assert status == "DONE"
            assert finished_at is not None

            expected_rows = 8 * (7 + 30)
            assert total_inserted == expected_rows

            cur.execute("SELECT COUNT(*), MIN(predicted_qty) FROM forecast_result WHERE run_id = %s;", (run_id,))
            cnt_res, min_pred = cur.fetchone()
            assert cnt_res == expected_rows
            assert float(min_pred) >= 0.0
    finally:
        conn2.close()


def test_api_forecast_run_e2e_postgres(postgres_container, auth_headers, monkeypatch):
    """Kiểm thử tích hợp API end-to-end trên PostgreSQL 16 thật (#114).

    Kiểm tra:
    1. POST /forecast/run trả 202, runId và không tạo run thứ hai.
    2. Background task thực thi, ghi đúng số dòng vào forecast_result và cập nhật DONE.
    3. GET /forecast/runs/{runId} trả 200 DONE kèm các chỉ số MAE, WAPE.
    """
    from contextlib import contextmanager
    from fastapi.testclient import TestClient
    from app.main import create_app
    import app.pipelines.forecast_service as fs_mod

    cutoff = date(2026, 10, 1)
    df_daily = _make_dummy_daily_sales(cutoff)

    # 1. Nạp dữ liệu vào bảng daily_sales trong PostgreSQL container
    conn_setup = _get_pg_connection(postgres_container)
    try:
        with conn_setup.cursor() as cur:
            for _, r in df_daily.iterrows():
                cur.execute(
                    """
                    INSERT INTO daily_sales (
                        sale_date, branch_id, product_id, qty_sold, qty_returned, qty_net,
                        revenue, promo_flag, promo_discount_pct, stockout
                    )
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (sale_date, branch_id, product_id) DO NOTHING;
                    """,
                    (
                        r["sale_date"],
                        int(r["branch_id"]),
                        int(r["product_id"]),
                        int(r["qty_net"]),
                        0,
                        int(r["qty_net"]),
                        int(r["qty_net"] * 1000),
                        False,
                        0.0,
                        False,
                    ),
                )
        conn_setup.commit()
    finally:
        conn_setup.close()

    # Context manager connection factory trỏ tới Postgres container
    @contextmanager
    def pg_conn_factory():
        c = _get_pg_connection(postgres_container)
        try:
            yield c
        finally:
            c.close()

    monkeypatch.setattr("app.pipelines.forecast_service.get_db_connection", pg_conn_factory)
    monkeypatch.setattr("app.api.router.get_db_connection", pg_conn_factory)

    # Mock phần train để test chạy nhanh (~vài trăm ms), kiểm tra toàn bộ luồng DB thật
    report = DummyReport(cutoff_date=cutoff, top_sku_ids={101}, optimal_thresholds={7: 0.4, 30: 0.4})
    pred_values = {101: 9.5, 102: 0.15, 103: 0.10, 104: 0.05}
    models = {7: DummyModel(pred_values), 30: DummyModel(pred_values)}
    monkeypatch.setattr(fs_mod, "evaluate_lightgbm_pipeline", lambda df_daily, horizons: (report, models))

    app = create_app(pg_conn_factory)
    client = TestClient(app)

    # 2. Gọi POST /forecast/run
    post_res = client.post("/forecast/run", headers=auth_headers)
    assert post_res.status_code == 202
    post_data = post_res.json()
    run_id = post_data["runId"]
    assert post_data["status"] == "RUNNING"
    assert "startedAt" in post_data

    # 3. Gọi GET /forecast/runs/{runId}
    get_res = client.get(f"/forecast/runs/{run_id}", headers=auth_headers)
    assert get_res.status_code == 200
    get_data = get_res.json()
    assert get_data["runId"] == run_id
    assert get_data["status"] == "DONE"
    assert get_data["finishedAt"] is not None
    assert get_data["mae"] == 0.25
    assert get_data["wape"] == 0.15
    assert get_data["baselineMae"] == 0.30
    assert get_data["baselineWape"] == 0.20

    # 4. Kiểm tra trong DB: Đúng số dòng kết quả (8 chuỗi x 37 ngày = 296 dòng) và không có run trùng lặp
    conn_verify = _get_pg_connection(postgres_container)
    try:
        with conn_verify.cursor() as cur:
            # Kiểm tra duy nhất 1 run cho run_id này
            cur.execute("SELECT COUNT(*) FROM forecast_run WHERE id = %s;", (run_id,))
            assert cur.fetchone()[0] == 1

            # Kiểm tra số dòng kết quả
            cur.execute("SELECT COUNT(*), MIN(predicted_qty) FROM forecast_result WHERE run_id = %s;", (run_id,))
            cnt, min_qty = cur.fetchone()
            assert cnt == 8 * (7 + 30)
            assert float(min_qty) >= 0.0
    finally:
        conn_verify.close()


def test_api_forecast_run_concurrency_409(postgres_container, auth_headers, monkeypatch):
    """Kiểm tra chặn đồng thời trả về HTTP 409 khi đã có run RUNNING (#114)."""
    from contextlib import contextmanager
    from fastapi.testclient import TestClient
    from app.main import create_app

    @contextmanager
    def pg_conn_factory():
        c = _get_pg_connection(postgres_container)
        try:
            yield c
        finally:
            c.close()

    monkeypatch.setattr("app.pipelines.forecast_service.get_db_connection", pg_conn_factory)
    monkeypatch.setattr("app.api.router.get_db_connection", pg_conn_factory)

    # Chèn một run RUNNING vừa mới chạy
    conn = _get_pg_connection(postgres_container)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO forecast_run (started_at, status, model_version) "
                "VALUES (NOW(), 'RUNNING', 'Active-Run') RETURNING id;"
            )
            active_run_id = cur.fetchone()[0]
        conn.commit()
    finally:
        conn.close()

    app = create_app(pg_conn_factory)
    client = TestClient(app)

    res = client.post("/forecast/run", headers=auth_headers)
    assert res.status_code == 409
    data = res.json()
    assert data["code"] == "CONFLICT"
    assert f"run_id={active_run_id}" in data["message"]


def test_api_forecast_run_stale_cleanup_postgres(postgres_container, auth_headers, monkeypatch):
    """Kiểm tra tự động đánh dấu FAILED các run RUNNING quá 30 phút và khởi tạo run mới (#114)."""
    from contextlib import contextmanager
    from fastapi.testclient import TestClient
    from app.main import create_app
    import app.pipelines.forecast_service as fs_mod

    @contextmanager
    def pg_conn_factory():
        c = _get_pg_connection(postgres_container)
        try:
            yield c
        finally:
            c.close()

    monkeypatch.setattr("app.pipelines.forecast_service.get_db_connection", pg_conn_factory)
    monkeypatch.setattr("app.api.router.get_db_connection", pg_conn_factory)

    # 1. Chèn một run RUNNING kẹt từ 35 phút trước
    conn = _get_pg_connection(postgres_container)
    try:
        with conn.cursor() as cur:
            # Xóa các run RUNNING còn sót để đảm bảo test độc lập
            cur.execute("UPDATE forecast_run SET status = 'DONE' WHERE status = 'RUNNING';")
            cur.execute(
                """
                INSERT INTO forecast_run (started_at, status, model_version)
                VALUES (NOW() - INTERVAL '35 minutes', 'RUNNING', 'Stale-Run')
                RETURNING id;
                """
            )
            stale_id = cur.fetchone()[0]
        conn.commit()
    finally:
        conn.close()

    cutoff = date(2026, 10, 1)
    report = DummyReport(cutoff_date=cutoff, top_sku_ids={101}, optimal_thresholds={7: 0.4, 30: 0.4})
    models = {7: DummyModel({101: 5.0}), 30: DummyModel({101: 5.0})}
    monkeypatch.setattr(fs_mod, "evaluate_lightgbm_pipeline", lambda df_daily, horizons: (report, models))

    app = create_app(pg_conn_factory)
    client = TestClient(app)

    # 2. Gọi POST /forecast/run -> Phải thành công vì run cũ đã quá 30 phút và bị đánh dấu FAILED
    res = client.post("/forecast/run", headers=auth_headers)
    assert res.status_code == 202
    new_run_id = res.json()["runId"]
    assert new_run_id != stale_id

    # 3. Kiểm tra trong DB: stale_id phải có status FAILED và finished_at không NULL
    conn_verify = _get_pg_connection(postgres_container)
    try:
        with conn_verify.cursor() as cur:
            cur.execute("SELECT status, finished_at FROM forecast_run WHERE id = %s;", (stale_id,))
            stale_status, finished_at = cur.fetchone()
            assert stale_status == "FAILED"
            assert finished_at is not None
    finally:
        conn_verify.close()


def test_complete_forecast_run_records_real_clock_timestamp(postgres_container):
    """Kiểm tra finished_at ghi nhận clock_timestamp thật thay vì transaction start time (#114)."""
    import time
    from app.pipelines.forecast_runner import complete_forecast_run

    conn = _get_pg_connection(postgres_container)
    try:
        with conn.cursor() as cur:
            # 1. Bắt đầu transaction và tạo một run mới với clock_timestamp()
            cur.execute(
                """
                INSERT INTO forecast_run (started_at, status, model_version)
                VALUES (clock_timestamp(), 'RUNNING', 'Clock-Test')
                RETURNING id, started_at;
                """
            )
            run_id, started_at = cur.fetchone()

            # 2. Chờ 0.6s trong CÙNG transaction
            time.sleep(0.6)

            # 3. Gọi complete_forecast_run trong cùng connection/transaction này
            complete_forecast_run(
                conn,
                run_id=run_id,
                mae=0.25,
                wape=0.15,
                baseline_mae=0.30,
                baseline_wape=0.20,
            )

            # 4. Lấy lại finished_at và kiểm tra chênh lệch thời gian thực tế
            cur.execute("SELECT started_at, finished_at FROM forecast_run WHERE id = %s;", (run_id,))
            db_started_at, db_finished_at = cur.fetchone()

            duration = (db_finished_at - db_started_at).total_seconds()
            assert duration >= 0.5, f"Kỳ vọng duration >= 0.5s nhưng nhận {duration}s"
        conn.commit()
    finally:
        conn.close()


