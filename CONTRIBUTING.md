# Quy tắc làm việc

## Nhánh
- `main`: chỉ nhận merge từ `develop` sau mỗi buổi review tuần.
- `develop`: nhánh tích hợp, mọi PR đi vào đây.
- Nhánh tính năng: `feature/<mã>-<mô-tả>`, ví dụ `feature/ORD-01-pos-checkout`. Sửa lỗi: `fix/<số-issue>-<mô-tả>`.

## Commit
Dạng `<loại>(<module>): <nội dung> (#<issue>)`, loại là `feat`, `fix`, `test`, `docs`, `refactor`, `chore`.
Ví dụ: `feat(inventory): giữ chỗ tồn bằng update có điều kiện (#52)`.

## Pull Request
- Mở PR vào `develop`, mô tả ghi `Closes #<số issue>` để issue tự đóng khi merge.
- Cần 1 review đã duyệt và CI xanh mới được merge.
- PR nhỏ (khoảng ≤ 400 dòng), mở muộn nhất 2 ngày sau khi bắt đầu việc.
- Mỗi người review ít nhất 2 PR của người khác mỗi tuần.

## Definition of Done
- Merge vào `develop`, CI xanh.
- Có unit test cho logic nghiệp vụ (backend) hoặc chạy được với API thật (frontend).
- Khớp đặc tả trong tài liệu Phân tích nghiệp vụ; khác đặc tả phải báo Lead trước khi merge.
- Đóng issue kèm ảnh chụp hoặc lệnh kiểm tra.

## Thay đổi hợp đồng API
`openapi.yaml` chỉ do Lead (SV01) merge. Ai cần đổi thì mở issue nhãn `docs` và tag @NguyenDokhai2005.

## Nhịp làm việc
- Daily 21:00, 15 phút.
- Tích hợp giữa tuần: thứ Tư 20:00.
- Review + demo + retro: Chủ nhật 20:00.
