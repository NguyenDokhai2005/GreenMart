# Triển khai và khôi phục sự cố — SV07 (#141)

## Chuẩn bị

Dùng Docker Engine, Compose hỗ trợ `!override`, Python 3.11 và Git. Đọc SECRETS.md, cấp riêng từng secret, sao lưu PostgreSQL theo BACKUP-RESTORE.md. Giữ commit/image tag của bản đang chạy. Đối với máy mới dùng script demo để tạo volume và cấu hình riêng.

## Triển khai bản có phiên bản

```powershell
[xml]$project = Get-Content backend/pom.xml
$env:IMAGE_TAG = $project.project.version + "-sha-" + (git rev-parse HEAD)
docker compose config --quiet
docker compose build --pull
docker compose up -d --wait --wait-timeout 600
docker compose ps
pwsh -File scripts/check-health.ps1 -BaseUrl http://localhost:8080
```

Cổng trên máy tùy file override. Backend nội bộ luôn là backend:8080. Chạy kiểm tra bảo mật trong workflow Dependency and image security, giữ báo cáo theo từng service. Chỉ triển khai bản đã có kết quả review và các checks bắt buộc đạt. Các migration chạy khi backend khởi động. Không sửa migration đã áp dụng.

Backend và forecast-service phải dùng cùng FORECAST_INTERNAL_TOKEN. Compose truyền token cho cả hai. Không ghi token hoặc Authorization vào log. Sai/thiếu token được client chuyển thành FORECAST_UNAVAILABLE (503) để người dùng không nhận nội dung lỗi nội bộ. Không cho redirect mang theo token.

## Kiểm tra sau triển khai

Kiểm tra frontend, health/readiness, đăng nhập bằng tài khoản của môi trường, tìm hàng, tạo một giao dịch demo riêng và đối chiếu tồn. Admin mở GET /api/v1/integrations/sync-logs; lọc channelCode/status và phân trang page/size. Owner chỉ xem. POST /api/v1/integrations/sync-logs/{id}/retry trả 202 cho callback FAILED; 409 nếu đã SUCCESS/RETRYING hoặc không có payload gửi lại; 404 nếu không có log. Worker nền gửi lại cùng payload, số phiên bản và external ID.

## Xử lý sự cố

| Sự cố | Chẩn đoán và phục hồi |
|---|---|
| Cổng bị chiếm | Xem netstat, đổi cổng host trong override; nội bộ giữ nguyên |
| Backend không sẵn sàng | `docker compose logs --tail 100 backend postgres redis`, kiểm tra kết nối và kết quả Flyway |
| RabbitMQ ngừng | Xem log broker, khởi động lại `docker compose up -d rabbitmq`, theo dõi outbox gửi bù. Kịch bản thực nghiệm ở RABBITMQ-OUTAGE-DEMO.md |
| Redis ngừng hoặc không truy cập được | JWT cần registry phiên: API có JWT, gồm POS, trả 503 AUTH_UNAVAILABLE; login có thể trả 503 LOGIN_UNAVAILABLE. Kiểm tra `docker compose ps redis` và log backend/redis đã che secrets, khởi động lại bằng `docker compose up -d redis`, kiểm tra readiness rồi đăng nhập và gọi API có JWT. Không đổi lỗi thành 401 hay bỏ kiểm tra phiên để tiếp tục bán hàng. |
| Callback FAILED | Sửa nguyên nhân ở kênh, Admin gửi lại qua API, theo dõi SUCCESS/FAILED |
| Forecast trả 503 | Kiểm tra service/healthz, URL nội bộ và token cả hai bên bằng cấu hình quản lý secrets. Không in giá trị ra màn hình |
| Mất hoặc hỏng dữ liệu | Dừng ghi, giữ bản DB lỗi để chẩn đoán, phục hồi vào DB mới bằng BACKUP-RESTORE.md, đối chiếu số dòng và quyền, rồi đổi kết nối |
| Bản mới lỗi | Chuyển IMAGE_TAG về bản đã kiểm chứng rồi `docker compose up -d --no-build --wait`. Chỉ làm nếu schema tương thích. Với migration không tương thích, phục hồi snapshot vào DB riêng trước |

Không chạy `down -v` trên môi trường đang sử dụng. Rollback dữ liệu có thể làm mất giao dịch sau thời điểm backup, nên ghi lại mốc backup và dừng ghi trước khi chuyển DB. Ghi sự cố, thời điểm, commit, hành động và kết quả kiểm chứng vào biên bản vận hành.

## Diễn tập Redis mất kết nối (#155, review PR #434)

Chỉ diễn tập trong stack/container QA riêng, không dừng Redis đang dùng chung.
Chuẩn bị một phiên đăng nhập hợp lệ, xác nhận `/auth/me` hoạt động, rồi dừng Redis.
API có JWT và POS phải trả `503 AUTH_UNAVAILABLE` trước khi xử lý nghiệp vụ;
liveness còn 200 nhưng readiness phải 503. Không coi đó là sai mật khẩu và không
ghi nhận một giao dịch POS thành công. Client không được bỏ kiểm tra phiên hoặc
coi logout đã thu hồi thành công nếu Redis lỗi.

`RedisOutageApiTest` thực hiện kịch bản này bằng PostgreSQL/Redis Testcontainers
riêng: đăng nhập thật, dừng đúng container Redis của test, kiểm tra `/auth/me`,
POS quote và health. Chạy `mvn -B -ntp -Dtest=RedisOutageApiTest test` trong backend.
Test không dừng Compose và không dùng token/secret của môi trường thật.

Sau khôi phục, kiểm tra lại readiness, login và một API có JWT. Nếu dữ liệu phiên
Redis bị mất thì access/refresh cũ bị từ chối, người dùng phải đăng nhập lại;
không tự phục hồi phiên từ JWT. Bài test tự động trên kiểm tra lúc Redis dừng,
không thay thế nghiệm thu phục hồi của cấu hình triển khai thực tế. Ghi lại
thời điểm gián đoạn, commit, lỗi503 và kết quả phục hồi mà không lưu nguyên token.

## Bằng chứng nghiệm thu

Đính kèm commit, kết quả checks, trạng thái service, log test đã che dữ liệu nhạy cảm, và bản ghi thời gian demo. Bằng chứng cục bộ tuần 4 ở WEEK4-EVIDENCE.md. Tài liệu thao tác backup ở BACKUP-RESTORE.md.
