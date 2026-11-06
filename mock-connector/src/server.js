import http from 'node:http';
import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync, renameSync, existsSync } from 'node:fs';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const channels = ['WEB', 'MARKET'];
const statuses = ['NEW', 'RESERVED', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'EXPIRED', 'REJECTED', 'RETURNED'];
const integer = (n, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(n) && n >= 0 && n <= max;
const text = (s, max) => typeof s === 'string' && s.trim().length > 0 && s.length <= max;
export const sign = (body, secret) => createHmac('sha256', secret).update(body).digest('hex');
export function validSignature(body, secret, supplied = '') {
  const hex = supplied.replace(/^sha256=/, '');
  return /^[a-fA-F0-9]{64}$/.test(hex)
    && timingSafeEqual(Buffer.from(hex, 'hex'), Buffer.from(sign(body, secret), 'hex'));
}
function fail(status, message) { const error = new Error(message); error.status = status; throw error; }
function requireValid(condition, message = 'Dữ liệu không hợp lệ') { if (!condition) fail(400, message); }
function validTime(value) { return typeof value === 'string' && /(Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value)); }
function validateOrder(order) {
  requireValid(text(order?.externalOrderId, 60) && validTime(order.orderedAt));
  requireValid(text(order.customer?.name, 120) && /^0\d{9,10}$/.test(order.customer?.phone));
  requireValid(['ONLINE', 'COD'].includes(order.paymentMethod));
  requireValid(Array.isArray(order.items) && order.items.length >= 1 && order.items.length <= 100);
  for (const item of order.items) {
    requireValid(text(item?.externalSku, 60) && integer(item.quantity, 9999) && item.quantity > 0 && integer(item.unitPrice));
  }
  requireValid(order.shippingFee === undefined || integer(order.shippingFee));
}
async function readBody(request) {
  let length = 0; const chunks = [];
  for await (const chunk of request) {
    length += chunk.length;
    if (length > 1024 * 1024) fail(413, 'Body quá lớn');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

export class ConnectorConfigurationError extends Error {}

export function createConnector({ backendUrl, secrets, stateFile, fetchImpl = fetch }) {
  const missing = channels.filter(channel => !secrets?.[channel]?.trim() || secrets[channel] === 'change-me');
  if (missing.length) {
    throw new ConnectorConfigurationError(`Chưa cấu hình ${missing.map(channel => `CHANNEL_${channel}_SECRET`).join(', ')}. `
      + 'Thay giá trị change-me trong .env bằng secret riêng cho từng kênh trước khi chạy '
      + '(sinh từng secret bằng openssl rand -hex 32).');
  }
  const base = new URL(backendUrl);
  if (!['http:', 'https:'].includes(base.protocol)) throw new Error('Invalid backend URL');
  let state = Object.fromEntries(channels.map(channel => [channel, { stock: {}, orders: {} }]));
  if (stateFile && existsSync(stateFile)) state = JSON.parse(readFileSync(stateFile, 'utf8'));
  function save(next) {
    if (stateFile) {
      mkdirSync(dirname(stateFile), { recursive: true });
      writeFileSync(`${stateFile}.tmp`, JSON.stringify(next), { mode: 0o600 });
      renameSync(`${stateFile}.tmp`, stateFile);
    }
    state = next;
  }
  function respond(response, status, body, traceId) {
    response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'X-Correlation-ID': traceId,
      'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
    response.end(status === 204 ? undefined : JSON.stringify(body));
  }
  return http.createServer(async (request, response) => {
    const incomingTrace = request.headers['x-correlation-id'];
    const traceId = typeof incomingTrace === 'string' && /^[a-zA-Z0-9_-]{1,64}$/.test(incomingTrace) ? incomingTrace : randomUUID();
    try {
      const path = new URL(request.url, 'http://localhost').pathname;
      if (request.method === 'GET' && path === '/healthz') return respond(response, 200, { status: 'UP' }, traceId);
      if (request.method === 'GET' && path === '/') {
        response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'X-Content-Type-Options': 'nosniff' });
        return response.end(readFileSync(new URL('../public/index.html', import.meta.url)));
      }
      if (request.method === 'GET' && path === '/mock/state') return respond(response, 200, state, traceId);
      const demo = /^\/demo\/(WEB|MARKET)\/orders$/.exec(path);
      if (request.method === 'POST' && demo) {
        const channel = demo[1]; const raw = await readBody(request);
        let order; try { order = JSON.parse(raw); } catch { fail(400, 'JSON không hợp lệ'); }
        validateOrder(order);
        let upstream;
        try {
          upstream = await fetchImpl(new URL(`/api/v1/integrations/${channel}/orders`, base), {
            method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Signature': sign(raw, secrets[channel]),
              'X-Correlation-ID': traceId }, body: raw, signal: AbortSignal.timeout(10000), redirect: 'error'
          });
        } catch { fail(502, 'Không kết nối được backend; có thể gửi lại cùng externalOrderId'); }
        let result; try { result = await upstream.json(); } catch { fail(502, 'Backend trả dữ liệu không hợp lệ'); }
        const next = structuredClone(state);
        const old = Object.hasOwn(next[channel].orders, order.externalOrderId) ? next[channel].orders[order.externalOrderId] : {};
        next[channel].orders = { ...next[channel].orders,
          [order.externalOrderId]: { ...old, request: order, httpStatus: upstream.status, response: result } };
        save(next);
        return respond(response, upstream.status, result, traceId);
      }
      const callback = /^\/mock\/(WEB|MARKET)\/(stock|orders\/([^/]+)\/status)$/.exec(path);
      if (request.method === 'PUT' && callback) {
        const channel = callback[1]; const raw = await readBody(request);
        if (!validSignature(raw, secrets[channel], request.headers['x-signature'])) fail(401, 'Chữ ký không hợp lệ');
        let data; try { data = JSON.parse(raw); } catch { fail(400, 'JSON không hợp lệ'); }
        const next = structuredClone(state);
        if (callback[2] === 'stock') {
          requireValid(Array.isArray(data?.items));
          for (const item of data.items) requireValid(text(item?.externalSku, 60) && integer(item.available) && integer(item.version));
          for (const item of data.items) {
            const old = Object.hasOwn(next[channel].stock, item.externalSku) ? next[channel].stock[item.externalSku] : null;
            if (!old || item.version > old.version) next[channel].stock = { ...next[channel].stock, [item.externalSku]: item };
          }
        } else {
          requireValid(statuses.includes(data?.status) && validTime(data.occurredAt));
          requireValid(data.reason == null || typeof data.reason === 'string' && data.reason.length <= 255);
          const id = decodeURIComponent(callback[3]); requireValid(text(id, 60));
          const old = Object.hasOwn(next[channel].orders, id) ? next[channel].orders[id] : {};
          if (!old.statusUpdate || Date.parse(data.occurredAt) >= Date.parse(old.statusUpdate.occurredAt)) {
            next[channel].orders = { ...next[channel].orders, [id]: { ...old, statusUpdate: data } };
          }
        }
        save(next);
        return respond(response, 204, null, traceId);
      }
      fail(404, 'Không tìm thấy đường dẫn');
    } catch (error) {
      const status = error.status || 500;
      respond(response, status, { code: status === 401 ? 'INVALID_SIGNATURE' : status === 400 ? 'VALIDATION_ERROR'
        : status === 404 ? 'NOT_FOUND' : status === 502 ? 'BACKEND_UNAVAILABLE' : 'INTERNAL_SERVER_ERROR',
        message: status === 500 ? 'Lỗi hệ thống' : error.message, traceId }, traceId);
    }
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  try {
    const server = createConnector({ backendUrl: process.env.BACKEND_URL || 'http://backend:8080',
      secrets: { WEB: process.env.CHANNEL_WEB_SECRET, MARKET: process.env.CHANNEL_MARKET_SECRET },
      stateFile: process.env.STATE_FILE || '/data/state.json' });
    server.listen(Number(process.env.PORT || 8090), '0.0.0.0');
  } catch (error) {
    console.error('Mock connector không thể khởi động: ' + (error instanceof ConnectorConfigurationError
      ? error.message : 'Kiểm tra BACKEND_URL, PORT và STATE_FILE.'));
    process.exitCode = 1;
  }
}
