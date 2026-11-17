"""Back up and verify a GreenMart PostgreSQL database through Docker Compose."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

TABLES = ("product", "customer", "inventory", "sales_order", "order_item", "outbox_event")
COUNTS = "SELECT json_build_object(" + ",".join(
    f"'{t}',(SELECT count(*) FROM public.{t})" for t in TABLES
) + ");"
PSQL = 'exec psql -XqAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$1"'
SOURCE_DB = 'exec psql -XqAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
DUMP = 'exec pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB" --snapshot "$1"'
RESTORE = 'exec pg_restore -U "$POSTGRES_USER" -d "$1" --exit-on-error --single-transaction --no-owner --no-privileges'


def command(prefix, script, *args):
    return [*prefix, "exec", "-T", "postgres", "sh", "-ec", script, "sh", *args]


def capture(prefix, script, *args, sql=None):
    return subprocess.run(command(prefix, script, *args), input=sql, capture_output=True,
                          text=True, check=True, timeout=120).stdout.strip()


def backup(prefix, bundle):
    if bundle.exists():
        raise SystemExit("Backup directory already exists")
    with tempfile.TemporaryDirectory(prefix=".greenmart-", dir=bundle.parent) as tmp:
        work = Path(tmp)
        with subprocess.Popen(command(prefix, SOURCE_DB), stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, text=True) as session:
            try:
                session.stdin.write("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n"
                                    "SELECT pg_export_snapshot();\n" + COUNTS + "\n")
                session.stdin.flush()
                snapshot = session.stdout.readline().strip()
                if not re.fullmatch(r"[0-9A-Fa-f-]+", snapshot):
                    raise RuntimeError("PostgreSQL did not export a snapshot")
                counts = json.loads(session.stdout.readline())
                with (work / "database.dump").open("xb") as dump:
                    subprocess.run(command(prefix, DUMP, snapshot), stdout=dump, check=True, timeout=900)
            finally:
                if session.poll() is None:
                    session.stdin.close()
                    session.wait(timeout=15)
        (work / "counts.json").write_text(json.dumps(counts, indent=2) + "\n", encoding="utf-8")
        # Create the destination exclusively. A directory without both files is not a valid bundle.
        bundle.mkdir(mode=0o700)
        for name in ("database.dump", "counts.json"):
            os.replace(work / name, bundle / name)
    print("Backup counts:", json.dumps(counts, sort_keys=True))


def restore(prefix, bundle, target):
    if not re.fullmatch(r"restore_[a-z0-9_]{1,48}", target):
        raise SystemExit("Target database must start with restore_ and use lowercase letters, digits or underscores")
    counts = json.loads((bundle / "counts.json").read_text(encoding="utf-8"))
    if set(counts) != set(TABLES) or any(type(n) is not int or n < 0 for n in counts.values()):
        raise SystemExit("Invalid backup count manifest")
    with (bundle / "database.dump").open("rb") as dump:
        if dump.read(5) != b"PGDMP":
            raise SystemExit("Invalid PostgreSQL custom dump")
    with (bundle / "database.dump").open("rb", buffering=0) as dump:
        # createdb refuses an existing name; never replace or drop a database.
        capture(prefix, 'exec createdb -U "$POSTGRES_USER" --template=template0 "$1"', target)
        subprocess.run(command(prefix, RESTORE, target), stdin=dump, check=True, timeout=900)
    actual = json.loads(capture(prefix, PSQL, target, sql=COUNTS))
    if actual != counts:
        raise SystemExit(f"Restore count mismatch: expected {counts}, got {actual}")
    print(f"Restore verified in {target}:", json.dumps(actual, sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-p", "--project", required=True)
    parser.add_argument("-f", "--compose-file", action="append", default=[])
    parser.add_argument("--env-file")
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("backup").add_argument("bundle", type=Path)
    dest = sub.add_parser("restore")
    dest.add_argument("bundle", type=Path)
    dest.add_argument("target")
    args = parser.parse_args()
    prefix = ["docker", "compose", "-p", args.project]
    for file in args.compose_file or ["docker-compose.yml"]:
        prefix += ["-f", file]
    if args.env_file:
        prefix += ["--env-file", args.env_file]
    os.umask(0o077)
    if args.action == "backup":
        backup(prefix, args.bundle)
    else:
        restore(prefix, args.bundle, args.target)


if __name__ == "__main__":
    main()
