# Forecast service – Python

Python 3.11 · FastAPI · pandas · LightGBM. Phụ trách: SV06.

| Thư mục | Nội dung |
| --- | --- |
| `app/api` | Endpoint `POST /forecast/run`, `GET /forecast/runs/{id}` |
| `app/pipelines` | Tổng hợp `daily_sales`, tạo đặc trưng, huấn luyện, dự báo |
| `app/models` | Baseline (naive theo tuần, trung bình trượt 28 ngày) và LightGBM |
| `app/db` | Kết nối PostgreSQL (tài khoản chỉ đọc + ghi 3 bảng của dịch vụ) |
| `scripts` | Script sinh dữ liệu: 3 chi nhánh, 1.000 sản phẩm, 10.000 đơn |
| `notebooks` | Khám phá dữ liệu, đánh giá MAE/WAPE |
| `tests` | Unit test pipeline và hàm tính sai số |

Quy tắc dữ liệu: xem mục 7 SRS và tin nhắn thống nhất `daily_sales`.

## Runtime Docker Compose (#131)

```powershell
docker compose config --quiet
docker compose up --build -d --wait forecast-service
docker compose ps forecast-service
docker compose exec forecast-service python -c "import urllib.request; print(urllib.request.urlopen('http://localhost:8000/healthz').read().decode())"
```

Service dùng cổng 8000 trong mạng Docker, không mở cổng DB/API dự báo ra ngoài.
Chờ PostgreSQL và backend healthy để Flyway tạo bảng/cấp quyền xong trước khi kiểm
readiness. Chỉ nhận `FORECAST_DB_USER`/`FORECAST_DB_PASSWORD`, không nhận mật khẩu
tài khoản owner của backend. Role được tạo bởi init script PostgreSQL; V2 cấp ghi
3 bảng `daily_sales`, `forecast_run`, `forecast_result`, các bảng nghiệp vụ chỉ SELECT.

`/healthz` kết nối DB thật, kiểm role đúng và không phải superuser, đọc được dữ liệu
nghiệp vụ, ghi được 3 bảng dự báo, không được ghi bảng nghiệp vụ. Lỗi trả 503/DOWN
không lộ mật khẩu/DSN. `/livez` chỉ kiểm tiến trình. Sai mật khẩu, thiếu migration hoặc
vô tình cấp quyền quá rộng đều làm container unhealthy. Không tự sửa quyền trong app.

Đây là entry point FastAPI và health cho #131. SV06 bổ sung pipeline/API
`/forecast/run` ở #114 và scheduler/sự kiện ở #118; chưa có dự báo thì không trả kết
quả giả. Giữ `app.main:app` hoặc cập nhật Dockerfile cùng PR khi tích hợp pipeline.

```powershell
python -m pip install -r forecast-service/requirements.txt
python -m pytest forecast-service/tests
docker build -t greenmart-forecast:local forecast-service
```

Nếu volume PostgreSQL đã có từ trước, init script không chạy lại khi đổi `.env`.
Phối hợp SV03/SV07 để cập nhật role/mật khẩu có kiểm soát; không xóa volume dữ liệu
đang dùng để sửa lỗi healthcheck.
