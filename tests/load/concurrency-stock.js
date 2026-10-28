/**
 * k6 Load Test – Tồn kho đồng thời (AC-02, TC-CONC-01)
 * 
 * Mục tiêu: 100 yêu cầu song song vào sản phẩm có khả dụng = 5
 *   → Đúng 5 thành công, 95 nhận HTTP 409 INSUFFICIENT_STOCK
 *   → Khả dụng sau test = 0, không bao giờ âm
 * 
 * Chạy:
 *   k6 run --env BASE_URL=http://localhost:8080 \
 *          --env TOKEN=<cashier_token> \
 *          --env PRODUCT_ID=<id> \
 *          --env BRANCH_ID=1 \
 *          concurrency-stock.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const successCount = new Counter('orders_success');
const failCount    = new Counter('orders_fail_409');
const unexpectedCount = new Counter('orders_unexpected');

export const options = {
  // 100 VU khởi động cùng lúc, mỗi VU gửi 1 request
  vus: 100,
  iterations: 100,
  // Không có duration; test kết thúc khi đủ 100 iterations
};

const BASE_URL   = __ENV.BASE_URL   || 'http://localhost:8080/api/v1';
const TOKEN      = __ENV.TOKEN      || '';
const PRODUCT_ID = __ENV.PRODUCT_ID || '1';
const BRANCH_ID  = __ENV.BRANCH_ID  || '1';

export function setup() {
  // Kiểm tra tồn trước khi test
  const res = http.get(
    `${BASE_URL}/inventory?branchId=${BRANCH_ID}&productId=${PRODUCT_ID}`,
    { headers: { Authorization: `Bearer ${TOKEN}` } }
  );
  const body = JSON.parse(res.body);
  console.log(`[SETUP] Tồn khả dụng trước test: ${body.available}`);
  return { initialAvailable: body.available };
}

export default function () {
  const idempotencyKey = `concurrency-test-vu-${__VU}-iter-${__ITER}`;

  const payload = JSON.stringify({
    branchId: parseInt(BRANCH_ID),
    items: [{ productId: parseInt(PRODUCT_ID), quantity: 1 }],
    payment: { method: 'CASH', amountTendered: 999999 },
  });

  const params = {
    headers: {
      'Content-Type':    'application/json',
      'Authorization':   `Bearer ${TOKEN}`,
      'Idempotency-Key': idempotencyKey,
    },
  };

  const res = http.post(`${BASE_URL}/orders/pos`, payload, params);

  if (res.status === 200 || res.status === 201) {
    successCount.add(1);
    check(res, {
      'Đơn thành công có ID': (r) => {
        const b = JSON.parse(r.body);
        return b.id !== undefined || b.orderId !== undefined;
      },
    });
  } else if (res.status === 409) {
    failCount.add(1);
    check(res, {
      '409 có code INSUFFICIENT_STOCK': (r) => {
        try {
          const b = JSON.parse(r.body);
          return b.code === 'INSUFFICIENT_STOCK';
        } catch { return false; }
      },
    });
  } else {
    unexpectedCount.add(1);
    console.error(`[VU ${__VU}] Unexpected status: ${res.status} – ${res.body}`);
  }
}

export function teardown(data) {
  // Kiểm tra tồn sau test
  const res = http.get(
    `${BASE_URL}/inventory?branchId=${BRANCH_ID}&productId=${PRODUCT_ID}`,
    { headers: { Authorization: `Bearer ${TOKEN}` } }
  );
  const body = JSON.parse(res.body);
  const finalAvailable = body.available;
  const finalOnHand    = body.onHand;

  console.log(`[TEARDOWN] Tồn khả dụng sau test: ${finalAvailable}`);
  console.log(`[TEARDOWN] Tồn thực sau test:     ${finalOnHand}`);

  check(body, {
    'AC-02: Tồn khả dụng KHÔNG ÂM': (b) => b.available >= 0,
    'AC-02: Tồn thực KHÔNG ÂM':     (b) => b.onHand    >= 0,
  });

  if (finalAvailable < 0) {
    console.error(`[FAIL] TỒN ÂM: available = ${finalAvailable}`);
  }
}
