# Phục hồi callback sau outage (#419)

Chính sách mới trong PR này cần Lead review cùng code; không thay đổi nghiệm thu retry hữu hạn của #134. Không thêm mã lỗi hoặc field API công khai.

- Giữ một lần gửi ban đầu và 5 lần retry (1/2/4/8/16 giây), FAILED và DLQ khi hết lượt; giữ nguyên payload.
- Worker recovery kiểm tra từng kênh WEB/MARKET tối đa một lần mỗi 30 giây, bằng PUT stock có chữ ký với `{"items":[]}`. Đây là probe không thay đổi tồn của connector. Receiver phải hỗ trợ batch rỗng như mock hiện tại.
- Chỉ phục hồi lỗi kết nối, HTTP 5xx, 408 và 429; lỗi dữ liệu/chữ ký 4xx khác cần người vận hành xử lý và retry thủ công.
- Probe thành công (204) sẽ xếp lại tối đa 20 job FAILED/kênh/lượt, bắt đầu chu kỳ retry hữu hạn mới. Worker hiện tại gửi tuần tự, mặc định không quá 4 callback/giây/instance; probe không gửi lại toàn bộ backlog một lúc.
- Mục tiêu tồn mới nhất tới kênh trong 60 giây khi hàng đợi phục hồi có tối đa 20 job và receiver phản hồi nhanh. Không cam kết 60 giây cho backlog lớn hoặc callback chậm; #384 theo dõi tải riêng.
- Snapshot stock gửi dùng version lớn nhất đã lưu cho từng SKU trong hàng đợi, kể cả retry thủ công. Payload gốc vẫn giữ nguyên để tra cứu. Receiver vẫn phải kiểm tra version. Sự kiện trạng thái cùng kênh/mã đơn gửi theo thứ tự ghi vào hàng đợi; job cũ FAILED chặn job mới của cùng đơn đến khi được xử lý.
- Auto recovery và manual retry cùng khóa dòng delivery, chỉ chuyển FAILED sang RETRYING một lần. Giao tiếp HTTP vẫn có thể lặp khi tiến trình chết sau khi receiver nhận: receiver phải idempotent theo version/thời điểm như contract hiện tại; không tuyên bố exactly-once qua HTTP.
- FAILED chưa thành công sau 24 giờ phát cảnh báo WARN gồm event ID và kênh, tối đa một lần/24h/job; không in payload/secret và không tự xóa. Theo dõi cảnh báo trong log, dùng API sync log hiện có để tra cứu và retry.
- Tắt recovery bằng `greenmart.channel.recovery.enabled=false`; không tắt manual retry hoặc thay đổi lịch retry hiện có. Khi `greenmart.outbox.enabled=false`, scheduler recovery cũng tắt.

Kiểm tra: `mvn -B -ntp -f backend/pom.xml -Dtest=ChannelDeliveryTest,SyncLogApiTest,ChannelCallbackLifecycleTest test`.
