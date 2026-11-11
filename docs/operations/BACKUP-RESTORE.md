# Sao lưu và kiểm tra phục hồi PostgreSQL

Chạy từ thư mục repo với Python 3 và Docker Compose. Chọn một thư mục mới bên ngoài repo để giữ dữ liệu có thể chứa thông tin khách hàng. Giới hạn quyền truy cập thư mục (Windows: ACL, Linux/macOS: quyền 0700).

```bash
python scripts/postgres-backup.py -p greenmart backup ../greenmart-backup-20261111
python scripts/postgres-backup.py -p greenmart restore ../greenmart-backup-20261111 restore_20261111
```

Backup lưu `database.dump` và `counts.json`. Số dòng được đo trên cùng snapshot với `pg_dump`, kể cả khi có giao dịch mới trong lúc sao lưu. Restore tạo database mới (tên phải bắt đầu bằng `restore_`), từ chối tên đã tồn tại và không ghi vào DB đang chạy. Kết quả thành công in số dòng của sáu bảng. Nếu restore lỗi, DB đích được giữ để chẩn đoán; chọn tên mới cho lần chạy sau.

Đây là bản sao dữ liệu/schema để diễn tập phục hồi. Roles và grants cấp toàn cụm được quản lý riêng; khi chuyển server phải tạo lại các role theo hướng dẫn vận hành.
