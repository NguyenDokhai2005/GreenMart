# Báo cáo cá nhân SV07 — Nguyễn Hà Thanh

Vai trò Integration / DevOps. Báo cáo công việc đến ngày 18/11/2026.

## Công việc các tuần trước

- Tuần 1: khung backend, Dockerfile, Docker Compose, GitHub Actions, Gitleaks, hướng dẫn secrets, health/readiness và chạy backend từ IDE (#122–#126).
- Luồng tích hợp: nhận webhook có chữ ký và chống đơn trùng (#132), đồng bộ tồn có version (#133), callback bền vững và retry (#134), gửi trạng thái đơn (#137).
- Vận hành: công cụ backup/restore PostgreSQL (#136), thử RabbitMQ dừng 301 giây nhưng POS vẫn hoàn thành và tồn WEB được gửi bù (#138).
- Sửa bổ sung: thời gian đặt đơn bất thường (#324), API ánh xạ SKU cho Admin (#328), đóng tài nguyên callback khi ứng dụng dừng (#318), phát hiện file nguồn Java trùng đường dẫn trong CI.

## Công việc tuần 4

| Issue | Sản phẩm bàn giao |
|---|---|
| #139 | API nhật ký đồng bộ phân trang/lọc; Admin retry callback FAILED, Owner chỉ xem; kiểm thử quyền và trạng thái |
| #140 | Workflow build tag sha/phiên bản, quét phụ thuộc và runtime image, chặn Critical; báo cáo JSON |
| #141 | Runbook triển khai, chẩn đoán, rollback và phục hồi database |
| #142 | Script stack demo riêng, secrets/volume riêng, build + seed + health và timing.json |
| #143 | Báo cáo cá nhân và liên kết bằng chứng |
| #329 | Bỏ SELECT mặc định cho bảng tạo sau ở DB mới, test từ chối đọc app_user |
| #344 | Forecast HTTP client gửi Bearer token cho POST run và GET run; Compose truyền cùng token; xử lý 401 và thiếu token |

## Kiểm chứng và trạng thái

Nghiệm thu local: 547 test backend và 294 test frontend đạt; bốn image không có Critical; demo sạch build/start/seed với cache mất 52.50 giây; backup/restore đối chiếu đúng sáu bảng. Kết quả có thể tái hiện được ghi trong ../operations/WEEK4-EVIDENCE.md. Chỉ đóng issue sau PR review và checks bắt buộc đạt, merge vào develop. Kết quả chạy local và code chuẩn bị PR được phân biệt với kết quả CI/merge thực tế.

## Phối hợp

SV03 quản lý migration và thu hồi quyền DB cũ, SV06 quản lý API/token Forecast, SV08 hỗ trợ nghiệm thu và bảo mật. Endpoint sync log theo ma trận BA cho Admin và Owner. Các đề xuất và code tuần 4 cần review trước khi đưa vào nhánh chung.
