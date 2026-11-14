# Newman POS và AUTH/RBAC

Job GitHub Actions hiện có tên **QA POS Newman / pos-newman**, nhưng lệnh `npm run pos` chạy lần lượt **cả collection POS #149 và collection AUTH/RBAC #242**. Tên job chưa đổi; không hiểu job xanh là đã chạy đủ 65 test API/DB của #242.

Từ thư mục repo:

```text
npm ci --prefix tests/api/pos149 --ignore-scripts --no-audit --no-fund
npm test --prefix tests/api/pos149
npm run pos --prefix tests/api/pos149
```

- `npm test`: regression cho runner POS và AUTH (HTTP doubles, không phải bằng chứng backend).
- `npm run pos`: chạy POS trước, thành công mới chạy AUTH; lỗi một collection làm lệnh thất bại.
- `npm run auth`: chỉ collection AUTH/RBAC.
- AUTH dùng chung dependency Newman tại đây; hướng dẫn fixture và chạy 65 mã tại `../auth242/README.md`.

Khi chạy API thật, cấu hình `QA_BASE_URL`, `QA_SOURCE_COMMIT` và `QA_REPORT_DIR` đúng môi trường QA đã dựng. Artifact AUTH nằm trong thư mục con `auth242`, không ghi đè summary POS. Không lưu credential/token trong file cấu hình hoặc xuất body/header vào báo cáo.
