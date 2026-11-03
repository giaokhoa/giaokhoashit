# Hợp đồng tích hợp Keycloak — xác thực và quyết quyền của LMS

Tài liệu này là hợp đồng đích cho toàn bộ xác thực và quyết quyền của
LMS, làm căn cứ thi công cho các việc tích hợp Keycloak tiếp theo.
Nội dung quyết định được viết trọn vẹn tại đây; các việc con triển
khai theo hợp đồng này không cần nguồn nào ngoài repo.

> **Trạng thái: hợp đồng đích — CHƯA triển khai runtime.** Tại head
> của tài liệu này, runtime vẫn chạy JWT tự viết (HS256) song song
> Better Auth. Mọi mô tả dưới đây là đích phải đạt, không phải mô tả
> hành vi hiện tại. Phần auth cũ thuộc danh sách xoá ở
> `docs/auth-removal-list.md`; kiểm kê điểm vào hiện tại ở
> `docs/capability-inventory.md`; oracle quyền và dữ kiện kiểm chứng
> ở `docs/access-oracle.md` và `docs/access-verification-fixtures.md`.

## 1. Nguyên tắc nền

1. **Keycloak 26.8.1 nguyên bản là hệ xác thực duy nhất** cho web,
   API và MCP. Không viết Keycloak SPI, custom authenticator hay
   JavaScript tuỳ biến trong Keycloak; không port logic xác thực cũ
   sang nơi khác.
2. **LMS không kiểm mật khẩu, không kiểm OTP/TOTP, không phát token
   đăng nhập.** Mật khẩu, MFA, khoá tài khoản, đặt lại mật khẩu và
   thư xác thực là việc của Keycloak.
3. **ACCESS (quyết quyền nghiệp vụ) do engine của API quyết** trong
   đợt đầu và giữ ở API các phần cần dữ kiện thật (mục 6). Keycloak
   chỉ cung cấp danh tính và vai chính; claim trong token không tự
   chứng minh các sự kiện nghiệp vụ là thật.

## 2. Luồng danh tính chuẩn

Mọi request đã xác thực đi qua đúng một chuỗi:

```
Host đã xác minh
  → tenant (trung tâm) suy từ Host
  → realm của tenant → issuer của realm
  → token được kiểm theo issuer đã đăng ký
  → danh tính liên kết bằng (issuer, sub)
  → principal/user nghiệp vụ của LMS
  → engine quyết quyền của API (capability tại cây context)
```

- **Host đã xác minh chọn realm/issuer.** Host của request phải khớp
  một domain đã đăng ký cho một tenant; Host lạ, Host chéo realm
  hoặc issuer không thuộc registry đã đăng ký thì request bị từ
  chối trước khi xét quyền. Registry domain/issuer là dữ kiện của
  API, không suy từ claim trong token.
- **JWT chỉ có giá trị sau khi kiểm đủ bốn điều kiện:** chữ ký (theo
  JWKS của issuer), thời hạn, `audience` và `issuer` đã đăng ký.
  Thiếu hoặc sai bất kỳ điều kiện nào thì token vô giá trị.
- **Danh tính liên kết bằng cặp `(issuer, sub)`.** Email không làm
  khoá liên kết: hai tài khoản trùng email ở hai realm là hai danh
  tính khác nhau; một tài khoản đổi email không đổi danh tính.
- Principal nghiệp vụ là user của LMS được liên kết với `(issuer,
  sub)`; mọi quyết quyền sau đó dùng principal này, không dùng trực
  tiếp claim thô.

## 3. Mô hình realm

| Realm | Chứa ai | Quyền ngầm |
| --- | --- | --- |
| Một realm cho mỗi tenant (trung tâm) | Thành viên của đúng trung tâm đó: quản trị trung tâm, giáo viên, học viên, phụ huynh (tài khoản đăng nhập) | Không có quyền ở tenant khác |
| `giaokhoa` | Chỉ tài khoản quản trị platform | Không thuộc trung tâm nào; không có quyền ngầm trong bất kỳ tenant nào |
| `master` | Chỉ quản trị chính Keycloak | Chỉ quản trị Keycloak; không có quyền nghiệp vụ LMS |

- Tài khoản giáo viên/học viên demo phục vụ review thuộc **realm
  của trung tâm** chứa dữ liệu demo, không thuộc realm `giaokhoa`
  và không phải tài khoản platform.
- Platform muốn tác động vào một tenant thì qua cơ chế hỗ trợ tường
  minh (đăng nhập thay có lý do + audit) như nghiệp vụ hiện hành,
  không qua quyền ngầm của token realm `giaokhoa`.

## 4. Vai chính và ánh xạ role

- Mỗi thành viên của một trung tâm có **đúng một vai chính** trong
  ba giá trị `admin` / `teacher` / `member`, ánh xạ một-một sang
  realm role của realm trung tâm:
  `tenant-admin` / `tenant-teacher` / `tenant-member`.
- `platform-admin` là **client role của client `platform-api`
  trong realm `giaokhoa`**, không phải realm role và không ánh xạ
  vào tenant nào.
- **Không composite lồng ba vai tenant**: `tenant-admin` không chứa
  `tenant-teacher`/`tenant-member` dưới dạng composite; quyền rộng
  của admin trung tâm đến từ engine quyền của API, vẫn chịu phủ
  quyết PROHIBIT (xem `docs/access-oracle.md`).
- **Không role theo khoá/bài**: không tạo role cho từng khoá học,
  lớp hay bài; khác biệt theo ngữ cảnh do phân công và ghi đè tại
  cây context của API quyết.
- **Không vai chính `parent`/`student`**: phụ huynh và học viên đều
  là `member` ở vai chính. Quan hệ phụ huynh–con và tư cách học viên
  theo khoá/lớp là **quan hệ dữ liệu ở API**, không phải role. Cùng
  một người có thể đồng thời có quan hệ giáo viên (phân công dạy)
  và quan hệ phụ huynh (với con của mình) mà vẫn chỉ có một vai
  chính — xem ca dữ kiện ở `docs/access-verification-fixtures.md`.

## 5. Thư viện chuẩn và ranh giới trách nhiệm

Keycloak không còn cung cấp adapter Node.js dùng cho tích hợp này:
adapter `keycloak-connect` của Keycloak đã hết vòng đời và tài liệu
Keycloak hướng ứng dụng sang các thư viện OIDC/OAuth chuẩn. Hợp đồng
này chỉ định thư viện chuẩn đang được duy trì, cấm tự chế giao thức:

| Phía | Thư viện chỉ định | Trách nhiệm |
| --- | --- | --- |
| Client OIDC (BFF trong API, vai relying party) | `openid-client` — thư viện RP được chứng nhận hợp chuẩn OpenID Connect, đang được duy trì | Discovery theo issuer, luồng authorization code + PKCE, đổi code lấy token, refresh, gọi userinfo, thu hồi và đăng xuất theo chuẩn |
| Resource server (API kiểm access token) | `oauth4webapi` + `jose` — cùng hệ thư viện được chứng nhận, đang được duy trì | Nạp JWKS từ xa của issuer đã đăng ký (`jose`), kiểm chữ ký/thời hạn/`aud`/`iss` của access token JWT (RFC 9068) qua `oauth4webapi` |

- **Không dùng `keycloak-js` ở trình duyệt**: web không giữ token.
  Luồng đăng nhập của web đi qua BFF (mục 6).
- Không dùng adapter đã hết vòng đời, không tự viết mã kiểm chữ ký
  JWT, không tự dựng endpoint OAuth/OIDC phía LMS.
- Thư viện được chỉ định ở tầng hợp đồng; phiên bản cụ thể do việc
  triển khai chọn theo chính sách phiên bản của repo (ưu tiên bản
  mới nhất, ghim phải có lý do ghi thành văn bản).

## 6. BFF trong API và phiên web

- API đóng vai **backend-for-frontend (BFF)** cho web: API chạy
  luồng OIDC với Keycloak bằng `openid-client`, giữ **kết quả
  OIDC** (token set của Keycloak) ở phía server và cấp cho web một
  **opaque cookie** phiên — cookie không chứa token Keycloak và
  không tự nó chứng minh danh tính ngoài phiên server đã lập.
- BFF **chỉ giữ kết quả OIDC**: không tái tạo hệ xác thực riêng,
  không lưu mật khẩu, không tự phát token đăng nhập, không tự làm
  MFA/khoá tài khoản/đặt lại mật khẩu.
- Web không nhận access token/ID token; mọi gọi API của web đi kèm
  cookie phiên, API tự gắn danh tính đã xác minh vào request.

## 7. Phần quyết quyền giữ tại API

Các phần sau **giữ tại API lâu dài**, kể cả khi chính sách nền được
đối chiếu bằng Keycloak ở các việc sau:

- Phân công (ai dạy/quản lý lớp, khoá, ngân hàng nào).
- Quan hệ phụ huynh–con.
- Cây ngữ cảnh và ghi đè bốn trạng thái (oracle ở
  `docs/access-oracle.md`).
- Lọc từng đối tượng trong danh sách/tệp/batch (quyền tính trên
  từng bản ghi, không chỉ ở cổng route).
- Trạng thái bài/điểm (cổng công bố) và **kiểm tra trong giao dịch
  ghi**: trạng thái được đọc lại trong cùng giao dịch ghi, không
  dùng kết quả kiểm tra cũ từ lúc mở trang/bắt đầu làm bài.

Lý do giữ: Keycloak tính policy trên claim, mà claim không tự chứng
minh các sự kiện trên là trạng thái thật tại thời điểm ghi.

## 8. Ranh giới quyền của phụ huynh

- Phụ huynh (qua quan hệ phụ huynh–con hợp lệ ở API) chỉ được:
  **đọc thông báo lớp/khoá của con** và **nhắn tin 1-1 với giáo viên
  có quan hệ hợp lệ** (giáo viên của lớp/khoá con đang học).
- Quan hệ phụ huynh **không cấp quyền xem điểm hay bài làm** của
  con, kể cả điểm đã công bố.
- Thu hẹp phụ huynh theo quan hệ không làm mất quyền teacher/học
  viên mà cùng người đó được cấp theo ngữ cảnh khác: các quan hệ
  độc lập được engine xét độc lập.

## 9. Hợp đồng MCP

- MCP giữ **11 tool**: `list_my_banks`, `list_categories`,
  `get_question`, `search_questions`, `check_duplicates`,
  `search_curriculum_outcomes`, `create_question`,
  `create_questions_batch`, `create_stimulus_with_questions`,
  `publish_questions`, `submit_contribution`.
- Bốn scope giữ nguyên tên hiện tại: `lms.questions.read`,
  `lms.questions.draft`, `lms.questions.write`,
  `lms.banks.contribute`. Ánh xạ tool → scope giữ như bản đồ hiện
  tại trong `apps/api/src/modules/mcp/mcp.mount.ts`.
- **OAuth authorization server, trang đăng nhập và consent thuộc
  Keycloak** của realm trung tâm; LMS không còn làm AS, không còn
  cầu xác thực bằng JWT tự phát. Chi tiết triển khai thuộc việc
  tích hợp OAuth Keycloak cho MCP; tại hợp đồng này chỉ chốt:
  mỗi tool vẫn kiểm capability/ngữ cảnh bằng cùng engine quyền của
  API sau khi danh tính đã xác minh; câu hỏi AI tạo ra vào trạng
  thái `draft`; không scope nào cho ghi thẳng vào ngân hàng chung
  (đóng góp vào bank chung vẫn qua duyệt của con người).

## 10. Ranh giới dữ liệu

- Topology dữ liệu hiện tại giữ nguyên: shared DB + `tenant_id` +
  RLS fail-closed và cổng tenant của evaluator (thành viên `active`
  mới có quyền trong cây tenant; gán vai ngoài cây tenant không
  chảy vào tenant). Hợp đồng này không đổi topology dữ liệu.
- Dữ kiện danh tính mới cần lưu là liên kết `(issuer, sub)` →
  user; email chỉ là thuộc tính hiển thị/liên hệ, không phải khoá.
