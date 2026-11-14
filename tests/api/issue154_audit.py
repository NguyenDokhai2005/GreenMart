"""AUTH-03 real API/DB checks. Only an explicitly isolated QA154 is allowed.

No backend changes, fake audit insertion, trigger bypass or shared DB cleanup.
"""
from collections import Counter
from datetime import datetime, timezone
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
PROJECT = os.environ['QA154_PROJECT']
REVISION = os.environ['QA154_SOURCE_COMMIT']
PORT = int(os.environ['QA154_PORT'])
OUT = Path(os.environ['QA154_REPORT'])
if not re.fullmatch(r'greenmart-qa154-[a-z0-9-]+', PROJECT) or not re.fullmatch(r'[a-f0-9]{40}', REVISION) or not 18000 <= PORT < 19000:
    raise ValueError('An isolated QA154 project, port and full revision are required')
if OUT.exists():
    raise ValueError('Evidence exists; use a new QA154_REPORT')
PASSWORD = os.environ['GREENMART_DEMO_PASSWORD']
BASE = f'http://127.0.0.1:{PORT}/api/v1'
PREFIX = 'QA154-' + uuid.uuid4().hex[:8].upper()
ROWS, SESSIONS, RUNTIME = [], [], {}
DB = None


def command(args, data=None):
    return subprocess.run(args, input=data, text=True, encoding='utf-8', capture_output=True, timeout=30)


def sql(query, fixture=False):
    if not fixture and not query.lstrip().upper().startswith('SELECT'):
        raise ValueError('SQL reads only outside explicit isolated fixtures')
    p = command(['docker', 'exec', '-i', DB, 'psql', '-X', '-At', '-U', 'greenmart', '-d', 'greenmart', '-v', 'ON_ERROR_STOP=1'], query)
    if p.returncode:
        raise RuntimeError('QA154 SQL failed; raw output withheld')
    lines = [x for x in p.stdout.splitlines() if x.startswith(('{', '[', '"')) or x.isdigit()]
    return json.loads(lines[-1]) if lines else None


def http(method, path, session=None, body=None, signing_key=None):
    payload = None if body is None else json.dumps(body).encode()
    headers = {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())}
    if session:
        headers['Authorization'] = 'Bearer ' + session['accessToken']
    if signing_key:
        headers['X-Signature'] = hmac.new(signing_key.encode(), payload or b'', hashlib.sha256).hexdigest()
    req = urllib.request.Request(BASE + path, method=method, headers=headers, data=payload)
    try:
        response = urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.code, json.loads(raw) if raw else None


def record(name, assertions, evidence=None):
    ROWS.append({'id': f'AUD154-{len(ROWS)+1:03}', 'name': name,
                 'result': 'PASS' if all(assertions.values()) else 'FAIL',
                 'assertions': assertions, 'evidence': evidence})


def blocked(name, reason, dependency):
    ROWS.append({'id': f'AUD154-{len(ROWS)+1:03}', 'name': name, 'result': 'BLOCKED',
                 'reason': reason, 'dependency': dependency})


def call(name, method, path, expected, session=None, body=None, signing_key=None):
    status, data = http(method, path, session, body, signing_key)
    evidence = {'method': method, 'path': path, 'expectedStatus': expected, 'actualStatus': status}
    if isinstance(data, dict) and status >= 400:
        evidence.update(code=data.get('code'), traceId=data.get('traceId'))
    record(name, {'http': status == expected}, evidence)
    return status, data


def marker():
    return sql('SELECT to_json(coalesce(max(id),0)) FROM audit_log;')


def audits(since, actor, action, entity, entity_id):
    # Action/entity are tester-owned constants; identifiers come from real API responses.
    return sql(f"""SELECT coalesce(json_agg(json_build_object('id',id,'userId',user_id,
        'action',action,'entity',entity,'entityId',entity_id,'detail',detail,'createdAt',created_at,
        'recent',created_at >= now()-interval '5 minutes')), '[]'::json)
        FROM audit_log WHERE id>{int(since)} AND user_id={int(actor)}
        AND action='{action}' AND entity='{entity}' AND entity_id='{int(entity_id)}';""")


def verify_audit(name, rows, expected_detail, actor, entity_id):
    detail = rows[0].get('detail') if len(rows) == 1 else {}
    assertions = {'exactlyOne': len(rows) == 1, 'actor': len(rows) == 1 and rows[0]['userId'] == actor,
                  'object': len(rows) == 1 and rows[0]['entityId'] == str(entity_id),
                  'time': len(rows) == 1 and rows[0]['recent'],
                  'detail': isinstance(detail, dict) and all(detail.get(k) == v for k, v in expected_detail.items())}
    raw = json.dumps(rows)
    confidential = [PASSWORD] + [s[k] for s in SESSIONS for k in ['accessToken', 'refreshToken'] if s.get(k)]
    assertions['noCredentials'] = not any(value in raw for value in confidential)
    # Export only explicitly approved detail keys, never arbitrary runtime detail/body/headers.
    safe = [{k: x.get(k) for k in ['id', 'userId', 'action', 'entity', 'entityId', 'createdAt']} |
            {'detail': {k: x.get('detail', {}).get(k) for k in expected_detail}} for x in rows]
    record(name, assertions, {'rows': safe})
    return rows[0]['id'] if len(rows) == 1 else None


def guard_runtime():
    global DB
    for service in ['backend', 'postgres']:
        p = command(['docker', 'ps', '-q', '--filter', f'label=com.docker.compose.project={PROJECT}',
                     '--filter', f'label=com.docker.compose.service={service}'])
        ids = p.stdout.split()
        if p.returncode or len(ids) != 1:
            raise RuntimeError('Expected one isolated QA154 ' + service)
        if service == 'postgres':
            DB = ids[0]
        else:
            p = command(['docker', 'inspect', '--format', '{{.Config.Image}}|{{.Image}}', ids[0]])
            image = p.stdout.strip().split('|')
            if p.returncode or len(image) != 2 or image[0] != 'greenmart-backend:qa154-' + REVISION[:7]:
                raise RuntimeError('QA154 image baseline mismatch')
            p = command(['docker', 'image', 'inspect', '--format', '{{index .Config.Labels "org.opencontainers.image.revision"}}', image[1]])
            if p.returncode or p.stdout.strip() != REVISION:
                raise RuntimeError('QA154 OCI revision mismatch')
            RUNTIME.update(imageTag=image[0], imageId=image[1], verifiedRevision=REVISION)


def mutation_probe(audit_id):
    before = sql(f"SELECT to_json(md5(row_to_json(a)::text)) FROM audit_log a WHERE id={audit_id};")
    for operation in ['UPDATE', 'DELETE']:
        statement = f"UPDATE audit_log SET action='QA154_SHOULD_ROLL_BACK' WHERE id={audit_id}" if operation == 'UPDATE' else f'DELETE FROM audit_log WHERE id={audit_id}'
        # Even if the trigger is missing, a successful statement is rolled back.
        p = command(['docker', 'exec', '-i', DB, 'psql', '-X', '-At', '-U', 'greenmart', '-d', 'greenmart',
                     '-v', 'ON_ERROR_STOP=1'], '\\set VERBOSITY sqlstate\nBEGIN;\n' + statement + ';\nROLLBACK;\n')
        after = sql(f"SELECT to_json(md5(row_to_json(a)::text)) FROM audit_log a WHERE id={audit_id};")
        record('Audit rejects ' + operation, {'rejected55000': p.returncode != 0 and '55000' in p.stderr,
                                            'rowUnchanged': before == after},
               {'auditId': audit_id, 'sqlState55000': '55000' in p.stderr, 'rollbackProtected': True})


def main():
    guard_runtime()
    sessions = {}
    for username in ['owner', 'quanly.gm001', 'thungan.gm001', 'thukho.gm001']:
        _, s = call('Login ' + username, 'POST', '/auth/login', 200, body={'username': username, 'password': PASSWORD})
        if not isinstance(s, dict) or not s.get('accessToken'):
            raise RuntimeError('Demo login prerequisite failed')
        sessions[username] = s
        SESSIONS.append(s)
    owner, manager, cashier, keeper = [sessions[u] for u in ['owner', 'quanly.gm001', 'thungan.gm001', 'thukho.gm001']]
    actors = {u: sql(f"SELECT to_json(id) FROM app_user WHERE username='{u}';") for u in sessions}
    product = {'sku': PREFIX, 'name': PREFIX, 'unit': 'cai', 'categoryId': 1, 'salePrice': 29000, 'costPrice': 10000, 'active': True}
    _, created = call('Create own QA product', 'POST', '/products', 201, owner, product)
    pid = int(created['id'])
    start = marker()
    changed = product | {'salePrice': 31000}
    call('Owner changes sale price', 'PUT', f'/products/{pid}', 200, owner, changed)
    audit_id = verify_audit('Price audit: actor, prices, object, time', audits(start, actors['owner'], 'CHANGE_PRICE', 'product', pid),
                           {'oldPrice': 29000, 'newPrice': 31000}, actors['owner'], pid)
    start = marker()
    call('Unchanged sale price control', 'PUT', f'/products/{pid}', 200, owner, changed)
    record('No price-change audit for unchanged price', {'noEvent': not audits(start, actors['owner'], 'CHANGE_PRICE', 'product', pid)})
    start = marker()
    call('Cashier cannot change price', 'PUT', f'/products/{pid}', 403, cashier, changed | {'salePrice': 33000})
    record('Rejected price update creates no successful change audit', {'noEvent': not audits(start, actors['thungan.gm001'], 'CHANGE_PRICE', 'product', pid),
        'priceUnchanged': sql(f'SELECT to_json(sale_price) FROM product WHERE id={pid};') == 31000})
    document = {'type': 'IMPORT', 'branchId': 1, 'supplier': PREFIX, 'items': [{'productId': pid, 'quantity': 10, 'unitCost': 10000}]}
    _, d = call('Keeper creates import draft', 'POST', '/stock-documents', 201, keeper, document)
    did = int(d['id'])
    start = marker()
    call('Manager approves import', 'POST', f'/stock-documents/{did}/approve', 200, manager)
    verify_audit('Approval audit committed with stock change', audits(start, actors['quanly.gm001'], 'APPROVE_STOCK_DOCUMENT', 'stock_document', did),
                 {'type': 'IMPORT', 'status': 'APPROVED'}, actors['quanly.gm001'], did)
    record('Approved import stock is real', {'onHandTen': sql(f'SELECT to_json(on_hand) FROM inventory WHERE branch_id=1 AND product_id={pid};') == 10})
    start = marker()
    status, _ = http('POST', f'/stock-documents/{did}/approve', manager)
    record('Repeated approval has no duplicate success audit', {'rejected': status == 409,
        'noEvent': not audits(start, actors['quanly.gm001'], 'APPROVE_STOCK_DOCUMENT', 'stock_document', did)}, {'actualStatus': status})
    start = marker()
    call('Wrong-password login', 'POST', '/auth/login', 401, body={'username': 'thungan.gm001', 'password': secrets.token_hex(20)})
    verify_audit('Failed-login audit survives HTTP 401', audits(start, actors['thungan.gm001'], 'LOGIN_FAILED', 'app_user', actors['thungan.gm001']),
                 {'remainingAttempts': 4}, actors['thungan.gm001'], actors['thungan.gm001'])
    # Successful login clears the single failure counter using the application API.
    _, recovery = call('Recover demo login state', 'POST', '/auth/login', 200, body={'username': 'thungan.gm001', 'password': PASSWORD})
    if isinstance(recovery, dict) and recovery.get('accessToken'):
        SESSIONS.append(recovery)
    for label, path in [('Inventory', '/inventory?branchId=2'), ('Product lookup', '/products/lookup?branchId=2&code=' + PREFIX)]:
        start = marker()
        status, error = call(label + ' denies cross-branch', 'GET', path, 403, cashier)
        record(label + ' branch error code', {'code': isinstance(error, dict) and error.get('code') == 'BRANCH_FORBIDDEN'})
        # Filter actor/action/trace first, then validate identity/branch metadata; do not lose malformed rows.
        trace = error.get('traceId', '') if isinstance(error, dict) else ''
        rows = sql(f"""SELECT coalesce(json_agg(json_build_object('id',id,'userId',user_id,'action',action,'entity',entity,
            'entityId',entity_id,'detail',detail,'createdAt',created_at,'recent',created_at>=now()-interval '5 minutes')),'[]'::json)
            FROM audit_log WHERE id>{start} AND user_id={actors['thungan.gm001']} AND action='BRANCH_FORBIDDEN' AND detail->>'traceId'='{trace}';""")
        verify_audit(label + ' branch-denial audit metadata', rows,
                     {'branchId': 2, 'method': 'GET', 'path': '/api/v1' + path.split('?')[0], 'traceId': trace}, actors['thungan.gm001'], 2)
        record(label + ' audit entity is branch', {'entity': len(rows) == 1 and rows[0]['entity'] == 'branch'})
    adjustment(pid, keeper, manager)
    cancel_order(pid, manager)
    if audit_id:
        mutation_probe(audit_id)


def adjustment(pid, keeper, manager):
    status, d = http('POST', '/stock-documents', keeper, {'type': 'ADJUST', 'branchId': 1,
        'items': [{'productId': pid, 'quantity': 10}], 'note': PREFIX})
    if status != 201:
        blocked('Adjustment audit after approval', 'ADJUST creation unavailable; cannot establish approved adjustment through API', [63])
        ROWS[-1]['evidence'] = {'actualStatus': status, 'code': d.get('code') if isinstance(d, dict) else None,
                               'classification': 'Open implementation dependency #63; not a reproduced missing-audit bug'}
        return
    blocked('Adjustment audit after approval', 'ADJUST now available; separate snapshot/delta fixture required before accepting audit', [63])


def cancel_order(pid, manager):
    key = secrets.token_hex(32)
    sku = PREFIX + '-WEB'
    sql(f"UPDATE sales_channel SET webhook_secret='{key}' WHERE code='WEB'; INSERT INTO channel_product(channel_id,product_id,external_sku) SELECT id,{pid},'{sku}' FROM sales_channel WHERE code='WEB';", fixture=True)
    _, order = call('Create real COD order for cancellation', 'POST', '/integrations/WEB/orders', 202,
        body={'externalOrderId': PREFIX, 'orderedAt': datetime.now(timezone.utc).isoformat(),
              'customer': {'name': PREFIX, 'phone': '0991540014'}, 'items': [{'externalSku': sku, 'quantity': 1, 'unitPrice': 31000}],
              'shippingFee': 0, 'paymentMethod': 'COD'}, signing_key=key)
    if not isinstance(order, dict) or not order.get('orderId'):
        blocked('Order-cancellation audit', 'Real online order prerequisite failed', [40, 42, 320])
        return
    oid = int(order['orderId'])
    record('Cancellation fixture is a real CONFIRMED order', {'confirmed': order.get('status') == 'CONFIRMED',
        'databaseConfirmed': sql(f"SELECT to_json(status) FROM sales_order WHERE id={oid};") == 'CONFIRMED'}, {'orderId': oid})
    status, data = http('POST', f'/orders/{oid}/cancel', manager, {'reason': 'CUSTOMER_REQUEST', 'note': PREFIX})
    if status in [404, 405]:
        blocked('Order-cancellation audit', 'Cancel endpoint unavailable on real CONFIRMED order; no fake audit inserted', [42, 320])
        ROWS[-1]['evidence'] = {'orderId': oid, 'actualStatus': status, 'code': data.get('code') if isinstance(data, dict) else None}
    else:
        blocked('Order-cancellation audit', 'Cancel route responded; independent transition/audit fixture needs review before acceptance', [42])


if __name__ == '__main__':
    interrupted = None
    try:
        main()
    except Exception as error:
        interrupted = type(error).__name__  # Never persist raw exception/HTTP body/secrets.
    finally:
        for index, session in enumerate(SESSIONS, 1):
            try:
                call('Logout session ' + str(index), 'POST', '/auth/logout', 204, session, {'refreshToken': session.get('refreshToken')})
            except Exception:
                record('Logout session ' + str(index), {'completed': False})
        result = {'issue': 154, 'date': datetime.now(timezone.utc).isoformat(), 'sourceCommit': REVISION,
                  'project': PROJECT, 'base': BASE, 'fixturePrefix': PREFIX, 'runtime': RUNTIME,
                  'summary': dict(Counter(x['result'] for x in ROWS)), 'interrupted': interrupted, 'checks': ROWS,
                  'limitations': ['SQL mapping/signing-key fixture only in isolated QA154; no inserted audit fixtures.',
                                  'UPDATE/DELETE probes use rollback and do not disable triggers or test privileged TRUNCATE/DDL.',
                                  'Unavailable adjustment/cancellation paths remain BLOCKED, not audit PASS.',
                                  'Audit action names for supported workflows follow current implementation; branch metadata follows Lead Q-27.']}
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'summary': result['summary'], 'interrupted': interrupted}), flush=True)
    raise SystemExit(1 if interrupted or any(x['result'] != 'PASS' for x in ROWS) else 0)
