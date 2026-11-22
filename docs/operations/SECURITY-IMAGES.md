# Quét phụ thuộc và image phiên bản (#140)

Workflow security-images.yml build bốn image backend/frontend/forecast-service/mock-connector bằng tag <project-version>-sha-<commit> (version lấy từ backend/pom.xml). Khi push Git tag v*, gắn thêm tag phiên bản tương ứng. Trivy 0.67.2 quét phụ thuộc trong từng thư mục và thư viện/hệ điều hành trong image. Bất kỳ Critical nào hoặc lỗi công cụ đều làm job thất bại. Không dùng ignore-unfixed hoặc bỏ qua CVE toàn cục. Báo cáo JSON được đính kèm ở artifact security-<service>.

Khi phát hiện Critical, xác định gói và bản sửa trong báo cáo, cập nhật phụ thuộc/base image, build và quét lại. Không đánh dấu đạt khi quét chưa hoàn tất hoặc chỉ có kết quả từ một image. Tag sha định danh code; digest định danh image thực tế. Triển khai IMAGE_TAG theo bản đã qua checks. Không dùng tag dev làm bằng chứng bản phát hành.

```bash
docker build -t greenmart-backend:sha-<commit> backend
docker save greenmart-backend:sha-<commit> -o image.tar
docker run --rm -v "$PWD:/work" aquasec/trivy:0.67.2 image --input /work/image.tar --scanners vuln --severity CRITICAL --exit-code 1
```

Lệnh volume trên dùng shell Linux/CI. Trên Windows dùng --mount type=bind,source=<absolute-directory>,target=/work.

## Nâng Spring Boot 4

Backend chuyển từ Boot 3.5.7 sang 4.0.9 để xử lý Critical trong Spring MVC và các dependency được quản lý. Cập nhật starter Flyway, RestClient và các test starter/package tương ứng. Dùng spring-boot-jackson2 và preferred-json-mapper=jackson2 để giữ hợp đồng JSON hiện có; module này là cầu nối tương thích có trạng thái deprecated trong Boot 4. Các bài test API, JPA, token và RabbitMQ phải chạy lại trên bản mới.

Tài liệu chính thức: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide và https://docs.spring.io/spring-boot/reference/features/json.html.
