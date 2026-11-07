# Issue #148 — kiểm thử tài khoản demo

Phụ trách: Huynh-Phuc-Dat (SV08 — QA/Security). Issue: https://github.com/NguyenDokhai2005/GreenMart/issues/148 (đang mở, hạn 05/11/2026 khi đối chiếu ngày 06/11/2026).

## Phạm vi và nguồn yêu cầu

- AC #148 và SRS §7.4: sau seed, từng tài khoản demo đăng nhập được bằng cùng mật khẩu; đủ 5 vai trò, quản lý/thu ngân/thủ kho cho từng chi nhánh.
- BA §2: ADMIN/OWNER toàn chuỗi; 3 vai trò còn lại chỉ có chi nhánh được gán.
- OpenAPI 2.0.0: `/auth/login` trả `TokenResponse`, `/auth/me` trả `CurrentUser`; đối chiếu `role`, `branches` bằng mã chi nhánh và kiểm tra đủ trường bắt buộc. ADMIN/OWNER nhận mọi chi nhánh đang hoạt động.
- `README.md` và `generate_demo_users()` của SV06: 12 tài khoản, gồm `admin`, `owner`, `owner.pho`, `quanly.gm001..003`, `thungan.gm001..003`, `thukho.gm001..003`. Mật khẩu demo công khai `Demo@123`. “5 vai trò × 3 chi nhánh” không có nghĩa bắt buộc 15 tài khoản: ADMIN/OWNER dùng chung toàn chuỗi.
- `CONTRIBUTING.md`, CODEOWNERS và template PR: thay đổi phải được review, CI xanh và merge vào develop mới đủ DoD. Tài liệu thuộc diện Lead review.
- Không đổi seed, backend, API contract hoặc migration; không bao gồm full RBAC/negative test của #242.

Nguồn GitHub đọc trước khi thực hiện: nhánh `develop`, commit `7a7fdadaa9f114d53168a380c655ea355288c7cd`. Local: `842a29fa353b40cf52e7ef5ebe2467406f95bac4`. Local đã chứa merge auth #243 (`6fb333a2ea5ea819db809127f2b48b3e628eba13`), chưa có 10 commit phía GitHub về POS/mock connector. Báo cáo này không chứng nhận commit GitHub mới nhất đã được chạy test.

## Bộ kiểm tra

Bộ runner độc lập: `tests/api/issue148_demo_accounts.py` (Python standard library, không cần cài dependency); không phụ thuộc tài liệu hoặc script chưa được commit khác.

| TC | Kiểm tra |
|---|---|
| TC-DEMO-01..06 | DB có đúng các tài khoản demo theo nhóm vai trò, đang hoạt động, gán chính xác tập chi nhánh; 3 chi nhánh demo đang hoạt động |
| TC-DEMO-10, 11, 11b, 12..20 | Đăng nhập cả 12 tài khoản: HTTP 200, đủ trường token/user, Bearer, expiresIn=900, đúng role và chính xác tập chi nhánh |
| TC-DEMO-21 | Tổng hợp đủ 12 lần đăng nhập; mọi tài khoản đều phải pass |
| TC-DEMO-30..34 | `/auth/me` cho ADMIN, OWNER, CASHIER GM-001, quản lý GM-002, thủ kho GM-003: đúng user ID, role và tập chi nhánh |
| TC-DEMO-40 | Hash của cả 12 tài khoản có định dạng BCrypt, cost ≥ 10; SQL chỉ trả boolean, không xuất hash |
| TC-DEMO-41 | Hash 2 thu ngân khác nhau dù cùng mật khẩu demo; SQL chỉ trả boolean |

Tổng 26 TC. Kiểm tra bằng tập mã chi nhánh chính xác, không chỉ đếm “ít nhất 1”. Không cần phụ thuộc ID branch cố định. Không có môi trường sẽ ghi BLOCKED, không ghi PASS. Mỗi login thành công được logout để thu hồi refresh token của phiên test. Login có thể reset counter đăng nhập thành công, do đó chỉ chạy trên QA riêng. Không thử sai mật khẩu hoặc đổi mật khẩu.

## Chạy và tái hiện

Từ thư mục gốc repo, bật Docker Desktop/Linux containers, có Python 3.11+ trên host và Docker Compose 2.24.4+ (hỗ trợ `!override`). Setup build backend và image Python `greenmart-forecast:qa148` từ source hiện tại để chạy generator SV06. Lần đầu cần mạng để tải base image/dependency Docker; không cài dependency Python trên host và không sửa source SV06. Root Compose cần file `.env` local như hướng dẫn README; không đưa file đó vào Git.

```powershell
# Kiểm tra runner (mock; không phải bằng chứng API thật)
python -m unittest discover -s tests/api -p test_issue148_demo_accounts.py -v

# Tạo môi trường QA mới: build backend từ source local, seed vào volume riêng
python tests/api/run_issue148_qa.py

# Chạy API thật và đọc DB thật; chọn tên báo cáo mới, không ghi đè báo cáo cũ
python tests/api/issue148_demo_accounts.py --base-url http://127.0.0.1:18080/api/v1 --project greenmart-qa148 --reference-commit 7a7fdadaa9f114d53168a380c655ea355288c7cd --output docs/testing/results/issue-148-2026-11-06.json
```

Setup dùng `docker-compose.yml` và `tests/api/compose.issue148.yml` rõ ràng, không ghép override local của nhóm. Project/volume riêng `greenmart-qa148`, backend cổng 18080, mật khẩu QA ngẫu nhiên chỉ truyền qua môi trường process; không sửa/đọc `.env` bằng script Python, override bỏ `env_file` của backend. Không chạy frontend/forecast trên stack QA. Seed generator được mount chỉ đọc và xuất dữ liệu vào `.local-tools/qa148-data` bị Git bỏ qua. Setup từ chối nếu project/volume QA đã tồn tại, không tự reset.

Runner trả exit 0 khi cả 26 PASS và cleanup thành công, 1 nếu có FAIL, 2 nếu BLOCKED hoặc logout chưa hoàn tất. Report ghi thời gian UTC+7, local commit, GitHub reference commit, project, URL, kết quả từng TC; không ghi password, token, hash hay raw error body.

Stack QA được giữ lại để xem xét. Khi cần dừng riêng stack này:

```powershell
docker compose -p greenmart-qa148 -f docker-compose.yml -f tests/api/compose.issue148.yml stop
```

Không dùng `down -v` hoặc seed lên database đang dùng chung.

## Kết quả

Ngày 06/11/2026, kiểm thử API/DB thật trên project riêng `greenmart-qa148`, backend build từ local commit `842a29fa353b40cf52e7ef5ebe2467406f95bac4`, database PostgreSQL 16.10 được nạp generator SV06: **26 PASS, 0 FAIL, 0 BLOCKED**. Không có cảnh báo cleanup; các phiên test đã logout. Bằng chứng từng TC: [issue-148-2026-11-06.json](results/issue-148-2026-11-06.json).

Kiểm tra lại trước commit ngày 06/11/2026: **26/26 TC API/DB thật PASS**, logout đủ, không có cảnh báo cleanup. Bằng chứng: [issue-148-2026-11-06-recheck.json](results/issue-148-2026-11-06-recheck.json). Đã build thành công image Python QA từ source local. Không seed lại dữ liệu QA khi chạy lại.

Kiểm tra runner riêng: **7/7 unit test PASS**; Python compile thành công. Bao gồm từ chối chi nhánh dư/sai/trùng, dữ liệu branch sai kiểu, lỗi tài khoản cuối phải làm tổng hợp fail, môi trường unavailable không pass, lỗi `/me` không tạo kết quả trùng và không đưa token/hash vào kết quả. Các unit test dùng mock, không được cộng vào 26 TC API/DB thật.

Chưa chạy trên GitHub develop `7a7fdad`, chưa chạy toàn bộ #242 hoặc CI GitHub. Gitleaks CLI chưa có trên host, chưa xác nhận quét gitleaks cục bộ/CI. Không có thay đổi backend/seed/contract/migration. DoD review, CI và merge chưa hoàn tất. Stack QA được giữ chạy, không xóa dữ liệu.
