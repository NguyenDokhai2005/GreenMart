from contextlib import closing
from pathlib import Path

import docker
import psycopg
import pytest
try:
    from testcontainers.community.postgres import PostgresContainer
except ImportError:
    from testcontainers.postgres import PostgresContainer


def test_rebuild_stock_alerts_reconciles_inventory_and_is_idempotent():
    try:
        with closing(docker.from_env()) as client:
            client.ping()
    except docker.errors.DockerException:
        pytest.skip("Docker daemon is unavailable")

    root = Path(__file__).resolve().parents[2]
    migrations = root / "backend/src/main/resources/db/migration"
    rebuild = (root / "forecast-service/scripts/rebuild_stock_alerts.sql").read_text(encoding="utf-8")
    with PostgresContainer("postgres:16.10-alpine") as postgres:
        with psycopg.connect(host=postgres.get_container_host_ip(), port=postgres.get_exposed_port(5432),
                             user=postgres.username, password=postgres.password, dbname=postgres.dbname) as db:
            for migration in ("V1__init_schema.sql", "V12__stock_alert.sql"):
                db.execute((migrations / migration).read_text(encoding="utf-8"))
            db.execute("""
                INSERT INTO branch(id, code, name) VALUES (1, 'B1', 'Branch');
                INSERT INTO category(id, name) VALUES (1, 'Category');
                INSERT INTO product(id, sku, name, unit, category_id, sale_price, cost_price)
                SELECT id, 'SKU-' || id, 'Product', 'box', 1, 10, 5 FROM generate_series(1, 4) id;
                INSERT INTO inventory(branch_id, product_id, on_hand, reserved, reorder_point)
                VALUES (1, 1, 1, 1, NULL), (1, 2, 5, 2, 3), (1, 3, 4, 0, 3), (1, 4, 4, 0, NULL);
            """)

            db.execute(rebuild)
            snapshot = "SELECT * FROM stock_alert ORDER BY product_id, id"
            assert db.execute("""
                SELECT product_id, severity FROM stock_alert WHERE closed_at IS NULL ORDER BY product_id
            """).fetchall() == [(1, "OUT"), (2, "LOW")]
            first = db.execute(snapshot).fetchall()

            db.execute(rebuild)
            assert db.execute(snapshot).fetchall() == first

            db.execute("UPDATE inventory SET on_hand = 9 WHERE product_id = 1")
            db.execute("UPDATE inventory SET reserved = on_hand WHERE product_id = 2")
            db.execute(rebuild)
            assert db.execute("""
                SELECT id, product_id, severity, closed_at IS NOT NULL FROM stock_alert ORDER BY product_id
            """).fetchall() == [(first[0][0], 1, "OUT", True), (first[1][0], 2, "OUT", False)]
            assert db.execute("SELECT count(*) FROM outbox_event").fetchone() == (0,)
