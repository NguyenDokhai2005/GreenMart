# Môi trường demo sạch và đo khởi động (#142)

Mở Docker Desktop, dùng Compose hỗ trợ !override, Python 3.11 (cài requirements-dev của forecast nếu generator cần). Chạy từ thư mục gốc:

```powershell
$env:PYTHONUTF8 = '1'
python scripts/demo-clean.py --port 28080
```

Script tạo tên project greenmart-demo- riêng, volume mới, secrets ngẫu nhiên trong .local-tools, cổng riêng 28080–28083; build, khởi động, chờ health, sinh seed, nạp bằng ON_ERROR_STOP và kiểm tra frontend/backend/forecast. Thời gian tính cả build và seed, phụ thuộc cache và mạng. Kết quả timing.json ghi số giây thực tế trong .local-tools/<project>.

Không đưa file .env tạo ra vào PR. Dùng danh sách lệnh compose trong timing.json để xem log/ps hoặc dừng stack. Giữ nguyên volume khi cần kiểm tra. Mỗi lần chạy sạch chọn project mới, script từ chối ghi đè thư mục cũ. Nếu chạy lỗi, xem service log và khởi chạy lại với project mới sau khi xử lý nguyên nhân.

Các tài khoản demo và mật khẩu được generator mô tả trong forecast-service/scripts/README.md. Các giá trị này chỉ phục vụ dữ liệu mẫu. Sau seed, thử đăng nhập và một giao dịch riêng để kiểm tra luồng ứng dụng.

Lần đo trên nhánh riêng #142 ngày 18/11/2026, project `greenmart-demo-142-split`, cổng 28180–28183, volume mới: build + start + seed + health đạt trong **103.95 giây**. Máy đã có build cache. Đây là thời gian quan sát trên máy thử, chưa đo thời gian tải image trên máy hoàn toàn mới.
