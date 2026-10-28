# Biến môi trường và secrets

Sao chép `.env.example` thành `.env` tại thư mục gốc. Giữ tên biến có sẵn.
Thay các giá trị `change-me` bằng mật khẩu riêng cho môi trường của mình.
Không dùng chung mật khẩu DB, Redis, RabbitMQ và khóa JWT/webhook.
Khóa JWT cần đủ độ dài cho thuật toán JWT mà SV08 lựa chọn.

`.env` chỉ dùng cục bộ và bị Git bỏ qua. `.env.example` được commit và chỉ
chứa giá trị mẫu, không được điền secrets thật vào file mẫu.
Không gửi đầu ra `docker compose config` hoặc `docker inspect` lên PR vì có
thể chứa mật khẩu; dùng `docker compose config --quiet` khi chỉ kiểm tra cú pháp.

## GitHub Actions

Workflow `Secrets` quét toàn bộ lịch sử bằng gitleaks, quyền `contents: read`.
Không cần tạo PAT cho `GITHUB_TOKEN`; GitHub tự cấp token giới hạn theo workflow.
Tắt comment tự động và artifact để tránh đưa nội dung phát hiện vào PR.
Không thêm ngoại lệ toàn cục cho `.env.example`; một secret thật trong file mẫu
vẫn phải bị phát hiện. Giá trị `change-me` không cần bỏ qua bằng cấu hình riêng.

Đặt check `gitleaks` làm required check sau khi nó đã chạy lần đầu; Lead quản lý
branch protection. Repo thuộc organization có thể cần `GITLEAKS_LICENSE` theo
điều kiện sử dụng của gitleaks-action.

## Staging (chuẩn bị cho #135)

Khi đã chốt máy staging, tạo GitHub Environment `staging` và cấu hình các secrets:

| Secret | Mục đích |
| --- | --- |
| `STAGING_HOST`, `STAGING_USER`, `STAGING_SSH_KEY` | Kết nối triển khai |
| `STAGING_KNOWN_HOSTS` | Khóa host SSH đã xác minh |
| `POSTGRES_PASSWORD`, `FORECAST_DB_PASSWORD` | Tài khoản DB |
| `REDIS_PASSWORD` | Xác thực Redis |
| `RABBITMQ_DEFAULT_PASS` | Xác thực RabbitMQ |
| `JWT_SECRET` | Ký JWT |
| `CHANNEL_WEB_SECRET`, `CHANNEL_MARKET_SECRET` | HMAC từng kênh |

Tên không bí mật như DB/user/host service có thể đặt trong Environment variables.
Không tự tạo hoặc thay secrets trên GitHub khi chưa có môi trường staging cụ thể.

## Kiểm tra

```powershell
git check-ignore .env
git ls-files .env
git ls-files .env.example
```

Kết quả: lệnh đầu trả `.env`, lệnh thứ hai không có kết quả, lệnh cuối trả
`.env.example`. Chạy gitleaks cục bộ khi đã cài CLI:

```sh
gitleaks git --redact --verbose .
```

Để kiểm tra phát hiện mà không đưa secret vào lịch sử: tạo thư mục tạm ngoài repo,
ghi chuỗi giả dạng `ghp_` cộng 36 ký tự vào file rồi chạy
`gitleaks dir --redact <thu-muc-tam>`; mong đợi exit code 1. Không dùng secret thật.
CI chỉ được coi là đạt sau khi workflow chạy thành công trên PR.

Nếu lộ secret thật: thu hồi/đổi secret ngay, đánh giá nơi đã sử dụng và làm sạch
lịch sử theo quy trình của Lead. Xóa file khỏi commit mới không thu hồi secret cũ.
