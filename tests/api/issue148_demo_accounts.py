"""Issue #148: real API/DB demo-account checks; Python standard library only.

Run only against a seeded QA environment. Does not seed/reset data or manage Docker.
Login creates temporary sessions and resets successful-login counters; logout revokes
the sessions created here. Password hashes and tokens never enter the report.
"""
import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
BRANCHES = {"GM-001", "GM-002", "GM-003"}
ACCOUNTS = [("TC-DEMO-10", "admin", "ADMIN", BRANCHES),
            ("TC-DEMO-11", "owner", "OWNER", BRANCHES),
            ("TC-DEMO-11b", "owner.pho", "OWNER", BRANCHES)]
for start, prefix, role in [(12, "quanly", "BRANCH_MANAGER"),
                            (15, "thungan", "CASHIER"), (18, "thukho", "STOREKEEPER")]:
    for index in range(3):
        ACCOUNTS.append((f"TC-DEMO-{start + index}", f"{prefix}.gm00{index + 1}",
                         role, {f"GM-00{index + 1}"}))


class Blocked(Exception):
    pass


def request(base, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, headers=headers,
                                 data=None if body is None else json.dumps(body).encode())
    # Never forward credentials across redirects or through environment proxies.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(req, timeout=10) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        return error.code, None  # Do not retain arbitrary server error bodies.
    except (OSError, ValueError):
        raise Blocked("API unavailable or response is not valid JSON") from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate_user(user, username, role, branches):
    if not isinstance(user, dict):
        return False
    listed = user.get("branches")
    if not isinstance(listed, list) or not all(isinstance(b, dict) for b in listed):
        return False
    if not all(isinstance(b.get("code"), str) and type(b.get("id")) is int for b in listed):
        return False
    return (type(user.get("id")) is int and user["id"] > 0
            and user.get("username") == username and user.get("role") == role
            and isinstance(user.get("fullName"), str) and bool(user["fullName"].strip())
            and len(listed) == len(branches)
            and {b.get("code") for b in listed} == branches
            and len({b.get("id") for b in listed}) == len(branches)
            and all(type(b.get("id")) is int and b["id"] > 0
                    and isinstance(b.get("name"), str) and bool(b["name"].strip()) for b in listed))


def validate_session(data, username, role, branches):
    return (isinstance(data, dict) and data.get("tokenType") == "Bearer"
            and type(data.get("expiresIn")) is int and data["expiresIn"] == 900
            and all(isinstance(data.get(k), str) and bool(data[k])
                    for k in ["accessToken", "refreshToken"])
            and validate_user(data.get("user"), username, role, branches))


def snapshot(project, user, database):
    # Return only predicates about hashes, never the hashes themselves.
    sql = """BEGIN READ ONLY;
    SELECT json_build_object(
      'branches', (SELECT json_agg(code ORDER BY code) FROM branch WHERE is_active),
      'users', (SELECT json_agg(json_build_object(
        'username', u.username, 'role', u.role, 'active', u.is_active,
        'branches', (SELECT coalesce(json_agg(b.code ORDER BY b.code), '[]'::json)
          FROM user_branch ub JOIN branch b ON b.id=ub.branch_id WHERE ub.user_id=u.id),
        'bcrypt', CASE WHEN u.password_hash ~ '^[$]2[ab][$][0-9]{2}[$][./A-Za-z0-9]{53}$'
                   THEN substring(u.password_hash from 5 for 2)::int BETWEEN 10 AND 31 ELSE false END))
        FROM app_user u),
      'differentHashes', (SELECT a.password_hash <> b.password_hash
        FROM app_user a CROSS JOIN app_user b
        WHERE a.username='thungan.gm001' AND b.username='thungan.gm002'));
    ROLLBACK;"""
    try:
        containers = subprocess.run(
            ["docker", "ps", "-q", "--filter", f"label=com.docker.compose.project={project}",
             "--filter", "label=com.docker.compose.service=postgres"],
            capture_output=True, text=True, timeout=10, check=True).stdout.split()
        if len(containers) != 1:
            raise Blocked("Expected exactly one running QA postgres container")
        cmd = ["docker", "exec", "-i", containers[0], "psql", "-X", "-U", user, "-d", database,
               "-v", "ON_ERROR_STOP=1", "-t", "-A", "-q"]
        result = subprocess.run(cmd, input=sql, cwd=ROOT, text=True, encoding="utf-8",
                                capture_output=True, timeout=30)
        if result.returncode:
            raise Blocked("Docker/PostgreSQL unavailable or schema not initialized")
        data = json.loads(result.stdout)
        if not isinstance(data.get("users"), list):
            raise Blocked("Demo users have not been seeded")
        return data
    except (OSError, subprocess.SubprocessError, ValueError):
        raise Blocked("Cannot read QA database snapshot") from None


def run_checks(db, db_error, base):
    results, cleanup = [], []

    def record(case, ok, detail, blocked=False):
        results.append({"id": case, "result": "BLOCKED" if blocked else "PASS" if ok else "FAIL",
                        "detail": detail})

    expected = {name: (role, branches) for _, name, role, branches in ACCOUNTS}
    users = {u["username"]: u for u in db["users"]} if db else {}
    for case, role in [("TC-DEMO-01", None), ("TC-DEMO-02", "ADMIN"),
                       ("TC-DEMO-03", "OWNER"), ("TC-DEMO-04", "BRANCH_MANAGER"),
                       ("TC-DEMO-05", "CASHIER"), ("TC-DEMO-06", "STOREKEEPER")]:
        names = [n for n, (r, _) in expected.items() if role is None or r == role]
        ok = all(n in users and users[n]["role"] == expected[n][0]
                 and users[n]["active"] is True
                 and len(users[n]["branches"]) == len(expected[n][1])
                 and set(users[n]["branches"]) == expected[n][1] for n in names)
        ok = ok and bool(db) and set(db["branches"] or []) == BRANCHES
        record(case, ok, db_error or "Expected demo users active; exact assigned branches and roles",
               blocked=db is None)

    login_results = []
    me_cases = {"admin": "TC-DEMO-30", "owner": "TC-DEMO-31", "thungan.gm001": "TC-DEMO-32",
                "quanly.gm002": "TC-DEMO-33", "thukho.gm003": "TC-DEMO-34"}
    for case, name, role, branches in ACCOUNTS:
        session = None
        try:
            status, session = request(base, "/auth/login", {"username": name, "password": "Demo@123"})
            ok = status == 200 and validate_session(session, name, role, branches)
            record(case, ok, f"{name}: HTTP {status}; expected role={role}; expected branches={','.join(sorted(branches))}")
            login_results.append("PASS" if ok else "FAIL")
            if name in me_cases:
                if not isinstance(session, dict) or not session.get("accessToken"):
                    record(me_cases[name], False, "No valid access token from login", blocked=True)
                else:
                    try:
                        code, me = request(base, "/auth/me", token=session["accessToken"])
                        record(me_cases[name], code == 200 and validate_user(me, name, role, branches)
                               and me.get("id") == session.get("user", {}).get("id"),
                               f"{name}: HTTP {code}; exact role and branch set")
                    except Blocked as error:
                        record(me_cases[name], False, str(error), blocked=True)
        except Blocked as error:
            record(case, False, str(error), blocked=True)
            login_results.append("BLOCKED")
            if name in me_cases and not any(r["id"] == me_cases[name] for r in results):
                record(me_cases[name], False, str(error), blocked=True)
        finally:
            if isinstance(session, dict) and session.get("accessToken") and session.get("refreshToken"):
                try:
                    code, _ = request(base, "/auth/logout", {"refreshToken": session["refreshToken"]},
                                      session["accessToken"])
                    if code != 204:
                        cleanup.append(f"{name}: logout HTTP {code}")
                except Blocked:
                    cleanup.append(f"{name}: logout unavailable")
    record("TC-DEMO-21", all(r == "PASS" for r in login_results), "Aggregate of all 12 logins",
           blocked="FAIL" not in login_results and "BLOCKED" in login_results)
    record("TC-DEMO-40", all(n in users and users[n]["bcrypt"] is True for n in expected),
           db_error or "All 12 demo users use BCrypt cost >= 10", blocked=db is None)
    record("TC-DEMO-41", bool(db and db["differentHashes"] is True),
           db_error or "Two cashier hashes differ; no hash exported", blocked=db is None)
    return results, cleanup


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18080/api/v1")
    parser.add_argument("--project", default="greenmart-qa148")
    parser.add_argument("--db-user", default="greenmart")
    parser.add_argument("--db-name", default="greenmart")
    parser.add_argument("--reference-commit", required=True, help="GitHub develop SHA read before execution")
    parser.add_argument("--output", type=Path, required=True, help="New report path (never overwrite)")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Report already exists; select a new filename")
    local = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    db, error = None, None
    try:
        db = snapshot(args.project, args.db_user, args.db_name)
    except Blocked as failure:
        error = str(failure)
    results, cleanup = run_checks(db, error, args.base_url.rstrip("/"))
    counts = dict(Counter(r["result"] for r in results))
    report = {"issue": 148, "executedAt": datetime.now(timezone(timedelta(hours=7))).isoformat(),
              "localCommit": local, "githubReferenceCommit": args.reference_commit,
              "composeProject": args.project, "baseUrl": args.base_url, "summary": counts,
              "results": results, "cleanupWarnings": cleanup,
              "scope": "Real API/DB checks only; no seed/reset or service management; not full RBAC #242"}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(json.dumps({"summary": counts, "cleanupWarnings": cleanup, "report": str(args.output)}))
    return 1 if counts.get("FAIL") else 2 if counts.get("BLOCKED") or cleanup else 0


if __name__ == "__main__":
    raise SystemExit(main())
