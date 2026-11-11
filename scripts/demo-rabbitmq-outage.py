"""Verify POS and stock delivery during a five-minute broker outage in an isolated Compose project."""
import argparse
import json
import os
import re
import subprocess
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def request(base, path, payload=None, headers=None, expected=200):
    raw = None if payload is None else json.dumps(payload).encode()
    req = Request(base + path, data=raw, headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urlopen(req, timeout=15) as response:
            require(response.status == expected, f"{path}: expected {expected}, got {response.status}")
            return json.load(response)
    except HTTPError as exc:
        raise RuntimeError(f"{path}: expected {expected}, got {exc.code}") from None


def run(args):
    require(re.fullmatch(r"greenmart-demo-[a-z0-9][a-z0-9-]*", args.project),
            "Use an isolated Compose project with the greenmart-demo- prefix")
    for url in (args.backend, args.mock):
        require(re.fullmatch(r"http://127\.0\.0\.1:[0-9]+", url), "Use dedicated loopback URLs")
    require(re.fullmatch(r"[A-Za-z0-9_-]{1,60}", args.sku), "Invalid external SKU")
    compose = ["docker", "compose", "-p", args.project]
    for path in args.file:
        compose += ["-f", path]

    def docker(*parts, check=True):
        return subprocess.run([*compose, *parts], check=check, capture_output=True, text=True, timeout=60)

    def sql(query):
        return docker("exec", "-T", "postgres", "sh", "-ec",
                      'psql -XqAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"',
                      "sh", query).stdout.strip()

    for service, url, container_port in (("backend", args.backend, 8080), ("mock-connector", args.mock, 8090)):
        addresses = docker("port", service, str(container_port)).stdout.splitlines()
        require(url.removeprefix("http://") in addresses, f"{url} is not the loopback port for {service} in {args.project}")
    rabbit = docker("ps", "-q", "rabbitmq").stdout.strip()
    require(bool(re.fullmatch(r"[a-f0-9]{12,64}", rabbit)), "RabbitMQ container missing or ambiguous")
    project = subprocess.check_output(["docker", "inspect", "--format",
                                       '{{ index .Config.Labels "com.docker.compose.project" }}', rabbit], text=True).strip()
    require(project == args.project, "Unexpected RabbitMQ project")

    token = request(args.backend, "/api/v1/auth/login", {
        "username": os.environ["DEMO_POS_USER"], "password": os.environ["DEMO_POS_PASSWORD"]
    })["accessToken"]
    auth = {"Authorization": "Bearer " + token}
    payload = {"branchId": args.branch, "items": [{"productId": args.product, "quantity": 1}]}
    quote = request(args.backend, "/api/v1/orders/quote", payload, auth)
    require(quote["canComplete"], "Fixture POS order cannot complete")
    trace = "demo138-" + uuid4().hex
    condition = f"WHERE trace_id='{trace}' AND event_type IN ('order.completed','stock.changed')"
    query = f"SELECT event_type || ':' || status FROM outbox_event {condition} ORDER BY id"
    types = {"order.completed", "stock.changed"}
    stock_sql = f"""
        SELECT GREATEST(COALESCE(SUM(i.on_hand-i.reserved),0)-c.safety_stock,0)
        FROM sales_channel c JOIN channel_product cp ON cp.channel_id=c.id
        JOIN channel_branch b ON b.channel_id=c.id
        LEFT JOIN inventory i ON i.branch_id=b.branch_id AND i.product_id=cp.product_id
        WHERE c.code='{args.channel}' AND cp.external_sku='{args.sku}' AND cp.product_id={args.product}
        GROUP BY c.safety_stock
        HAVING bool_or(b.branch_id={args.branch})
    """
    expected_stock = int(sql(stock_sql))
    require(expected_stock >= 1, "Fixture has no sellable stock in channel")
    old = request(args.mock, "/mock/state")[args.channel]["stock"].get(args.sku, {})
    docker("exec", "-T", "rabbitmq", "rabbitmq-diagnostics", "-q", "check_running")
    try:
        docker("exec", "-T", "rabbitmq", "rabbitmqctl", "stop_app")
        down = time.monotonic()
        order = request(args.backend, "/api/v1/orders/pos", {
            **payload, "expectedTotal": quote["total"],
            "payment": {"method": "CASH", "cashReceived": quote["total"]}
        }, {**auth, "Idempotency-Key": str(uuid4()), "X-Correlation-ID": trace}, expected=201)
        require(order["status"] == "COMPLETED", "POS did not complete")
        pending = sql(query).splitlines()
        require(len(pending) == 2 and set(pending) == {e + ":PENDING" for e in types}, str(pending))
        print(f"POS completed: {order['code']}; outbox pending; trace={trace}", flush=True)
        time.sleep(max(0, down + 300 - time.monotonic()))
        require(docker("exec", "-T", "rabbitmq", "rabbitmq-diagnostics", "-q", "check_running",
                       check=False).returncode != 0, "Broker restarted before five minutes")
        request(args.backend, "/actuator/health/readiness")
        require(sql(query).splitlines() == pending, "Outbox changed while broker was down")
        print(f"Broker down {int(time.monotonic() - down)} seconds; backend ready", flush=True)
    finally:
        docker("exec", "-T", "rabbitmq", "rabbitmqctl", "start_app")
    for _ in range(60):
        sent = sql(query).splitlines()
        stock = request(args.mock, "/mock/state")[args.channel]["stock"].get(args.sku, {})
        if (len(sent) == 2 and set(sent) == {e + ":SENT" for e in types}
                and stock.get("version", 0) > old.get("version", 0)
                and stock.get("available") == expected_stock - 1):
            require(int(sql(stock_sql)) == stock["available"], "Mock stock differs from current DB stock")
            print(f"Outbox SENT; {args.channel}/{args.sku} stock={stock['available']} version={stock['version']}")
            return
        time.sleep(2)
    raise RuntimeError("Outbox replay or mock stock verification timed out")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("-p", "--project", required=True)
    p.add_argument("-f", "--file", action="append", default=[])
    p.add_argument("--backend", required=True)
    p.add_argument("--mock", required=True)
    p.add_argument("--branch", required=True, type=int)
    p.add_argument("--product", required=True, type=int)
    p.add_argument("--channel", required=True, choices=("WEB", "MARKET"))
    p.add_argument("--sku", required=True)
    run(p.parse_args())
