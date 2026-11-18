"""Bounded NFR-06 probes on an isolated QA155 API; never a shared target.

No destructive SQL payloads, dependency installation, backend fixes or raw logs.
Rate threshold and CORS allowlist remain unapproved policy, not invented PASS.
"""
from collections import Counter
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
PROJECT = os.environ['QA155_PROJECT']
REVISION = os.environ['QA155_SOURCE_COMMIT']
PORT = int(os.environ['QA155_PORT'])
OUT = Path(os.environ['QA155_REPORT'])
if not re.fullmatch(r'greenmart-qa155-[a-z0-9-]+', PROJECT) or not re.fullmatch(r'[a-f0-9]{40}', REVISION) or not 18000 <= PORT < 19000:
    raise ValueError('An isolated QA155 project, port and full revision are required')
if OUT.exists():
    raise ValueError('Evidence exists; use a new QA155_REPORT')
PASSWORD = os.environ['GREENMART_DEMO_PASSWORD']
BASE = f'http://127.0.0.1:{PORT}/api/v1'
PREFIX = 'QA155-' + uuid.uuid4().hex[:8].upper()
ROWS, SESSIONS, OBSERVATIONS, ERROR_TRACES = [], [], [], []
RUNTIME = {}
DB = BACKEND = None
STARTED = datetime.now(timezone.utc).isoformat()


def command(args, data=None):
    return subprocess.run(args, input=data, text=True, encoding='utf-8', capture_output=True, timeout=30)


def sql(query):
    if not query.lstrip().upper().startswith('SELECT'):
        raise ValueError('QA155 SQL is read-only')
    p = command(['docker', 'exec', '-i', DB, 'psql', '-X', '-At', '-U', 'greenmart', '-d', 'greenmart', '-v', 'ON_ERROR_STOP=1'], query)
    if p.returncode:
        raise RuntimeError('QA155 SQL failed; raw output withheld')
    lines = [x for x in p.stdout.splitlines() if x.startswith(('{', '[', '"')) or x.isdigit()]
    return json.loads(lines[-1]) if lines else None


def http(method, path, session=None, body=None, headers=None, raw=None):
    payload = raw if raw is not None else (None if body is None else json.dumps(body).encode())
    request_headers = {'Content-Type': 'application/json'} | (headers or {})
    if session:
        request_headers['Authorization'] = 'Bearer ' + session['accessToken']
    req = urllib.request.Request(BASE + path, method=method, headers=request_headers, data=payload)
    try:
        response = urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        text = response.read().decode('utf-8', errors='replace')
        try:
            data = json.loads(text) if text else None
        except json.JSONDecodeError:
            data = None
        # Headers are examined in memory; only relevant safe values are exported by probes.
        return response.code, data, dict((k.lower(), v) for k, v in response.headers.items()), text


def record(name, assertions, evidence=None):
    ROWS.append({'id': f'SEC155-{len(ROWS)+1:03}', 'name': name,
                 'result': 'PASS' if all(assertions.values()) else 'FAIL',
                 'assertions': assertions, 'evidence': evidence})


def blocked(name, reason):
    ROWS.append({'id': f'SEC155-{len(ROWS)+1:03}', 'name': name, 'result': 'BLOCKED', 'reason': reason})


def safe_error(name, response):
    status, data, headers, text = response
    forbidden = ['stacktrace', 'stack_trace', 'org.springframework', 'java.lang.', 'org.postgresql',
                 'jdbc:postgresql', 'sqlstate', 'password_hash', 'webhook_secret', 'hibernate.sql']
    serialized = text.lower()
    record(name, {'errorStatus': status >= 400,
        'envelope': isinstance(data, dict) and all(k in data for k in ['code', 'message', 'traceId']),
        'noStackOrSqlInternals': not any(term in serialized for term in forbidden),
        'noStackFields': isinstance(data, dict) and not any(k.lower() in ['trace', 'exception', 'stacktrace'] for k in data)},
        {'status': status, 'code': data.get('code') if isinstance(data, dict) else None,
         'traceId': data.get('traceId') if isinstance(data, dict) else None})
    if isinstance(data, dict) and data.get('traceId'):
        ERROR_TRACES.append(data['traceId'])


def guard_runtime():
    global DB, BACKEND
    for service in ['backend', 'postgres']:
        p = command(['docker', 'ps', '-q', '--filter', f'label=com.docker.compose.project={PROJECT}',
                     '--filter', f'label=com.docker.compose.service={service}'])
        ids = p.stdout.split()
        if p.returncode or len(ids) != 1:
            raise RuntimeError('Expected one isolated QA155 ' + service)
        if service == 'postgres':
            DB = ids[0]
        else:
            BACKEND = ids[0]
            p = command(['docker', 'inspect', '--format', '{{.Config.Image}}|{{.Image}}', BACKEND])
            image = p.stdout.strip().split('|')
            if p.returncode or len(image) != 2 or image[0] != 'greenmart-backend:qa155-' + REVISION[:7]:
                raise RuntimeError('QA155 image baseline mismatch')
            p = command(['docker', 'image', 'inspect', '--format', '{{index .Config.Labels "org.opencontainers.image.revision"}}', image[1]])
            if p.returncode or p.stdout.strip() != REVISION:
                raise RuntimeError('QA155 OCI revision mismatch')
            RUNTIME.update(imageTag=image[0], imageId=image[1], verifiedRevision=REVISION)


def login(username):
    status, data, _, _ = http('POST', '/auth/login', body={'username': username, 'password': PASSWORD})
    ok = status == 200 and isinstance(data, dict) and bool(data.get('accessToken'))
    record('Login ' + username, {'authenticated': ok}, {'status': status})
    if not ok:
        raise RuntimeError('Login prerequisite failed')
    SESSIONS.append(data)
    return data


def main():
    guard_runtime()
    if os.environ.get('QA155_MODE') == 'error-only':
        error_recheck()
        return
    owner, cashier = login('owner'), login('thungan.gm001')
    product = {'sku': PREFIX, 'name': PREFIX, 'unit': 'cai', 'categoryId': 1, 'salePrice': 31000, 'costPrice': 10000, 'active': True}
    status, p, _, _ = http('POST', '/products', owner, product)
    record('Create controlled search product', {'created': status == 201}, {'status': status})
    if status != 201:
        raise RuntimeError('Product control prerequisite failed')
    pid = int(p['id'])
    counts = sql('SELECT json_build_object(\'products\',(SELECT count(*) FROM product),\'customers\',(SELECT count(*) FROM customer),\'users\',(SELECT count(*) FROM app_user));')
    for endpoint in ['/products', '/customers']:
        status, data, _, _ = http('GET', endpoint, cashier)
        record(endpoint + ' baseline is nonempty', {'http200': status == 200,
            'nonempty': isinstance(data, dict) and data.get('totalElements', 0) > 0}, {'status': status})
        if endpoint == '/products':
            status, control, _, _ = http('GET', endpoint + '?' + urllib.parse.urlencode({'q': PREFIX}), cashier)
            record('Literal product-search positive control', {'http200': status == 200,
                'onlyOwnProduct': isinstance(control, dict) and len(control.get('content', [])) == 1 and control['content'][0]['id'] == pid})
        for payload in ["' OR 1=1 --", "' OR 1=2 --", "' UNION SELECT NULL --"]:
            status, data, _, _ = http('GET', endpoint + '?' + urllib.parse.urlencode({'q': payload}), cashier)
            record(endpoint + ' SQL-like q stays literal: ' + payload,
                   {'http200': status == 200, 'noRows': isinstance(data, dict) and data.get('totalElements') == 0},
                   {'status': status, 'totalElements': data.get('totalElements') if isinstance(data, dict) else None})
    status, data, _, _ = http('POST', '/auth/login', body={'username': "' OR '1'='1", 'password': secrets.token_hex(16)})
    record('Login SQL-like username cannot authenticate', {'http401': status == 401,
        'noToken': isinstance(data, dict) and 'accessToken' not in data}, {'status': status})
    after = sql('SELECT json_build_object(\'products\',(SELECT count(*) FROM product),\'customers\',(SELECT count(*) FROM customer),\'users\',(SELECT count(*) FROM app_user));')
    record('Read-only injection probes preserve row counts', {'unchanged': counts == after}, {'before': counts, 'after': after})
    for name, response in [
        ('Malformed login JSON has no stack trace', http('POST', '/auth/login', raw=b'{"username":')),
        ('Invalid product ID has no stack trace', http('GET', '/products/not-a-number', cashier)),
        ('Missing lookup code has no stack trace', http('GET', '/products/lookup?branchId=1', cashier)),
        ('Invalid quantity has no stack trace', http('POST', '/orders/quote', cashier, {'branchId': 1, 'items': [{'productId': pid, 'quantity': -1}]})),
        ('Null-item error response has no stack trace', http('POST', '/orders/quote', cashier, {'branchId': 1, 'items': [None]})),
        ('No-token error has no stack trace', http('GET', '/auth/me'))]:
        safe_error(name, response)
        if response[0] == 500:
            OBSERVATIONS.append({'name': name, 'status': 500, 'code': response[1].get('code') if isinstance(response[1], dict) else None,
                                 'classification': 'Functional server error; error sanitization assessed separately', 'relatedIssue': 316})
    # Candidates are observed, not silently declared an approved production allowlist.
    for origin in ['https://untrusted.qa.invalid', 'null', 'http://localhost:3000']:
        status, _, h, _ = http('GET', '/auth/me', cashier, headers={'Origin': origin})
        ps, _, ph, _ = http('OPTIONS', '/auth/login', headers={'Origin': origin, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'content-type'})
        OBSERVATIONS.append({'name': 'CORS origin observation', 'origin': origin, 'actualStatus': status,
            'allowOrigin': h.get('access-control-allow-origin'), 'preflightStatus': ps,
            'preflightAllowOrigin': ph.get('access-control-allow-origin')})
        if origin != 'http://localhost:3000':
            record('Untrusted CORS origin is not readable: ' + origin,
                   {'notAllowed': h.get('access-control-allow-origin') not in ['*', origin],
                    'preflightNotAllowed': ph.get('access-control-allow-origin') not in ['*', origin]},
                   {'status': status, 'preflightStatus': ps, 'allowOrigin': h.get('access-control-allow-origin')})
    blocked('Approved frontend CORS allowlist and positive preflight',
            'Lead has not approved origins; localhost:3000 candidate also has no ACAO and preflight is denied. Same-origin proxy differs from direct cross-origin API.')
    _, _, h, _ = http('GET', '/auth/me', cashier)
    record('No framework-version headers', {'noPoweredBy': 'x-powered-by' not in h,
        'noVersionedServer': not re.search(r'\d+\.\d+', h.get('server', ''))},
        {'hasPoweredBy': 'x-powered-by' in h, 'hasServerHeader': 'server' in h})
    rate_probe()
    lockout_probe()
    blocked('Additional application-log secret/correlation checks',
            'Raw Docker log access is excluded after automatic safety review; API responses are checked without logs. No full log-secret assurance.')


def rate_probe():
    statuses = Counter()
    codes = Counter()
    start = time.monotonic()
    for index in range(60):
        status, data, _, _ = http('POST', '/auth/login', body={'username': PREFIX + '-missing-' + str(index), 'password': secrets.token_hex(12)})
        statuses[str(status)] += 1
        codes[str(data.get('code') if isinstance(data, dict) else None)] += 1
        if status == 429:
            break
    OBSERVATIONS.append({'name': 'Bounded single-IP login burst', 'requests': sum(statuses.values()),
        'elapsedSeconds': round(time.monotonic()-start, 2), 'statuses': dict(statuses), 'codes': dict(codes),
        'ipMethod': 'One direct local client; no forged forwarding headers; distinct nonexistent usernames'})
    blocked('IP rate-limit threshold/window and recovery acceptance',
            'Lead has not specified threshold/window/cooldown or IP/proxy trust policy. Observed burst is evidence, not proof of policy compliance or no limiter at any threshold.')


def lockout_probe():
    user = os.environ.get('QA155_LOCKOUT_USER', 'thungan.gm003')
    if user not in ['thungan.gm002', 'thungan.gm003']:
        raise ValueError('Only dedicated QA demo accounts allowed for lockout')
    # Use a fresh dedicated account when rechecking an existing isolated stack.
    statuses, codes = [], []
    for _ in range(5):
        status, data, _, _ = http('POST', '/auth/login', body={'username': user, 'password': secrets.token_hex(12)})
        statuses.append(status)
        codes.append(data.get('code') if isinstance(data, dict) else None)
    record('Account lockout after five wrong passwords (separate from IP limiter)',
        {'all401': statuses == [401]*5, 'firstFourInvalid': codes[:4] == ['INVALID_CREDENTIALS']*4, 'fifthLocked': codes[-1] == 'ACCOUNT_LOCKED'},
        {'username': user, 'statuses': statuses, 'codes': codes})
    status, data, _, _ = http('POST', '/auth/login', body={'username': user, 'password': PASSWORD})
    record('Correct password cannot bypass active account lock', {'http401': status == 401,
        'locked': isinstance(data, dict) and data.get('code') == 'ACCOUNT_LOCKED'}, {'status': status})
    state = sql(f"SELECT json_build_object('failures',failed_login_count,'remainingSeconds',extract(epoch from locked_until-now())) FROM app_user WHERE username='{user}';")
    record('Lock deadline approximately fifteen minutes', {'fiveFailures': state['failures'] == 5,
        'fifteenMinutes': 850 <= state['remainingSeconds'] <= 900}, state)


def error_recheck():
    """Independently exercise a real 500 without repeating the account lockout."""
    cashier = login('thungan.gm001')
    query = "SELECT json_build_object('orders',(SELECT count(*) FROM sales_order),'ledger',(SELECT count(*) FROM inventory_transaction));"
    before = sql(query)
    response = http('POST', '/orders/quote', cashier, {'branchId': 1, 'items': [None]})
    safe_error('Known #316 null-item server error has no stack trace', response)
    record('Invalid quote has no order/ledger side effect', {'unchanged': before == sql(query)})
    status, data, _, _ = response
    OBSERVATIONS.append({'name': 'Known #316 quote null-item diagnostic', 'status': status,
        'code': data.get('code') if isinstance(data, dict) else None, 'relatedIssue': 316,
        'classification': 'Functional bug and information-leak acceptance are separate'})


if __name__ == '__main__':
    interrupted = None
    try:
        main()
    except Exception as error:
        interrupted = type(error).__name__
    finally:
        for index, session in enumerate(SESSIONS, 1):
            try:
                status, _, _, _ = http('POST', '/auth/logout', session, {'refreshToken': session.get('refreshToken')})
                record('Logout session ' + str(index), {'http204': status == 204}, {'status': status})
            except Exception:
                record('Logout session ' + str(index), {'completed': False})
        result = {'issue': 155, 'date': datetime.now(timezone.utc).isoformat(), 'sourceCommit': REVISION,
            'mode': os.environ.get('QA155_MODE', 'full'),
            'project': PROJECT, 'base': BASE, 'fixturePrefix': PREFIX, 'runtime': RUNTIME,
            'summary': dict(Counter(r['result'] for r in ROWS)), 'interrupted': interrupted,
            'checks': ROWS, 'observations': OBSERVATIONS,
            'limitations': ['User confirms rate threshold/window and CORS allowlist are not approved; do not invent acceptance policy.',
                'Bounded API probes and source review are not a full penetration test or a browser XSS execution test.',
                'Dedicated QA demo account remains temporarily locked after lockout probe; no shared account was changed.',
                'No additional Docker logs read after safety review; no full log-secret/correlation assurance.']}
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'summary': result['summary'], 'interrupted': interrupted}), flush=True)
    raise SystemExit(1 if interrupted or any(x['result'] != 'PASS' for x in ROWS) else 0)
