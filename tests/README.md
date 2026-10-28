# Kiểm thử liên hệ thống

Phụ trách: SV08 – QA/Security (Huỳnh Phúc Đạt)

## Cấu trúc

| Thư mục | Công cụ | Nội dung |
|---|---|---|
| `api` | Postman + Newman | Bộ test API theo luồng nghiệp vụ, chạy trong CI |
| `load` | k6 | Bán đồng thời 100 yêu cầu (AC-02), tải 50 người dùng (NFR-01) |
| `e2e` | Playwright | Luồng bán tại quầy và đơn online trên giao diện |

## Cách chạy

### Postman / Newman (API test)

```bash
# Cài Newman
npm install -g newman

# Chạy toàn bộ collection
newman run tests/api/greenmart.postman_collection.json \
  -e tests/api/environment.json \
  --reporters cli,json \
  --reporter-json-export tests/api/newman-report.json
```

### k6 (Load test)

```bash
# Cài k6: https://grafana.com/docs/k6/latest/set-up/install-k6/

# Test tồn kho đồng thời (AC-02) – chạy sau khi seed dữ liệu
k6 run --env BASE_URL=http://localhost:8080/api/v1 \
       --env TOKEN=<cashier_token> \
       --env PRODUCT_ID=<id_san_pham_con_5> \
       --env BRANCH_ID=1 \
       tests/load/concurrency-stock.js

# Load test hiệu năng API (NFR-01)
k6 run --env BASE_URL=http://localhost:8080/api/v1 \
       --env TOKEN=<owner_token> \
       tests/load/load-api.js
```

### Playwright (E2E)

```bash
# Cài Playwright
npm install
npx playwright install chromium

# Chạy E2E
cd tests/e2e
npx playwright test

# Xem báo cáo HTML
npx playwright show-report ../../docs/testing/playwright-report
```

## Tài liệu liên quan

- Kế hoạch kiểm thử: `docs/testing/test-plan.md`
- Checklist bảo mật: `docs/testing/security-checklist.md`
- Test cases đầy đủ: xem mục 4 trong `docs/testing/test-plan.md`
