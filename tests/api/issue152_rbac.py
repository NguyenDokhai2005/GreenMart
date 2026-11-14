"""Issue #152: real HTTP branch/role matrix on isolated QA152 only.

Set GREENMART_DEMO_PASSWORD in the process environment. Creates three IMPORT
drafts and three one-unit circular TRANSFER fixtures, approves/transfers only
inside isolated QA152, and never resets data. Evidence is sanitized.
"""
from collections import Counter
from datetime import datetime, timezone
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
PROJECT = os.environ.get('QA152_PROJECT', 'greenmart-qa152-20261113')
SOURCE = os.environ.get('QA152_SOURCE_COMMIT')
VERSIONS = {'20261111': (18152, '81ddef8'), '20261112': (18153, '15d136b'),
            '20261113': (18154, '8358978')}
if SOURCE:
    if not re.fullmatch(r'[0-9a-f]{40}', SOURCE) or not re.fullmatch(r'greenmart-qa152-[a-z0-9-]+', PROJECT):
        raise ValueError('Explicit source SHA and isolated QA152 project required')
    PORT, REVISION = int(os.environ['QA152_PORT']), SOURCE[:7]
    if not 18000 <= PORT < 19000:
        raise ValueError('QA port must be in the isolated QA range')
else:
    if PROJECT not in ['greenmart-qa152-' + day for day in VERSIONS]:
        raise ValueError('Only isolated QA152 projects are allowed')
    PORT, REVISION = VERSIONS[PROJECT[-8:]]
BASE = f'http://127.0.0.1:{PORT}/api/v1'
OUT = Path(os.environ.get('QA152_REPORT', str(ROOT / f'docs/testing/results/issue-152-{PROJECT[-8:-4]}-{PROJECT[-4:-2]}-{PROJECT[-2:]}.json')))
if OUT.exists():
    raise ValueError('Evidence exists; choose a new QA152_REPORT, never overwrite history')
RESULTS, SESSIONS = [], []
RUNTIME = {}
PASSWORD = os.environ['GREENMART_DEMO_PASSWORD']
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def sql(query):
    ids = subprocess.check_output(['docker', 'ps', '-q', '--filter',
        f'label=com.docker.compose.project={PROJECT}', '--filter',
        'label=com.docker.compose.service=postgres'], text=True, timeout=15).split()
    if len(ids) != 1:
        raise RuntimeError('Expected exactly one isolated QA152 postgres')
    p = subprocess.run(['docker', 'exec', '-i', ids[0], 'psql', '-X', '-At',
        '-U', 'greenmart', '-d', 'greenmart', '-v', 'ON_ERROR_STOP=1'],
        input=query, text=True, encoding='utf-8', capture_output=True, timeout=20)
    if p.returncode:
        raise RuntimeError('Read-only QA152 SQL failed; output withheld')
    return json.loads(p.stdout)


def http(method, path, session=None, body=None):
    headers = {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())}
    if session:
        headers['Authorization'] = 'Bearer ' + session['accessToken']
    request = urllib.request.Request(BASE + path, method=method, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    try:
        response = OPENER.open(request, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.code, json.loads(raw) if raw else None


def check(username, name, method, path, expected, session=None, body=None,
          code=None, verify=None):
    audit_before = sql("SELECT to_json(coalesce(max(id),0)) FROM audit_log;") if SOURCE and code == 'BRANCH_FORBIDDEN' else None
    status, data = http(method, path, session, body)
    assertions = {'status': status == expected}
    if code:
        assertions['code'] = isinstance(data, dict) and data.get('code') == code
    if expected >= 400:
        assertions['envelope'] = (isinstance(data, dict) and bool(data.get('traceId'))
            and isinstance(data.get('message'), str) and 'stackTrace' not in data)
    if verify:
        try:
            assertions.update(verify(data))
        except (KeyError, TypeError, ValueError):
            assertions['shape'] = False
    audit = None
    if audit_before is not None:
        actor = int(session['user']['id'])
        trace = data.get('traceId') if isinstance(data, dict) else None
        if status == 403 and re.fullmatch(r'[a-fA-F0-9-]{36}', trace or ''):
            clean_path = '/api/v1' + path.split('?')[0]
            audit = sql(f"""SELECT json_build_object('newRows',count(*),
                'actorRows',count(*) FILTER (WHERE user_id={actor}),
                'missingBranchRows',count(*) FILTER (WHERE entity_id IS NULL OR NOT (detail ? 'branchId')),
                'completeRows',count(*) FILTER (WHERE user_id={actor}
                  AND action='BRANCH_FORBIDDEN' AND entity='branch'
                  AND entity_id IN ('1','2','3') AND detail->>'branchId'=entity_id
                  AND detail->>'method'='{method}' AND detail->>'path'='{clean_path}'
                  AND detail->>'traceId'='{trace}'
                  AND detail - ARRAY['branchId','method','path','traceId'] = '{{}}'::jsonb))
                FROM audit_log WHERE id>{int(audit_before)} AND detail->>'traceId'='{trace}';""")
            assertions['auditMetadata'] = audit['newRows'] == audit['completeRows'] == 1
        else:
            assertions['auditMetadata'] = False
    RESULTS.append({'id': f'RBAC152-{len(RESULTS)+1:03}', 'username': username,
        'name': name, 'method': method, 'path': path, 'expectedStatus': expected,
        'expectedCode': code, 'actualStatus': status,
        'actualCode': data.get('code') if status >= 400 and isinstance(data, dict) else None,
        'traceId': data.get('traceId') if status >= 400 and isinstance(data, dict) else None,
        'bodyBranchId': body.get('branchId') if isinstance(body, dict) else None,
        'responseEvidence': {key: data[key] for key in ['branchId', 'available'] if key in data}
            if isinstance(data, dict) and '/products/lookup?' in path else None,
        'auditEvidence': audit,
        'assertions': assertions, 'result': 'PASS' if all(assertions.values()) else 'FAIL'})
    return data


def main():
    containers = subprocess.check_output(['docker', 'ps', '-q', '--filter',
        f'label=com.docker.compose.project={PROJECT}', '--filter',
        'label=com.docker.compose.service=backend'], text=True, timeout=15).split()
    if len(containers) != 1:
        raise RuntimeError('Expected one running isolated QA152 backend')
    image = subprocess.check_output(['docker', 'inspect', '--format',
        '{{.Config.Image}}|{{.Image}}', containers[0]], text=True, timeout=15).strip()
    expected_tag = 'greenmart-backend:qa152-' + REVISION
    if not image.startswith(expected_tag + '|'):
        raise RuntimeError('QA backend image does not match intended baseline')
    RUNTIME.update(imageTag=expected_tag, imageId=image.split('|')[1])
    if SOURCE:
        revision = subprocess.check_output(['docker', 'image', 'inspect', image.split('|')[1],
            '--format', '{{index .Config.Labels "org.opencontainers.image.revision"}}'], text=True, timeout=15).strip()
        if revision != SOURCE:
            raise RuntimeError('QA image source revision mismatch')
        RUNTIME['sourceRevision'] = revision
    accounts = [('admin', 'ADMIN', None), ('owner', 'OWNER', None), ('owner.pho', 'OWNER', None)]
    accounts += [(f'{prefix}.gm00{i}', role, i) for prefix, role in
        [('quanly', 'BRANCH_MANAGER'), ('thungan', 'CASHIER'), ('thukho', 'STOREKEEPER')]
        for i in range(1, 4)]
    sessions = {}
    baseline = sql("SELECT json_build_object('users',(SELECT count(*) FROM app_user),'branches',(SELECT count(*) FROM branch));")
    if baseline != {'users': 12, 'branches': 3}:
        raise RuntimeError('Expected official demo fixture: 12 users, 3 branches')
    for username, role, branch in accounts:
        session = check(username, 'Login fixture', 'POST', '/auth/login', 200,
            body={'username': username, 'password': PASSWORD},
            verify=lambda d, u=username, r=role, a=branch: {'identity': d['user']['username'] == u,
                'role': d['user']['role'] == r,
                'branches': {b['id'] for b in d['user']['branches']} == ({1, 2, 3} if a is None else {a})})
        if not session or 'accessToken' not in session:
            raise RuntimeError('Login prerequisite failed')
        SESSIONS.append((username, session))
        sessions[username] = session
    product = sql("SELECT json_build_object('id',p.id,'sku',p.sku) FROM product p JOIN inventory i ON i.product_id=p.id WHERE p.is_active=true GROUP BY p.id,p.sku HAVING count(DISTINCT i.branch_id)=3 AND min(i.on_hand-i.reserved)>0 ORDER BY p.id LIMIT 1;")
    pid, sku = product['id'], product['sku']
    customer = sql("SELECT to_json(customer_id) FROM sales_order WHERE customer_id IS NOT NULL GROUP BY customer_id HAVING count(DISTINCT branch_id)=3 ORDER BY customer_id LIMIT 1;")
    if not customer:
        raise RuntimeError('Need one seeded customer with orders across all branches')
    # Legacy probe retained; explicit-source runs additionally verify full metadata
    # on every branch denial per Lead's Q-27 decision in issue #310.
    audit_before = sql("SELECT to_json(coalesce(max(id),0)) FROM audit_log;")
    check('thungan.gm001', 'Dedicated audit probe: foreign inventory', 'GET',
        '/inventory?branchId=2', 403, sessions['thungan.gm001'], code='BRANCH_FORBIDDEN')
    actor = sessions['thungan.gm001']['user']['id']
    audit_after = sql(f"SELECT json_build_object('newRows',count(*),'actorRows',count(*) FILTER (WHERE user_id={int(actor)})) FROM audit_log WHERE id>{int(audit_before)};")
    RESULTS.append({'id': f'RBAC152-{len(RESULTS)+1:03}', 'name': 'Dedicated branch denial audit',
        'actual': audit_after, 'assertions': {'newActorEvent': audit_after['actorRows'] > 0},
        'result': 'PASS' if audit_after['actorRows'] > 0 else 'FAIL'})
    docs, transfers = {}, {}
    orders = sql("SELECT json_object_agg(branch_id,id) FROM (SELECT branch_id,min(id) id FROM sales_order GROUP BY branch_id) t;")
    for branch in range(1, 4):
        username = f'thukho.gm00{branch}'
        draft = check(username, 'Create own-branch IMPORT fixture', 'POST', '/stock-documents', 201,
            sessions[username], {'type': 'IMPORT', 'branchId': branch, 'supplier': 'QA152',
                'note': 'QA152 authorization fixture; do not approve',
                'items': [{'productId': pid, 'quantity': 1, 'unitCost': 1}]})
        docs[branch] = draft['id']
        transfer = check(username, 'Create transfer fixture at source branch', 'POST', '/stock-documents', 201,
            sessions[username], {'type': 'TRANSFER', 'branchId': branch, 'toBranchId': branch % 3 + 1,
                'note': 'QA152 isolated transfer fixture', 'items': [{'productId': pid, 'quantity': 1}]})
        transfers[branch] = transfer
        check(f'quanly.gm00{branch}', 'Approve transfer fixture at source branch', 'POST',
            f'/stock-documents/{transfer["id"]}/approve', 200, sessions[f'quanly.gm00{branch}'],
            verify=lambda d: {'inTransit': d['status'] == 'IN_TRANSIT'})
    before = snapshot()
    for username, role, assigned in accounts:
        print('Checking role/branch matrix for ' + username + '...', flush=True)
        session = sessions[username]
        for branch in range(1, 4):
            allowed = assigned is None or assigned == branch
            status, code = (200, None) if allowed else (403, 'BRANCH_FORBIDDEN')
            check(username, 'Inventory branch matrix', 'GET', f'/inventory?branchId={branch}&size=100', status,
                session, code=code, verify=(lambda d, b=branch: {'scopedRows': bool(d['content']) and all(x['branch']['id'] == b for x in d['content'])}) if allowed else None)
            check(username, 'Product lookup branch matrix', 'GET', f'/products/lookup?code={sku}&branchId={branch}', status,
                session, code=code, verify=(lambda d, b=branch: {'branch': d['branchId'] == b}) if allowed else None)
            ledger_role = role in ['OWNER', 'BRANCH_MANAGER', 'STOREKEEPER']
            check(username, 'Ledger role then branch', 'GET', f'/inventory/transactions?branchId={branch}',
                status if ledger_role else 403, session,
                code=code if ledger_role else 'FORBIDDEN')
            sales_role = role in ['BRANCH_MANAGER', 'CASHIER']
            quote = {'branchId': branch, 'items': [{'productId': pid, 'quantity': 1}]}
            check(username, 'Quote role then branch', 'POST', '/orders/quote', status if sales_role else 403,
                session, quote, code=code if sales_role else 'FORBIDDEN',
                verify=(lambda d, b=branch: {'branch': d['branchId'] == b}) if sales_role and allowed else None)
            order_role = role != 'ADMIN'
            check(username, 'Persisted order IDOR read', 'GET', f'/orders/{orders[str(branch)]}',
                status if order_role else 403, session, code=code if order_role else 'FORBIDDEN',
                verify=(lambda d, b=branch: {'branch': d['branch']['id'] == b}) if order_role and allowed else None)
            report_role = role in ['OWNER', 'BRANCH_MANAGER']
            for endpoint in ['revenue', 'top-products']:
                check(username, 'Report role then branch: ' + endpoint, 'GET',
                    f'/reports/{endpoint}?branchId={branch}&from=2026-10-01&to=2026-10-31',
                    status if report_role else 403, session, code=code if report_role else 'FORBIDDEN')
            if (SOURCE or PROJECT.endswith('20261113')) and report_role:
                check(username, 'Low-stock alerts branch guard (#61 supplemental)', 'GET',
                    f'/alerts/low-stock?branchId={branch}', status, session, code=code,
                    verify=(lambda d, b=branch: {'list': isinstance(d, list),
                        'scopedRows': all(x['branchId'] == b for x in d)}) if allowed else None)
            read_role = role in ['OWNER', 'BRANCH_MANAGER', 'STOREKEEPER']
            check(username, 'Stock document IDOR read', 'GET', f'/stock-documents/{docs[branch]}',
                status if read_role else 403, session, code=code if read_role else 'FORBIDDEN')
            transfer = transfers[branch]
            destination = branch % 3 + 1
            transfer_allowed = assigned is None or assigned in [branch, destination]
            check(username, 'TRANSFER read source/destination/third branch', 'GET',
                f'/stock-documents/{transfer["id"]}',
                (200 if transfer_allowed else 403) if read_role else 403, session,
                code=(None if transfer_allowed else 'BRANCH_FORBIDDEN') if read_role else 'FORBIDDEN')
            if assigned != destination:
                check(username, 'TRANSFER receive denied outside destination', 'POST',
                    f'/stock-documents/{transfer["id"]}/receive', 403, session,
                    {'items': [{'itemId': transfer['items'][0]['id'], 'receivedQuantity': 1}]},
                    code='BRANCH_FORBIDDEN' if role in ['BRANCH_MANAGER', 'STOREKEEPER'] else 'FORBIDDEN')
            if assigned != branch:
                # ADMIN/OWNER still obey endpoint roles: no blanket role bypass.
                write_role = role in ['BRANCH_MANAGER', 'STOREKEEPER']
                denied_code = 'BRANCH_FORBIDDEN' if write_role else 'FORBIDDEN'
                check(username, 'Cross-branch IMPORT creation denied', 'POST', '/stock-documents', 403, session,
                    {'type': 'IMPORT', 'branchId': branch, 'supplier': 'QA152',
                     'items': [{'productId': pid, 'quantity': 1, 'unitCost': 1}]}, code=denied_code)
                check(username, 'Cross-branch document update denied', 'PUT', f'/stock-documents/{docs[branch]}', 403,
                    session, {'supplier': 'QA152 unauthorized', 'items': [{'productId': pid, 'quantity': 2, 'unitCost': 1}]}, code=denied_code)
                check(username, 'Cross-branch document cancel denied', 'POST', f'/stock-documents/{docs[branch]}/cancel', 403,
                    session, {'reason': 'QA152 denied cross-branch'}, code=denied_code)
                check(username, 'Cross-branch approval denied', 'POST', f'/stock-documents/{docs[branch]}/approve', 403,
                    session, code='BRANCH_FORBIDDEN' if role == 'BRANCH_MANAGER' else (None if role == 'STOREKEEPER' else 'FORBIDDEN'))
                check(username, 'Cross-branch receive denied before status validation', 'POST', f'/stock-documents/{docs[branch]}/receive', 403,
                    session, {'items': [{'itemId': 1, 'receivedQuantity': 1}]}, code=denied_code)
                check(username, 'Cross-branch POS denied', 'POST', '/orders/pos', 403, session,
                    {**quote, 'payment': {'method': 'CASH', 'cashReceived': 10000000}, 'expectedTotal': 1},
                    code='BRANCH_FORBIDDEN' if sales_role else 'FORBIDDEN')
        # No explicit branch must not leak restricted rows.
        check(username, 'Default inventory scope', 'GET', f'/inventory?productId={pid}&size=100', 200, session,
            verify=lambda d, a=assigned: {'nonempty': bool(d['content']),
                'scope': {x['branch']['id'] for x in d['content']} == ({1, 2, 3} if a is None else {a})})
        check(username, 'Customer order history scope', 'GET', f'/customers/{customer}/orders?size=100', 200, session,
            verify=lambda d, a=assigned: {'nonempty': bool(d['content']), 'scope': all(a is None or x['branch']['id'] == a for x in d['content'])})
    after = snapshot()
    RESULTS.append({'id': f'RBAC152-{len(RESULTS)+1:03}', 'name': 'Denied writes leave business state unchanged',
        'assertions': {'unchanged': before == after}, 'before': before, 'after': after,
        'result': 'PASS' if before == after else 'FAIL'})
    for source, transfer in transfers.items():
        destination = source % 3 + 1
        username = f'thukho.gm00{destination}'
        check(username, 'TRANSFER receive allowed at destination', 'POST',
            f'/stock-documents/{transfer["id"]}/receive', 200, sessions[username],
            {'items': [{'itemId': transfer['items'][0]['id'], 'receivedQuantity': 1}]},
            verify=lambda d: {'received': d['status'] == 'RECEIVED', 'destination': d['toBranch']['id'] == destination})


def snapshot():
    return sql("""SELECT json_build_object(
        'orders',(SELECT count(*) FROM sales_order),
        'documents',(SELECT count(*) FROM stock_document),
        'documentState',(SELECT md5(string_agg(row_to_json(t)::text,',' ORDER BY id)) FROM stock_document t),
        'itemState',(SELECT md5(string_agg(row_to_json(t)::text,',' ORDER BY id)) FROM stock_document_item t),
        'inventory',(SELECT md5(string_agg(row_to_json(t)::text,',' ORDER BY branch_id,product_id)) FROM inventory t),
        'ledger',(SELECT count(*) FROM inventory_transaction));""")


if __name__ == '__main__':
    blocked = None
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        blocked = (str(error) if type(error) is RuntimeError else
            type(error).__name__ + ': execution interrupted; raw output withheld')
    finally:
        for username, session in SESSIONS:
            try:
                check(username, 'Logout cleanup', 'POST', '/auth/logout', 204, session,
                    {'refreshToken': session['refreshToken']})
            except Exception:
                blocked = 'Logout cleanup incomplete; session may remain until expiry'
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(json.dumps({'issue': 152, 'date': datetime.now(timezone.utc).isoformat(),
            'sourceCommit': SOURCE or subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
            'project': PROJECT, 'base': BASE, 'runtime': RUNTIME, 'summary': dict(Counter(r['result'] for r in RESULTS)),
            'blocked': blocked, 'checks': RESULTS,
            'limitations': ['No browser E2E claim.', 'Role restrictions follow OpenAPI, ADMIN/OWNER only bypass branch restrictions.',
                'Order list/branches routes outside this legacy matrix require supplemental checks; never infer PASS.',
                'Transfers use one product/unit in isolated QA; not all discrepancy/stock failure scenarios.',
                'Full audit metadata checked on branch denials only for explicit-source runs.',
                'ADMIN receipt403 follows Lead confirmation on issue259.']}, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'summary': dict(Counter(r['result'] for r in RESULTS)), 'blocked': blocked}))
    sys.exit(1 if blocked or any(r['result'] == 'FAIL' for r in RESULTS) else 0)
