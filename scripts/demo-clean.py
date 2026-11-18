"""Create an isolated demo stack and record cold start including build and seed (#142)."""
import argparse
import json
import os
from pathlib import Path
import secrets
import re
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent

def run(command, **kwargs):
    return subprocess.run(command, check=True, cwd=ROOT, **kwargs)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default="greenmart-demo-" + time.strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--port", type=int, default=28080)
    parser.add_argument("--skip-build", action="store_true", help="Use previously built images tagged with --project")
    args = parser.parse_args()
    if not re.fullmatch(r"greenmart-demo-[a-z0-9-]{1,45}", args.project) or not 1024 <= args.port <= 65000:
        parser.error("Use a greenmart-demo- project and port between 1024 and 65000")
    for port in range(args.port, args.port + 4):
        with socket.socket() as probe:
            try:
                probe.bind(("127.0.0.1", port))
            except OSError:
                parser.error(f"Port {port} unavailable; choose a different --port before building")
    work = ROOT / ".local-tools" / args.project
    work.mkdir(parents=True, exist_ok=False)
    settings = {"POSTGRES_DB": "greenmart", "POSTGRES_USER": "greenmart", "FORECAST_DB_USER": "forecast_ro",
                "IMAGE_TAG": args.project, "RABBITMQ_DEFAULT_USER": "greenmart", "MOCK_CONNECTOR_URL": "http://mock-connector:8090"}
    for key in ("POSTGRES_PASSWORD", "FORECAST_DB_PASSWORD", "REDIS_PASSWORD", "RABBITMQ_DEFAULT_PASS",
                "JWT_SECRET", "CHANNEL_WEB_SECRET", "CHANNEL_MARKET_SECRET", "FORECAST_INTERNAL_TOKEN"):
        settings[key] = secrets.token_urlsafe(48)
    envfile = work / ".env"
    envfile.write_text("".join(f"{k}={v}\n" for k, v in settings.items()), encoding="utf-8")
    os.chmod(envfile, 0o600)
    overrides = {"services": {"backend": {"env_file": [str(envfile)], "ports": [f"127.0.0.1:{args.port}:8080"]},
                 "frontend": {"ports": [f"127.0.0.1:{args.port+1}:8080"]},
                 "mock-connector": {"ports": [f"127.0.0.1:{args.port+2}:8090"]},
                 "forecast-service": {"ports": [f"127.0.0.1:{args.port+3}:8000"]}}}
    # !override replaces host port mappings instead of exposing both old and new ports.
    lines = ["services:"]
    for service, config in overrides["services"].items():
        lines += [f"  {service}:", "    ports: !override"]
        lines += [f"      - {json.dumps(value)}" for value in config["ports"]]
        if service == "backend":
            lines += ["    env_file: !override", f"      - {json.dumps(str(envfile))}"]
    override = work / "ports.yml"
    override.write_text("\n".join(lines) + "\n", encoding="utf-8")
    prefix = ["docker", "compose", "--env-file", str(envfile), "-p", args.project,
              "-f", "docker-compose.yml", "-f", str(override)]
    # Shell variables take precedence over --env-file, so use the same generated values in subprocesses.
    env = dict(os.environ, PYTHONUTF8="1", **settings)
    started = time.monotonic()
    build_options = [] if args.skip_build else ["--build"]
    run([*prefix, "up", *build_options, "-d", "--wait", "--wait-timeout", "600"], env=env)
    run(["python", "forecast-service/scripts/generate_data.py"], env=env)
    sql = ROOT / "forecast-service/data/seed_data.sql"
    with sql.open("rb") as source:
        run([*prefix, "exec", "-T", "postgres", "sh", "-ec",
             'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1'], stdin=source, env=env)
    for suffix in ("/actuator/health/readiness",):
        with urllib.request.urlopen(f"http://127.0.0.1:{args.port}{suffix}", timeout=10) as response:
            if json.load(response)["status"] != "UP":
                raise RuntimeError("Backend not ready")
    with urllib.request.urlopen(f"http://127.0.0.1:{args.port+1}/healthz", timeout=10) as response:
        if response.status != 200:
            raise RuntimeError("Frontend not ready")
    with urllib.request.urlopen(f"http://127.0.0.1:{args.port+3}/healthz", timeout=10) as response:
        if response.status != 200:
            raise RuntimeError("Forecast not ready")
    result = {"project": args.project, "seconds_total": round(time.monotonic()-started, 2), "build_included": not args.skip_build,
              "frontend": f"http://127.0.0.1:{args.port+1}", "seed": "loaded", "compose": prefix}
    (work / "timing.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))

if __name__ == "__main__":
    main()
