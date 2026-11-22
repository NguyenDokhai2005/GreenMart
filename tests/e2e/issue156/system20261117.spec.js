import { test, expect } from '@playwright/test';
import { createHmac, randomUUID } from 'node:crypto';
import { eventHeaders } from '../../../mock-connector/src/event-signature.js';
import { spawnSync } from 'node:child_process';

const base = `http://127.0.0.1:${process.env.QA156_PORT}/api/v1`;
const project = process.env.QA156_PROJECT;
const revision = process.env.QA156_SOURCE_COMMIT;
const password = process.env.GREENMART_DEMO_PASSWORD;
const sessions = [];
let api, owner, cashier, manager, keeper, db;
let stage = 'bootstrap';
let httpSteps = [];
function run(args, input) {
  stage = 'Docker isolation check: ' + args[0];
  const result = spawnSync('docker', args, { input, encoding: 'utf8', timeout: 30000, windowsHide: true });
  if (result.status !== 0) throw new Error('Isolated Docker prerequisite failed; raw output withheld');
  return result.stdout.trim();
}
function sql(query, fixture = false) {
  if (!fixture && !/^SELECT\b/i.test(query.trim())) throw new Error('SQL reads only outside explicit fixture');
  const out = run(['exec', '-i', db, 'psql', '-X', '-At', '-U', 'greenmart', '-d', 'greenmart', '-v', 'ON_ERROR_STOP=1'], query);
  const line = out.split(/\r?\n/).filter(x => /^[{["\d]/.test(x)).at(-1);
  return line ? JSON.parse(line) : null;
}
async function call(method, path, session, body, expected = 200, headers = {}) {
  stage = method + ' ' + path.split('?')[0];
  const response = await api.fetch(base + path, { method, data: body,
    headers: { 'Content-Type': 'application/json', ...(session ? { Authorization: `Bearer ${session.accessToken}` } : {}), ...headers } });
  httpSteps.push({ method, path: path.split('?')[0], expectedStatus: expected, actualStatus: response.status() });
  if (response.status() !== expected) throw new Error(`${method} ${path.split('?')[0]} returned ${response.status()}, expected ${expected}`);
  return response.status() === 204 ? null : await response.json();
}
async function login(username) {
  const session = await call('POST', '/auth/login', null, { username, password });
  sessions.push(session);
  return session;
}
function evidence(info, data) { info.annotations.push({ type: 'qa-evidence', description: JSON.stringify(data) }); }
async function fixture() {
  const sku = 'QA156-' + randomUUID().replaceAll('-', '').slice(0, 12).toUpperCase();
  const product = await call('POST', '/products', owner, { sku, name: sku, unit: 'cai', categoryId: 1,
    salePrice: 31000, costPrice: 10000, active: true }, 201);
  const draft = await call('POST', '/stock-documents', keeper, { type: 'IMPORT', branchId: 1, supplier: sku,
    items: [{ productId: product.id, quantity: 20, unitCost: 10000 }] }, 201);
  await call('POST', `/stock-documents/${draft.id}/approve`, manager);
  return { id: product.id, sku };
}
function inventory(pid) {
  return sql(`SELECT json_build_object('onHand',on_hand,'reserved',reserved) FROM inventory WHERE branch_id=1 AND product_id=${Number(pid)};`);
}
test.beforeAll(async ({ playwright }) => {
  if (!password) throw new Error('Demo password process environment required');
  const backend = run(['ps', '-q', '--filter', `label=com.docker.compose.project=${project}`,
    '--filter', 'label=com.docker.compose.service=backend']);
  if (!backend || /\s/.test(backend)) throw new Error('Expected exactly one isolated backend');
  const image = run(['inspect', '--format', '{{.Config.Image}}|{{.Image}}', backend]).split('|');
  if (image[0] !== 'greenmart-backend:qa156-' + revision.slice(0, 7) ||
      run(['image', 'inspect', '--format', '{{index .Config.Labels "org.opencontainers.image.revision"}}', image[1]]) !== revision) {
    throw new Error('QA156 backend source mismatch');
  }
  const frontend = run(['ps', '-q', '--filter', `label=com.docker.compose.project=${project}`,
    '--filter', 'label=com.docker.compose.service=frontend']);
  if (!frontend || /\s/.test(frontend)) throw new Error('Expected exactly one isolated frontend');
  const uiImage = run(['inspect', '--format', '{{.Config.Image}}|{{.Image}}', frontend]).split('|');
  if (uiImage[0] !== 'greenmart-frontend:qa156-' + revision.slice(0, 7) ||
      run(['image', 'inspect', '--format', '{{index .Config.Labels "org.opencontainers.image.revision"}}', uiImage[1]]) !== revision) {
    throw new Error('QA156 frontend source mismatch');
  }
  db = run(['ps', '-q', '--filter', `label=com.docker.compose.project=${project}`, '--filter', 'label=com.docker.compose.service=postgres']);
  if (!db || /\s/.test(db)) throw new Error('Expected exactly one QA database');
  api = await playwright.request.newContext();
  [owner, cashier, manager, keeper] = await Promise.all(['owner', 'thungan.gm001', 'quanly.gm001', 'thukho.gm001'].map(login));
});
test.afterAll(async () => {
  if (!api) return;
  let failures = 0;
  for (const s of sessions) {
    try { await call('POST', '/auth/logout', s, { refreshToken: s.refreshToken }, 204); } catch { failures++; }
  }
  await api.dispose();
  if (failures) throw new Error('Session logout did not complete');
});
test.beforeEach(() => { httpSteps = []; stage = 'test started'; });
test.afterEach(async ({page}, info) => {
  if (info.status !== info.expectedStatus) evidence(info,{uiPath:new URL(page.url()).pathname});
  info.annotations.push({ type: 'qa-stage', description: stage });
  if (httpSteps.length) evidence(info, { httpSteps });
});

test('API POS: quote → pay → COMPLETED → detail, stock and idempotency', async ({}, info) => {
  const p = await fixture();
  const quote = await call('POST', '/orders/quote', cashier, { branchId: 1, items: [{ productId: p.id, quantity: 2 }] });
  expect(quote.total).toBe(62000);
  const key = randomUUID();
  const body = { branchId: 1, items: [{ productId: p.id, quantity: 2 }], expectedTotal: quote.total,
    payment: { method: 'CASH', cashReceived: 100000, simulate: 'SUCCESS' } };
  const order = await call('POST', '/orders/pos', cashier, body, 201, { 'Idempotency-Key': key });
  expect(order.status).toBe('COMPLETED');
  expect(order.total).toBe(62000);
  expect(order.payments[0].status).toBe('PAID');
  expect(order.payments[0].amount).toBe(62000);
  expect(order.payments[0].change).toBe(38000);
  const replay = await call('POST', '/orders/pos', cashier, body, 200, { 'Idempotency-Key': key });
  expect(replay.id).toBe(order.id);
  const detail = await call('GET', `/orders/${order.id}`, cashier);
  expect(detail.id).toBe(order.id);
  expect(detail.status).toBe('COMPLETED');
  expect(inventory(p.id)).toEqual({ onHand: 18, reserved: 0 });
  const sales = sql(`SELECT to_json(count(*)) FROM inventory_transaction WHERE ref_type='ORDER' AND ref_id=${Number(order.id)} AND type='SALE';`);
  expect(sales).toBe(1);
  evidence(info, { orderId: order.id, productId: p.id, total: order.total, status: detail.status, stock: inventory(p.id), saleRows: sales, replaySameOrder: true });
});

test('API online: signed WEB → RESERVED → PAID → CONFIRMED → COMPLETED', async ({}, info) => {
  const p = await fixture();
  const secret = process.env.QA156_CHANNEL_KEY;
  if (!/^[a-f0-9]{64}$/.test(secret ?? '')) throw new Error('Ephemeral QA signing key required');
  sql(`UPDATE sales_channel SET webhook_secret='${secret}' WHERE code='WEB'; INSERT INTO channel_product(channel_id,product_id,external_sku) SELECT id,${Number(p.id)},'${p.sku}' FROM sales_channel WHERE code='WEB';`, true);
  async function signed(path, body, expected) {
    const raw = JSON.stringify(body);
    const headers = path.endsWith('/events') ? eventHeaders({ path: '/api/v1' + path, body: raw, secret })
      : { 'X-Signature': createHmac('sha256', secret).update(raw).digest('hex') };
    return call('POST', path, null, raw, expected, headers);
  }
  const intake = await signed('/integrations/WEB/orders', { externalOrderId: p.sku, orderedAt: new Date().toISOString(),
    customer: { name: p.sku, phone: '0991560014' }, items: [{ externalSku: p.sku, quantity: 3, unitPrice: 31000 }],
    shippingFee: 0, paymentMethod: 'ONLINE' }, 202);
  expect(intake.status).toBe('RESERVED');
  expect(inventory(p.id)).toEqual({ onHand: 20, reserved: 3 });
  const confirmed = await signed(`/integrations/WEB/orders/${p.sku}/events`, { type: 'PAID', occurredAt: new Date().toISOString(), providerRef: p.sku }, 200);
  expect(confirmed.status).toBe('CONFIRMED');
  const paidReplay = await signed(`/integrations/WEB/orders/${p.sku}/events`, { type: 'PAID', occurredAt: new Date().toISOString(), providerRef: p.sku }, 200);
  expect(paidReplay.orderId).toBe(intake.orderId);
  expect(paidReplay.status).toBe('CONFIRMED');
  expect(inventory(p.id)).toEqual({ onHand: 20, reserved: 3 });
  const done = await call('POST', `/orders/${intake.orderId}/complete`, manager);
  expect(done.status).toBe('COMPLETED');
  const detail = await call('GET', `/orders/${intake.orderId}`, manager);
  expect(detail.status).toBe('COMPLETED');
  expect(detail.total).toBe(93000);
  expect(detail.payments[0].status).toBe('PAID');
  expect(inventory(p.id)).toEqual({ onHand: 17, reserved: 0 });
  const repeated = await call('POST', `/orders/${intake.orderId}/complete`, manager, undefined, 409);
  expect(repeated.code).toBe('INVALID_STATUS');
  expect(inventory(p.id)).toEqual({ onHand: 17, reserved: 0 });
  const saleRows = sql(`SELECT to_json(count(*)) FROM inventory_transaction WHERE ref_type='ORDER' AND ref_id=${Number(intake.orderId)} AND type='SALE';`);
  expect(saleRows).toBe(1);
  const events = sql(`SELECT json_build_object('reserved',(SELECT count(*) FROM outbox_event WHERE aggregate_id='${Number(intake.orderId)}' AND event_type='order.reserved'),'completed',(SELECT count(*) FROM outbox_event WHERE aggregate_id='${Number(intake.orderId)}' AND event_type='order.completed'));`);
  expect(events.reserved).toBe(1);
  expect(events.completed).toBe(1);
  evidence(info, { orderId: intake.orderId, productId: p.id, transitions: ['RESERVED', 'CONFIRMED', 'COMPLETED'], total: detail.total, stock: inventory(p.id), events, saleRows, paidReplaySameOrder: true, repeatedCompleteStatus: 409 });
});


async function loginUi(page, username, home) {
  stage = 'UI login '+username;
  await page.goto('/login');
  await page.getByLabel(/^Tên đăng nhập/).fill(username);
  await page.getByLabel(/^Mật khẩu/).fill(password);
  const loggedIn = page.waitForResponse(r => r.url().endsWith('/auth/login') && r.request().method() === 'POST');
  await page.getByRole('button', {name:'Đăng nhập', exact:true}).click();
  const response = await loggedIn;
  expect(response.status()).toBe(200);
  sessions.push(await response.json());
  await page.waitForURL(url => url.pathname === home || url.pathname === '/select-branch');
  if (new URL(page.url()).pathname === '/select-branch') {
    await page.getByRole('button', {name:/^GM-001/}).click();
    await page.getByRole('button', {name:/^Tiếp tục với/}).click();
  }
  await expect(page).toHaveURL(new RegExp(home+'$'));
}
async function logoutUi(page) {
  stage='UI logout control';
  const direct=page.getByRole('button',{name:'Đăng xuất',exact:true});
  const account=page.getByRole('button',{name:/^Tài khoản /});
  // Navigation can finish before React renders either layout's account control.
  await expect.poll(async()=>await direct.isVisible() || await account.isVisible()).toBe(true);
  if (!(await direct.isVisible())) await account.click();
  stage='UI logout request';
  const response=page.waitForResponse(r=>r.url().endsWith('/auth/logout') && r.request().method()==='POST');
  if (await direct.isVisible()) await direct.click();
  else await page.getByRole('menuitem',{name:'Đăng xuất',exact:true}).click();
  expect((await response).status()).toBe(204);
  await expect(page).toHaveURL(/\/login$/);
}
const demoAccounts=[['admin','ADMIN','/admin'],['owner','OWNER','/operations'],['owner.pho','OWNER','/operations']];
for(const [prefix,role,home] of [['quanly','BRANCH_MANAGER','/operations'],['thungan','CASHIER','/pos'],['thukho','STOREKEEPER','/operations/inventory']]) {
  for(let branch=1;branch<=3;branch++) demoAccounts.push([`${prefix}.gm00${branch}`,role,home]);
}
for(const width of [1440,360]) {
  for(const [username,role,home] of demoAccounts) {
    test(`UI login ${username} ${width}px and role boundary`,async({page,browser},info)=>{
      await page.setViewportSize({width,height:900});
      const errors=[];page.on('pageerror',()=>errors.push('PAGE_EXCEPTION'));
      await loginUi(page,username,home);
      if(role==='CASHIER') {
        await expect(page.getByRole('heading',{name:'Bán hàng tại quầy',exact:true})).toBeVisible();
        stage = 'UI inventory route '+username;
        await page.goto('/operations/inventory');
        await expect(page).toHaveURL(/\/403$/);
        await page.goto('/pos');
      } else if(role!=='ADMIN') {
        stage = 'UI inventory route '+username;
        await page.goto('/operations/inventory');
        await expect(page.getByRole('heading',{name:'Tồn kho theo chi nhánh',exact:true})).toBeVisible();
        stage = 'UI forbidden admin route '+username;
        await page.goto('/admin');
        await expect(page).toHaveURL(/\/403$/);
        stage = 'UI returning Operations '+username;
        await page.goto('/operations');
      } else {
        await expect(page.getByRole('heading',{name:'Dashboard',exact:true})).toBeVisible();
      }
      expect(errors).toHaveLength(0);
      evidence(info,{username,role,width,browserVersion:browser.version(),home,realLogin:true});
      await logoutUi(page);
    });
  }
  test(`UI POS scan/cart/quote ${width}px`,async({page},info)=>{
    await page.setViewportSize({width,height:900});
    const p=await fixture();
    await loginUi(page,'thungan.gm001','/pos');
    const search=page.getByLabel(/^Quét mã vạch hoặc tìm theo tên/);
    await search.fill(p.sku);
    const result=page.locator('.pos-sale__products li').filter({hasText:p.sku});
    await expect(result).toHaveCount(1);
    await result.getByRole('button',{name:'Thêm',exact:true}).click();
    const quantity=page.getByRole('spinbutton',{name:`Số lượng ${p.sku}`,exact:true});
    await expect(quantity).toHaveValue('1');
    await page.getByRole('button',{name:`Tăng số lượng ${p.sku}`,exact:true}).click();
    await expect(quantity).toHaveValue('2');
    const checkout=page.getByRole('button',{name:'Thanh toán · F4',exact:true});
    await expect(checkout).toBeEnabled();
    expect(inventory(p.id)).toEqual({onHand:20,reserved:0});
    evidence(info,{productId:p.id,width,scanCartQuote:'PASS',checkout:'NOT_TESTED',dependencies:[75,77]});
    await logoutUi(page);
  });
}
// Pending business flows are not assertions that a development placeholder exists.
// Replace each fixme with its real UI flow after the dependency is integrated and
// verified; merging the screen alone must not make an unavailable-state test fail.
for (const [flow, dependencies] of [
  ['POS payment and invoice', '#75/#77'],
  ['online order lifecycle', '#80/#81'],
  ['stock document create and approve', '#92'],
  ['transfer create, approve and receive', '#96'],
  ['Forecast and replenishment', '#100'],
]) {
  test.fixme(`UI pending business flow: ${flow}`, {
    annotation: { type: 'blocked', description: `Real UI flow awaits QA implementation after ${dependencies}; not accepted as completed E2E.` },
  }, async () => {});
}

for(const width of [1440,360]) {
  test(`UI catalog and exact customer search ${width}px with real API`,async({page},info)=>{
    await page.setViewportSize({width,height:900});
    const product=await fixture();
    const name='QAUI-'+randomUUID().slice(0,8);
    const phone='099'+String(Math.floor(Math.random()*10000000)).padStart(7,'0');
    const customer=await call('POST','/customers',owner,{name,phone},201);
    await loginUi(page,'thungan.gm001','/pos');
    await page.goto('/pos/products');
    await expect(page.getByRole('heading',{name:'Sản phẩm',exact:true})).toBeVisible();
    await page.getByLabel('Tìm sản phẩm',{exact:true}).fill(product.sku);
    await expect(page.getByText(product.sku,{exact:true}).first()).toBeVisible();
    const missing='QA-NOT-FOUND-'+randomUUID();
    const empty=page.waitForResponse(r=>r.request().method()==='GET' && new URL(r.url()).pathname.endsWith('/products') && new URL(r.url()).searchParams.get('q')===missing);
    await page.getByLabel('Tìm sản phẩm',{exact:true}).fill(missing);
    const response=await empty;expect(response.status()).toBe(200);expect((await response.json()).content).toHaveLength(0);
    await expect(page.getByRole('table')).toHaveCount(0);
    await page.goto('/pos/customers');
    await expect(page.getByRole('heading',{name:'Tra cứu khách hàng',exact:true})).toBeVisible();
    await page.getByLabel('Chế độ tìm kiếm',{exact:true}).selectOption('phone');
    await page.getByLabel('Số điện thoại chính xác',{exact:true}).fill(phone);
    await page.getByRole('button',{name:'Tra cứu',exact:true}).click();
    await expect(page.getByText(name,{exact:true})).toBeVisible();
    evidence(info,{width,productId:product.id,customerId:customer.id,productSearch:true,emptyState:true,phoneOnlyCustomerSearch:true});
    await logoutUi(page);
  });
}
