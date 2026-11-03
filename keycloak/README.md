# Mẫu realm Keycloak và bộ cấp phát

Thư mục này giữ mẫu cấu hình Keycloak có phiên bản trong repo và
bộ cấp phát áp mẫu đó lên một bản Keycloak **26.8.1** qua Admin
REST API. Mục tiêu: một bản Keycloak trống có thể được dựng lại
nguyên trạng — realm tenant, realm nền tảng `giaokhoa`, realm
`master` — chỉ từ repo này cộng biến môi trường bí mật của lần
chạy; chạy lại bộ cấp phát không tạo trùng và không ghi đè bí
mật đã cấp.

Phạm vi không gồm: SPI/JAR tuỳ biến hay authenticator tự viết,
Authorization Services của Keycloak (giai đoạn này quyết quyền
nằm ở API theo hợp đồng trong `docs/keycloak-auth-contract.md`),
triển khai production, và việc biến `tenant-admin` thành quản
trị viên realm.

## Cấu trúc thư mục

| Đường dẫn | Vai trò |
| --- | --- |
| `templates/tenant-realm.json` | Mẫu realm cho một tenant: vai, client, scope, mapper, flow MFA/LoA, IdP broker, chính sách brute force và thời lượng phiên của tenant. |
| `templates/platform-realm.json` | Mẫu realm `giaokhoa` cho quản trị nền tảng: client `platform-web`/`platform-api`, client role `platform-admin`, MFA vô điều kiện, thời lượng phiên ngắn hơn tenant. |
| `provision.mjs` | Bộ cấp phát (Node, chỉ dùng thư viện chuẩn): render mẫu, tạo realm hoặc hoà giải realm đã có, áp lớp phủ lên realm `master`. |
| `provision.config.example.json` | Mẫu file cấu hình cấp phát: danh sách tenant và realm nền tảng của một bản cài. File cấu hình thật không chứa bí mật và không bắt buộc commit. |
| `verify/verify.mjs` | Bộ kiểm chứng trên Keycloak thật: cấp phát từ môi trường trống, đối chiếu token/flow/phiên/khoá ký theo từng tiêu chí nghiệm thu của issue. |
| `verify/compose.verify.yaml` | Stack Docker độc lập (PostgreSQL + Keycloak 26.8.1) chỉ phục vụ kiểm chứng; thông tin đăng nhập trong file là giá trị dùng một lần cho môi trường kiểm chứng. |
| `verify/provision.config.verify.json` | Cấu hình cấp phát cho stack kiểm chứng: hai tenant `tenant-a`, `tenant-b` và realm `giaokhoa`. |

## Cách chạy bộ cấp phát

```bash
node keycloak/provision.mjs --config <file-cấu-hình-cấp-phát>
```

- `--dry-run`: chỉ render mẫu và in kế hoạch, không gọi API.
- Địa chỉ Keycloak lấy từ `KC_BASE_URL`, hoặc `authBaseUrl`
  trong file cấu hình khi biến không đặt.
- Tài khoản gọi Admin REST API lấy từ `KC_ADMIN_USERNAME` /
  `KC_ADMIN_PASSWORD`: tài khoản vận hành thuộc realm `master`,
  tách khỏi tài khoản quản trị nền tảng và tài khoản tenant.
- Mã thoát khác 0 khi có bất kỳ bước nào lỗi; log tiếng Việt,
  không bao giờ in giá trị bí mật.

### File cấu hình cấp phát

```json
{
  "authBaseUrl": "https://auth.fuckr.net",
  "platform": { "realm": "giaokhoa", "host": "admin.example.edu.vn" },
  "tenants": [
    {
      "realm": "tenant-demo",
      "host": "demo.example.edu.vn",
      "mcpResourceUrl": "https://demo.example.edu.vn/mcp",
      "mcpRedirectUris": ["http://127.0.0.1:8765/callback"]
    }
  ]
}
```

Mỗi tenant một realm riêng. `host` là hostname của tenant (ứng
dụng web của tenant chạy sau BFF tại host này); `mcpResourceUrl`
là URL của MCP server của tenant, được ghi thành thuộc tính
`resource_url` trên client `lms-mcp` (xem mục Resource
indicators); `mcpRedirectUris` là danh sách redirect URI cho
client MCP đăng ký trước của tenant.

### Biến môi trường bí mật

| Biến | Dùng cho |
| --- | --- |
| `LMS_WEB_SECRET__<MÃ_REALM>` | Bí mật client `lms-web` của realm tenant. `<MÃ_REALM>` là tên realm viết hoa, ký tự không phải chữ/số đổi thành `_` (ví dụ realm `tenant-a` → `LMS_WEB_SECRET__TENANT_A`). |
| `PLATFORM_WEB_SECRET` | Bí mật client `platform-web` của realm `giaokhoa`. |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | IdP Google cho broker của mọi realm tenant. |
| `FACEBOOK_CLIENT_ID` / `FACEBOOK_CLIENT_SECRET` | IdP Facebook cho broker của mọi realm tenant. |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_FROM`, `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_STARTTLS`, `SMTP_SSL` | Cấu hình SMTP của realm; chỉ áp khi có cả `SMTP_HOST` và `SMTP_FROM`. |
| `KC_MASTER_OPERATOR_USERNAME` | Tên tài khoản vận hành thuộc realm `master` cần gắn yêu cầu khai báo TOTP (xem mục realm `master`). |

Quy tắc bí mật: bí mật của client chỉ được đặt **khi tạo
client** và chỉ từ biến môi trường trên; hoà giải các lần sau
không chạm vào bí mật đã có (trường bí mật bị loại khỏi mọi lần
ghi đè khi biến tương ứng không được cung cấp). Thiếu thông tin
đăng nhập của một IdP thì IdP đó bị loại khỏi bản cấp phát kèm
dòng log, thay vì được tạo bằng giá trị rỗng.

## Mô hình hoà giải khi chạy lại

Realm chưa tồn tại: bộ cấp phát POST toàn bộ bản render của mẫu
lên `/admin/realms` — realm ra đời với đủ vai, client, scope,
mapper, flow và IdP trong một lần tạo.

Realm đã tồn tại: bộ cấp phát hoà giải từng nhóm, không xoá dữ
kiện vận hành (người dùng, phiên, bí mật):

1. Các trường vô hướng của realm (thời lượng token/phiên,
   brute force, cờ sự kiện, flow trình duyệt mặc định, tập
   scope mặc định) được phủ từ mẫu lên bản hiện tại rồi PUT.
2. Required action được tạo hoặc cập nhật theo alias.
3. Vai realm: tạo nếu thiếu; nếu một vai chính bị đặt thành
   composite thì gỡ composite — không vai chính nào được gộp
   vai chính khác.
4. Vai mặc định của realm (`default-roles-<realm>`) bị gỡ
   `offline_access` nếu có (offline access tắt theo mẫu).
5. Client scope: tạo nếu thiếu, PUT theo mẫu nếu đã có.
6. Client: tạo nếu thiếu; nếu đã có thì phủ các trường của mẫu
   lên bản hiện tại rồi PUT, loại trường bí mật trừ khi biến
   môi trường tương ứng cung cấp giá trị (đặt lại bí mật là
   hành động có chủ đích của vận hành, không phải hệ quả của
   chạy lại).
7. Gán client scope cho từng client được hoà giải về đúng tập
   của mẫu (cả tập default lẫn optional).
8. Client role (ví dụ `platform-admin`) được tạo nếu thiếu.
9. Ánh xạ vai lên client scope `roles` được bổ sung nếu thiếu
   (xem mục Mapper và vai trong token).
10. IdP: tạo hoặc PUT theo mẫu khi có thông tin đăng nhập;
    bỏ qua khi thiếu.
11. **Flow xác thực chỉ được kiểm chứng, không tự viết lại**:
    bộ cấp phát đối chiếu chữ ký của từng flow trong mẫu
    (authenticator, requirement, alias và nội dung cấu hình)
    với flow đang có; lệch hoặc thiếu flow là lỗi lớn kèm
    hướng dẫn xử lý tay. Lý do: trên Keycloak 26.8.1, API ghi
    hàng loạt executions của flow trả lỗi 400 (đã kiểm chứng
    trên bản thật), nên tự ý viết lại flow đang chạy có rủi ro
    phá vỡ đăng nhập; flow đúng mẫu được tạo ở lần import đầu
    tiên, và thay đổi flow là thay đổi có chủ đích phải đi qua
    kiểm chứng đăng nhập đầy đủ.

## Mẫu realm tenant

- **Vai chính**: đúng ba realm role `tenant-admin`,
  `tenant-teacher`, `tenant-member`, không vai nào là
  composite; không có vai theo khoá học/lớp. `tenant-admin`
  không mang quyền quản trị realm.
- **Client**:
  - `lms-web` (confidential): client của BFF. Chỉ bật
    authorization code flow; tắt implicit, direct access
    grants và service account; bắt buộc PKCE S256; redirect
    URI duy nhất `https://<host>/api/auth/callback/keycloak`;
    post-logout redirect qua thuộc tính
    `post.logout.redirect.uris`; Full Scope Allowed tắt.
  - `lms-api` (bearer-only): resource server của API.
  - `lms-mcp` (bearer-only): resource server của MCP server,
    mang thuộc tính `resource_url` của tenant.
  - `lms-mcp-client` (public): mẫu client MCP đăng ký trước
    cho các MCP client đã biết; bắt buộc PKCE S256; redirect
    URI lấy từ `mcpRedirectUris` của file cấu hình.
- **Client scope và mapper**: scope `lms-api-audience` chứa
  audience mapper ghi `aud=lms-api` **chỉ vào access token**
  (không vào ID token, không liệt kê trong claim `scope`) và
  được gán default cho `lms-web`. Bốn scope nghiệp vụ MCP giữ
  nguyên theo hợp đồng: `lms.questions.read`,
  `lms.questions.draft`, `lms.questions.write`,
  `lms.banks.contribute` — gán optional cho `lms-mcp-client`.
  Scope `lms-mcp-audience` được mô tả ở mục Resource
  indicators. Không claim nghiệp vụ nào (khoá học, lớp, con,
  trạng thái bài học) được đưa vào token.
- **Mapper và vai trong token**: vì Full Scope Allowed tắt ở
  mọi client, một vai chỉ xuất hiện trong token khi nó được
  ánh xạ vào một client scope mà client mang. Bộ cấp phát ánh
  xạ đúng ba vai chính vào client scope `roles` (scope mặc
  định của `lms-web`) — đây là toàn bộ tập vai realm được phép
  vào token, không hơn.
- **Đăng ký và mặc định**: tự đăng ký tắt; Remember Me tắt;
  realm không bật Organizations và không bật Authorization
  Services/User-Managed Access.
- **Thời lượng** (giá trị khởi điểm): access token 5 phút;
  SSO idle 60 phút, SSO max 12 giờ; client session idle
  30 phút, client session max 8 giờ. Refresh token bật
  rotation và phát hiện tái dùng (`refreshTokenMaxReuse=0`):
  tái dùng một refresh token đã xoay sẽ thu hồi cả phiên.
- **Brute force**: khoá tạm sau 5 lần sai; thời gian chờ tăng
  tuyến tính 60 giây mỗi lần sai thêm, trần 15 phút; cửa sổ
  đếm số lần sai là 12 giờ (`maxDeltaTimeSeconds`).
- **Sự kiện kiểm toán**: sự kiện đăng nhập và sự kiện quản trị
  đều bật; chi tiết sự kiện quản trị tắt
  (`adminEventsDetailsEnabled=false`) để representation của
  đối tượng quản trị không lọt vào log kiểm toán.

## MFA theo vai và step-up LoA

Flow trình duyệt của realm tenant (`lms-browser`) theo pattern
step-up chính thức của Keycloak, với ánh xạ LoA khai trên
client `lms-web`: `loa1` = mức 1, `loa2` = mức 2, mức mặc định
`loa1`.

- Form mật khẩu nằm trong subflow có điều kiện LoA mức 1
  (`lms-loa1-forms`): đăng nhập thường chỉ cần mật khẩu.
- Trong cùng subflow mức 1, hai subflow con có điều kiện theo
  vai (`conditional-user-role`) bắt `auth-otp-form` với vai
  `tenant-admin` và `tenant-teacher`. Hệ quả: admin và giáo
  viên luôn phải có OTP mới đăng nhập xong; tài khoản hai vai
  này chưa khai báo TOTP thì bị required action
  `CONFIGURE_TOTP` chặn giữa chừng và không đăng nhập xong
  được cho tới khi khai báo xong. Member không bị bắt OTP.
- Subflow `lms-stepup-otp` có điều kiện LoA mức 2: khi một
  phiên mức 1 bị yêu cầu nâng lên `loa2` (tham số
  `acr_values=loa2`), người dùng phải nhập OTP và token đạt
  `acr=loa2`.
- Cấu hình LoA luôn khai `loa-max-age` tường minh: nếu thiếu,
  Keycloak 26.8.1 ghi lỗi `Invalid max age configured` và coi
  mức LoA là hết hạn ngay lập tức (đã kiểm chứng trên bản
  thật).

Flow hậu broker (`lms-post-broker-mfa`) gồm hai subflow có
điều kiện theo vai tương tự: đăng nhập qua IdP ngoài xong,
admin/giáo viên vẫn phải qua OTP của realm trước khi phiên
được hoàn tất; kể cả khi tự đăng ký bị tắt, việc tạo tài khoản
qua broker vẫn đi qua flow first broker login mặc định và
flow hậu broker này.

## Broker Google/Facebook

Mỗi realm tenant có hai IdP `google` và `facebook` (chỉ xuất
hiện trong bản cấp phát khi có thông tin đăng nhập qua biến
môi trường):

- Callback của nhà cung cấp là endpoint broker của chính
  realm: `<authBaseUrl>/realms/<realm>/broker/google/endpoint`
  và `.../broker/facebook/endpoint` — đây là redirect URI
  phải đăng ký phía Google/Facebook cho từng realm.
- `storeToken=false`: không lưu token của nhà cung cấp ngoài.
- `trustEmail=false` và không bật tự liên kết theo email:
  email trùng từ IdP ngoài không tự gắn vào tài khoản sẵn có.
- `firstBrokerLoginFlowAlias` giữ flow first broker login mặc
  định của Keycloak; `postBrokerLoginFlowAlias` trỏ về
  `lms-post-broker-mfa` (trường này là trường của từng IdP
  trong bản 26.8.1, không phải trường của realm).

Mức bằng chứng kiểm chứng trong repo dừng ở cấu hình IdP và
luồng khởi tạo broker (Keycloak chuyển hướng sang nhà cung
cấp với đúng `redirect_uri` ở trên). Đăng nhập trọn vẹn qua
tài khoản Google/Facebook thật cần thông tin đăng nhập kiểm
chứng của nhà cung cấp và không được tuyên bố là đã đạt nếu
chưa chạy với thông tin thật đó.

## Resource indicators (RFC 8707)

Resource indicators là **tính năng thử nghiệm** của Keycloak
26.8 (khởi động bằng `--features=resource-indicators`; có thể
thay đổi phá vỡ ở bản sau). Cơ chế của Keycloak — đã kiểm
chứng trên bản 26.8.1 thật — gồm hai nửa bắt buộc cả hai:

1. Client của MCP server (`lms-mcp`) mang thuộc tính
   `resource_url` bằng URL của MCP server của tenant.
2. Client gửi yêu cầu (RP) mang một audience mapper có
   *Included Client Audience* trỏ tới `lms-mcp`. Mẫu đóng gói
   mapper này thành client scope `lms-mcp-audience` và gán
   default cho `lms-mcp-client`.

Khi cả hai nửa có mặt, một yêu cầu có tham số `resource` bằng
đúng `resource_url` (khai ở cả endpoint authorize lẫn endpoint
token — Keycloak đối chiếu với yêu cầu gốc) được cấp access
token mà claim `aud` chỉ còn đúng giá trị resource đó;
`resource` không khớp bất kỳ `resource_url` nào đã đăng ký bị
từ chối bằng lỗi `invalid_target`.

Trạng thái áp dụng: stack kiểm chứng bật cờ thử nghiệm để
kiểm chứng hành vi trên; `compose.yaml` của stack thật **chưa
bật** cờ này và client CIMD (OAuth Client ID Metadata
Document) **chưa được bật** — cả hai thuộc quyết định của
việc tích hợp MCP, không thuộc việc cấp phát này.

## Realm `giaokhoa` và realm `master`

Realm `giaokhoa` dành cho quản trị nền tảng, không thuộc
tenant nào:

- Client `platform-web` (confidential, cho BFF của trang quản
  trị) và `platform-api` (bearer-only). Quyền nền tảng là
  **client role** `platform-admin` của `platform-api`, không
  phải realm role; realm này không có realm role nào và token
  nền tảng không mang vai tenant nào. Ánh xạ
  `platform-admin` vào client scope `roles` (qua scope
  mappings phía client) là cách vai này vào được token khi
  Full Scope Allowed tắt.
- Scope `platform-api-audience` ghi `aud=platform-api` chỉ
  vào access token của `platform-web`.
- Flow trình duyệt `platform-browser` bắt OTP vô điều kiện
  sau mật khẩu: mọi tài khoản nền tảng đều phải có MFA.
- Thời lượng ngắn hơn tenant: access token 2 phút; SSO idle
  15 phút, SSO max 8 giờ; client session idle 10 phút, client
  session max 4 giờ. Brute force và refresh rotation cùng
  chính sách như tenant.
- Không IdP broker trong realm này.

Realm `master` chỉ dành cho vận hành Keycloak. Bộ cấp phát áp
lớp phủ vô hướng (chính sách brute force như mẫu, sự kiện
kiểm toán bật, chi tiết sự kiện quản trị tắt) và — khi
`KC_MASTER_OPERATOR_USERNAME` được đặt — gắn required action
`CONFIGURE_TOTP` cho tài khoản vận hành đó để MFA cũng bắt
buộc ở realm `master`. Lưu ý đã kiểm chứng: không được gắn
required action này cho chính tài khoản đang gọi Admin API
trước khi tài khoản đó hoàn tất khai báo, vì Keycloak từ
chối cấp token cho tài khoản chưa hoàn tất required action;
bộ cấp phát tự bỏ qua trường hợp trùng này và in cảnh báo.

## Kiểm chứng

Bộ kiểm chứng `verify/verify.mjs` chạy toàn bộ trên một bản
Keycloak 26.8.1 thật và in một dòng ĐẠT/KHÔNG ĐẠT cho từng
kiểm, mã thoát khác 0 nếu có kiểm nào không đạt:

- cấp phát từ môi trường trống hai realm tenant + realm
  `giaokhoa`; chạy lại: không trùng realm/client, không đổi
  bí mật, không đổi định danh người dùng đã có;
- token thật: issuer, audience của access token và ID token,
  vai trong token, không claim nghiệp vụ, tuổi thọ token;
  redirect URI lạ, PKCE sai và resource indicator không khớp
  đều bị từ chối; resource indicator khớp cho `aud` đúng
  resource;
- đăng nhập ở mức HTTP qua form thật của Keycloak: member
  không bị bắt OTP; giáo viên bị buộc khai báo TOTP rồi mới
  đăng nhập xong và lần sau bắt buộc nhập OTP; nâng vai lên
  teacher thì bị bắt MFA; step-up `loa2`; SSO không hỏi lại
  mật khẩu trong cùng phiên; brute force khoá sau 5 lần sai;
- refresh token rotation và thu hồi cả phiên khi tái dùng;
  cấu hình broker và redirect khởi tạo broker;
- thời lượng phiên theo mẫu ở cả hai loại realm; sự kiện
  kiểm toán không chứa giá trị bí mật; đổi khoá ký: token mới
  ký bằng kid mới, token ký bằng khoá cũ vẫn kiểm chứng được;
- cách ly tenant: token của realm tenant này bị userinfo của
  realm tenant kia từ chối;
- diễn tập khôi phục: sửa lệch cấu hình có chủ đích (tuổi thọ
  token, redirect URI, gán scope) rồi chạy lại bộ cấp phát để
  khôi phục đúng mẫu mà không mất bí mật và người dùng. Đây
  cũng là quy trình khôi phục (rollback) của mẫu: quay về mẫu
  trong repo bằng một lần chạy cấp phát, không mở lại cơ chế
  xác thực cũ.

Cách chạy bằng Docker (môi trường có Docker):

```bash
docker compose -f keycloak/verify/compose.verify.yaml up -d
# chờ http://localhost:8180/realms/master trả 200, rồi chạy với
# đúng các biến môi trường ghi trong đầu file compose:
node keycloak/verify/verify.mjs
```

Workflow `.github/workflows/keycloak-verify.yml` chạy đúng
các bước trên trên CI khi thư mục `keycloak/` thay đổi. Trong
môi trường không có Docker, bộ kiểm chứng chạy trực tiếp với
bản phân phối Keycloak 26.8.1 (`bin/kc.sh start-dev
--features=resource-indicators`) và một JRE 21, trỏ
`KC_BASE_URL` về cổng của bản đó — đây là cách bộ kiểm chứng
đã được chạy khi thi công issue này.

## Ghi chú hành vi của Keycloak 26.8.1 đã kiểm chứng

Các điểm sau là hành vi thật của bản 26.8.1 (khác tài liệu
hoặc thói quen của bản cũ hơn), mẫu và bộ cấp phát đã theo
đúng các hành vi này:

- Trường `failureResetTime` không còn tồn tại trong
  `RealmRepresentation`: đưa vào bản tạo realm sẽ bị lỗi 400.
  Cửa sổ đặt lại số lần sai của brute force nay là trường
  `maxDeltaTimeSeconds`; mẫu đặt 43200 giây (12 giờ). Chiến
  lược chờ tuyến tính là giá trị enum `LINEAR` của trường
  `bruteForceStrategy`.
- Trường `postLogoutRedirectUris` trên client bị từ chối khi
  tạo client; dạng dùng được là thuộc tính
  `post.logout.redirect.uris`.
- PUT hàng loạt executions của một flow xác thực
  (`PUT /admin/realms/{realm}/authentication/flows/{alias}/executions`)
  trả lỗi 400 `Cannot parse the JSON` kể cả với payload nguyên
  trạng đọc từ GET — vì vậy flow chỉ được tạo qua realm import
  lúc tạo realm và được kiểm chứng chữ ký ở các lần chạy sau
  (xem mục Mô hình hoà giải).
- Realm PUT bỏ qua trường `authenticationFlows` một cách im
  lặng nhưng vẫn áp các trường vô hướng — bộ cấp phát dựa vào
  đúng hành vi này cho bước hoà giải vô hướng.
- Keycloak đánh dấu cookie của nó là `Secure` khi realm yêu
  cầu SSL với bên ngoài; kiểm chứng đăng nhập bằng curl trên
  HTTP thường sẽ mất cookie SSO vì curl không gửi cookie
  `Secure` qua HTTP (trình duyệt với localhost thì có). Bộ
  kiểm chứng dùng một HTTP client không áp hạn chế đó trên
  localhost.
