"""Real API QA for #153; fixture DML is restricted to fresh isolated QA153.

No mocks of PromotionCalculator/expiry. Missing prerequisites stay BLOCKED.
Infrastructure secrets and session tokens are never written to evidence.
"""
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import hashlib
import hmac
import os
import re
import secrets
from pathlib import Path
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
PROJECT = os.environ.get('QA153_PROJECT', 'greenmart-qa153-20261113')
REVISION = os.environ.get('QA153_SOURCE_COMMIT', '83589787bd78aec61940177f9863a80853ca6226')
if not re.fullmatch(r'greenmart-qa153-[a-z0-9-]+', PROJECT) or not re.fullmatch(r'[a-f0-9]{40}', REVISION):
    raise ValueError('Isolated QA153 project and full source revision required')
PORT = int(os.environ.get('QA153_PORT', '18155'))
if not 18000 <= PORT < 19000:
    raise ValueError('Only isolated QA ports allowed')
BASE = f'http://127.0.0.1:{PORT}/api/v1'
OUT = Path(os.environ.get('QA153_REPORT', str(ROOT / 'docs/testing/results/issue-153-2026-11-13.json')))
if OUT.exists():
    raise ValueError('Evidence exists; select a new QA153_REPORT')
PASSWORD = os.environ['GREENMART_DEMO_PASSWORD']
ROWS, SESSIONS, OBSERVATIONS, FIXTURES = [], [], [], []
RUNTIME = {}
PREFIX = 'QA153-' + uuid.uuid4().hex[:8].upper()


def sql(query, fixture=False):
    if not fixture and not query.lstrip().upper().startswith('SELECT'):
        raise ValueError('SQL is read-only except explicit isolated QA fixtures')
    ids = subprocess.check_output(['docker', 'ps', '-q', '--filter',
        f'label=com.docker.compose.project={PROJECT}', '--filter',
        'label=com.docker.compose.service=postgres'], text=True, timeout=15).split()
    if len(ids) != 1:
        raise RuntimeError('QA153 database prerequisite unavailable')
    p = subprocess.run(['docker', 'exec', '-i', ids[0], 'psql', '-X', '-At',
        '-U', 'greenmart', '-d', 'greenmart', '-v', 'ON_ERROR_STOP=1'],
        input=query, text=True, encoding='utf-8', capture_output=True, timeout=20)
    if p.returncode:
        raise RuntimeError('QA153 SQL failed; raw output withheld')
    values = [line for line in p.stdout.splitlines() if line.startswith(('{', '[', '"')) or line.isdigit()]
    return json.loads(values[-1]) if values else None


def http(method, path, session=None, body=None, signing_key=None):
    headers = {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())}
    if session:
        headers['Authorization'] = 'Bearer ' + session['accessToken']
    payload = None if body is None else json.dumps(body).encode()
    if signing_key:
        headers['X-Signature'] = hmac.new(signing_key.encode(), payload or b'', hashlib.sha256).hexdigest()
    request = urllib.request.Request(BASE + path, method=method, headers=headers, data=payload)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        response = opener.open(request, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.code, json.loads(raw) if raw else None


def record(name, assertions, evidence=None):
    ROWS.append({'id': f'EDGE153-{len(ROWS)+1:03}', 'name': name,
        'result': 'PASS' if all(assertions.values()) else 'FAIL',
        'assertions': assertions, 'evidence': evidence})


def blocked(name, reason, dependency):
    ROWS.append({'id': f'EDGE153-{len(ROWS)+1:03}', 'name': name,
        'result': 'BLOCKED', 'reason': reason, 'dependency': dependency})


def check(name, method, path, expected, session=None, body=None, verify=None, code=None):
    status, data = http(method, path, session, body)
    assertions = {'http': status == expected}
    if code:
        assertions['code'] = isinstance(data, dict) and data.get('code') == code
    if verify:
        try:
            assertions.update(verify(data))
        except (TypeError, KeyError, AttributeError):
            assertions['shape'] = False
    evidence = {'method': method, 'path': path, 'expectedStatus': expected, 'actualStatus': status}
    if isinstance(data, dict) and '/orders/quote' in path:
        evidence['quote'] = {k: data.get(k) for k in ['branchId', 'subtotal', 'itemDiscount',
            'orderDiscount', 'discount', 'total', 'orderPromotion', 'canComplete']}
    if status >= 400 and isinstance(data, dict):
        evidence.update(code=data.get('code'), traceId=data.get('traceId'))
    record(name, assertions, evidence)
    return data


def promotion(code, kind, value, minimum, cap, hours, limit=None):
    # Only INSERT new QA-prefixed rows and their scopes. Never touch seed campaigns.
    pid = sql(f"""WITH p AS (
        INSERT INTO promotion(code,type,level,value,min_order,max_discount,start_at,end_at,usage_limit,status)
        VALUES('{code}','{kind}','ORDER',{value},{minimum},{cap if cap is not None else 'NULL'},
        now()-interval '1 hour',now()+interval '{hours} hours',{limit if limit is not None else 'NULL'},'ACTIVE') RETURNING id),
        b AS (INSERT INTO promotion_branch SELECT id,1 FROM p),
        c AS (INSERT INTO promotion_channel SELECT p.id,s.id FROM p CROSS JOIN sales_channel s WHERE s.code='POS')
        SELECT to_json(id) FROM p;""", fixture=True)
    snapshot = sql(f"""SELECT json_build_object('id',id,'code',code,'type',type,'level',level,
        'value',value,'minOrder',min_order,'maxDiscount',max_discount,'status',status,
        'usageLimit',usage_limit,'usedCount',used_count,'effectiveNow',start_at<=now() AND end_at>now(),
        'branches',(SELECT json_agg(branch_id) FROM promotion_branch WHERE promotion_id=p.id),
        'channels',(SELECT json_agg(s.code) FROM promotion_channel c JOIN sales_channel s ON s.id=c.channel_id WHERE c.promotion_id=p.id))
        FROM promotion p WHERE id={pid};""")
    FIXTURES.append(snapshot)
    ready = snapshot['status']=='ACTIVE' and snapshot['effectiveNow'] and snapshot['branches']==[1] and snapshot['channels']==['POS'] and snapshot['usedCount']==0
    record('Promotion fixture ready ' + code, {'ready': ready}, snapshot)
    if not ready:
        raise RuntimeError('Promotion fixture did not become eligible; stop before behavior checks')
    return pid


def quote(session, product):
    return {'branchId': 1, 'items': [{'productId': product, 'quantity': 1}]}


def main():
    ids = subprocess.check_output(['docker', 'ps', '-q', '--filter',
        f'label=com.docker.compose.project={PROJECT}', '--filter',
        'label=com.docker.compose.service=backend'], text=True, timeout=15).split()
    if len(ids) != 1:
        raise RuntimeError('Expected one isolated QA153 backend')
    image = subprocess.check_output(['docker', 'inspect', '--format', '{{.Config.Image}}|{{.Image}}', ids[0]],
        text=True, timeout=15).strip()
    if not image.startswith('greenmart-backend:qa153-' + REVISION[:7] + '|'):
        raise RuntimeError('Wrong QA153 image baseline')
    revision = subprocess.check_output(['docker', 'image', 'inspect', '--format',
        '{{index .Config.Labels "org.opencontainers.image.revision"}}', image.split('|')[1]], text=True, timeout=10).strip()
    if revision != REVISION:
        raise RuntimeError('Image revision mismatch')
    RUNTIME.update(imageTag=image.split('|')[0], imageId=image.split('|')[1], verifiedRevision=revision)
    sessions = {}
    for username in ['owner', 'quanly.gm001', 'thungan.gm001', 'thukho.gm001']:
        session = check('Login ' + username, 'POST', '/auth/login', 200,
            body={'username': username, 'password': PASSWORD})
        if not session or 'accessToken' not in session:
            raise RuntimeError('Demo login prerequisite failed')
        sessions[username] = session
        SESSIONS.append((username, session))
    owner, manager, cashier = [sessions[u] for u in ['owner', 'quanly.gm001', 'thungan.gm001']]
    active_seed = sql("SELECT to_json(count(*)) FROM promotion WHERE status='ACTIVE' AND start_at<=now() AND end_at>now();")
    if active_seed:
        raise RuntimeError('Existing active campaigns would contaminate fixtures; refusing to modify them')
    category = sql('SELECT to_json(id) FROM category ORDER BY id LIMIT 1;')
    products = {}
    for price in [100000, 180000, 223000, 250000, 260000, 350000, 240000]:
        product = check('Create QA product ' + str(price), 'POST', '/products', 201, owner,
            {'sku': PREFIX + '-' + str(price), 'name': 'QA153 fixture ' + str(price), 'unit': 'cai',
             'categoryId': category, 'salePrice': price, 'costPrice': 1, 'active': True})
        products[price] = product['id']
    draft = check('Prepare stock through IMPORT API', 'POST', '/stock-documents', 201,
        sessions['thukho.gm001'], {'type': 'IMPORT', 'branchId': 1, 'supplier': 'QA153 fixtures',
            'items': [{'productId': p, 'quantity': 20, 'unitCost': 1} for p in products.values()]})
    check('Approve QA fixture import', 'POST', f'/stock-documents/{draft["id"]}/approve', 200, manager)
    body = quote(cashier, products[100000])
    check('Control quote without promotions', 'POST', '/orders/quote', 200, cashier, body,
        verify=lambda d: {'total': d['total'] == 100000, 'discount': d['discount'] == 0, 'stock': d['canComplete'] is True})
    order = check('Control successful POS before campaign setup', 'POST', '/orders/pos', 201, cashier,
        {**body, 'payment': {'method': 'CASH', 'cashReceived': 100000}, 'expectedTotal': 100000},
        verify=lambda d: {'total': d['total'] == 100000, 'completed': d['status'] == 'COMPLETED'})
    if not order or 'id' not in order:
        raise RuntimeError('POS positive control unavailable')
    p10 = promotion(PREFIX + '-10', 'PERCENT', 10, 200000, 30000, 2)
    p25 = promotion(PREFIX + '-25K', 'AMOUNT', 25000, 300000, None, 1)
    before_quote = sql(f"SELECT json_build_object('uses',sum(used_count),'orders',(SELECT count(*) FROM sales_order)) FROM promotion WHERE id IN({p10},{p25});")
    for price, discount in [(180000, 0), (223000, 22300), (260000, 26000), (350000, 30000)]:
        check('BA7 amount ' + str(price), 'POST', '/orders/quote', 200, cashier,
            quote(cashier, products[price]), verify=lambda d, p=price, off=discount:
                {'subtotal': d['subtotal'] == p, 'orderDiscount': d['orderDiscount'] == off,
                 'total': d['total'] == p - off})
    after_quote = sql(f"SELECT json_build_object('uses',sum(used_count),'orders',(SELECT count(*) FROM sales_order)) FROM promotion WHERE id IN({p10},{p25});")
    record('Quote must not consume campaign usage or create orders', {'unchanged': before_quote == after_quote},
        {'before': before_quote, 'after': after_quote})
    sql(f"UPDATE promotion_channel SET channel_id=(SELECT id FROM sales_channel WHERE code='WEB') WHERE promotion_id={p10};", fixture=True)
    check('BA7 channel outside campaign scope', 'POST', '/orders/quote', 200, cashier,
        quote(cashier, products[240000]), verify=lambda d: {'discount': d['discount'] == 0, 'total': d['total'] == 240000})
    sql(f"UPDATE promotion_channel SET channel_id=(SELECT id FROM sales_channel WHERE code='POS') WHERE promotion_id={p10}; UPDATE promotion SET min_order=200000 WHERE id={p25};", fixture=True)
    check('Equal discount chooses earlier expiry', 'POST', '/orders/quote', 200, cashier,
        quote(cashier, products[250000]), verify=lambda d:
            {'discount': d['orderDiscount'] == 25000, 'chosen': (d.get('orderPromotion') or {}).get('id') == p25, 'total': d['total'] == 225000})
    sql(f"UPDATE promotion SET status='PAUSED' WHERE id IN({p10},{p25});", fixture=True)
    check('Paused campaigns do not apply', 'POST', '/orders/quote', 200, cashier,
        quote(cashier, products[250000]), verify=lambda d: {'discount': d['discount'] == 0, 'total': d['total'] == 250000})
    last = promotion(PREFIX + '-LAST', 'PERCENT', 10, 0, None, 2, 1)
    quotes = [check('EX10 eligible quote ' + str(i), 'POST', '/orders/quote', 200, sessions[u], body,
        verify=lambda d: {'discount': d['orderDiscount'] == 10000, 'total': d['total'] == 90000})
        for i, u in enumerate(['thungan.gm001', 'quanly.gm001'], 1)]
    if all(d and d.get('total') == 90000 and (d.get('orderPromotion') or {}).get('id') == last for d in quotes):
        barrier = threading.Barrier(2)
        def buy(session):
            barrier.wait(timeout=10)
            return http('POST', '/orders/pos', session,
                {**body, 'payment': {'method': 'CASH', 'cashReceived': 100000}, 'expectedTotal': 90000})
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(buy, [cashier, manager]))
        record('EX10 last-use concurrent result', {'oneWinnerOneRequote': sorted(s for s,d in results) == [201,422],
            'loserQuote': any(s==422 and d.get('code')=='TOTAL_CHANGED' and d.get('details',{}).get('quote',{}).get('total')==100000 for s,d in results)},
            {'statuses': sorted(s for s,d in results)})
        used = sql(f'SELECT to_json(used_count) FROM promotion WHERE id={last};')
        record('EX10 usage remains within limit', {'oneUse': used == 1}, {'usedCount': used, 'limit': 1})
    else:
        blocked('EX10 concurrent last-use', 'Valid ACTIVE last-use campaign is not applied by quote; cannot create the required discounted quote precondition. No fabricated stale total submitted.', [39])
    sql(f"UPDATE promotion SET used_count=1 WHERE id={last};", fixture=True)
    check('Exhausted campaign control', 'POST', '/orders/quote', 200, cashier, body,
        verify=lambda d: {'noDiscount': d['discount'] == 0, 'total': d['total'] == 100000})
    sql(f"UPDATE promotion SET used_count=0,start_at=now()-interval '1 day',end_at=now()-interval '1 second' WHERE id={last};", fixture=True)
    check('Expired campaign control (not order expiry)', 'POST', '/orders/quote', 200, cashier, body,
        verify=lambda d: {'noDiscount': d['discount'] == 0, 'total': d['total'] == 100000})
    for path in ['/promotions']:
        method = 'GET' if path == '/promotions' else 'POST'
        status, data = http(method, path, owner, None if method == 'GET' else {})
        OBSERVATIONS.append({'method': method, 'path': path, 'status': status,
            'code': data.get('code') if isinstance(data,dict) else None,
            'purpose': 'Authenticated routing diagnostic only; not a valid HMAC webhook flow'})
    online_cases(products[100000], owner)


def online_cases(product_id, owner):
    """Create a signed ONLINE order, then observe the real expiry path.

    Only move the deadline of the newly created QA order into the past. Never
    set status=EXPIRED or release stock in SQL to fabricate a successful job.
    """
    key = secrets.token_hex(32)
    external = PREFIX + '-ONLINE'
    external_sku = PREFIX + '-WEB'
    sql(f"UPDATE sales_channel SET webhook_secret='{key}' WHERE code='WEB'; "
        f"INSERT INTO channel_product(channel_id,product_id,external_sku) "
        f"SELECT id,{int(product_id)},'{external_sku}' FROM sales_channel WHERE code='WEB';", fixture=True)
    OBSERVATIONS.append({'purpose':'QA webhook setup','channel':'WEB','mappingProductId':product_id,
                         'secret':'ephemeral process-only key; value never exported'})
    initial = sql(f"SELECT json_build_object('onHand',on_hand,'reserved',reserved) FROM inventory WHERE branch_id=1 AND product_id={int(product_id)};")
    payload={'externalOrderId':external,'orderedAt':datetime.now(timezone.utc).isoformat(),
             'customer':{'name':'QA153 online fixture','phone':'0991530014'},
             'items':[{'externalSku':external_sku,'quantity':3,'unitPrice':100000}],
             'shippingFee':0,'paymentMethod':'ONLINE'}
    status,ack=http('POST','/integrations/WEB/orders',body=payload,signing_key=key)
    record('EX05 create real signed ONLINE order',{'http':status==202,'reserved':isinstance(ack,dict) and ack.get('status')=='RESERVED'},
           {'actualStatus':status,'orderId':ack.get('orderId') if isinstance(ack,dict) else None})
    if status!=202 or not isinstance(ack,dict) or not ack.get('orderId'):
        for name in ['EX05 deadline/release/channel update','EX06 late PAID with stock','EX06 late PAID without stock']:
            blocked(name,'Cannot establish real signed RESERVED order; no forced SQL business state used',[40,41])
        return
    oid=int(ack['orderId'])
    def state():
        return sql(f"""SELECT json_build_object('orderId',o.id,'status',o.status,'branchId',o.branch_id,
            'deadlinePassed',o.reserved_until<now(),'originalWindowSeconds',extract(epoch from o.reserved_until-o.created_at),
            'onHand',i.on_hand,'reserved',i.reserved,'available',i.on_hand-i.reserved,
            'releaseRows',(SELECT count(*) FROM inventory_transaction WHERE ref_type='ORDER' AND ref_id=o.id AND type='RELEASE'),
            'expiredEvents',(SELECT count(*) FROM outbox_event WHERE aggregate_type='order' AND aggregate_id=o.id::text AND event_type='order.expired'))
            FROM sales_order o JOIN inventory i ON i.branch_id=o.branch_id AND i.product_id={int(product_id)} WHERE o.id={oid};""")
    created=state()
    record('EX05 real reservation quantity and 15-minute deadline',
        {'reservedThree':created['reserved']==initial['reserved']+3,'onHandUnchanged':created['onHand']==initial['onHand'],
         'deadline900seconds':895<=created['originalWindowSeconds']<=905},created)
    # Moving a QA fixture deadline tests scheduler behavior without a 15-minute wait.
    sql(f"UPDATE sales_order SET reserved_until=now()-interval '1 second' WHERE id={oid} AND status='RESERVED';",fixture=True)
    observed=[]
    start=time.monotonic()
    while time.monotonic()-start<70:
        latest=state()
        observed.append({'elapsedSeconds':round(time.monotonic()-start,1),'status':latest['status'],'reserved':latest['reserved']})
        if latest['status']=='EXPIRED':break
        print('Waiting for real expiry job: '+str(int(time.monotonic()-start))+' seconds...',flush=True)
        time.sleep(5)
    final=state()
    record('EX05 scheduler expires order and releases stock',
        {'expired':final['status']=='EXPIRED','released':final['reserved']==initial['reserved'],
         'onHandUnchanged':final['onHand']==initial['onHand'],'oneRelease':final['releaseRows']==1,'oneExpiryEvent':final['expiredEvents']==1},
        {'initial':initial,'final':final,'polls':observed,'methodology':'Only reserved_until was moved to past on this real QA order; no forced status/release',
         'relatedIssues':[41,320]})
    if final['status']!='EXPIRED':
        blocked('EX05 channel receives released stock','Real expiry/release did not happen, so channel recovery cannot be accepted',[41,320])
    event={'type':'PAID','occurredAt':datetime.now(timezone.utc).isoformat(),'providerRef':PREFIX+'-PAID'}
    path=f'/integrations/WEB/orders/{external}/events'
    anonymous,_=http('POST',path,body=event,signing_key=key)
    authenticated,data=http('POST',path,owner,event,signing_key=key)
    OBSERVATIONS.append({'method':'POST','path':path,'purpose':'Valid HMAC event route diagnostic; does not prove EX06 business transition',
        'anonymousStatus':anonymous,'authenticatedStatus':authenticated,'code':data.get('code') if isinstance(data,dict) else None})
    if final['status']!='EXPIRED' or authenticated in [404,405] or anonymous!=200:
        reason=f'Real EXPIRED prerequisite unavailable or event route absent (public={anonymous}, authenticated={authenticated}); no SQL-forced EXPIRED/payment/refund'
        blocked('EX06 late PAID with stock -> CONFIRMED',reason,[40,41,320])
        blocked('EX06 late PAID without stock -> CANCELLED/REFUNDED',reason,[40,41,320])
    else:
        blocked('EX06 late PAID fixture separation required','Event diagnostic reached handler; prepare independent expired with-stock/no-stock fixtures before accepting both outcomes',[41])


if __name__ == '__main__':
    interrupted = None
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        interrupted = str(error) if type(error) is RuntimeError else type(error).__name__ + '; raw output withheld'
    finally:
        for username, session in SESSIONS:
            try:
                check('Logout ' + username, 'POST', '/auth/logout', 204, session,
                    {'refreshToken': session['refreshToken']})
            except Exception:
                interrupted = 'Logout unavailable; QA session may persist until expiry'
        data = {'issue':153, 'date':datetime.now(timezone.utc).isoformat(), 'sourceCommit':REVISION,
            'project':PROJECT, 'base':BASE, 'runtime':RUNTIME, 'fixturePrefix':PREFIX,
            'summary':dict(Counter(r['result'] for r in ROWS)), 'interrupted':interrupted,
            'checks':ROWS, 'routeObservations':OBSERVATIONS, 'promotionFixturesAtCreation':FIXTURES,
            'limitations':['SQL fixture setup only inside QA153 because promotion CRUD #38 is absent.',
                'No end-to-end claim for online expiry or last-use race until prerequisites exist.',
                'No-discount controls alone cannot prove exclusion filtering; active positive cases must also pass.',
                'Online deadline is advanced only for a newly received signed QA order; no forced EXPIRED or inventory release.',
                'WEB webhook key is replaced with a process-generated QA fixture key only in this isolated database.']}
        OUT.parent.mkdir(parents=True,exist_ok=True)
        OUT.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({'summary':data['summary'],'interrupted':interrupted}))
    sys.exit(1 if interrupted or any(r['result'] != 'PASS' for r in ROWS) else 0)
