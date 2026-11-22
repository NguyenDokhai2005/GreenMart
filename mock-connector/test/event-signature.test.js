import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createHash, createHmac } from 'node:crypto';
import { eventHeaders } from '../src/event-signature.js';
import { createConnector } from '../src/server.js';

const id = '00000000-0000-0000-0000-000000000416';
test('event signer matches the Java vector and binds path/body/event/time', () => {
  const options = { path: '/api/v1/integrations/WEB/orders/A/events', body: '{}', secret: 'test-only-416',
    eventId: id, timestamp: 1800000000 };
  const h = eventHeaders(options);
  assert.equal(h['X-Signature'], 'b37f5913280c099c1d1b685534b15be206318b91cbe5af1f325ee73438ed9b1d');
  for (const change of [{ path: options.path.replace('/A/', '/B/') }, { body: '{"message":"Trà đậu"}' },
    { eventId: id.replace('416', '417') }, { timestamp: 1800000001 }]) {
    assert.notEqual(eventHeaders({ ...options, ...change })['X-Signature'], h['X-Signature']);
  }
});

test('demo forwards bound events and preserves event id/body across retry', async t => {
  const secret = 'test-only-416';
  const body = JSON.stringify({ type: 'PAID', occurredAt: '2026-10-01T00:00:00Z', providerRef: 'REF-416' });
  let received = 0;
  const backend = http.createServer(async (req, res) => {
    let raw = ''; for await (const part of req) raw += part;
    assert.equal(req.url, '/api/v1/integrations/WEB/orders/A%20B/events');
    assert.equal(raw, body);
    assert.equal(req.headers['x-event-id'], id);
    const time = req.headers['x-event-timestamp'];
    assert.ok(Math.abs(Number(time) - Math.floor(Date.now() / 1000)) < 5);
    const canonical = ['GREENMART-EVENT-V1', req.method, req.url, id, time,
      createHash('sha256').update(raw).digest('hex')].join('\n');
    assert.equal(req.headers['x-signature'], createHmac('sha256', secret).update(canonical).digest('hex'));
    received++;
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"status":"CONFIRMED"}');
  });
  await new Promise(resolve => backend.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { backend.close(resolve); backend.closeAllConnections(); }));
  const app = createConnector({ backendUrl: `http://127.0.0.1:${backend.address().port}`,
    secrets: { WEB: secret, MARKET: 'test-market-416' } });
  await new Promise(resolve => app.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { app.close(resolve); app.closeAllConnections(); }));
  const url = `http://127.0.0.1:${app.address().port}/demo/WEB/orders/A%20B/events`;
  for (let attempt = 0; attempt < 2; attempt++) {
    const response = await fetch(url, { method: 'POST', body,
      headers: { 'Content-Type': 'application/json', 'X-Event-ID': id } });
    assert.equal(response.status, 200); assert.equal(response.headers.get('X-Event-ID'), id);
    assert.deepEqual(await response.json(), { status: 'CONFIRMED' });
  }
  assert.equal(received, 2);
  for (const invalid of [{ type: 'PAID', occurredAt: '2026-10-01T00:00:00Z' }, null]) {
    const response = await fetch(url, { method: 'POST', body: JSON.stringify(invalid) });
    assert.equal(response.status, 400); await response.text();
  }
  assert.equal(received, 2);
});
