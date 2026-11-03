# apps/server — Backend Spring Boot

Ứng dụng backend mới của LMS viết bằng Spring Boot, là hướng thay thế dài hạn cho `apps/api`. Lát cắt đầu tiên này chỉ
dựng khung chạy được và tích hợp kiểm chứng token với Keycloak: chưa có nghiệp vụ, chưa dùng Authorization Services —
các phần đó thuộc các lát cắt tiếp theo.

## Công nghệ

- Spring Boot 4.1.2 (bản ổn định hiện hành theo tài liệu Spring Boot), Spring Security 7.1.2 (OAuth2 Resource
  Server).
- Java 25 (LTS): dự án yêu cầu JDK 25 — `pom.xml` khai báo `java.version` 25 và biên dịch với `--release 25`. Mức
  tối thiểu Java 17 là yêu cầu của riêng Spring Boot 4.1.2 đối với ứng dụng dùng nó, không phải mức của dự án này;
  JDK 17 không đủ để biên dịch dự án.
- Build bằng Maven qua wrapper `./mvnw` đi kèm trong thư mục này (không cần cài Maven riêng). Tài liệu Spring Boot hỗ
  trợ cả Maven và Gradle; Maven được chọn vì wrapper gọn, không cần daemon và khớp thói quen build một module đơn.
- Dự án được khởi tạo bằng Spring Initializr với các dependency: webmvc, security, security-oauth2-resource-server,
  actuator — tên starter theo đúng thế hệ Spring Boot 4.
- Tầng dữ liệu dùng Hibernate/JPA qua `spring-boot-starter-data-jpa` (Spring Data JPA) trên PostgreSQL. Schema do
  Flyway quản lý bằng các script trong `src/main/resources/db/migration`; Hibernate không tự tạo hay sửa schema
  (`spring.jpa.hibernate.ddl-auto=none`). Tầng dữ liệu không dùng jOOQ.

## Cấu hình

Ứng dụng là một resource server thuần: nó không đăng nhập thay người dùng, chỉ kiểm chứng JWT do Keycloak phát hành.

| Biến môi trường  | Bắt buộc | Ý nghĩa                                                                 |
| ---------------- | -------- | ----------------------------------------------------------------------- |
| `OIDC_ISSUER_URI` | Có       | Issuer của realm, dạng `<địa chỉ Keycloak>/realms/giaokhoa`. Ứng dụng   |
|                  |          | tự khám phá JWKS từ tài liệu `.well-known/openid-configuration` của     |
|                  |          | issuer này và kiểm chứng chữ ký, claim `iss` và hạn của mọi JWT. Thiếu  |
|                  |          | biến này ứng dụng không khởi động — cố ý không ghi sẵn địa chỉ của bất  |
|                  |          | kỳ môi trường nào trong file cấu hình.                                  |
| `SERVER_PORT`    | Không    | Cổng HTTP, mặc định 8080.                                               |
| `SPRING_DATASOURCE_URL`      | Có       | JDBC URL của PostgreSQL của ứng dụng, dạng                              |
|                              |          | `jdbc:postgresql://<host>:<cổng>/<database>`. Spring Boot tự cấu hình   |
|                              |          | datasource và Flyway từ ba biến `SPRING_DATASOURCE_*` theo cơ chế       |
|                              |          | relaxed binding; không ghi sẵn địa chỉ của bất kỳ môi trường nào.       |
| `SPRING_DATASOURCE_USERNAME` | Có       | Tên đăng nhập database của ứng dụng.                                    |
| `SPRING_DATASOURCE_PASSWORD` | Có       | Mật khẩu database của ứng dụng.                                         |

## Chạy tại local

Điều kiện: JDK 25, một Keycloak 26.8.1 có realm `giaokhoa` đang chạy và một PostgreSQL trống cho ứng dụng (Flyway
tự tạo schema khi khởi động).

```bash
export OIDC_ISSUER_URI=http://localhost:8180/realms/giaokhoa
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/lms
export SPRING_DATASOURCE_USERNAME=<tên đăng nhập database>
export SPRING_DATASOURCE_PASSWORD=<mật khẩu database>
./mvnw spring-boot:run
```

## Endpoint

| Endpoint                        | Quyền truy cập   | Mô tả                                                                 |
| ------------------------------- | ---------------- | --------------------------------------------------------------------- |
| `GET /actuator/health`          | Mở               | Tình trạng ứng dụng theo Spring Boot Actuator.                         |
| `GET /actuator/health/liveness` | Mở               | Nhóm health liveness cho hạ tầng thăm dò.                              |
| `GET /actuator/health/readiness`| Mở               | Nhóm health readiness cho hạ tầng thăm dò.                             |
| `GET /api/ping`                 | JWT hợp lệ       | Endpoint mẫu chứng minh kiểm chứng token: trả `status` và `subject`    |
|                                 |                  | lấy từ JWT. Thiếu token, token sai chữ ký, sai issuer hoặc hết hạn     |
|                                 |                  | đều bị trả 401.                                                        |
| `GET /api/profile`              | JWT hợp lệ       | Bản ghi liên kết danh tính của chính người gọi: `sub` lấy từ JWT đã     |
|                                 |                  | kiểm chứng là khoá tra cứu duy nhất; lần gọi đầu của một `sub` chưa có  |
|                                 |                  | bản ghi thì tự tạo. Trả `id`, `keycloakSub`, `createdAt`, `updatedAt`.  |

## Test

```bash
./mvnw test
```

Bộ test `ProtectedEndpointTests` chạy toàn bộ chuỗi lọc bảo mật thật của ứng dụng qua MockMvc với các ca: health mở
không cần token; thiếu token trả 401; token hợp lệ trả 200 kèm `subject`; token sai chữ ký, sai issuer và hết hạn đều
trả 401. Phần duy nhất được thay bằng fixture là bước tải JWKS qua mạng: test cấp một `NimbusJwtDecoder` dùng khoá RSA
tĩnh tự sinh kèm validator issuer và hạn token giống hệt cấu hình chạy thật, nên chữ ký, issuer và thời hạn vẫn được
kiểm chứng thật mà test không cần Keycloak đang chạy và không cần Docker (môi trường dev hiện tại không có Docker nên
không dùng Testcontainers).

Bộ test `ProfileEndpointTests` chạy cùng chuỗi lọc bảo mật đó cộng tầng dữ liệu thật (Flyway áp migration lên H2 ở
chế độ PostgreSQL, Hibernate/JPA truy vấn) với các ca: thiếu token trả 401; lần gọi đầu tự tạo bản ghi liên kết theo
`sub` của token và lần gọi sau trả đúng cùng một bản ghi; người dùng thứ hai có bản ghi riêng và không thấy bản ghi
của người dùng trước.

## Kiểm chứng trực tiếp với Keycloak thật

Ngoài bộ test tự động, hành vi trên đường chạy thật đã được kiểm chứng với một Keycloak 26.8.1 chạy tại local (bản phân
phối chính thức, chế độ `start-dev`, cổng riêng 8180, dữ liệu riêng — không dùng chung với Keycloak dev đang chạy):

1. Tạo realm `giaokhoa`, một client public bật Direct Access Grants và một người dùng thử qua Admin REST API.
2. Lấy access token thật của người dùng thử từ endpoint token của realm (issuer của token là
   `http://localhost:8180/realms/giaokhoa`).
3. Khởi động `apps/server` từ jar đã đóng gói và gọi các endpoint bằng token thật ở bước 2.

Kết quả: `GET /actuator/health` và `GET /actuator/health/readiness` trả 200, trạng thái UP kể cả khi không có token;
`GET /api/ping` không kèm token trả 401; kèm access token thật trả 200 kèm đúng `subject` của người dùng thử; token rác
và token bị sửa chữ ký đều trả 401.

Một điểm riêng của môi trường kiểm chứng này cần ghi rõ: sandbox chạy ứng dụng chặn mọi kết nối TCP ra ngoài của JVM
(kể cả tới localhost), nên bước tải JWKS qua HTTP từ `OIDC_ISSUER_URI` không thực hiện được tại đây — lượt chạy với
cấu hình issuer thuần vẫn mở health bình thường nhưng trả 401 cho mọi token kèm lỗi khởi tạo decoder trong log, đúng
như kỳ vọng khi không lấy được khoá. Vì vậy lượt kiểm chứng token thật cấp khoá công khai của chính realm đó (lấy từ
endpoint `certs` của realm) qua thuộc tính có tài liệu của Spring Boot là
`spring.security.oauth2.resourceserver.jwt.public-key-location`, thay đúng một khâu tải khoá qua mạng; chữ ký và hạn
token vẫn được kiểm chứng thật bằng khoá thật của realm. Kiểm chứng claim `iss` nằm trong bộ test tự động ở trên (ca
sai issuer trả 401, với validator issuer giống hệt cấu hình issuer). Ở môi trường chạy bình thường, cấu hình cam kết
chỉ dùng `OIDC_ISSUER_URI` và ứng dụng tự khám phá JWKS theo tài liệu — không có cấu hình khoá tĩnh nào được ghi vào
repo.

## Kiểm chứng khung liên kết danh tính trên đường chạy thật

Khung liên kết danh tính (`app_user`, migration `V1`) đã được kiểm chứng trên đường chạy thật tại local: ứng dụng
đóng gói dạng jar chạy với PostgreSQL 16 thật (database trống, Flyway tự áp `V1` khi khởi động) và Keycloak 26.8.1
nói trên với hai người dùng thử thật. Kết quả: gọi `GET /api/profile` không kèm token trả 401; người dùng thứ nhất
gọi lần đầu được tạo bản ghi với `keycloakSub` đúng `sub` của mình và gọi lần hai trả đúng cùng `id`; người dùng thứ
hai có bản ghi riêng với `id` khác, phản hồi không chứa dữ kiện của người dùng thứ nhất; hai hàng tương ứng xuất
hiện trong bảng `app_user`. Sau khi vô hiệu hoá người dùng thứ hai trong Keycloak, cấp token mới cho tài khoản đó bị
từ chối với lỗi `invalid_grant` ("Account disabled").

Vì sandbox chặn TCP của JVM (cùng lý do ở mục trên), lượt chạy này cấp khoá công khai của realm qua
`public-key-location` như đã mô tả và nối JDBC tới PostgreSQL qua Unix socket bằng thư viện junixsocket đặt trên
classpath lúc khởi động — cả hai đều là cấu hình của riêng lượt chạy kiểm chứng, không có trong repo; ở môi trường
chạy bình thường ứng dụng dùng `OIDC_ISSUER_URI` và JDBC qua TCP như phần Cấu hình đã ghi.

## Nguồn tài liệu đã theo

- Spring Boot — System Requirements (bản 4.1, khớp Spring Boot 4.1.2 của dự án; yêu cầu Java, Maven/Gradle):
  https://docs.spring.io/spring-boot/4.1/system-requirements.html
- Spring Security 7.1.2 — OAuth 2.0 Resource Server JWT (hai bước cấu hình tối thiểu: dependency và `issuer-uri`;
  cơ chế khám phá JWKS và kiểm chứng `iss`, `exp`, `nbf`):
  https://docs.spring.io/spring-security/reference/7.1/servlet/oauth2/resource-server/jwt.html
- Spring Boot Reference 4.1 — Spring Security (cách Spring Boot tích hợp Spring Security):
  https://docs.spring.io/spring-boot/4.1/reference/web/spring-security.html
- Spring Boot Reference 4.1 — Actuator, Endpoints (mục Health: các nhóm liveness/readiness và thuộc tính
  `management.endpoint.health.probes.enabled`):
  https://docs.spring.io/spring-boot/4.1/reference/actuator/endpoints.html
- Spring Boot Reference 4.1 — SQL Databases (cách dùng `spring-boot-starter-data-jpa`, Flyway chạy migration khi
  khởi động và `spring.jpa.hibernate.ddl-auto` khi schema do công cụ migration quản lý):
  https://docs.spring.io/spring-boot/4.1/reference/data/sql.html
- Keycloak 26.8.1 — Securing Applications and Services, mục Planning for securing applications and services
  (nguyên tắc: ưu tiên hỗ trợ OIDC/OAuth2 có sẵn trong hệ sinh thái của ứng dụng, client adapter của Keycloak chỉ
  là lựa chọn cuối):
  https://www.keycloak.org/securing-apps/overview
