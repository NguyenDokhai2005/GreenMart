# Backend – Spring Boot

Java 21 · Spring Boot 3 · Maven · PostgreSQL · Redis · RabbitMQ · Flyway.
Kiến trúc modular monolith: mỗi module là một package trong `vn.greenmart`, chỉ gọi nhau qua interface service hoặc sự kiện.

| Thư mục | Nội dung |
| --- | --- |
| `src/main/java/vn/greenmart/<module>` | Mỗi module có `api` (controller, DTO), `service`, `domain` (entity), `repository` |
| `src/main/resources/db/migration` | Migration Flyway: `V1__init.sql`, `V2__...` (SV03 quản lý) |
| `src/test/java/vn/greenmart` | Unit test và integration test (Testcontainers) |

Khung dự án (pom.xml, Application.java) do SV07 khởi tạo trong tuần 1.
