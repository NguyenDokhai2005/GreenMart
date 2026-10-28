# API Tests – Postman / Newman

Phụ trách: SV08 – QA/Security

## Cài đặt một lần (máy local)

```bash
# Cài Newman và reporter
npm install -g newman newman-reporter-htmlextra
```

## Cấu hình môi trường

1. Copy file mẫu:
```bash
copy tests\api\environment.json tests\api\environment.local.json
```

2. Sửa `environment.local.json`:
   - `baseUrl` → URL backend đang chạy (mặc định `http://localhost:8080/api/v1`)
   - `WEB_SECRET` → lấy từ `.env` (giá trị `CHANNEL_WEB_SECRET`)

> `environment.local.json` đã có trong `.gitignore` (không commit lên repo)

## Chạy tests

```bash
# Chạy toàn bộ collection
newman run tests/api/greenmart.postman_collection.json \
  -e tests/api/environment.local.json \
  --reporters cli,htmlextra \
  --reporter-htmlextra-export docs/testing/newman-report.html

# Chỉ chạy một folder (ví dụ AUTH)
newman run tests/api/greenmart.postman_collection.json \
  -e tests/api/environment.local.json \
  --folder "01 – AUTH: Xác thực và phân quyền"
```

## Cấu trúc collection

```
greenmart.postman_collection.json
├── 00 – Setup: Login tất cả vai trò      ← chạy đầu tiên để lấy token
├── 01 – AUTH: Xác thực và phân quyền     ← TC-AUTH-01..10
├── 02 – POS: Bán tại quầy                ← TC-POS-01..08  
├── 03 – Online: Đơn từ kênh              ← TC-ONL-01..07
└── 04 – Security: OWASP cơ bản           ← S-01..10
```

## Thêm test case mới

Mở Postman → Import `greenmart.postman_collection.json` → thêm request → Export lại đè file cũ.

Quy ước đặt tên request: `TC-XXX-YY – Mô tả ngắn`
