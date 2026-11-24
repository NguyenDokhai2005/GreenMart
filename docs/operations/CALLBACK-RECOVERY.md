# Phục hồi callback sau outage (#419)

Chính sách trong PR #513 cập nhật theo review Lead ngày 24/11; chỉ triển khai sau khi Lead duyệt và merge PR. Không thay đổi nghiệm thu retry hữu hạn của #134 hoặc thêm mã lỗi/field API công khai.

- Giữ một lần gửi ban đầu và 5 lần retry (1/2/4/8/16 giây), FAILED và DLQ khi hết lượt; giữ nguyên payload.
- Worker recovery kiểm tra từng kênh WEB/MARKET tối đa một lần mỗi 30 giây, bằng PUT stock có chữ ký với `{"items":[]}`. Đây là probe không thay đổi tồn của connector. Receiver phải hỗ trợ batch rỗng như mock hiện tại.
- Chỉ phục hồi lỗi kết nối, HTTP 5xx, 408 và 429; lỗi dữ liệu/chữ ký 4xx khác cần người vận hành xử lý và retry thủ công.
- Probe thành công (204) sẽ xếp lại tối đa 20 job FAILED/kênh/lượt. Mỗi job chỉ được tự phục hồi tối đa **3 lần trong vòng đời job**, kể cả khi stock probe khỏe nhưng endpoint trạng thái đơn vẫn lỗi. Hết ngân sách thì giữ FAILED, payload và DLQ; chỉ ADMIN retry thủ công. Manual retry không cấp lại ngân sách tự phục hồi.
- Mỗi chu kỳ vẫn chỉ có 1 lần gửi + 5 retry. `cycle_attempts` đếm riêng cho chu kỳ; `sync_log.attempts` giữ tổng số lần gửi, không reset khi auto hoặc manual retry. Ví dụ endpoint luôn lỗi 503: chu kỳ đầu 6 lần + tối đa 3 chu kỳ phục hồi × 6 = **24 lần gửi**, sau đó dừng tự động. Probe không tính là gửi payload job.
- Claim probe được commit trước khi gọi HTTP, không giữ transaction/khóa DB trong lúc chờ receiver. Sau probe, worker mở transaction ngắn, kiểm tra lại token claim, trạng thái và ngân sách rồi mới khóa và xếp job; kết quả probe cũ không được dùng cho claim mới.
- Worker hiện tại gửi tuần tự, mặc định không quá 4 callback/giây/instance; probe không gửi lại toàn bộ backlog một lúc.
- Mục tiêu tồn mới nhất tới kênh trong 60 giây khi hàng đợi phục hồi có tối đa 20 job và receiver phản hồi nhanh. Không cam kết 60 giây cho backlog lớn hoặc callback chậm; #384 theo dõi tải riêng.
- Snapshot stock gửi dùng version lớn nhất đã lưu cho từng SKU trong hàng đợi, kể cả retry thủ công. Payload gốc vẫn giữ nguyên để tra cứu. Receiver vẫn phải kiểm tra version. Sự kiện trạng thái cùng kênh/mã đơn gửi theo thứ tự ghi vào hàng đợi; job cũ FAILED chặn job mới của cùng đơn đến khi được xử lý.
- Auto recovery và manual retry cùng khóa dòng delivery, chỉ chuyển FAILED sang RETRYING một lần. Giao tiếp HTTP vẫn có thể lặp khi tiến trình chết sau khi receiver nhận: receiver phải idempotent theo version/thời điểm như contract hiện tại; không tuyên bố exactly-once qua HTTP.
- FAILED chưa thành công sau 24 giờ phát cảnh báo WARN gồm event ID và kênh, tối đa một lần/24h/job; không in payload/secret và không tự xóa. Theo dõi cảnh báo trong log, dùng API sync log hiện có để tra cứu và retry.
- Tắt recovery bằng `greenmart.channel.recovery.enabled=false`; không tắt manual retry hoặc thay đổi lịch retry hiện có. Khi `greenmart.outbox.enabled=false`, scheduler recovery cũng tắt.

Migration V22 giữ nguyên để không đổi checksum của môi trường QA đã áp dụng. V23 thêm ngân sách phục hồi, bộ đếm chu kỳ và token probe; giữ số attempts đang có khi nâng cấp. Các lượt đã bị bản cũ reset trước nâng cấp không thể được suy ra lại từ bộ đếm hiện tại.

Kiểm tra: `mvn -B -ntp -f backend/pom.xml -Dtest=ChannelDeliveryTest,SyncLogApiTest,ChannelCallbackLifecycleTest,OutboxIntegrationTest,ChannelEventSecurityTest,ChannelRecoveryMigrationTest test`.
