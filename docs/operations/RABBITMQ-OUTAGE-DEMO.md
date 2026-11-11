# Demo POS khi RabbitMQ mất kết nối

Dùng **stack demo riêng** có các thay đổi #133 và #134, cổng backend/mock riêng bind loopback và tên Compose bắt đầu bằng `greenmart-demo-`. Chuẩn bị tài khoản thu ngân, mặt hàng có tồn ở chi nhánh, ánh xạ SKU và cấu hình chi nhánh phục vụ kênh. Không chạy cùng giao dịch khác trên fixture này.

Cấp `DEMO_POS_USER` và `DEMO_POS_PASSWORD` qua môi trường rồi chạy từ thư mục repo; thay ID và SKU theo fixture:

```bash
python scripts/demo-rabbitmq-outage.py -p greenmart-demo-week3 -f docker-compose.yml -f demo-ports.yml --backend http://127.0.0.1:18083 --mock http://127.0.0.1:18091 --branch 1 --product 205 --channel WEB --sku W-DEMO
```

Kịch bản tự dừng app RabbitMQ trong container demo, bán một sản phẩm, kiểm tra hai sự kiện `PENDING`, giữ broker dừng đủ 300 giây rồi bật lại. Đạt khi hai sự kiện chuyển `SENT` và SKU kênh giảm 1 với version mới, khớp PostgreSQL. Kịch bản bật lại RabbitMQ kể cả khi kiểm tra lỗi; nếu tiến trình bị buộc dừng, khôi phục bằng `docker compose -p greenmart-demo-week3 -f docker-compose.yml -f demo-ports.yml exec rabbitmq rabbitmqctl start_app`.

Lần chạy 11/11/2026 trên fixture riêng với chi nhánh 901, sản phẩm 205 và SKU `W-SUA-TH-1L`:

```text
POS completed: POS-DEMO901-20261111-0001; outbox pending
Broker down 301 seconds; backend ready
Outbox SENT; WEB/W-SUA-TH-1L stock=39 version=6
```
