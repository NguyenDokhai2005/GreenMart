"""Create an isolated QA stack for #148; never reads the user's .env in Python.

Uses the root Compose plus an explicit QA override (no development override).
Random credentials are passed in the child process environment, never printed.
Leaves QA containers/volumes available for inspection; no volume deletion.
"""
import os
from pathlib import Path
import secrets
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
PROJECT = "greenmart-qa148"


def main():
    # Refuse to reseed an existing QA stack; preserves earlier QA data too.
    existing = subprocess.run(["docker", "ps", "-aq", "--filter", f"label=com.docker.compose.project={PROJECT}"],
                              capture_output=True, text=True, check=True)
    volume = subprocess.run(["docker", "volume", "ls", "-q", "--filter", f"label=com.docker.compose.project={PROJECT}"],
                            capture_output=True, text=True, check=True)
    if existing.stdout.strip() or volume.stdout.strip():
        raise SystemExit("QA project already exists; do not reseed. Run the checks against it directly.")
    env = os.environ.copy()
    env.update(POSTGRES_DB="greenmart", POSTGRES_USER="greenmart", FORECAST_DB_USER="forecast_ro",
               RABBITMQ_DEFAULT_USER="greenmart")
    for key in ["POSTGRES_PASSWORD", "FORECAST_DB_PASSWORD", "REDIS_PASSWORD", "RABBITMQ_DEFAULT_PASS",
                "JWT_SECRET", "CHANNEL_WEB_SECRET", "CHANNEL_MARKET_SECRET"]:
        env[key] = secrets.token_hex(32)
    compose = ["docker", "compose", "-p", PROJECT, "-f", "docker-compose.yml",
               "-f", "tests/api/compose.issue148.yml"]

    def execute(command, *, data=None, timeout=600):
        result = subprocess.run(command, cwd=ROOT, env=env, input=data,
                                capture_output=True, timeout=timeout)
        if result.returncode:
            # Do not publish Docker logs/build output: they can contain environment secrets.
            print("QA step failed; exit", result.returncode, "(raw output withheld)", flush=True)
            raise SystemExit(result.returncode)
        return result.stdout

    execute(compose + ["config", "--quiet"])
    print("Building isolated backend from local source...", flush=True)
    execute(compose + ["build", "backend"])
    print("Building QA Python image from local forecast source...", flush=True)
    execute(["docker", "build", "-t", "greenmart-forecast:qa148", "forecast-service"])
    print("Starting QA-only postgres, redis, rabbitmq, backend on port 18080...", flush=True)
    execute(compose + ["up", "-d", "--wait", "--wait-timeout", "300",
                       "postgres", "redis", "rabbitmq", "backend"])
    data = ROOT / ".local-tools" / "qa148-data"
    data.mkdir(parents=True, exist_ok=False)
    print("Generating seed using unchanged SV06 generator in the QA Python image...", flush=True)
    execute(["docker", "run", "--rm", "--network", "none", "--entrypoint", "python",
             "--mount", f"type=bind,src={ROOT / 'forecast-service' / 'scripts'},dst=/qa/scripts,readonly",
             "--mount", f"type=bind,src={data},dst=/qa/data", "-w", "/qa",
             "greenmart-forecast:qa148", "scripts/generate_data.py"])
    print("Loading seed into isolated QA database only...", flush=True)
    execute(compose + ["exec", "-T", "postgres", "psql", "-X", "-U", "greenmart", "-d", "greenmart",
                       "-v", "ON_ERROR_STOP=1"], data=(data / "seed_data.sql").read_bytes())
    print("QA seed loaded. Ready: http://127.0.0.1:18080/api/v1. QA stack left running.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
