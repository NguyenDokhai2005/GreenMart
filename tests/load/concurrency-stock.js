// #150 / AC-02: one synchronized request per VU, never retries or resets stock.
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import crypto from 'k6/crypto';
import { Counter, Gauge, Trend } from 'k6/metrics';

const base = __ENV.BASE_URL || 'http://backend:8080/api/v1';
const attempts = new Counter('pos_attempts');
const created = new Counter('pos_created');
const rejected = new Counter('pos_insufficient_stock');
const unexpected = new Counter('pos_unexpected');
const finalStock = new Gauge('final_available');
const launchOffset = new Trend('launch_offset_ms');
export const options = {
  scenarios: { stock_race: { executor: 'per-vu-iterations', vus: 100, iterations: 1, maxDuration: '60s' } },
  setupTimeout: '30s', teardownTimeout: '30s',
  summaryTrendStats: ['min', 'med', 'max', 'p(95)'],
  thresholds: {
    pos_attempts: ['count==100'], pos_created: ['count==5'],
    pos_insufficient_stock: ['count==95'], pos_unexpected: ['count==0'],
    iterations: ['count==100'], checks: ['rate==1'], final_available: ['value==0'],
    launch_offset_ms: ['max<1000'],
  },
};
function json(response) { try { return response.json(); } catch (_) { return null; } }
function params(token) { return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, timeout: '30s', redirects: 0 }; }
function inventory(token) { return http.get(`${base}/inventory?branchId=1&productId=900150`, params(token)); }
function logout(data) { return http.post(`${base}/auth/logout`, JSON.stringify({ refreshToken: data.refresh }), params(data.token)); }
function uuid() {
  const a = new Uint8Array(crypto.randomBytes(16)); a[6] = (a[6] & 15) | 64; a[8] = (a[8] & 63) | 128;
  const s = Array.from(a, b => b.toString(16).padStart(2, '0')).join('');
  return `${s.slice(0,8)}-${s.slice(8,12)}-${s.slice(12,16)}-${s.slice(16,20)}-${s.slice(20)}`;
}
export function setup() {
  [attempts, created, rejected, unexpected].forEach(m => m.add(0));
  const response = http.post(`${base}/auth/login`, JSON.stringify({ username: 'thungan.gm001', password: 'Demo@123' }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '10s', redirects: 0 });
  const session = json(response);
  if (response.status !== 200 || !session?.accessToken || !session?.refreshToken) fail('QA login failed; no credentials exported');
  const data = { token: session.accessToken, refresh: session.refreshToken };
  const stock = inventory(data.token); const row = json(stock)?.content?.[0];
  if (stock.status !== 200 || row?.productId !== 900150 || row?.onHand !== 5 || row?.reserved !== 0 || row?.available !== 5) {
    logout(data); fail('Fixture must start at onHand=5, reserved=0, available=5; refusing to reset');
  }
  return { ...data, startAt: Date.now() + 5000 };
}
export default function (data) {
  sleep(Math.max(0, (data.startAt - Date.now()) / 1000));
  launchOffset.add(Math.max(0, Date.now() - data.startAt));
  const p = params(data.token); p.headers['Idempotency-Key'] = uuid();
  attempts.add(1);
  const response = http.post(`${base}/orders/pos`, JSON.stringify({ branchId: 1,
    items: [{ productId: 900150, quantity: 1 }], expectedTotal: 10000,
    payment: { method: 'CASH', cashReceived: 10000 } }), p);
  const body = json(response);
  const success = response.status === 201 && body?.status === 'COMPLETED' && body?.total === 10000
    && body?.items?.length === 1 && body.items[0].productId === 900150 && body.items[0].quantity === 1
    && body?.payments?.[0]?.status === 'PAID' && body.payments[0].amount === 10000;
  const insufficient = response.status === 409 && body?.code === 'INSUFFICIENT_STOCK';
  if (success) created.add(1); else if (insufficient) rejected.add(1); else unexpected.add(1);
  check(response, { '201 valid sale or 409 INSUFFICIENT_STOCK': () => success || insufficient });
}
export function teardown(data) {
  try {
    const response = inventory(data.token); const row = json(response)?.content?.[0];
    finalStock.add(row?.available ?? -1);
    check(response, { 'final inventory is zero, never reserved or negative': () => response.status === 200
      && row?.productId === 900150 && row?.onHand === 0 && row?.reserved === 0 && row?.available === 0 });
  } finally { check(logout(data), { 'test session logged out': r => r.status === 204 }); }
}
export function handleSummary(data) {
  // Never export setup return values, tokens, request/response bodies or __ENV.
  const count = name => data.metrics[name]?.values?.count ?? 0;
  const report = { issue: 150, sourceCommit: __ENV.SOURCE_COMMIT || 'unknown',
    scenario: { executor: 'per-vu-iterations', vus: 100, iterationsPerVu: 1, synchronizedStart: true },
    attempts: count('pos_attempts'), created: count('pos_created'), insufficientStock: count('pos_insufficient_stock'),
    unexpected: count('pos_unexpected'), iterations: count('iterations'),
    finalAvailable: data.metrics.final_available?.values?.value ?? null,
    checksRate: data.metrics.checks?.values?.rate ?? 0,
    launchOffsetMaxMs: data.metrics.launch_offset_ms?.values?.max ?? null,
    posDurationMs: data.metrics.http_req_duration?.values || {},
    thresholds: Object.fromEntries(Object.entries(data.metrics).filter(([,m]) => m.thresholds)
      .map(([name,m]) => [name, Object.fromEntries(Object.entries(m.thresholds).map(([t,v]) => [t,v.ok]))])) };
  return { [__ENV.SUMMARY_PATH || '/results/k6-summary.json']: JSON.stringify(report, null, 2) + '\n',
    stdout: JSON.stringify({ attempts: report.attempts, created: report.created, insufficientStock: report.insufficientStock,
      unexpected: report.unexpected, finalAvailable: report.finalAvailable }) + '\n' };
}
