# Script vận hành

Phụ trách: SV07. Ví dụ: `backup.sh` / `restore.sh` (pg_dump), `seed.sh` (gọi script sinh dữ liệu).

---

## 1. Bộ điều phối Seed dữ liệu một lệnh (Issue #108)

Các script wrapper tiện ích tại thư mục gốc giúp lập trình viên và môi trường CI/CD nạp dữ liệu mẫu nhanh chóng:

- **Linux / macOS / Git Bash**:
  ```bash
  ./scripts/seed.sh [TÙY_CHỌN]
  ```
- **Windows PowerShell**:
  ```powershell
  .\scripts\seed.ps1 [TÙY_CHỌN]
  ```

### Các tùy chọn thông dụng:
- `--generate-only`: Chỉ sinh file dữ liệu SQL/JSON và cập nhật `SHA256SUMS`, không nạp DB.
- `--load-only`: Nạp file SQL có sẵn vào DB mà không sinh lại.
- `--verify-only`: Kiểm đếm AC-01 trên DB hiện tại và xuất bảng mã vân tay (fingerprint).
- `-y` hoặc `--yes`: Bỏ qua câu hỏi xác nhận xóa dữ liệu khi DB đã có đơn hàng.
- `--check-golden`: Đối chiếu mã SHA-256 với mốc vàng (Golden Checksum) từ unit test.

> Chi tiết đặc tả logic điều phối xem tại: [forecast-service/scripts/README.md](../forecast-service/scripts/README.md).
