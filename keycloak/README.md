# Cấp phát Keycloak: realm giaokhoa và Organization theo trung tâm

Thư mục này giữ mẫu cấu hình Keycloak có phiên bản trong repo và bộ cấp phát áp mẫu đó lên một bản Keycloak **26.8.1**
qua Admin REST API. Mô hình hiện hành: đúng **một realm duy nhất tên `giaokhoa`**, mỗi trung tâm là một **Organization**
trong realm, admin của trung tâm được uỷ quyền bằng **Admin Permissions (FGAP v2)**. Một bản Keycloak trống có thể được
dựng lại nguyên trạng — realm `giaokhoa` với đủ client, role, scope và Organization — chỉ từ repo này cộng biến môi
trường bí mật của lần chạy; chạy lại bộ cấp phát không tạo trùng và không ghi đè bí mật đã cấp. Realm `master` giữ
nguyên cấu hình mặc định của Keycloak: bộ cấp phát chỉ lấy access token admin qua realm master, không sửa gì trên đó.

Mô hình cũ của issue #6 — mỗi trung tâm một realm tenant, cộng một realm nền tảng riêng — đã bị thay thế. Bộ cấp phát
**từ chối** mọi file cấu hình còn theo mô hình cũ (xem mục File cấu hình cấp phát).

Phạm vi không gồm: SPI/JAR tuỳ biến hay authenticator tự viết, triển khai production, và việc biến admin của trung tâm
thành quản trị viên realm. Chỉ cấu hình phần cấu trúc (realm, Organization, client, role, scope/mapper, FGAP) được cấp
phát; mọi tuỳ chọn còn lại của Keycloak — MFA, chính sách mật khẩu, thời hạn token, brute force — để mặc định của
Keycloak, không ghi vào mẫu, không khoá vào code, quyết lúc vận hành khi cần.

## Cấu trúc thư mục

| Đường dẫn | Vai trò |
| --- | --- |
| `templates/giaokhoa-realm.json` | Mẫu của realm `giaokhoa` duy nhất: bật Organizations và Admin Permissions, realm role, client, client scope và mapper. |
| `provision.mjs` | Bộ cấp phát (Node, chỉ dùng thư viện chuẩn): render mẫu, tạo realm hoặc hoà giải realm đã có, tạo Organization và bộ permission FGAP theo trung tâm. |
| `provision.config.example.json` | Mẫu file cấu hình cấp phát: một khoá `realm` duy nhất với danh sách Organization theo trung tâm. File cấu hình thật không chứa bí mật và không bắt buộc commit. |
| `verify/verify.mjs` | Bộ kiểm chứng trên Keycloak thật: từ chối mô hình cũ, cấp phát từ môi trường trống, đối chiếu cấu trúc realm, FGAP, claim trong token, ma trận quản trị của admin trung tâm, mặc định của realm master và tính idempotent. |
| `verify/compose.verify.yaml` | Stack Docker độc lập (PostgreSQL + Keycloak 26.8.1) chỉ phục vụ kiểm chứng; thông tin đăng nhập trong file là giá trị dùng một lần cho môi trường kiểm chứng. |
| `verify/provision.config.verify.json` | Cấu hình cấp phát cho stack kiểm chứng: realm `giaokhoa` với 2 Organization mẫu `trung-tam-a`, `trung-tam-b`. |

## Cách chạy bộ cấp phát

```bash
node keycloak/provision.mjs --config <file-cấu-hình-cấp-phát>
```

- `--dry-run`: chỉ render mẫu và in kế hoạch, không gọi API.
- Địa chỉ Keycloak lấy từ `KC_BASE_URL`, hoặc `authBaseUrl` trong file cấu hình khi biến không đặt.
- Tài khoản gọi Admin REST API lấy từ `KC_ADMIN_USERNAME` / `KC_ADMIN_PASSWORD`: tài khoản vận hành thuộc realm
  `master`, tách khỏi tài khoản của người dùng trong realm `giaokhoa`.
- Mã thoát khác 0 khi có bất kỳ bước nào lỗi; log tiếng Việt, không bao giờ in giá trị bí mật.

### File cấu hình cấp phát

```json
{
  "authBaseUrl": "https://auth.fuckr.net",
  "realm": {
    "name": "giaokhoa",
    "host": "giaokhoa.fuckr.net",
    "mcpRedirectUris": ["http://127.0.0.1:8765/callback"],
    "mcpResourceUrl": "https://giaokhoa.fuckr.net/mcp",
    "organizations": [{ "alias": "trung-tam-mau", "name": "Trung tâm mẫu" }]
  }
}
```

Cấu hình chỉ có một khoá `realm`; `realm.name` bắt buộc đúng `giaokhoa`. Mỗi phần tử của `organizations` là một trung
tâm: `alias` (duy nhất trong realm, không đổi sau khi tạo) và `name`; trường `domains` là tuỳ chọn — domain và IdP
riêng chỉ thêm khi trung tâm yêu cầu, không phải điều kiện để trung tâm hoạt động, và trung tâm được cấp tài khoản cho
học viên ngay trong Organization mà không cần email hay domain riêng.

Bộ cấp phát từ chối mô hình cũ ngay khi đọc cấu hình, trước mọi lời gọi API: cấu hình còn khoá `tenants` hoặc
`platform` bị thoát với mã 2 kèm thông báo mô hình realm-mỗi-trung-tâm đã bị thay thế; `realm.name` khác `giaokhoa`
cũng bị thoát với mã 2.

### Biến môi trường bí mật

| Biến | Dùng cho |
| --- | --- |
| `LMS_WEB_SECRET` | Bí mật client `lms-web` của realm `giaokhoa` (chỉ một bí mật web duy nhất, không còn bí mật theo từng realm). |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_FROM`, `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_STARTTLS`, `SMTP_SSL` | Cấu hình SMTP của realm; chỉ áp khi có cả `SMTP_HOST` và `SMTP_FROM`. |

Quy tắc bí mật: bí mật của client chỉ được đặt từ biến môi trường trên; hoà giải các lần sau không chạm vào bí mật đã
có (trường bí mật bị loại khỏi mọi lần ghi đè khi biến tương ứng không được cung cấp). Đặt lại bí mật là hành động có
chủ đích của vận hành khi biến môi trường được cấp giá trị mới, không phải hệ quả của chạy lại.

## Mô hình hoà giải khi chạy lại

Realm chưa tồn tại: bộ cấp phát POST bản render của mẫu lên `/admin/realms`. Bản tạo realm **không** gửi kèm danh sách
client scope của mẫu: Keycloak phải tự dựng bộ scope mặc định của nó (gồm scope `organization` kèm mapper membership
của tính năng Organizations); các scope của mẫu được hoà giải ngay sau đó như bình thường.

Realm đã tồn tại: bộ cấp phát hoà giải từng nhóm, không xoá dữ kiện vận hành (người dùng, phiên, bí mật):

1. Các trường vô hướng của realm mà mẫu quản (các cờ Organizations/FGAP, đăng ký, scope mặc định) được phủ từ mẫu lên
   bản hiện tại rồi PUT. Mọi tuỳ chọn không khai trong mẫu (thời hạn token, brute force, OTP...) không bị chạm tới.
2. Required action được tạo hoặc cập nhật theo alias.
3. Realm role: tạo nếu thiếu; nếu một vai chính bị đặt thành composite thì gỡ composite — không vai chính nào được
   gộp vai chính khác.
4. Vai mặc định của realm (`default-roles-giaokhoa`) bị gỡ `offline_access` nếu có (offline access tắt theo mặc định).
5. Client scope: tạo nếu thiếu, PUT theo mẫu nếu đã có.
6. Client: tạo nếu thiếu; nếu đã có thì phủ các trường của mẫu lên bản hiện tại rồi PUT, loại trường bí mật trừ khi
   biến môi trường tương ứng cung cấp giá trị.
7. Gán client scope cho từng client được hoà giải về đúng tập của mẫu (cả tập default lẫn optional).
8. Ánh xạ vai lên client scope `roles` được hoà giải hai chiều về đúng tập của mẫu: thiếu thì ánh xạ thêm, ánh xạ
   ngoài mẫu (realm role hoặc client role lạ đã có sẵn trên scope) thì bị gỡ (xem mục Mẫu realm giaokhoa).
9. Organization theo cấu hình: tạo nếu thiếu theo alias, cập nhật tên nếu lệch; không xoá Organization ngoài cấu
   hình.
10. Scope `organization` được gắn làm scope mặc định cho `lms-web` và `lms-api` **sau khi** Organization đã có, và
    bộ cấp phát đọc lại để kiểm chứng thay vì tin mã trạng thái (xem mục Ghi chú hành vi).
11. Bộ permission FGAP cho admin của từng trung tâm được hoà giải theo mẫu ở mục Organization và FGAP: với mỗi
    Organization, realm role, role policy và permission `-organizations` được tạo nếu thiếu, sửa nếu lệch; cặp dùng
    chung toàn realm cho Users (`org-admin-users-policy` và `org-admin-users`) cũng được hoà giải.

## Mẫu realm giaokhoa

- **Realm role**: đúng bốn realm role `tenant-admin`, `tenant-teacher`, `tenant-member` (ba vai chính của trung tâm)
  và `platform-admin` (vai quản trị nền tảng, là realm role trong cùng realm, không thuộc trung tâm nào). Không vai
  nào là composite; không có vai theo khoá học/lớp và không có client role nào trong realm.
- **Client**:
  - `lms-web` (confidential): client của BFF. Chỉ bật authorization code flow; tắt implicit, direct access grants và
    service account; bắt buộc PKCE S256; redirect URI duy nhất `https://<host>/api/auth/callback/keycloak`;
    post-logout redirect qua thuộc tính `post.logout.redirect.uris`; Full Scope Allowed tắt.
  - `lms-api` (bearer-only): resource server của API, chỉ xác thực access token, không tự đăng nhập.
  - `lms-mcp` (bearer-only): resource server của MCP server, mang thuộc tính `resource_url` của realm.
  - `lms-mcp-client` (public): mẫu client MCP đăng ký trước cho các MCP client đã biết; bắt buộc PKCE S256; redirect
    URI lấy từ `mcpRedirectUris` của file cấu hình.
- **Client scope và mapper**: scope `lms-api-audience` chứa audience mapper ghi `aud=lms-api` **chỉ vào access
  token** (không vào ID token, không liệt kê trong claim `scope`) và được gán default cho `lms-web`. Bốn scope
  nghiệp vụ MCP giữ nguyên theo hợp đồng: `lms.questions.read`, `lms.questions.draft`, `lms.questions.write`,
  `lms.banks.contribute` — gán optional cho `lms-mcp-client`. Scope `lms-mcp-audience` được mô tả ở mục Resource
  indicators. Không claim nghiệp vụ nào (khoá học, lớp, con, trạng thái bài học) được đưa vào token.
- **Mapper và vai trong token**: vì Full Scope Allowed tắt ở mọi client, một vai chỉ xuất hiện trong token khi nó
  được ánh xạ vào một client scope mà client mang. Bộ cấp phát ánh xạ đúng bốn realm role vào client scope `roles`
  (scope mặc định của `lms-web`) — đây là toàn bộ tập vai realm được phép vào token, không hơn. Tập ánh xạ này được
  giữ khép kín khi chạy lại: ánh xạ realm role hoặc client role ngoài mẫu đã có sẵn trên scope `roles` bị gỡ, nên
  token không thể mang vai ngoài tập cho phép chỉ vì realm từng bị sửa tay.
- **Scope organization và claim trong token**: realm bật Organizations thì Keycloak tự tạo client scope
  `organization` kèm mapper `oidc-organization-membership-mapper`. Bộ cấp phát gắn scope này làm scope mặc định cho
  `lms-web` và `lms-api`; token của một thành viên vì vậy mang claim `organization` chứa alias của các Organization
  người đó thuộc về.
- **Đăng ký và mặc định**: tự đăng ký tắt; Remember Me tắt. Realm bật Organizations và Admin Permissions (FGAP v2);
  Authorization Services cho client (`lms-api` là resource server thuần, quyết quyền nghiệp vụ nằm ở API) không
  thuộc mẫu này.

## Organization theo trung tâm và FGAP v2

Mỗi trung tâm là một Organization trong realm `giaokhoa`. Uỷ quyền quản trị dùng Admin Permissions (FGAP v2) trên
client `admin-permissions` mà Keycloak tự tạo khi realm bật Admin Permissions. Với mỗi Organization có alias
`<alias>`, bộ cấp phát dựng:

- realm role `org-admin-<alias>` — gán cho user nào là admin của trung tâm đó;
- role policy `org-admin-<alias>` trên client `admin-permissions`, trỏ đúng realm role trên;
- permission `org-admin-<alias>-organizations`: resourceType `Organizations`, `resources` chứa đúng id của
  Organization đó, scope `view` và `manage`, gắn với role policy trên.

Cộng một cặp dùng chung toàn realm cho quyền trên Users:

- role policy `org-admin-users-policy` trên client `admin-permissions`, chứa id của mọi realm role
  `org-admin-<alias>`, tất cả đều `required: false`;
- permission `org-admin-users`: resourceType `Users`, không khai `resources`, scope `view` và `manage`, gắn với
  role policy `org-admin-users-policy`.

Lý do phải gộp permission Users thành một cặp dùng chung: permission FGAP chỉ chấp nhận decisionStrategy
`UNANIMOUS`; khi đánh giá, mọi permission áp dụng được cho resource và scope đang xét đều phải cho qua. Hai
permission Users ở mức type theo từng trung tâm cùng áp dụng cho mọi user, nên admin của trung tâm này luôn trượt
policy của trung tâm kia và bị phủ quyết lẫn nhau — đã kiểm chứng trên Keycloak 26.8.1. Một permission Users dùng
chung với policy chứa mọi role admin (đều không bắt buộc) tránh được phủ quyết đó, trong khi ranh giới theo
Organization vẫn do permission `-organizations` theo từng trung tâm giữ.

Admin của một trung tâm còn cần các vai `query-users` và `query-organizations` của client `realm-management` để truy
vấn resource qua Admin API — theo docs FGAP của Keycloak, đây là vai cơ bản để truy vấn, tự chúng không cấp quyền
xem hay sửa; ranh giới xem/sửa do các permission ở trên quyết định. Việc gán vai cho từng admin cụ thể là thao tác
vận hành (bộ kiểm chứng thực hiện trong fixture), không nằm trong mẫu cấp phát.

Hành vi đã kiểm chứng trên Keycloak 26.8.1 với mô hình trên: admin của trung tâm A đọc và cập nhật được
Organization A (GET 200, PUT 204), thấy được danh sách member của A, và thêm/xoá member qua Organization A đạt
(POST 201, DELETE 204); mọi thao tác tương tự lên Organization B đều bị 403.

**Giới hạn đã kiểm chứng trên Keycloak 26.8.1**: thao tác member của Organization qua Admin API cần permission loại
Users (`view` + `manage`) như trên, vì FGAP v2 chưa giới hạn được Users theo membership của Organization —
permission Users vì vậy là mức toàn realm, nên qua bề mặt `/users` chung, admin của trung tâm không bị lọc theo
Organization. Ranh giới Organization thực thi ở permission loại Organizations (Organization khác bị 403 toàn
diện) và ở các thao tác member qua Organization của mình.

## Realm master

Realm `master` chỉ dành cho vận hành Keycloak và giữ **nguyên cấu hình mặc định** của Keycloak: bộ cấp phát không áp
lớp phủ nào lên realm này — không flow trình duyệt riêng, không ép MFA, không mốc migration thu hồi phiên, không gắn
required action cho tài khoản nào. (Lớp phủ MFA trên master của issue #6 đã bị gỡ hẳn cùng mô hình cũ.) Bộ cấp phát
chỉ dùng tài khoản thuộc realm master để lấy access token gọi Admin REST API. Bật MFA hay chính sách nào khác cho
realm master là quyết định lúc vận hành, thực hiện ngoài mẫu cấp phát này.

## Resource indicators (RFC 8707)

Resource indicators là **tính năng thử nghiệm** của Keycloak 26.8 (khởi động bằng `--features=resource-indicators`;
có thể thay đổi phá vỡ ở bản sau). Cơ chế của Keycloak — đã kiểm chứng trên bản 26.8.1 thật — gồm hai nửa bắt buộc
cả hai:

1. Client của MCP server (`lms-mcp`) mang thuộc tính `resource_url` bằng URL của MCP server trong cấu hình realm.
2. Client gửi yêu cầu (RP) mang một audience mapper có *Included Client Audience* trỏ tới `lms-mcp`. Mẫu đóng gói
   mapper này thành client scope `lms-mcp-audience` và gán default cho `lms-mcp-client`.

Khi cả hai nửa có mặt, một yêu cầu có tham số `resource` bằng đúng `resource_url` (khai ở cả endpoint authorize lẫn
endpoint token — Keycloak đối chiếu với yêu cầu gốc) được cấp access token mà claim `aud` chỉ còn đúng giá trị
resource đó; `resource` không khớp bất kỳ `resource_url` nào đã đăng ký bị từ chối bằng lỗi `invalid_target`.

Trạng thái áp dụng: stack kiểm chứng bật cờ thử nghiệm để kiểm chứng hành vi trên; `compose.yaml` của stack thật
**chưa bật** cờ này và client CIMD (OAuth Client ID Metadata Document) **chưa được bật** — cả hai thuộc quyết định
của việc tích hợp MCP, không thuộc việc cấp phát này.

## Kiểm chứng

Bộ kiểm chứng `verify/verify.mjs` chạy toàn bộ trên một bản Keycloak 26.8.1 thật và in một dòng ĐẠT/KHÔNG ĐẠT cho
từng kiểm, mã thoát khác 0 nếu có kiểm nào không đạt:

- mục 0: bộ cấp phát từ chối cấu hình còn khoá `tenants` (thoát 2, thông báo mô hình cũ đã bị thay thế) và cấu hình
  có `realm.name` khác `giaokhoa` (thoát 2);
- mục 1: cấp phát từ môi trường trống bằng `verify/provision.config.verify.json`;
- mục 2: realm `giaokhoa` có `organizationsEnabled` và `adminPermissionsEnabled` đều bật, đủ 4 realm role, đủ 4
  client, scope `organization` kèm mapper membership và là scope mặc định của `lms-web`/`lms-api`; GET realm
  `tenant-a`/`tenant-b` trả 404; scope `roles` ánh xạ đúng 4 realm role, không client role;
- mục 3: đúng 2 Organization theo cấu hình; mỗi Organization có đủ realm role `org-admin-<alias>`, role policy tương
  ứng và permission `-organizations` khoá đúng id của Organization đó với scope `view` + `manage`; role policy
  `org-admin-users-policy` chứa đúng 2 role id của `org-admin-trung-tam-a` và `org-admin-trung-tam-b`, tất cả
  `required: false`; tồn tại đúng một permission `org-admin-users` (resourceType `Users`, scope `view` + `manage`,
  gắn đúng policy trên) và không tồn tại permission `org-admin-trung-tam-a-users` hay
  `org-admin-trung-tam-b-users`;
- mục 4: fixture qua tài khoản quản trị master — tạo user `member-a`, `member-b`, `admin-a` (bắt buộc có
  firstName/lastName), thêm member theo đúng Organization, gán realm role `org-admin-trung-tam-a` và 2 vai
  `query-*` cho admin-a;
- mục 5: `member-a` đăng nhập bằng password grant qua `lms-web` (fixture của môi trường verify bật tạm direct
  access grants trên client này) — access token mang claim `organization` gồm `trung-tam-a` và không chứa
  `trung-tam-b`;
- mục 6: ma trận FGAP bằng token của admin-a (password grant qua `admin-cli`), đạt 10/10: GET/PUT Organization của
  mình đạt (200/204), Organization khác bị 403 toàn diện, xem được members của Organization mình, thêm member vào
  Organization mình đạt 201 và vào Organization khác bị 403, xoá member khỏi Organization mình đạt 204;
- mục 7: realm master ở mặc định — `browserFlow` là flow dựng sẵn `browser`, không tồn tại flow
  `lms-master-browser`, không có thuộc tính `lms.master.mfa-session-migration`, user quản trị không bị gắn required
  action `CONFIGURE_TOTP`;
- mục 8: chạy lại bộ cấp phát thoát 0, số Organization vẫn 2, số permission FGAP không tăng (tổng ở lượt đầu là 3:
  2 permission `-organizations` và 1 permission `org-admin-users`, so bằng số đếm động của lượt đầu), bí mật của
  `lms-web` không đổi.

Kết quả lượt chạy sạch gần nhất trên Keycloak 26.8.1 thật (xoá realm `giaokhoa` rồi chạy lại toàn trình, lặp lại
hai lần): **60 đạt, 0 không đạt, mã thoát 0** ở cả hai lượt, trong đó mục 6 đạt 10/10.

Cách chạy bằng Docker (môi trường có Docker):

```bash
docker compose -f keycloak/verify/compose.verify.yaml up -d
# chờ http://localhost:8180/realms/master trả 200, rồi chạy với
# đúng các biến môi trường ghi trong đầu file compose:
node keycloak/verify/verify.mjs
```

Workflow `.github/workflows/keycloak-verify.yml` chạy đúng các bước trên trên CI khi thư mục `keycloak/` thay đổi.
Trong môi trường không có Docker, bộ kiểm chứng chạy trực tiếp với bản phân phối Keycloak 26.8.1 (`bin/kc.sh
start-dev --features=resource-indicators`) và một JRE 21, trỏ `KC_BASE_URL` về cổng của bản đó — đây là cách bộ
kiểm chứng đã được chạy khi thi công issue này.

## Ghi chú hành vi của Keycloak 26.8.1 đã kiểm chứng

Các điểm sau là hành vi thật của bản 26.8.1 (khác tài liệu hoặc thói quen của bản cũ hơn), mẫu và bộ cấp phát đã
theo đúng các hành vi này:

- Khi bản import tạo realm có gửi kèm danh sách client scope, Keycloak dùng đúng danh sách đó thay cho bộ scope mặc
  định và **không** tự tạo scope `organization`; vì vậy lượt tạo realm để trống client scope và hoà giải scope của
  mẫu ngay sau đó.
- Keycloak chỉ lưu việc gán scope `organization` cho client khi realm đã có ít nhất một Organization: realm chưa có
  Organization nào thì PUT gán scope trả 204 nhưng không lưu. Bộ cấp phát vì vậy gắn scope sau bước tạo Organization
  và đọc lại để kiểm chứng.
- Claim `organization` trong access token là danh sách alias của các Organization user thuộc về (mapper
  `oidc-organization-membership-mapper` của scope `organization`, cấu hình multivalued).
- Trường `postLogoutRedirectUris` trên client bị từ chối khi tạo client; dạng dùng được là thuộc tính
  `post.logout.redirect.uris`.
- Bản ghi scope-mappings của một client scope trả `{}` khi scope chưa có ánh xạ nào: Keycloak bỏ hẳn trường
  `realmMappings`/`clientMappings` khi tập tương ứng rỗng. Bộ cấp phát hiểu trường vắng mặt là tập rỗng theo đúng
  thiết kế này, nhưng vẫn fail-closed — dừng và báo lỗi — khi HTTP lỗi hoặc trường có mặt mà sai kiểu dữ liệu, thay
  vì diễn giải một lần đọc hỏng thành "không có ánh xạ".
- Permission FGAP chỉ chấp nhận decisionStrategy `UNANIMOUS`: khi đánh giá, mọi permission áp dụng được cho
  resource và scope đang xét đều phải cho qua. Vì vậy nhiều permission Users ở mức type theo từng trung tâm sẽ phủ
  quyết lẫn nhau (mỗi permission cùng áp dụng cho mọi user, admin của trung tâm này luôn trượt policy của trung
  tâm kia) — đây là lý do permission Users phải gộp thành một permission dùng chung toàn realm như ở mục
  Organization và FGAP.
- Realm PUT bỏ qua trường `authenticationFlows` một cách im lặng nhưng vẫn áp các trường vô hướng — bộ cấp phát
  dựa vào đúng hành vi này cho bước hoà giải vô hướng.
