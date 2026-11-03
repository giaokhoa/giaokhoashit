# Danh sách xoá xác thực cũ

Danh sách các thành phần xác thực cũ phải xoá khi hợp đồng Keycloak
(`docs/keycloak-auth-contract.md`) được triển khai, kèm chủ sở hữu
xoá theo bảng phụ thuộc của chương trình Keycloak. Tài liệu này
chỉ lập danh sách và phân công — **việc hợp đồng không tự xoá
runtime nào**.

## Nguyên tắc xoá

1. **Không bảo toàn** endpoint, phiên hay credential của auth cũ:
   khi một thành phần bị xoá, đường đăng nhập/phiên/token của nó
   ngừng tồn tại, không giữ tương thích cho client cũ.
2. **Không dựng lớp tương thích hay đường lùi** về auth cũ (không
   fallback kép, không cờ bật lại).
3. Chỉ giữ **dữ liệu và quy tắc nghiệp vụ còn cần** (user nghiệp
   vụ, phân công, quan hệ, điểm, nội dung…) — dữ liệu kỹ thuật của
   auth cũ (phiên, token, credential, bảng OAuth tự quản) không
   thuộc diện giữ.
4. Chủ sở hữu xoá là việc được ghi ở cột "Chủ sở hữu xoá"; việc đó
   chỉ xoá sau khi phần thay thế của nó đã nghiệm thu, theo thứ tự
   phụ thuộc đã chốt.

## Danh sách

| # | Thành phần phải xoá | Vị trí hiện tại (dẫn chứng) | Thay thế bởi | Chủ sở hữu xoá |
| --- | --- | --- | --- | --- |
| 1 | **Better Auth** — instance, mount, service, cầu credential, gate MFA, hook khoá tài khoản, service hoàn tất 2FA; guard phiên Better Auth; client phía web; các bảng OAuth của Better Auth trong schema | `apps/api/src/modules/auth/better-auth/` (`better-auth.instance.ts`, `better-auth.mount.ts`, `better-auth.service.ts`, `credential-bridge.service.ts`, `mfa-gate.ts`, `lockout.hooks.ts`, `two-factor-completion.service.ts`), `apps/api/src/common/better-auth-session.guard.ts`, `apps/web/src/lib/auth-client.ts`, ngoại lệ bảng OAuth của Better Auth ghi ở `packages/db/README.md` | Đăng nhập web qua Keycloak bằng phiên OIDC phía server (BFF) và verifier Keycloak tại API | **#14** — sau khi #9 (web/BFF) và #13 (thu hồi) nghiệm thu |
| 2 | **JWT HS256/refresh tự phát** — phát/kiểm JWT đăng nhập bằng `JwtService`/`passport-jwt` với bí mật riêng, service phiên/refresh tự quản, strategy và guard JWT | `apps/api/src/modules/auth/auth.service.ts`, `apps/api/src/modules/auth/session.service.ts`, `apps/api/src/modules/auth/strategies.ts`, `apps/api/src/modules/auth/oauth.service.ts` (phần phát JWT nội bộ), `apps/api/src/common/jwt-auth.guard.ts`, dependency `@nestjs/jwt`/`passport-jwt` | Token do Keycloak phát hành; API chỉ kiểm token theo issuer đã đăng ký | **#14** — sau khi #7 (verifier theo Host) và #9 (BFF) nghiệm thu |
| 3 | **Cầu MCP xác thực** — cầu nhận JWT HS256 nội bộ làm token MCP và verifier OAuth trỏ vào AS Better Auth tự quản | `apps/api/src/modules/mcp/mcp-bridge-auth.ts` (`verifyBridgeToken` kiểm HMAC HS256 bằng `JWT_SECRET`), `apps/api/src/modules/mcp/mcp-oauth-verifier.ts` (JWKS của AS tự quản), `MCP_AUTH_ISSUER` mặc định trỏ chính origin của LMS | OAuth AS là Keycloak của realm trung tâm; verifier kiểm issuer/JWKS của Keycloak | **#12** thay thế khi tích hợp OAuth Keycloak cho MCP; **#14** dọn phần còn lại nếu còn sót |
| 4 | **MFA/lockout/reset/mật khẩu/mail xác thực tự viết** — TOTP tự viết, khoá đăng nhập tự viết, hoàn tất 2FA tự viết, plugin email OTP, các luồng đặt lại mật khẩu/quản lý mật khẩu phía LMS | `apps/api/src/modules/auth/totp.service.ts`, `apps/api/src/modules/auth/login-lockout.service.ts`, `apps/api/src/modules/auth/better-auth/lockout.hooks.ts`, `apps/api/src/modules/auth/better-auth/two-factor-completion.service.ts`, `apps/api/src/modules/auth/better-auth/mfa-gate.ts`, `apps/api/src/modules/mail/email-otp.plugin.ts`, các luồng mật khẩu trong `auth.service.ts`/`better-auth.service.ts` | Keycloak nguyên bản (MFA, chính sách khoá, đặt lại mật khẩu, thư xác thực do Keycloak gửi); mẫu realm cấu hình ở việc cấp realm | **#14** — phần cấu hình Keycloak tương ứng thuộc #6 |
| 5 | **Cờ `AUTH_CUTOVER_STAGE`** và toàn bộ máy móc cửa sổ bake/diễn tập rollback của đợt chuyển auth cũ | `apps/api/src/config/env.ts`, `apps/api/src/modules/auth/auth.controller.ts`, `apps/api/src/modules/auth/auth.service.ts`, `apps/api/test/e2e/auth/better-auth-cutover-flow.e2e-spec.ts` | Không có thay thế: hợp đồng Keycloak không dùng cờ cutover hai hệ; chuyển đổi đi theo thứ tự phụ thuộc của chương trình | **#14** |

## Ghi chú phạm vi

- Mục 1 và 2 chồng nhau ở tầng phiên web (cookie phiên Better Auth
  là đường đăng nhập web hiện tại): #14 xoá cả hai trong cùng đợt
  nghiệm thu xoá auth cũ, sau khi BFF OIDC đã là đường đăng nhập
  duy nhất còn hoạt động.
- Realtime (socket) và worker hiện xác thực/gác theo danh tính đã
  được API thiết lập; thay đổi cơ chế thu hồi phiên thuộc việc thu
  hồi thống nhất (#13), không thuộc danh sách xoá này.
- Xoá một thành phần gồm đủ: mã, endpoint, cấu hình/biến môi
  trường, dependency và dữ liệu kỹ thuật của nó; thiếu một trong
  các lớp đó thì thành phần chưa được coi là đã xoá.
