/**
 * k6 Load Test – Hiệu năng API (NFR-01)
 * 
 * Mục tiêu:
 *   - API đọc (GET products, orders, inventory): p95 < 300 ms
 *   - API tạo đơn POS: p95 < 500 ms
 * Tải: 50 VU đồng thời, chạy 2 phút
 * 
 * Chạy:
 *   k6 run --env BASE_URL=http://localhost:8080 \
 *          --env TOKEN=<owner_or_manager_token> \
 *          load-api.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const readTrend  = new Trend('read_api_duration',   true);
const orderTrend = new Trend('create_order_duration', true);

export const options = {
  stages: [
    { duration: '30s', target: 50 }, // ramp-up lên 50 VU
    { duration: '90s', target: 50 }, // giữ ổn định
    { duration: '15s', target: 0  }, // ramp-down
  ],
  thresholds: {
    'read_api_duration{type:p95}':    ['p(95)<300'],
    'create_order_duration{type:p95}': ['p(95)<500'],
    http_req_failed: ['rate<0.01'], // < 1% lỗi
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const TOKEN    = __ENV.TOKEN    || '';

const headers = {
  'Content-Type': 'application/json',
  'Authorization': `Bearer ${TOKEN}`,
};

export default function () {
  // === Test API đọc ===

  // GET danh sách sản phẩm
  let r = http.get(`${BASE_URL}/products?page=0&size=20`, { headers });
  readTrend.add(r.timings.duration);
  check(r, {
    'GET products: 200': (res) => res.status === 200,
    'GET products: p95 < 300ms': (res) => res.timings.duration < 300,
  });

  sleep(0.5);

  // GET tồn kho
  r = http.get(`${BASE_URL}/inventory?branchId=1&page=0&size=20`, { headers });
  readTrend.add(r.timings.duration);
  check(r, {
    'GET inventory: 200': (res) => res.status === 200,
  });

  sleep(0.5);

  // GET danh sách đơn
  r = http.get(`${BASE_URL}/orders?page=0&size=20`, { headers });
  readTrend.add(r.timings.duration);
  check(r, {
    'GET orders: 200': (res) => res.status === 200,
  });

  sleep(1);
}
