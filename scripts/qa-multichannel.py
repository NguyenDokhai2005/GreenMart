"""Kiểm tra luồng đa kênh WEB/MARKET đầu–cuối trên stack Compose riêng (#16, AC-03, UC-02).

Chạy sau scripts/demo-clean.py (hoặc stack tương đương có seed chính thức):

    python scripts/qa-multichannel.py --project greenmart-demo-qa16 --port 28180

Script đọc secret kênh từ .local-tools/<project>/.env, không in secret, ghi kết quả
JSON vào .local-tools/<project>/multichannel-result.json. Có bước dừng/bật lại
mock-connector của chính project đó; không đụng tới stack khác.
"""
import argparse
import hashlib
import hmac
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VN = timezone(timedelta(hours=7))
PASSWORD = "Demo@123"  # mật khẩu demo công khai trong README, chỉ dùng cho seed


class Ctx:
    def __init__(self, project, port):
        work = ROOT / ".local-tools" / project
        self.env = dict(line.split("=", 1) for line in (work / ".env").read_text(encoding="utf-8").splitlines() if "=" in line)
        self.compose = ["docker", "compose", "--env-file", str(work / ".env"), "-p", project,
                        "-f", str(ROOT / "docker-compose.yml"), "-f", str(work / "ports.yml")]
        self.api = f"http://127.0.0.1:{port}/api/v1"
        self.mock = f"http://127.0.0.1:{port + 2}"
        self.out = work / "multichannel-result.json"
        self.results = []
        self.tokens = {}

    # ---------------------------------------------------------------- HTTP
    def http(self, method, url, body=None, headers=None, raw=None):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(url, data=data, method=method,
                                     headers={"Content-Type": "application/json", **(headers or {})})
        started = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                text = resp.read().decode() or "null"
                return resp.status, json.loads(text), round((time.monotonic() - started) * 1000)
        except urllib.error.HTTPError as err:
            text = err.read().decode() or "null"
            try:
                parsed = json.loads(text)
            except ValueError:
                parsed = text
            return err.code, parsed, round((time.monotonic() - started) * 1000)

    def token(self, user):
        if user not in self.tokens:
            status, body, _ = self.http("POST", f"{self.api}/auth/login", {"username": user, "password": PASSWORD})
            assert status == 200, f"login {user}: {status} {body}"
            self.tokens[user] = body["accessToken"]
        return {"Authorization": f"Bearer {self.tokens[user]}"}

    def signed(self, channel, path, body):
        raw = json.dumps(body, ensure_ascii=False).encode()
        secret = self.env[f"CHANNEL_{channel}_SECRET"].encode()
        sig = hmac.new(secret, raw, hashlib.sha256).hexdigest()
        return self.http("POST", f"{self.api}/integrations/{channel}{path}", raw=raw, headers={"X-Signature": sig})

    # ---------------------------------------------------------------- DB / Docker
    def sql(self, query):
        cmd = [*self.compose, "exec", "-T", "postgres", "sh", "-ec",
               'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F "|" -v ON_ERROR_STOP=1']
        res = subprocess.run(cmd, input=query.encode(), capture_output=True, check=True, cwd=ROOT)
        return [line.split("|") for line in res.stdout.decode().splitlines() if line]

    def docker(self, *args):
        subprocess.run([*self.compose, *args], check=True, cwd=ROOT, capture_output=True)

    def mock_state(self):
        status, body, _ = self.http("GET", f"{self.mock}/mock/state")
        assert status == 200, status
        return body

    # ---------------------------------------------------------------- helpers
    def record(self, sid, name, ok, **evidence):
        self.results.append({"id": sid, "name": name, "result": "PASS" if ok else "FAIL", **evidence})
        print(f"[{'PASS' if ok else 'FAIL'}] {sid} {name} {json.dumps(evidence, ensure_ascii=False)[:300]}")

    def stock(self, product_id):
        rows = self.sql(f"SELECT branch_id,on_hand,reserved FROM inventory WHERE product_id={int(product_id)} ORDER BY branch_id;")
        return {int(b): (int(o), int(r)) for b, o, r in rows}

    def order(self, order_id):
        row = self.sql(f"SELECT status,branch_id,reserved_until,completed_at FROM sales_order WHERE id={int(order_id)};")[0]
        return {"status": row[0], "branch": int(row[1]), "reservedUntil": row[2] or None, "completedAt": row[3] or None}

    def wait(self, predicate, timeout=60, step=0.5):
        started = time.monotonic()
        while time.monotonic() - started < timeout:
            value = predicate()
            if value:
                return round(time.monotonic() - started, 2), value
            time.sleep(step)
        return None, None

    def mock_order_status(self, channel, ext):
        upd = self.mock_state()[channel]["orders"].get(ext, {}).get("statusUpdate")
        return upd and upd["status"]

    def mock_stock(self, channel, sku):
        return self.mock_state()[channel]["stock"].get(sku)

    def new_order(self, channel, sku, qty, method="ONLINE", ext=None):
        ext = ext or f"QA16-{channel}-{uuid.uuid4().hex[:10]}"
        body = {"externalOrderId": ext, "orderedAt": datetime.now(VN).isoformat(timespec="seconds"),
                "customer": {"name": "Khách QA đa kênh", "phone": "0901234567"},
                "items": [{"externalSku": sku, "quantity": qty, "unitPrice": 25000}],
                "shippingFee": 15000, "paymentMethod": method}
        return ext, body

    def event(self, channel, ext, kind, **extra):
        body = {"type": kind, "occurredAt": datetime.now(VN).isoformat(timespec="seconds"), **extra}
        return self.signed(channel, f"/orders/{ext}/events", body)


def manager_of(ctx, branch_id):
    code = ctx.sql(f"SELECT code FROM branch WHERE id={int(branch_id)};")[0][0]
    return "quanly." + code.lower().replace("-", "")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--port", type=int, required=True)
    args = parser.parse_args()
    ctx = Ctx(args.project, args.port)
    commit = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True, cwd=ROOT).stdout.strip()

    fx = ctx.sql("""
        SELECT w.external_sku, m.external_sku, w.product_id
        FROM channel_product w
        JOIN sales_channel cw ON cw.id = w.channel_id AND cw.code = 'WEB'
        JOIN channel_product m ON m.product_id = w.product_id
        JOIN sales_channel cm ON cm.id = m.channel_id AND cm.code = 'MARKET'
        WHERE (SELECT COALESCE(SUM(i.on_hand - i.reserved), 0) FROM inventory i
               JOIN channel_branch cb ON cb.branch_id = i.branch_id AND cb.channel_id = cw.id
               WHERE i.product_id = w.product_id) >= 40
          AND (SELECT COALESCE(SUM(i.on_hand - i.reserved), 0) FROM inventory i
               JOIN channel_branch cb ON cb.branch_id = i.branch_id AND cb.channel_id = cm.id
               WHERE i.product_id = w.product_id) >= 40
        ORDER BY w.product_id LIMIT 1;""")[0]
    wsku, msku, pid = fx[0], fx[1], int(fx[2])
    print("fixture", wsku, msku, pid, ctx.stock(pid))

    # S01 – WEB ONLINE: giữ chỗ 15 phút, đẩy tồn ra kênh
    before = ctx.stock(pid)
    web_before = ctx.mock_stock("WEB", wsku)
    ext1, body1 = ctx.new_order("WEB", wsku, 2)
    st, ack, ms = ctx.signed("WEB", "/orders", body1)
    o1 = ack.get("orderId") if isinstance(ack, dict) else None
    after = ctx.stock(pid)
    branch1 = ack.get("branch", {}).get("id") if isinstance(ack, dict) and ack.get("branch") else None
    hold = None
    if isinstance(ack, dict) and ack.get("reservedUntil"):
        hold = (datetime.fromisoformat(ack["reservedUntil"].replace("Z", "+00:00")) - datetime.now(timezone.utc)).total_seconds() / 60
    ok = st == 202 and ack.get("status") == "RESERVED" and branch1 is not None \
        and after[branch1][1] - before[branch1][1] == 2 and hold is not None and 14 <= hold <= 15.1
    ctx.record("S01", "WEB ONLINE → RESERVED, giữ chỗ 15 phút", ok, http=st, ms=ms, orderId=o1, branch=branch1,
               reservedDelta=after.get(branch1, (0, 0))[1] - before.get(branch1, (0, 0))[1] if branch1 else None,
               holdMinutes=round(hold, 2) if hold else None)
    def web_pushed():
        cur = ctx.mock_stock("WEB", wsku)
        return cur if cur and (not web_before or cur["version"] > web_before["version"]) else None
    secs, pushed = ctx.wait(web_pushed)
    ctx.record("S02", "Đẩy tồn mới ra WEB sau giữ chỗ (≤ 60 s)", secs is not None and secs <= 60,
               seconds=secs, pushed=pushed)
    secs_m, pushed_m = ctx.wait(lambda: ctx.mock_stock("MARKET", msku), timeout=60)
    ctx.record("S03", "Kênh MARKET cũng nhận tồn của cùng sản phẩm", secs_m is not None, seconds=secs_m, pushed=pushed_m)

    # S04 – gửi trùng
    st, dup, ms = ctx.signed("WEB", "/orders", body1)
    again = ctx.stock(pid)
    cnt = ctx.sql(f"SELECT COUNT(*) FROM sales_order WHERE external_order_id='{ext1}';")[0][0]
    ctx.record("S04", "Gửi trùng externalOrderId → 200, không giữ chỗ thêm", st == 200 and dup.get("orderId") == o1
               and again == after and cnt == "1", http=st, ms=ms, orders=cnt)

    # S05 – sai chữ ký
    ext_bad, body_bad = ctx.new_order("WEB", wsku, 1)
    raw = json.dumps(body_bad).encode()
    st, err, ms = ctx.http("POST", f"{ctx.api}/integrations/WEB/orders", raw=raw, headers={"X-Signature": "0" * 64})
    cnt = ctx.sql(f"SELECT COUNT(*) FROM sales_order WHERE external_order_id='{ext_bad}';")[0][0]
    ctx.record("S05", "Sai chữ ký HMAC → 401, không tạo đơn", st == 401 and cnt == "0", http=st,
               code=err.get("code") if isinstance(err, dict) else err, orders=cnt)

    # S06 – PAID → CONFIRMED, báo trạng thái về kênh
    st, ev, ms = ctx.event("WEB", ext1, "PAID", providerRef="QA16-PAY-1")
    secs, status = ctx.wait(lambda: ctx.mock_order_status("WEB", ext1) == "CONFIRMED")
    ctx.record("S06", "Sự kiện PAID → CONFIRMED, kênh nhận CONFIRMED", st == 200 and ev.get("status") == "CONFIRMED"
               and secs is not None, http=st, ms=ms, callbackSeconds=secs)
    st, ev2, _ = ctx.event("WEB", ext1, "PAID", providerRef="QA16-PAY-1")
    ctx.record("S07", "Gửi lại PAID → 200, không đổi", st == 200 and ev2.get("status") == "CONFIRMED", http=st)

    # S08 – hoàn tất đơn bởi QL chi nhánh được phân bổ
    pre = ctx.stock(pid)
    mgr = manager_of(ctx, branch1)
    st, done, ms = ctx.http("POST", f"{ctx.api}/orders/{o1}/complete", headers=ctx.token(mgr))
    post = ctx.stock(pid)
    d_on, d_res = post[branch1][0] - pre[branch1][0], post[branch1][1] - pre[branch1][1]
    secs, _ = ctx.wait(lambda: ctx.mock_order_status("WEB", ext1) == "COMPLETED")
    ctx.record("S08", "QLCN hoàn tất → COMPLETED, trừ tồn thực và giữ chỗ, kênh nhận COMPLETED",
               st == 200 and done.get("status") == "COMPLETED" and d_on == -2 and d_res == -2 and secs is not None,
               http=st, ms=ms, user=mgr, onHandDelta=d_on, reservedDelta=d_res, callbackSeconds=secs)
    ext_x, body_x = ctx.new_order("WEB", wsku, 1, method="COD")
    st, ackx, _ = ctx.signed("WEB", "/orders", body_x)
    bx = ackx["branch"]["id"]
    other_branch = ctx.sql(f"SELECT id FROM branch WHERE id <> {int(bx)} AND is_active ORDER BY id LIMIT 1;")[0][0]
    other = manager_of(ctx, other_branch)
    st2, errx, _ = ctx.http("POST", f"{ctx.api}/orders/{ackx['orderId']}/complete", headers=ctx.token(other))
    ctx.record("S09", "QLCN chi nhánh khác hoàn tất đơn → 403", st2 == 403, http=st2,
               code=errx.get("code") if isinstance(errx, dict) else errx, user=other, orderBranch=bx)
    ctx.http("POST", f"{ctx.api}/orders/{ackx['orderId']}/cancel", {"reason": "OTHER", "note": "Dọn dữ liệu QA"},
             headers=ctx.token(manager_of(ctx, bx)))

    # S10 – MARKET COD → CONFIRMED ngay; S11 – kênh hủy → nhả giữ chỗ
    ext2, body2 = ctx.new_order("MARKET", msku, 1, method="COD")
    st, ack2, ms = ctx.signed("MARKET", "/orders", body2)
    b2 = ack2.get("branch", {}).get("id")
    ctx.record("S10", "MARKET COD → CONFIRMED ngay sau giữ chỗ", st == 202 and ack2.get("status") == "CONFIRMED",
               http=st, ms=ms, orderId=ack2.get("orderId"), branch=b2)
    pre = ctx.stock(pid)
    st, ev, ms = ctx.event("MARKET", ext2, "CANCELLED", reason="Khách hủy trên sàn")
    post = ctx.stock(pid)
    secs, _ = ctx.wait(lambda: ctx.mock_order_status("MARKET", ext2) == "CANCELLED")
    ctx.record("S11", "Kênh báo CANCELLED → nhả giữ chỗ, kênh nhận CANCELLED", st == 200 and ev.get("status") == "CANCELLED"
               and post[b2][1] - pre[b2][1] == -1 and post[b2][0] == pre[b2][0] and secs is not None,
               http=st, ms=ms, reservedDelta=post[b2][1] - pre[b2][1], callbackSeconds=secs)

    # S12 – SKU chưa ánh xạ; S13 – thiếu tồn mọi chi nhánh
    ext3, body3 = ctx.new_order("WEB", "QA16-KHONG-CO-SKU", 1)
    st, e3, ms = ctx.signed("WEB", "/orders", body3)
    ctx.record("S12", "SKU chưa ánh xạ → 422 UNMAPPED_SKU, đơn lưu REJECTED", st == 422 and e3.get("code") == "UNMAPPED_SKU"
               and (e3.get("details") or {}).get("status") == "REJECTED", http=st, ms=ms, details=e3.get("details"))
    pre = ctx.stock(pid)
    ext4, body4 = ctx.new_order("WEB", wsku, 9999)
    st, e4, ms = ctx.signed("WEB", "/orders", body4)
    ctx.record("S13", "Thiếu tồn mọi chi nhánh → 409 INSUFFICIENT_STOCK, REJECTED, không giữ chỗ",
               st == 409 and e4.get("code") == "INSUFFICIENT_STOCK" and (e4.get("details") or {}).get("status") == "REJECTED"
               and ctx.stock(pid) == pre, http=st, ms=ms, lines=(e4.get("details") or {}).get("lines"))

    # S14 – hết hạn giữ chỗ; S15 – PAID sau EXPIRED giữ chỗ lại (BA E2); S16 – QLCN hủy đơn
    ext5, body5 = ctx.new_order("WEB", wsku, 1)
    st, ack5, _ = ctx.signed("WEB", "/orders", body5)
    o5, b5 = ack5["orderId"], ack5["branch"]["id"]
    pre = ctx.stock(pid)
    ctx.sql(f"UPDATE sales_order SET reserved_until = now() - interval '1 minute' WHERE id={int(o5)};")
    secs, _ = ctx.wait(lambda: ctx.order(o5)["status"] == "EXPIRED", timeout=60)
    post = ctx.stock(pid)
    secs_cb, _ = ctx.wait(lambda: ctx.mock_order_status("WEB", ext5) == "EXPIRED")
    ctx.record("S14", "Quá hạn giữ chỗ → EXPIRED, nhả giữ chỗ, kênh nhận EXPIRED (dời reserved_until bằng SQL)",
               secs is not None and post[b5][1] - pre[b5][1] == -1 and secs_cb is not None,
               expireSeconds=secs, reservedDelta=post[b5][1] - pre[b5][1], callbackSeconds=secs_cb)
    st, ev, ms = ctx.event("WEB", ext5, "PAID", providerRef="QA16-PAY-LATE")
    ctx.record("S15", "PAID sau khi EXPIRED, còn đủ hàng → giữ chỗ lại, CONFIRMED", st == 200 and ev.get("status") == "CONFIRMED",
               http=st, ms=ms, status=ev.get("status"))
    pre = ctx.stock(pid)
    st, can, ms = ctx.http("POST", f"{ctx.api}/orders/{o5}/cancel", {"reason": "CUSTOMER_REQUEST", "note": "QA đa kênh"},
                           headers=ctx.token(manager_of(ctx, ctx.order(o5)["branch"])))
    post = ctx.stock(pid)
    b5 = ctx.order(o5)["branch"]
    pay = ctx.sql(f"SELECT string_agg(status, ',') FROM payment WHERE order_id={int(o5)};")[0][0]
    secs, _ = ctx.wait(lambda: ctx.mock_order_status("WEB", ext5) == "CANCELLED")
    ctx.record("S16", "QLCN hủy đơn đã trả → CANCELLED, nhả giữ chỗ, hoàn tiền giả lập, kênh nhận CANCELLED",
               st == 200 and can.get("status") == "CANCELLED" and post[b5][1] - pre[b5][1] == -1 and "REFUNDED" in (pay or "")
               and secs is not None, http=st, ms=ms, payments=pay, callbackSeconds=secs)

    # S17–S18 – kênh lỗi: tắt mock-connector, callback FAILED, bật lại, ADMIN gửi lại
    failed = None
    mark = ctx.sql("SELECT COALESCE(MAX(id), 0) FROM sync_log;")[0][0]
    ctx.docker("stop", "mock-connector")
    try:
        ext6, body6 = ctx.new_order("WEB", wsku, 1, method="COD")
        st, ack6, ms = ctx.signed("WEB", "/orders", body6)
        down_ok = st == 202
        secs, failed = ctx.wait(lambda: ctx.sql(
            f"SELECT id, attempts FROM sync_log WHERE id > {mark} AND direction='OUT' AND status='FAILED' ORDER BY id;"),
            timeout=120, step=2)
        ctx.record("S17", "Kênh WEB/MARKET mất kết nối: nhận đơn vẫn 202, callback thử lại rồi FAILED",
                   down_ok and bool(failed), http=st, orderId=ack6.get("orderId"), secondsToFailed=secs,
                   failed=[{"id": int(i), "attempts": int(a)} for i, a in (failed or [])])
    finally:
        ctx.docker("start", "mock-connector")
    ctx.wait(lambda: ctx.http("GET", f"{ctx.mock}/healthz")[0] == 200, timeout=60)
    st_list, logs, _ = ctx.http("GET", f"{ctx.api}/integrations/sync-logs?status=FAILED&size=100", headers=ctx.token("admin"))
    listed = {row["id"] for row in logs.get("content", [])} if isinstance(logs, dict) else set()
    retried = []
    for log_id, _ in failed or []:
        st, _, _ = ctx.http("POST", f"{ctx.api}/integrations/sync-logs/{log_id}/retry", headers=ctx.token("admin"))
        retried.append((int(log_id), st))
    def all_success():
        ids = ",".join(str(i) for i, _ in retried) or "0"
        rows = ctx.sql(f"SELECT status FROM sync_log WHERE id IN ({ids});")
        return rows and all(r[0] == "SUCCESS" for r in rows)
    secs, _ = ctx.wait(all_success, timeout=90, step=2)
    db_ver = ctx.sql(f"SELECT MAX(version) FROM inventory WHERE product_id={pid};")[0][0]
    secs2, _ = ctx.wait(lambda: ctx.mock_order_status("WEB", ext6) == "CONFIRMED", timeout=30)
    st_cashier, _, _ = ctx.http("POST", f"{ctx.api}/integrations/sync-logs/{retried[0][0] if retried else 1}/retry",
                                headers=ctx.token("thungan.gm001"))
    ctx.record("S18", "ADMIN thấy log FAILED, bật lại kênh, gửi lại → SUCCESS, kênh nhận trạng thái đơn",
               st_list == 200 and all(int(i) in listed for i, _ in (failed or [])) and bool(retried) and all(s == 202 for _, s in retried) and secs is not None and secs2 is not None,
               retried=retried, secondsToSuccess=secs, mockOrderStatus=ctx.mock_order_status("WEB", ext6),
               inventoryVersion=db_ver, mockWebStock=ctx.mock_stock("WEB", wsku),
               adminListHttp=st_list, failedListed=all(int(i) in listed for i, _ in (failed or [])))
    ctx.record("S19", "Thu ngân gọi gửi lại sync log → 403", st_cashier == 403, http=st_cashier)

    summary = {"commit": commit, "finishedAt": datetime.now(VN).isoformat(timespec="seconds"),
               "fixture": {"webSku": wsku, "marketSku": msku, "productId": pid},
               "pass": sum(r["result"] == "PASS" for r in ctx.results), "total": len(ctx.results), "results": ctx.results}
    ctx.out.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{summary['pass']}/{summary['total']} PASS → {ctx.out}")
    return 0 if summary["pass"] == summary["total"] else 1


if __name__ == "__main__":
    sys.exit(main())
