import { test, expect } from '@playwright/test';
import { createHmac, randomUUID } from 'node:crypto';
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
test.afterEach(async ({}, info) => {
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
    return call('POST', path, null, raw, expected, { 'X-Signature': createHmac('sha256', secret).update(raw).digest('hex') });
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

async function browserLogin(page) {
  stage = 'Browser login page';
  await page.goto('/login');
  stage = 'Browser login form';
  await page.getByLabel(/^Tên đăng nhập/).fill('thungan.gm001');
  await page.getByLabel(/^Mật khẩu/).fill(password);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  stage = 'Browser redirect to POS';
  await page.waitForURL('**/pos');
}
async function browserLogout(page) {
  stage = 'Browser logout';
  const response = page.waitForResponse(r => r.url().endsWith('/auth/logout') && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Đăng xuất', exact: true }).click();
  expect((await response).status()).toBe(204);
  await page.waitForURL('**/login');
}
test('Browser smoke: real login → POS shell → logout', async ({ page, browser }, info) => {
  await browserLogin(page);
  await expect(page.getByRole('heading', { name: 'Bán hàng tại quầy', exact: true })).toBeVisible();
  await expect(page.getByText('Màn hình đang được chuẩn bị', { exact: true })).toBeVisible();
  evidence(info, { route: '/pos', browserVersion: browser.version(), observed: 'Current business screen is a placeholder; smoke only' });
  await browserLogout(page);
});
test('Browser POS business checkout', async ({ page }, info) => {
  await browserLogin(page);
  const placeholder = page.getByText('Màn hình đang được chuẩn bị', { exact: true });
  await expect(placeholder).toBeVisible();
  info.annotations.push({ type: 'blocked', description: 'POS cart/payment UI not implemented; dependencies #73/#75, SV04; no API substitution for UI checkout' });
  await browserLogout(page);
  test.skip(true, 'BLOCKED: business POS screen is a placeholder');
});
test('Browser online order through completion', async ({ page }, info) => {
  await page.goto('/login');
  await page.getByLabel(/^Tên đăng nhập/).fill('quanly.gm001');
  await page.getByLabel(/^Mật khẩu/).fill(password);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await page.waitForURL('**/operations');
  await page.getByRole('link', { name: 'Đơn hàng', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Chức năng đang phát triển', exact: true })).toBeVisible();
  evidence(info, { route: '/operations/orders', observed: 'Business order screen is a development placeholder' });
  info.annotations.push({ type: 'blocked', description: 'Online list/detail UI #80/#81 awaits implementation/design #176 (SV04). API lifecycle test is separate.' });
  await page.getByRole('button', { name: /^Tài khoản / }).click();
  const loggedOut = page.waitForResponse(r => r.url().endsWith('/auth/logout') && r.request().method() === 'POST');
  await page.getByRole('menuitem', { name: 'Đăng xuất', exact: true }).click();
  expect((await loggedOut).status()).toBe(204);
  await page.waitForURL('**/login');
  test.skip(true, 'BLOCKED: online order UI is not implemented at this source revision');
});
