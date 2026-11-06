import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createConnector, sign, ConnectorConfigurationError } from '../src/server.js';

const secrets = { WEB: 'test-web-only', MARKET: 'test-market-only' };
async function start(server) { server.listen(0, '127.0.0.1'); await once(server, 'listening'); return `http://127.0.0.1:${server.address().port}`; }
function close(t, server) { t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); })); }
const order = { externalOrderId: 'WEB-1', orderedAt: '2026-11-04T10:00:00+07:00', customer: { name: 'Khách', phone: '0912345678' },
  items: [{ externalSku: 'W-SUA-TH-1L', quantity: 3, unitPrice: 29000 }], paymentMethod: 'ONLINE' };

test('WEB and MARKET forward exact signed bytes and preserve 202/200/409', async t => {
  let received = 0;
  const backend = http.createServer(async (req, res) => {
    let raw = ''; for await (const chunk of req) raw += chunk;
    const channel = req.url.includes('/WEB/') ? 'WEB' : 'MARKET';
    assert.equal(req.headers['x-signature'], sign(raw, secrets[channel]));
    assert.equal(req.headers['x-correlation-id'], 'test-128');
    assert.deepEqual(JSON.parse(raw), order);
    const status = [202, 200, 409][received++ % 3];
    res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ status }));
  });
  const backendUrl = await start(backend); close(t, backend);
  const app = createConnector({ backendUrl, secrets }); const base = await start(app); close(t, app);
  for (const channel of ['WEB', 'MARKET']) for (const status of [202, 200, 409]) {
    const response = await fetch(`${base}/demo/${channel}/orders`, { method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Correlation-ID': 'test-128' }, body: JSON.stringify(order) });
    assert.equal(response.status, status); assert.deepEqual(await response.json(), { status });
  }
});

test('signed stock callbacks ignore old/equal versions and persist across restart', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'greenmart-mock-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const options = { backendUrl: 'http://127.0.0.1:1', secrets, stateFile: join(directory, 'state.json') };
  const app = createConnector(options); const base = await start(app); close(t, app);
  for (const [version, available] of [[5, 41], [3, 99], [5, 100]]) {
    const body = JSON.stringify({ items: [{ externalSku: 'W-SUA', available, version }] });
    const response = await fetch(`${base}/mock/WEB/stock`, { method: 'PUT', body, headers: { 'X-Signature': 'sha256=' + sign(body, secrets.WEB) } });
    assert.equal(response.status, 204);
  }
  const restarted = createConnector(options); const newBase = await start(restarted); close(t, restarted);
  const state = await (await fetch(`${newBase}/mock/state`)).json();
  assert.deepEqual(state.WEB.stock['W-SUA'], { externalSku: 'W-SUA', available: 41, version: 5 });
  assert.deepEqual(state.MARKET.stock, {});
});

test('invalid signature, tampered body and wrong channel cannot change stock', async t => {
  const app = createConnector({ backendUrl: 'http://127.0.0.1:1', secrets }); const base = await start(app); close(t, app);
  const body = JSON.stringify({ items: [{ externalSku: 'W-SUA', available: 1, version: 1 }] });
  for (const signature of ['', sign(body, secrets.MARKET), sign(body + ' ', secrets.WEB), 'invalid']) {
    assert.equal((await fetch(`${base}/mock/WEB/stock`, { method: 'PUT', body, headers: { 'X-Signature': signature } })).status, 401);
  }
  assert.deepEqual((await (await fetch(`${base}/mock/state`)).json()).WEB.stock, {});
  assert.equal((await fetch(`${base}/mock/OTHER/stock`, { method: 'PUT', body })).status, 404);
});

test('invalid batch is atomic; signed status callback is visible and stale status ignored', async t => {
  const app = createConnector({ backendUrl: 'http://127.0.0.1:1', secrets }); const base = await start(app); close(t, app);
  async function put(path, data) { const body = JSON.stringify(data); return fetch(base + path, { method: 'PUT', body,
    headers: { 'X-Signature': sign(body, secrets.WEB) } }); }
  assert.equal((await put('/mock/WEB/stock', { items: [{ externalSku: 'A', available: 1, version: 1 }, { externalSku: 'B', available: -1, version: 1 }] })).status, 400);
  assert.deepEqual((await (await fetch(`${base}/mock/state`)).json()).WEB.stock, {});
  for (const [status, occurredAt] of [['COMPLETED', '2026-11-04T12:00:00Z'], ['CONFIRMED', '2026-11-04T11:00:00Z']]) {
    assert.equal((await put('/mock/WEB/orders/WEB-1/status', { status, occurredAt })).status, 204);
  }
  assert.equal((await (await fetch(`${base}/mock/state`)).json()).WEB.orders['WEB-1'].statusUpdate.status, 'COMPLETED');
});

test('validation rejects bad orders; backend outage returns 502 without losing user order id', async t => {
  const app = createConnector({ backendUrl: 'http://127.0.0.1:1', secrets }); const base = await start(app); close(t, app);
  assert.equal((await fetch(`${base}/demo/WEB/orders`, { method: 'POST', body: '{}' })).status, 400);
  const response = await fetch(`${base}/demo/WEB/orders`, { method: 'POST', body: JSON.stringify(order) });
  assert.equal(response.status, 502); assert.equal((await response.json()).code, 'BACKEND_UNAVAILABLE');
  assert.equal((await fetch(`${base}/healthz`)).status, 200);
});

test('phone validation accepts 10 and 11 digits per OpenAPI and rejects other lengths', async t => {
  const forwarded = [];
  const app = createConnector({ backendUrl: 'http://127.0.0.1:1', secrets,
    fetchImpl: async (_url, options) => {
      forwarded.push(JSON.parse(options.body).customer.phone);
      return new Response(JSON.stringify({ status: 'RESERVED' }), { status: 202 });
    } });
  const base = await start(app); close(t, app);
  for (const [phone, status] of [['0912345678', 202], ['01234567890', 202],
    ['012345678', 400], ['012345678901', 400], ['11234567890', 400]]) {
    const response = await fetch(`${base}/demo/WEB/orders`, { method: 'POST',
      body: JSON.stringify({ ...order, customer: { ...order.customer, phone } }) });
    assert.equal(response.status, status, phone);
  }
  assert.deepEqual(forwarded, ['0912345678', '01234567890']);
});

test('configuration errors name every missing/default channel secret without exposing values', () => {
  for (const [configured, missing] of [[{}, ['WEB', 'MARKET']],
    [{ ...secrets, WEB: 'change-me' }, ['WEB']],
    [{ ...secrets, MARKET: 'change-me' }, ['MARKET']],
    [{ ...secrets, MARKET: '   ' }, ['MARKET']]]) {
    assert.throws(() => createConnector({ backendUrl: 'http://localhost:8080', secrets: configured }), error => {
      assert.ok(error instanceof ConnectorConfigurationError);
      for (const channel of missing) assert.ok(error.message.includes(`CHANNEL_${channel}_SECRET`));
      assert.ok(error.message.includes('openssl rand -hex 32'));
      assert.ok(!error.message.includes(secrets.WEB));
      assert.ok(!error.message.includes(secrets.MARKET));
      return true;
    });
  }
});

test('CLI exits with a clear configuration message when example secrets are unchanged', () => {
  const result = spawnSync(process.execPath, [fileURLToPath(new URL('../src/server.js', import.meta.url))],
    { env: { ...process.env, CHANNEL_WEB_SECRET: 'change-me', CHANNEL_MARKET_SECRET: 'change-me' },
      encoding: 'utf8', timeout: 5000 });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Mock connector không thể khởi động/);
  assert.match(result.stderr, /CHANNEL_WEB_SECRET/);
  assert.match(result.stderr, /CHANNEL_MARKET_SECRET/);
  assert.match(result.stderr, /openssl rand -hex 32/);
  assert.equal(result.stdout, '');
});
