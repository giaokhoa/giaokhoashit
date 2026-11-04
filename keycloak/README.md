# Keycloak

Thư mục này chỉ giữ login theme của Keycloak. Mọi cấu trúc realm (realm, Organization, client, role, người dùng)
được tạo tay trong Admin Console — repo không giữ mẫu realm, bộ cấp phát hay bộ kiểm chứng bằng code cho Keycloak.

## Mô hình

- Keycloak là một service riêng: chạy image chính thức `quay.io/keycloak/keycloak:26.8.1` (service `keycloak`
  trong `compose.yaml`), dữ kiện của nó nằm trong PostgreSQL riêng (service `kc-db`, volume `keycloak-db-data`).
  Toàn bộ trạng thái và cấu hình của Keycloak sống trong database đó; LMS không bao giờ đọc hay ghi trực tiếp
  database của Keycloak.
- Một realm duy nhất tên `giaokhoa`; mỗi trung tâm là một Organization trong realm. Realm role: `tenant-admin`,
  `tenant-teacher`, `tenant-member`, `platform-admin`. Client: `lms-web` (public, cho ứng dụng web) và `lms-api`
  (confidential), đều gắn client scope `organization` dạng Default.
- LMS tích hợp với Keycloak chỉ qua giao thức chuẩn: OIDC để đăng nhập và lấy token, Admin API khi cần thao tác
  quản trị. Cấu hình Keycloak không khoá vào code của LMS.

## Tạo cấu trúc cho môi trường dev

1. Dựng stack trong `compose.yaml` (hai service `kc-db` và `keycloak`) trên database trống; lần khởi động đầu
   tiên Keycloak tạo tài khoản quản trị bootstrap từ biến môi trường của stack.
2. Đăng nhập Admin Console bằng tài khoản quản trị và tạo tay: realm `giaokhoa` (bật Organizations), bốn realm
   role ở trên, hai client ở trên kèm client scope `organization`, và một Organization cho mỗi trung tâm.
3. Gán login theme `giaokhoa` cho realm tại mục Themes của Admin Console.
4. Các tuỳ chọn vận hành (MFA, chính sách mật khẩu, thời hạn token, brute force, định tuyến domain/IdP) để mặc
   định của Keycloak; bật hay tắt là quyết định lúc vận hành của từng môi trường, không ghi vào repo.

Cách dựng realm cho production được quyết tại thời điểm deploy (tạo tay như dev, hoặc export/import realm bằng
chính cơ chế của Keycloak); repo không giữ script cấp phát cho việc này.

## Login theme

`themes/giaokhoa/` là login theme tên `giaokhoa`, dựng cho các trang của luồng đăng nhập: trang đăng nhập một
bước (`login.ftl`) và hai trang bước của luồng Organization Identity-First Login (`login-username.ftl`,
`login-password.ftl`), cùng trang đặt lại mật khẩu. Gói thông điệp tiếng Việt và tiếng Anh nằm tại
`login/messages/`; CSS đã biên dịch từ Tailwind nằm tại `login/resources/css/`, nguồn Tailwind là
`themes/giaokhoa/tailwind.input.css`.

Theme được gắn vào container qua volume `./keycloak/themes` → `/opt/keycloak/themes` (chỉ đọc) trong
`compose.yaml`; việc chọn theme cho realm thực hiện tại mục Themes của Admin Console.
