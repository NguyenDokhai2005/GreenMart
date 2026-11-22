"""Validate the actual seed SQL check on disposable PostgreSQL, never a configured DB."""

from contextlib import closing, redirect_stdout
import io
from pathlib import Path
import shutil
import tempfile
from unittest import mock

import docker
import psycopg
import pytest
try:
    from testcontainers.community.postgres import PostgresContainer
except ImportError:
    from testcontainers.postgres import PostgresContainer

ROOT = Path(__file__).resolve().parents[2]
pytestmark = pytest.mark.integration
SCRIPT = ROOT / "forecast-service/scripts/verify_seed.sql"
CHECK = SCRIPT.read_text(encoding="utf-8").split("-- BEGIN ISSUE 422 PROMOTION USAGE CHECK\n", 1)[1].split(
    "-- END ISSUE 422 PROMOTION USAGE CHECK", 1
)[0]


@pytest.fixture(scope="module")
def database():
    try:
        with closing(docker.from_env()) as client:
            client.ping()
    except docker.errors.DockerException:
        pytest.skip("Docker daemon is unavailable")
    with PostgresContainer("postgres:16.10-alpine") as pg:
        with psycopg.connect(host=pg.get_container_host_ip(), port=pg.get_exposed_port(5432),
                             user=pg.username, password=pg.password, dbname=pg.dbname, autocommit=True) as db:
            yield db


@pytest.fixture
def small_seed(database):
    database.execute("""
        DROP SCHEMA public CASCADE; CREATE SCHEMA public;
        CREATE TABLE promotion(id int PRIMARY KEY,used_count int NOT NULL,usage_limit int);
        CREATE TABLE sales_order(id int PRIMARY KEY,status text,promotion_id int);
        CREATE TABLE order_item(order_id int,promotion_id int,quantity int);
        INSERT INTO promotion VALUES(1,2,10),(2,1,NULL),(3,0,NULL),(4,1,NULL);
        INSERT INTO sales_order VALUES(10,'COMPLETED',2),(11,'RETURNED',NULL),
            (12,'CANCELLED',1),(13,'EXPIRED',1),(14,'REJECTED',1),(15,'COMPLETED',4);
        INSERT INTO order_item VALUES(10,1,5),(10,1,3),(11,1,2),
            (12,1,1),(13,1,1),(14,1,1),(15,4,9);
    """)
    return database


def test_distinct_campaign_order_usage_keeps_returns_and_excludes_failed_orders(small_seed):
    before = small_seed.execute("SELECT * FROM promotion ORDER BY id").fetchall()
    small_seed.execute(CHECK)
    assert small_seed.execute("SELECT * FROM promotion ORDER BY id").fetchall() == before


@pytest.mark.parametrize("counter", [1, 3, 8])
def test_wrong_counter_is_rejected_without_repair(small_seed, counter):
    small_seed.execute("UPDATE promotion SET used_count=%s WHERE id=1", (counter,))
    with pytest.raises(psycopg.Error, match=r"\[#422\]"):
        small_seed.execute(CHECK)
    assert small_seed.execute("SELECT used_count FROM promotion WHERE id=1").fetchone()[0] == counter


def test_unused_campaign_cannot_have_phantom_usage(small_seed):
    small_seed.execute("UPDATE promotion SET used_count=1 WHERE id=3")
    with pytest.raises(psycopg.Error, match=r"\[#422\]"):
        small_seed.execute(CHECK)


@pytest.mark.parametrize("status", ["NEW", "RESERVED", "CONFIRMED"])
def test_seed_check_refuses_to_reconcile_live_reservations(small_seed, status):
    small_seed.execute("INSERT INTO sales_order VALUES(20,%s,NULL)", (status,))
    with pytest.raises(psycopg.Error, match="seed-only"):
        small_seed.execute(CHECK)


def test_usage_limit_is_still_enforced(small_seed):
    small_seed.execute("UPDATE promotion SET usage_limit=1 WHERE id=1")
    with pytest.raises(psycopg.Error, match=r"\[#422\]"):
        small_seed.execute(CHECK)


def test_full_generated_seed_matches_develop_schema_and_verifier(database):
    from scripts import generate_data

    database.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public")
    migrations = ROOT / "backend/src/main/resources/db/migration"
    for path in sorted(migrations.glob("V*__*.sql"), key=lambda p: int(p.name.split("__", 1)[0][1:])):
        database.execute(path.read_text(encoding="utf-8").replace("${forecast_user}", "forecast_ro"))
    # main() derives output paths and the stock-alert include from __file__. Redirect both to a temp tree.
    with tempfile.TemporaryDirectory() as directory:
        script_dir = Path(directory) / "scripts"
        script_dir.mkdir()
        shutil.copyfile(ROOT / "forecast-service/scripts/rebuild_stock_alerts.sql", script_dir / "rebuild_stock_alerts.sql")
        with mock.patch.object(generate_data, "__file__", str(script_dir / "generate_data.py")), redirect_stdout(io.StringIO()):
            generate_data.main()
        sql = (Path(directory) / "data/seed_data.sql").read_text(encoding="utf-8")
        database.execute(sql)
    verifier = "\n".join(line for line in SCRIPT.read_text(encoding="utf-8").splitlines() if not line.startswith("\\"))
    database.execute(verifier)
    mismatches = database.execute("""
        WITH uses AS (
            SELECT promotion_id,id AS order_id FROM sales_order
            WHERE status IN ('COMPLETED','RETURNED') AND promotion_id IS NOT NULL
            UNION
            SELECT i.promotion_id,o.id FROM order_item i JOIN sales_order o ON o.id=i.order_id
            WHERE o.status IN ('COMPLETED','RETURNED') AND i.promotion_id IS NOT NULL
        )
        SELECT count(*) FROM promotion p
        WHERE p.used_count<>(SELECT count(*) FROM uses u WHERE u.promotion_id=p.id)
    """).fetchone()[0]
    assert mismatches == 0
