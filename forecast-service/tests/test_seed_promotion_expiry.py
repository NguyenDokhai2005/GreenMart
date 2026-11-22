"""Exercise the exact seed normalization on a private PostgreSQL instance."""

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

from scripts.generate_data import PROMOTION_EXPIRY_SQL

pytestmark = pytest.mark.integration


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


def test_import_time_expiry_preserves_usage_and_history_and_is_idempotent(database):
    # Freeze CURRENT_TIMESTAMP across the equality-boundary fixture and normalization.
    with database.transaction():
        db = database
        db.execute("""
            CREATE TABLE promotion(id int PRIMARY KEY,status text,end_at timestamptz,used_count int);
            INSERT INTO promotion VALUES
                (1,'DRAFT',CURRENT_TIMESTAMP,2),
                (2,'ACTIVE',CURRENT_TIMESTAMP-interval '1 day',7),
                (3,'PAUSED',CURRENT_TIMESTAMP,3),
                (4,'ENDED',CURRENT_TIMESTAMP-interval '1 day',4),
                (5,'ACTIVE',CURRENT_TIMESTAMP+interval '1 day',5),
                (6,'DRAFT',CURRENT_TIMESTAMP+interval '1 day',0),
                (7,'PAUSED',CURRENT_TIMESTAMP+interval '1 day',1);
            """)
        history = db.execute("SELECT id,end_at,used_count FROM promotion ORDER BY id").fetchall()
        db.execute(PROMOTION_EXPIRY_SQL)
        assert db.execute("SELECT status FROM promotion ORDER BY id").fetchall() == [
            ("ENDED",), ("ENDED",), ("ENDED",), ("ENDED",), ("ACTIVE",), ("DRAFT",), ("PAUSED",)]
        assert db.execute("SELECT id,end_at,used_count FROM promotion ORDER BY id").fetchall() == history
        snapshot = db.execute("SELECT * FROM promotion ORDER BY id").fetchall()
        db.execute(PROMOTION_EXPIRY_SQL)
        assert db.execute("SELECT * FROM promotion ORDER BY id").fetchall() == snapshot


def test_full_seed_import_sets_expired_status_and_passes_existing_checks(database):
    from scripts import generate_data

    root = Path(__file__).resolve().parents[2]
    database.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public")
    migrations = root / "backend/src/main/resources/db/migration"
    for path in sorted(migrations.glob("V*__*.sql"), key=lambda p: int(p.name.split("__", 1)[0][1:])):
        database.execute(path.read_text(encoding="utf-8").replace("${forecast_user}", "forecast_ro"))
    with tempfile.TemporaryDirectory() as directory:
        scripts = Path(directory) / "scripts"
        scripts.mkdir()
        shutil.copyfile(root / "forecast-service/scripts/rebuild_stock_alerts.sql", scripts / "rebuild_stock_alerts.sql")
        with mock.patch.object(generate_data, "__file__", str(scripts / "generate_data.py")), redirect_stdout(io.StringIO()):
            generate_data.main()
        database.execute((Path(directory) / "data/seed_data.sql").read_text(encoding="utf-8"))
    assert database.execute("SELECT count(*) FROM promotion WHERE end_at<=CURRENT_TIMESTAMP AND status<>'ENDED'").fetchone()[0] == 0
    assert database.execute("SELECT count(*) FROM promotion WHERE status='ENDED'").fetchone()[0] > 0
    verifier = (root / "forecast-service/scripts/verify_seed.sql").read_text(encoding="utf-8")
    database.execute("\n".join(line for line in verifier.splitlines() if not line.startswith("\\")))
