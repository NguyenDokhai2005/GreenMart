# Báo cáo kiểm thử GreenMart vòng 2

**Đề xuất QA tại commit đã kiểm thử: chưa nghiệm thu toàn hệ thống.** Ưu tiên xử lý callback thanh toán phát lại giữa hai đơn (#416), đồng bộ kênh sau sự cố (#384) và mất giỏ POS khi chuyển trang (#445). Đây là đề xuất QA; Lead quyết định nghiệm thu.

| Thông tin | Nội dung |
| --- | --- |
| Người thực hiện | Huynh-Phuc-Dat — SV08 QA/Security |
| Ngày kiểm thử | 21/11/2026 (ảnh JSON có timestamp UTC, tương ứng giờ Việt Nam ngày 21/11) |
| Commit đã chạy | `4e45b443fb25424554aae68df9a58102335d8f67` |
| Nhánh kiểm thử | `feature/158-final-verification-20261121` |
| Môi trường | QA riêng; API cổng 18222, frontend cổng 13222, PostgreSQL thật; dữ liệu mô phỏng |
| Cập nhật GitHub khi soạn báo cáo | 2026-11-21T12:33:27+07:00; develop `310c55b02ba522f71dcf46e9e8bfb71eeca27e03` đã khác commit kiểm thử |
| Phạm vi kết luận | Chỉ bằng chứng tại commit đã chạy. Chưa kiểm thử lại develop mới; không khẳng định lỗi cũ còn trên phiên bản mới |

## Đọc báo cáo theo nhu cầu

- Đọc trang này để nắm kết quả và phần còn chờ.
- Xem [chi tiết 26 phát hiện đã gộp theo issue](findings.md) để biết cách tái hiện, kết quả, phân loại và thành viên phối hợp.
- Xem [tóm tắt bằng chứng có cấu trúc](summary.json); ảnh nằm trong thư mục `images`, liên kết tương đối dùng được trên GitHub.
- Báo cáo này dùng issue có sẵn; không tạo lỗi mới hoặc thay đổi assignee. Bộ 1.862 bản ghi/Word/Excel đầy đủ được giữ local, không đưa cả ZIP, nhật ký hoặc log thô vào PR.

## Kết quả đã chạy

| Nhóm | Kết quả mới | Ý nghĩa và giới hạn |
| --- | --- | --- |
| Backend Maven | 583 testcase PASS; verify exit 0 | Có unit/integration; không coi mọi method là một API thật |
| Frontend Vitest | 379 testcase PASS | Component/unit; không thay kiểm thử thao tác trình duyệt |
| Forecast pytest | 214 PASS, 1 skip; 4 subtest PASS | XML có 215 testcase; ca daily_sales skip đã chạy riêng PASS, vẫn giữ kết quả skip gốc |
| Newman API | POS 15 và Auth 26 assertion PASS | API thật; lỗi runner exit 134 ban đầu đã tách khỏi kết quả retry |
| Role và chi nhánh | Các probe IDOR/khác chi nhánh chặn đúng; API delta đủ 12 tài khoản và 36 role×branch Forecast | Không thấy rò dữ liệu trong probe đã chạy; ADMIN/orders còn mâu thuẫn OpenAPI và quyết định Lead |
| Giao dịch và kho | Idempotency 15 recheck PASS; tranh tồn 100 VU đạt đúng 5 thắng khi tồn 5 | Có đối chiếu order/payment/ledger/reserved; không suy ra mọi tải đều an toàn |
| Tải 50 VU | 13.870 flow trong 5 phút; read p95 35,434 ms; create p95 132,419 ms | Backend thực tế 640 MiB/JVM 384 MiB/2 CPU; không dùng nhãn tài nguyên sai trong raw script |
| AI trên holdout | Hậu xử lý tốt hơn hai baseline BA; hai lần metrics và model trùng | Zero diagnostic vẫn tốt hơn ở daily; chưa chứng minh vận hành nightly và AI→Dashboard E2E |
| UI thật | 39 bản ghi: 8 PASS, 25 FAIL, 6 BLOCKED; thêm 2 ca phiên/logout PASS | Các FAIL có UX/enhancement và trùng issue; chưa test đủ 12 tài khoản trên UI vòng 2 |
| Regression cũ | Customer 4 tổ hợp filter PASS; nhóm #386–389 có 9 kiểm tra PASS | Không coi tất cả lỗi lịch sử đều đã được retest |
| Sao lưu | Restore DB riêng và đối chiếu SQL PASS | Chưa nối backend vào DB khôi phục để kiểm E2E |

**Tổng 1.862 bản ghi: 1.786 PASS, 45 FAIL, 24 BLOCKED, 7 NOT RUN.** Đơn vị đếm gồm test method, assertion, control và rerun; không phải 1.862 nghiệp vụ độc lập, 45 bug riêng hoặc tỷ lệ hoàn thành dự án. Các oracle sai/chưa chốt được giữ raw và đánh giá BLOCKED, không đổi thành PASS.

## Phát hiện cần ưu tiên

| Issue | Hiện tượng tại 4e45b44 | Phụ trách module và phối hợp |
| --- | --- | --- |
| #416 | Cùng body/HMAC chuyển hai đơn sang CONFIRMED; có payment/refund tương ứng trong QA | SV03 chính theo nội dung issue; SV07 ký/webhook và SV02 thanh toán phối hợp; chưa chứng minh thất thoát tiền gateway thật |
| #384 | Outbox còn dữ liệu nhưng trace chưa SENT trong 90 giây và kênh chưa nhận trong 60 giây sau phục hồi | SV07 điều tra delivery/backlog, SV03 tồn kho và SV02 giao dịch phối hợp; chưa có bằng chứng mất dữ liệu |
| #445 | Giỏ chưa thanh toán bị mất khi sang Hóa đơn rồi quay lại | SV04 giao diện POS |
| #406 | Sáu biến thể POST/PUT promotion chứa phần tử null trả HTTP 500 | SV02; chưa kết luận nguyên nhân gốc chỉ từ response |
| #442 / #443 | Tìm không dấu trả 0, có dấu trả 4 tại API và UI | SV02 products / SV03 inventory; là enhancement/kỳ vọng tìm kiếm, không phải HTTP 500 hay lỗi render đã chứng minh |

**Cập nhật sau lượt test:** #443 đã CLOSED và PR #473 hỗ trợ tìm không dấu đã merge. Báo cáo giữ kết quả lịch sử tại 4e45b44; trạng thái xác minh bản sửa là **NOT RUN**, không ghi lỗi còn tồn tại trên develop mới và không tự đánh dấu đã sửa PASS.

## Phần bị chặn và chưa chạy

- **BLOCKED:** print preview do công cụ timeout; input ngày chưa kích hoạt state (chỉ lỗi lọc qua URL được xác minh); policy IP/CORS/Q31; ADMIN/orders mâu thuẫn quyền; backend `/forecasts/runs` trả 404 và ba màn Operations placeholder.
- **NOT RUN:** UI đủ 12 tài khoản vòng 2; findings #463–466; staging/TLS; full DAST/SSRF/stored XSS; lịch AI 02 giờ và sự kiện AI qua broker thật; app E2E sau restore. Không tuyên bố đạt toàn bộ OWASP.
- Fixture 111 dự đoán thật được copy SQL để test ACL backend không phải pipeline AI→backend→UI tự động. Snapshot CI chỉ xác minh Secrets success, không nhận tất cả CI xanh.

## Điều kiện xác minh lại

Lead chốt phạm vi UX/chính sách còn mở. Với bản sửa, tạo lượt QA/output mới trên commit cụ thể; chạy ca gốc, control hợp lệ, boundary, role/branch và SQL invariants, rồi kiểm tra luồng liền kề. #416 phải chặn replay khác đơn và vẫn idempotent cùng đơn; #384 cần trace SENT và kênh hội tụ; #445 phải giữ đúng giỏ khi chuyển trang/reload và không lẫn người dùng/chi nhánh. Chỉ cập nhật PASS khi có bằng chứng mới; báo cáo này không tự đóng issue hoặc xác nhận Done.
