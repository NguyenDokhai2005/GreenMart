# Load Tests – k6

Phụ trách: SV08 – QA/Security

## Cài đặt k6

Windows (winget):
```bash
winget install k6 --source winget
```

Hoặc tải trực tiếp: https://grafana.com/docs/k6/latest/set-up/install-k6/

## Scripts

### `concurrency-stock.js` — Kiểm chứng AC-02 (tồn kho đồng thời)

**Mục tiêu:** 100 yêu cầu song song vào sản phẩm có khả dụng = 5  
→ Đúng 5 thành công, 95 nhận 409, khả dụng = 0, không bao giờ âm

**Chuẩn bị trước khi chạy:**
1. Backend đang chạy và có dữ liệu (seed script đã chạy)
2. Tìm một sản phẩm có `available = 5` tại chi nhánh 1
3. Lấy token của cashier CN1 (đăng nhập qua API rồi copy accessToken)

```bash
k6 run --env BASE_URL=http://localhost:8080/api/v1 \
       --env TOKEN=eyJ... \
       --env PRODUCT_ID=123 \
       --env BRANCH_ID=1 \
       tests/load/concurrency-stock.js
```

**Kết quả mong đợi trong output:**
```
orders_success.....: 5
orders_fail_409....: 95
AC-02: Tồn khả dụng KHÔNG ÂM: ✓
AC-02: Tồn thực KHÔNG ÂM:     ✓
```

---

### `load-api.js` — Kiểm tra hiệu năng NFR-01

**Mục tiêu:** 50 VU đồng thời trong 2 phút  
→ API đọc p95 < 300ms, API tạo đơn p95 < 500ms

```bash
k6 run --env BASE_URL=http://localhost:8080/api/v1 \
       --env TOKEN=eyJ... \
       tests/load/load-api.js
```

## Khi nào chạy

| Script | Khi nào | Tuần |
|---|---|---|
| `concurrency-stock.js` | Sau khi INV-07 implement xong (SV03) | 2–3 |
| `load-api.js` | Sau khi có đủ API đọc (SV02, SV03) | 3 |
| Cả hai | Trước demo cuối kỳ | 4 |
