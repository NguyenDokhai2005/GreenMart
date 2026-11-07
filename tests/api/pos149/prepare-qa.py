"""Build and seed a NEW, isolated POS QA stack; never reset existing volumes.

Uses unchanged backend/seed from --source-root, which may be an isolated Git snapshot.
Secrets exist only in child process environments; raw Docker output is not published.
"""
import argparse
import os
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[3]
PROJECT = "greenmart-qa149"


def call(command, **kwargs):
    result = subprocess.run(command, capture_output=True, timeout=600, **kwargs)
    if result.returncode:
        raise SystemExit(f"QA step failed (exit {result.returncode}); raw output withheld")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--stop", action="store_true", help="Stop only qa149 containers; preserve volumes")
    args = parser.parse_args()
    ids = call(["docker", "ps", "-aq", "--filter", f"label=com.docker.compose.project={PROJECT}"]).decode().split()
    if args.stop:
        if ids:
            call(["docker", "stop", *ids])
        print("QA149 containers stopped; volumes preserved")
        return
    volumes = call(["docker", "volume", "ls", "-q", "--filter", f"label=com.docker.compose.project={PROJECT}"])
    if ids or volumes.strip():
        raise SystemExit("QA149 already exists: refuse reseeding. Run Newman against it or inspect it manually.")
    source = args.source_root.resolve()
    data = ROOT / ".local-tools" / "pos149-data"
    data.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    env.update(POSTGRES_DB="greenmart",POSTGRES_USER="greenmart",FORECAST_DB_USER="forecast_ro",
               RABBITMQ_DEFAULT_USER="greenmart")
    for key in ["POSTGRES_PASSWORD","FORECAST_DB_PASSWORD","REDIS_PASSWORD","RABBITMQ_DEFAULT_PASS",
                "JWT_SECRET","CHANNEL_WEB_SECRET","CHANNEL_MARKET_SECRET"]:
        env[key] = secrets.token_hex(32)
    compose = ["docker","compose","-p",PROJECT,"-f",str(source / "docker-compose.yml"),
               "-f",str(ROOT / "tests/api/pos149/compose.qa.yml")]
    print("Validating and building isolated QA149 backend and seed runtime...",flush=True)
    call(compose + ["config","--quiet"],env=env)
    call(compose + ["build","backend"],env=env)
    call(["docker","build","-t","greenmart-forecast:qa149",str(source / "forecast-service")],env=env)
    print("Starting QA149 on 127.0.0.1:18081...",flush=True)
    call(compose + ["up","-d","--wait","--wait-timeout","300","postgres","redis","rabbitmq","backend"],env=env)
    print("Generating unchanged SV06 seed; loading seed and QA-only fixture...",flush=True)
    call(["docker","run","--rm","--network","none","--entrypoint","python",
          "--mount",f"type=bind,src={source / 'forecast-service/scripts'},dst=/qa/scripts,readonly",
          "--mount",f"type=bind,src={data},dst=/qa/data","-w","/qa",
          "greenmart-forecast:qa149","scripts/generate_data.py"],env=env)
    psql = compose + ["exec","-T","postgres","psql","-X","-U","greenmart","-d","greenmart","-v","ON_ERROR_STOP=1"]
    call(psql,env=env,input=(data / "seed_data.sql").read_bytes())
    call(psql,env=env,input=(ROOT / "tests/api/pos149/fixture.sql").read_bytes())
    print("QA149 ready; existing development/QA148 data untouched",flush=True)


if __name__ == "__main__":
    main()
