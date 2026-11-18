"""Reproduce four observed bugs on an explicitly identified, seeded QA stack only.

Does not bootstrap, reseed, stop services, read .env or print HTTP credentials.
Exit 1 means a business invariant failed; inspect individual checkpoint results.
"""
import concurrent.futures as cf
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import urllib.error
import urllib.request
import uuid


def docker(*args):
    result = subprocess.run(['docker', *args], capture_output=True, text=True, timeout=30)
    if result.returncode:
        raise RuntimeError('Docker prerequisite failed; raw output withheld')
    return result.stdout.strip()


class Suite:
    def __init__(self):
        project = os.environ.get('QA_BUGHUNT_PROJECT', '')
        revision = os.environ.get('QA_BUGHUNT_COMMIT', '')
        port = int(os.environ.get('QA_BUGHUNT_PORT', '0'))
        self.password = os.environ.get('GREENMART_DEMO_PASSWORD')
        report = os.environ.get('QA_BUGHUNT_REPORT')
        if not re.fullmatch(r'greenmart-qa156-bughunt-[a-z0-9-]+', project):
            raise ValueError('Explicit isolated bughunt project required')
        if not re.fullmatch(r'[a-f0-9]{40}', revision) or not 18000 <= port < 19000:
            raise ValueError('Explicit QA revision and high localhost port required')
        if not report or not self.password:
            raise ValueError('Process-only password and new report path required')
        self.report = Path(report)
        if self.report.exists():
            raise ValueError('Refuse to overwrite existing evidence')
        backend = docker('ps', '-q', '--filter', 'label=com.docker.compose.project='+project,
                         '--filter', 'label=com.docker.compose.service=backend')
        if not backend or re.search(r'\s', backend):
            raise ValueError('Expected one running isolated backend')
        image = docker('inspect', '--format', '{{.Image}}', backend)
        if docker('image', 'inspect', '--format', '{{index .Config.Labels "org.opencontainers.image.revision"}}', image) != revision:
            raise ValueError('Backend source revision mismatch')
        if docker('port', backend, '8080/tcp') != '127.0.0.1:'+str(port):
            raise ValueError('Backend port binding mismatch')
        self.base = 'http://127.0.0.1:'+str(port)+'/api/v1'
        self.rows, self.sessions = [], []
        self.metadata = dict(sourceCommit=revision, project=project, cases=self.rows)
        self.report.parent.mkdir(parents=True, exist_ok=True)
        self.report.open('x', encoding='utf-8').close()

    def http(self, method, path, session=None, body=None, headers=None):
        h = {'Content-Type': 'application/json', **(headers or {})}
        if session:
            h['Authorization'] = 'Bearer '+session['accessToken']
        request = urllib.request.Request(self.base+path, method=method, headers=h,
                    data=None if body is None else json.dumps(body).encode())
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            response = opener.open(request, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read()
            return response.status, json.loads(data) if data else None

    def api(self, method, path, session=None, body=None, expected=200, headers=None):
        status, value = self.http(method, path, session, body, headers)
        if status != expected:
            raise RuntimeError('Fixture prerequisite failed: '+method+' '+path+' HTTP'+str(status))
        return value

    def record(self, name, passed, evidence):
        self.rows.append(dict(name=name, status='PASS' if passed else 'FAIL', evidence=evidence))
        self.report.write_text(json.dumps(self.metadata, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        print(('PASS ' if passed else 'FAIL ')+name, flush=True)

    def login(self, username):
        session = self.api('POST', '/auth/login', body=dict(username=username, password=self.password))
        self.sessions.append(session)
        return session

    def product(self, owner, keeper, manager, price=10000):
        sku = 'QA-BH-'+uuid.uuid4().hex[:12].upper()
        product = self.api('POST', '/products', owner, dict(sku=sku, name=sku, unit='cai',
                          categoryId=1, salePrice=price, costPrice=0, active=True), 201)
        draft = self.api('POST', '/stock-documents', keeper, dict(type='IMPORT', branchId=1,
                    supplier=sku, items=[dict(productId=product['id'], quantity=20, unitCost=0)]), 201)
        self.api('POST', f'/stock-documents/{draft["id"]}/approve', manager)
        return product

    def sale(self, session, products, discount=0):
        body = dict(branchId=1, items=[dict(productId=p['id'], quantity=1) for p in products])
        if discount:
            body['manualDiscount'] = dict(amount=discount, reason='QA manual discount')
        quote = self.api('POST', '/orders/quote', session, body)
        return self.api('POST', '/orders/pos', session, dict(**body, expectedTotal=quote['total'],
                       payment=dict(method='CASH', cashReceived=quote['total'])), 201,
                       {'Idempotency-Key': str(uuid.uuid4())})

    def run(self):
        owner, keeper, manager, cashier = [self.login(x) for x in
            ['owner', 'thukho.gm001', 'quanly.gm001', 'thungan.gm001']]
        for attempt in range(6):
            cats = [self.api('POST', '/categories', owner, dict(name='QA-BH-'+uuid.uuid4().hex[:8]), 201) for _ in range(2)]
            barrier = threading.Barrier(2)
            def update(i):
                barrier.wait(timeout=10)
                status, value = self.http('PUT', '/categories/'+str(cats[i]['id']), owner,
                                           dict(name=cats[i]['name'], parentId=cats[1-i]['id']))
                return dict(http=status, code=value.get('code'))
            with cf.ThreadPoolExecutor(max_workers=2) as pool:
                responses = list(pool.map(update, [0, 1]))
            saved = {x['id']: x.get('parentId') for x in self.api('GET', '/categories', owner)}
            cycle = saved[cats[0]['id']] == cats[1]['id'] and saved[cats[1]['id']] == cats[0]['id']
            self.record('BH01 category race '+str(attempt+1), not cycle,
                        dict(ids=[x['id'] for x in cats], parents=[saved[x['id']] for x in cats], responses=responses))
        product = self.product(owner, keeper, manager, 9223372036854775807)
        status, quote = self.http('POST', '/orders/quote', cashier,
                                dict(branchId=1, items=[dict(productId=product['id'], quantity=2)]))
        self.record('BH02 quote arithmetic', status in [400, 409, 422] or
                    (status == 200 and isinstance(quote.get('total'), int) and quote['total'] >= 0),
                    dict(http=status, unitPrice=9223372036854775807, quantity=2, total=quote.get('total')))
        today = dt.datetime.now(dt.timezone(dt.timedelta(hours=7))).date().isoformat()
        path = f'/reports/top-products?from={today}&to={today}&branchId=1&limit=100'
        product = self.product(owner, keeper, manager)
        self.sale(cashier, [product])
        before = next((x for x in self.api('GET', path, owner)['topSelling'] if x['productId'] == product['id']), None)
        if before is None:
            raise RuntimeError('History fixture not ranked; select a fresh QA dataset')
        self.api('PUT', '/products/'+str(product['id']), owner, dict(sku=product['sku'], name=product['name'],
                 unit='cai', categoryId=1, salePrice=10000, costPrice=0, active=False))
        after = next((x for x in self.api('GET', path, owner)['topSelling'] if x['productId'] == product['id']), None)
        self.record('BH03 inactive product history', after is not None and before == after,
                    dict(productId=product['id'], before=before, after=after))
        products = [self.product(owner, keeper, manager) for _ in range(2)]
        order = self.sale(manager, products, 2000)
        rows = [x for x in self.api('GET', path, owner)['topSelling'] if x['productId'] in [p['id'] for p in products]]
        if len(rows) != 2:
            raise RuntimeError('Discount fixture not ranked; select a fresh QA dataset')
        self.record('BH04 order discount allocation', sum(x['revenue'] for x in rows) == order['total'],
                    dict(orderId=order['id'], paid=order['total'], productNetRevenue=sum(x['revenue'] for x in rows), products=rows))

    def cleanup(self):
        for session in self.sessions:
            try:
                self.api('POST', '/auth/logout', session, dict(refreshToken=session['refreshToken']), 204)
            except Exception:
                self.metadata['cleanupWarning'] = 'Some QA sessions could not be revoked; credentials withheld'
        self.report.write_text(json.dumps(self.metadata, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')


if __name__ == '__main__':
    suite = None
    try:
        suite = Suite()
        suite.run()
        code = int(any(row['status'] == 'FAIL' for row in suite.rows))
    except Exception as error:
        if suite:
            suite.metadata['blocked'] = type(error).__name__+'; raw output withheld'
        print('BLOCKED: prerequisite or fixture; raw output withheld')
        code = 2
    finally:
        if suite:
            suite.cleanup()
    raise SystemExit(code)
