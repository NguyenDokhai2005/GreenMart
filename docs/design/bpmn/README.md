# Sơ đồ quy trình nghiệp vụ (BPMN) – GreenMart

Issue #10. Khớp BA §3 (UC-01), §4 (UC-02), §6.3 (UC-07) và các ngoại lệ ở BA §11.

| # | Quy trình | Làn (lane) | Ảnh |
| --- | --- | --- | --- |
| 01 | Bán tại quầy (UC-01) | Khách hàng, Thu ngân, Hệ thống | [png](png/01-ban-tai-quay.png) |
| 02 | Đơn online đa kênh (UC-02) | Kênh bán, Hệ thống, QL chi nhánh / Thủ kho | [png](png/02-don-online.png) |
| 03 | Chuyển kho giữa chi nhánh (UC-07) | Thủ kho CN nguồn, Hệ thống, QL CN nguồn, Thủ kho CN đích | [png](png/03-chuyen-kho.png) |

## Ký hiệu

Vẽ bằng PlantUML activity diagram theo lối BPMN: mỗi cột là một làn (lane), chấm xanh là sự kiện bắt đầu, chấm đen là sự kiện kết thúc, hình thoi là cổng rẽ nhánh (gateway). Mã `E1`…`E7` là luồng ngoại lệ trong BA §3/§4, `EX-xx` là tình huống ngoại lệ ở BA §11.

## Điểm cần đọc kỹ

- **01:** mọi bước từ "Tính lại báo giá" đến "Ghi outbox" nằm trong một transaction; bất kỳ lỗi nào (tổng đổi, thiếu tồn, thanh toán lỗi) đều rollback, không tạo đơn, giỏ hàng giữ nguyên. Bấm lại cùng `Idempotency-Key` trả đơn cũ.
- **02:** đơn online chỉ giữ chỗ (`reserved`) khi vào hệ thống, trừ `on_hand` khi hoàn tất. Ba điểm dừng REJECTED / EXPIRED / CANCELLED đều không trừ tồn thực và báo trạng thái về kênh.
- **03:** tồn nguồn giảm khi QL CN nguồn xác nhận xuất, tồn đích tăng theo số thực nhận; chênh lệch ghi hao hụt (EX-09).

## Cập nhật ảnh

```sh
cd docs/design/bpmn
java -jar plantuml.jar -charset UTF-8 -tpng -o png *.puml
```
