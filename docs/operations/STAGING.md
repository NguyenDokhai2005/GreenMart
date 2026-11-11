# Triển khai staging

CI chỉ triển khai khi push vào `develop` và toàn bộ job kiểm tra thành công. PR không được cấp quyền truy cập máy staging. Job dùng SHA đã qua CI và bỏ qua SHA cũ nếu `develop` đã tiến lên.

Chuẩn bị một máy Linux có Git, Docker Compose, curl, flock và cổng loopback 8080 trống. Tạo `/srv/greenmart/{shared,releases}` và clone repo vào `/srv/greenmart/repo` bằng quyền đọc. Tài khoản deploy cần quyền ghi hai thư mục trên, metadata Git và quyền chạy Docker. Đặt `.env` staging riêng ở `shared/.env` (quyền 0600), dùng secret riêng cho môi trường này. Thiết lập GitHub Environment `staging` và bốn secret mô tả trong [SECRETS.md](SECRETS.md). Giới hạn nhánh deploy của environment ở `develop`.

Job chờ Compose lên và kiểm tra readiness tại `127.0.0.1:8080`. Khi job đỏ, kiểm tra `docker compose -p greenmart-staging ps` và log dịch vụ trên máy. Không xóa volume để khắc phục lỗi. Flyway có thể đã đổi schema dù rollout thất bại; kiểm tra tương thích và bản sao lưu trước khi quay lại bản cũ.
