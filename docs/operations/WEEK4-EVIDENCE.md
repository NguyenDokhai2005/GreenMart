# Bằng chứng tuần 4 — SV07

Ngày kiểm tra 18/11/2026. Nhánh codex/week4-sv07. Kết quả local được phân biệt với review, GitHub CI và merge.

Kết quả cuối trên Boot 4.0.9: **547 test backend đạt, 0 lỗi, 0 bỏ qua, coverage gate đạt**. Frontend **294 test đạt**. Bốn runtime image **0 Critical**, nội dung layer của image demo khớp các báo cáo scan. Bản tóm tắt máy đọc được ở evidence/week4-20261118.json.

## Lệnh kiểm tra backend

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.11'
.\.local-tools\apache-maven-3.9.9\bin\mvn.cmd -B -ntp -f backend/pom.xml verify
```

Trên máy có Maven trong PATH dùng `mvn -B -ntp -f backend/pom.xml verify`. Docker phải chạy để Testcontainers tạo PostgreSQL, Redis và RabbitMQ riêng.

## Frontend

```powershell
npm --prefix frontend ci
npm --prefix frontend run generate:api-types
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend test -- --run
npm --prefix frontend run build
```

Lần chạy local đạt 32 file test, 294 bài test; typecheck/lint/format/build đạt. OpenAPI schema đã sinh lại cho API sync log.

## Quyền DB mới (#329)

ForecastRoleInitTest dựng PostgreSQL mới, chạy init và Flyway, dùng đúng role forecast kiểm tra SELECT password_hash/app_user, webhook_secret/sales_channel và audit_log. Mong đợi SQLSTATE 42501 (permission denied). Không dùng init để thay quyền trên volume cũ.

## API sync log (#139)

SyncLogApiTest kiểm tra lọc WEB/FAILED, phân trang hợp lệ, callback FAILED chuyển RETRYING và có lịch gửi; lặp retry trả 409; ID không có trả 404; bộ lọc sai trả 400. Admin được gửi lại, Owner chỉ xem, Cashier bị 403, chưa đăng nhập bị 401.

```bash
curl -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN" 'http://localhost:28080/api/v1/integrations/sync-logs?channelCode=WEB&status=FAILED&page=0&size=20'
curl -X POST -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN" http://localhost:28080/api/v1/integrations/sync-logs/<failed-id>/retry
```

Đặt access token trong môi trường riêng. Chỉ thử với callback FAILED của fixture demo.

## Forecast authentication (#344)

ForecastClientTest dùng HTTP server thật trong test để xác nhận Authorization của cả POST /forecast/run và GET /forecast/runs/{id}, thiếu token fail-closed, upstream 401 chuyển thành 503 không chứa body/token nội bộ. ForecastRunApiTest kiểm tra phân quyền, ánh xạ runId nội bộ thành id của OpenAPI và audit FORECAST_RUN.

Smoke trên stack demo riêng: đăng nhập Admin thành công; GET sync log 200; GET run không có qua backend và gọi trực tiếp với token hợp lệ đều 404; thiếu/sai token gọi trực tiếp Forecast đều 401; POST qua backend trả 409 do fixture chưa có daily_sales. 409 xác nhận yêu cầu đã qua xác thực và tới kiểm tra nghiệp vụ, không phải kết quả chạy mô hình thành công.

## Quét bảo mật (#140)

Dùng Trivy 0.67.2, vulnerability DB tải ngày 18/11/2026. Quét image backend Boot 3 ban đầu phát hiện Critical trong Spring MVC, Spring Security, Tomcat và Netty. Nâng Boot 4.0.9 để xử lý các Critical được phát hiện. Frontend nâng gói hệ điều hành bằng apk upgrade, tiếp tục chạy user 101.

Bốn báo cáo runtime image sau xử lý có 0 Critical: backend Boot 4, frontend đã vá, forecast, mock-connector. Không dùng ignore-unfixed và không bỏ qua CVE. Báo cáo local nằm trong .local-tools/week4-*-image.json. CI sinh lại báo cáo theo commit và lưu artifact security-<service>. Gate yêu cầu kết quả scan hoàn tất và exit code 0, lỗi scanner không được tính là đạt.

## Demo sạch (#142)

Script mặc định build + khởi động + seed, tạo volume và secret riêng, kiểm tra cổng trước build. Lần baseline có cache, trước nâng Boot 4, đo được 106.81 giây bao gồm build/start/seed. Lần Boot 4 dùng các image đã build, volume mới và --skip-build đo được 70.77 giây bao gồm start/seed/health, không gồm build. Hai số đo có phạm vi khác nhau.

```powershell
python scripts/demo-clean.py --project greenmart-demo-<unique-name> --port 28080
```

Script ghi timing.json chỉ khi mọi bước thành công. Nếu dùng --skip-build phải chuẩn bị bốn image với tag trùng --project. Các .env và volume của demo không ghi đè cấu hình/DB hiện có.

## Lần nghiệm thu cuối trên code e834d2f

- Project greenmart-demo-week4-verified, volume mới, build + start + seed + health đạt trong **52.50 giây** với build cache có sẵn. Lệnh `python scripts/demo-clean.py --project greenmart-demo-week4-verified --port 28080`.
- API thật: sync list 200, retry 202 rồi chuyển SUCCESS tại mock WEB, retry lại 409. GET Forecast qua backend trả 200 và ánh xạ đúng id. Thiếu/sai token gọi trực tiếp Forecast trả 401. POST qua backend trả 409 do fixture chưa có daily_sales.
- Backup và restore vào restore_week4_verified đối chiếu đúng sáu bảng: product=1000, customer=1500, inventory=3000, sales_order=10200, order_item=56341, outbox_event=0. Dùng postgres-backup.py với project/env-file/compose override của demo, restore vào tên DB mới.
- Các lệnh smoke chỉ thao tác fixture demo, không thay cấu hình local của người dùng.

## Kiểm chứng khi tách từng chức năng

- #142 chạy từ nhánh riêng, build + start + seed + health với volume mới đạt **103.95 giây** trên máy có cache, project `greenmart-demo-142-split`. Phạm vi và commit khác số đo 52.50 giây của bản tích hợp.
- #139: 4 test API với PostgreSQL đạt. #344: 4 test client/API đạt. #329: test DB mới đạt với Flyway của develop đã có migration #330.
- #344 bổ sung fixture 365 ngày cho một sản phẩm trên stack riêng: POST qua backend trả 202, GET 200, run DONE và 37 dòng kết quả. Số liệu nằm trong evidence/week4-forecast-auth-e2e.json. Đây là smoke xác thực/tích hợp, không đại diện nghiệm thu chất lượng toàn mô hình.

## Điều kiện đóng issue

Mỗi issue có nhánh và PR riêng, PR ghi Closes cho issue tương ứng. PR #329 ghi thêm Refs #308. Chỉ đóng sau review, CI xanh và merge develop. Tài liệu phục hồi ở DEPLOYMENT-RECOVERY.md; báo cáo cá nhân ở ../reports/SV07-week4.md.
