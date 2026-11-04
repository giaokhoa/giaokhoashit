## Bối cảnh và vấn đề

`apps/server` đã có khung xác thực JWT của realm `giaokhoa`, hồ sơ người dùng phía LMS và khung tenancy gồm
`TenantContext`, discriminator theo phân vùng và Row Level Security ở PostgreSQL. Khung tenancy mới trả lời được
request đang thuộc ngữ cảnh nào và vai theo Organization của request là gì; nó chưa quyết định một người được làm
gì trên một tài nguyên cụ thể. Trong khi đó, module `access` của `apps/api` là phụ thuộc của hầu hết các module
nghiệp vụ, nhưng thiết kế quyền của module cũ không được chuyển sang `apps/server`.

Nếu không có một lõi quyền dùng chung, các feat tài nguyên về sau sẽ tự định nghĩa quyền theo cách riêng: câu hỏi
một kiểu, đề một kiểu, ma trận một kiểu. Hệ quả là các quy tắc quan trọng như quyền theo từng phiên bản, quyền chỉ
áp dụng tại một trung tâm, phân công biên tập/duyệt/phát hành và việc người tạo không tự có quyền sửa tài nguyên
của trung tâm sẽ không kiểm chứng được tại một điểm duy nhất.

Feat này dựng lõi quyền tại API của LMS cho `apps/server`, ở mức khung có kiểm chứng: bảng dữ kiện quyền dùng
chung, một dịch vụ quyết định quyền duy nhất và bề mặt kiểm chứng bằng tài nguyên mẫu không có nội dung nghiệp vụ.
Nội dung chuyên biệt của câu hỏi, ngân hàng câu hỏi, đề và ma trận thuộc các feat tài nguyên đứng trên lõi này.

## Mục tiêu

- Mọi thao tác đọc hoặc ghi trên một tài nguyên trong `apps/server` đều đi qua cùng một dịch vụ quyết định quyền
  tại API; không endpoint nào tự suy quyền từ vai trò chung, người tạo hoặc dữ kiện do client gửi.
- Quyết định quyền kết hợp đúng ngữ cảnh của F3 với dữ kiện của LMS: workspace và chủ sở hữu của tài nguyên, vai
  theo Organization hiện hành, phân công theo ngân hàng/bộ sưu tập và loại tài nguyên, grant trên đúng phiên bản,
  trạng thái của phiên bản, giới hạn sử dụng kế thừa từ nguồn và điều kiện nghiệp vụ của loại tài nguyên.
- Quyền của một trung tâm không áp dụng được tại trung tâm khác; grant cho một phiên bản không mở quyền trên các
  phiên bản còn lại; grant xem hoặc dùng không tự mở thành quyền sửa, chia sẻ lại, nộp hoặc đổi chủ sở hữu.
- `created_by` chỉ là dữ kiện truy vết. Người tạo một tài nguyên đã thuộc trung tâm không vì thế được xem, sửa,
  duyệt, phát hành hoặc chia sẻ tài nguyên đó.
- Phiên bản đã phát hành hoặc đã được grant không bị sửa tại chỗ; thao tác sửa hợp lệ tạo một phiên bản nháp mới.
- Toàn bộ lõi được kiểm chứng bằng test tự động và bằng request runtime qua các endpoint kiểm chứng chuyên dụng,
  theo các tiêu chí ở mục Nghiệm thu; phần test cần database thật chạy trên test-pg online theo quyết định 11.

## Phạm vi

### Làm

- Migration Flyway và tầng dữ kiện JPA cho các thành phần lõi: `resource`, `resource_version`, `resource_grant`,
  `collection_assignment`, `resource_dependency`, `handover_request`, `submission` và `submission_item`. Ngân
  hàng/bộ sưu tập được biểu diễn bằng một `resource` có loại tương ứng, nên `collection_assignment.collection_id`
  trỏ tới `resource` đó.
- Ngữ nghĩa dữ kiện của bản nhận tại trung tâm: phân biệt bản trung tâm sở hữu (qua nộp/bàn giao hoặc do trung
  tâm tạo) với bản trung tâm được cấp quyền giữ và dùng (share lâu dài), kèm nguồn cấp phép và giới hạn sử dụng
  áp lên bản nhận, theo mục 2.
- Phần bàn giao dùng chung ở mức lõi theo mục 13: yêu cầu nộp, danh sách bàn giao, kiểm quyền trên từng mục,
  tạo bản thuộc trung tâm và biên nhận bất biến. Phạm vi bàn giao theo từng loại tài nguyên thuộc F10/F11.
- Dịch vụ quyết định quyền tại API dùng chung cho mọi loại tài nguyên, nhận đầu vào từ `TenantContext` của F3 và
  các bảng lõi ở trên. Các endpoint kiểm chứng của feat này gọi đúng dịch vụ đó, không có đường kiểm quyền riêng.
- Mô hình grant theo đúng tài nguyên, đúng phiên bản, đúng chủ thể nhận và đúng hành động; grant cho Organization
  chỉ áp dụng trong ngữ cảnh của đúng Organization được cấp.
- Mô hình phân công theo Organization, ngân hàng/bộ sưu tập và loại tài nguyên, gồm các vai `bank-manager`,
  `bank-editor`, `bank-publisher`, `resource-editor`, `resource-reviewer` và `resource-publisher`.
- Row Level Security cho các bảng lõi theo khuôn của F3 và quyền tối thiểu cho tài khoản database runtime.
- Các endpoint `/api/access-check/...` tối thiểu để tạo dữ kiện mẫu, tạo/gỡ phân công, tạo/thu hồi grant tạm thời,
  chạy phần bàn giao dùng chung với tài nguyên mẫu, hỏi quyết định quyền và kiểm chứng hành vi của dịch vụ trên
  đường runtime thật. Đây là bề mặt kiểm chứng, không phải bề mặt sản phẩm, theo giới hạn ở mục 10.
- Test tự động cho tầng dữ kiện, thuật toán quyết định, cách ly giữa hai trung tâm và hồi quy của F2/F3.

### Không làm

- Không chuyển module `access` của `apps/api` sang `apps/server`, không sao chép thiết kế hoặc dữ kiện quyền cũ.
- Không đưa nội dung chuyên biệt của câu hỏi, ngân hàng câu hỏi, đề hoặc ma trận vào feat này: không có bảng nội
  dung câu hỏi/đề/ma trận, không có quan hệ ngân hàng-câu hỏi và không có endpoint nghiệp vụ cho các loại đó.
- Không làm luồng share hoàn chỉnh của câu hỏi, đề hoặc ma trận và không tạo bản sao hoặc snapshot nội dung
  chuyên biệt tại trung tâm khi grant dùng lâu dài. Feat này ghi và đánh giá grant, đồng thời định nghĩa ngữ
  nghĩa dữ kiện của bản nhận được cấp quyền ở mục 2; luồng tạo bản nhận thuộc các feat tài nguyên.
- Không làm phạm vi bàn giao theo từng loại tài nguyên (nộp một đề gồm những gì, nộp một ma trận gồm những gì)
  và không làm luồng nghiệp vụ nộp của câu hỏi, đề hoặc ma trận. Phần bàn giao dùng chung ở mức lõi thuộc feat
  này theo mục 13; phạm vi theo loại do F10/F11 định nghĩa trên phần dùng chung đó.
- Không làm catalog hệ thống cho câu hỏi: workspace hệ thống trong feat này chỉ là một workspace của lõi quyền
  với tài nguyên mẫu; bảng và repository catalog riêng thuộc feat câu hỏi.
- Không tạo object, resource, policy hoặc role trong Keycloak cho từng tài nguyên; không dùng Keycloak
  Authorization Services và không tích hợp policy enforcer.
- Không quản lý Organization, membership hoặc tài khoản qua Admin REST API; không làm FGAP cho admin trung tâm.
- Không làm cache quyết định quyền, không làm giao diện người dùng và không di trú dữ kiện quyền từ hệ cũ.

## Chi tiết việc cần làm

### 1. Đầu vào ngữ cảnh và nguyên tắc của dịch vụ quyết định quyền

Sau khi Spring Security xác thực JWT và resolver của F3 dựng `TenantContext`, mọi kiểm tra quyền trên tài nguyên
đều gọi một dịch vụ quyết định quyền duy nhất của `apps/server`. Dịch vụ nhận các đầu vào sau:

- `TenantContext` đã kiểm chứng: `sub` của người gọi, alias và ID của Organization của trang nếu có, tập vai tại
  `organization[<alias>].realm_access.roles`, cờ khách, cờ `platform-admin` và các thành phần phân vùng.
- Quyền soạn nền tảng của người gọi (`personal-author`): quyền cấp nền tảng cho tài khoản giáo viên được soạn
  tài nguyên cá nhân, độc lập với membership và với vai `tenant-teacher`. Không phải tài khoản nào có `sub`
  cũng được soạn; tài khoản không có quyền này, ví dụ tài khoản học viên, không tạo được tài nguyên cá nhân.
  Quyền này là đầu vào bắt buộc trong hợp đồng của dịch vụ, lấy từ ngữ cảnh đã kiểm chứng, không nhận từ thân
  request.
- Định danh tài nguyên và, khi hành động áp dụng lên một phiên bản, định danh phiên bản được yêu cầu.
- Hành động cần quyết định, lấy từ tập hành động lõi ở mục 5.
- Dữ kiện hiện tại trong database của LMS: tài nguyên, phiên bản, phân công và grant.
- Điều kiện nghiệp vụ do feat loại tài nguyên gắn lên tài nguyên hoặc phiên bản, ví dụ trạng thái chờ duyệt của
  một bản nhận. Dịch vụ áp các điều kiện này như ràng buộc thu hẹp ở mục 8: chúng chỉ được chặn thêm, không
  bao giờ mở quyền ngoài các nguồn quyền ở mục 5.

API không nhận người thực hiện, Organization hiện hành, vai, chủ sở hữu hoặc khoá phân vùng từ thân request để
dùng cho quyết định. Thân request của các thao tác quản trị có thể chứa định danh của đối tượng bị tác động, ví
dụ `sub` của người được phân công hoặc của người nhận grant; các giá trị đó là dữ kiện đích của thao tác, không
bao giờ thay thế danh tính và ngữ cảnh của người gọi đã được xác thực.

Dịch vụ trả một quyết định gồm `allowed` và mã lý do phục vụ kiểm chứng. Quyết định mặc định là từ chối. Một
quyền chỉ được công nhận khi khớp đồng thời tài nguyên, phiên bản nếu có, hành động, chủ thể và ngữ cảnh; không
có quy tắc "được một hành động thì suy ra được hành động khác" ngoài các ánh xạ được viết rõ trong đặc tả này.

Các thao tác ghi phải gọi dịch vụ quyết định trước khi ghi và đánh giá lại toàn bộ quyết định tại thời điểm
ghi theo mục 8, không chỉ kiểm lại trạng thái của tài nguyên/phiên bản. Bề mặt kiểm chứng của feat này và các
endpoint nghiệp vụ về sau dùng chung một đường quyết định; không cài một bản kiểm quyền rút gọn chỉ để test.

Tính hiện hành của membership do khung F3 xác lập và feat này dẫn chiếu: ngữ cảnh thành viên của một
Organization chỉ tồn tại khi tài khoản còn là thành viên của Organization đó tại thời điểm request. Sau khi
membership bị gỡ, một token được cấp từ trước không còn dựng được ngữ cảnh thành viên của Organization đó, nên
mọi nguồn quyền theo trung tâm trong feat này — vai theo Organization, phân công và grant cho Organization —
đều không còn hiệu lực với token cũ đó. Feat này không tự kiểm membership ngoài `TenantContext` của F3; tiêu
chí nghiệm thu tương ứng nằm ở mục Nghiệm thu.

Keycloak chỉ khẳng định danh tính, membership và vai theo Organization. Theo tài liệu Keycloak, Organization
groups thuộc về đúng một Organization và cách ly giữa các Organization; role gán cho Organization group chảy vào
token của thành viên, và khi mapper Organization Group Membership bật "Add group role mappings" thì các role đó
nằm trong claim `organization` theo từng Organization. Claim `realm_access` ở cấp cao nhất vẫn gộp role từ mọi
group, kể cả Organization groups, nên không được dùng để suy vai của một trung tâm. [Docs — Keycloak Server
Administration Guide, chương Organizations, mục "Managing groups" và "Mapping organization claims"]

### 2. Bảng `resource`

Một hàng `resource` là đầu mối quyền của một tài nguyên thuộc bất kỳ loại nào. Nội dung chuyên biệt không nằm ở
bảng này. Các cột lõi gồm:

| Cột | Ý nghĩa |
| --- | --- |
| `id` | Khoá chính UUID của tài nguyên. |
| `resource_type` | Loại tài nguyên do feat sở hữu loại đó định nghĩa; feat này dùng loại mẫu để kiểm chứng. |
| `workspace_kind` | Một trong ba giá trị `personal`, `center`, `system`. |
| `owner_type` | Một trong ba giá trị `user`, `organization`, `platform`. |
| `owner_user_sub` | `sub` của chủ sở hữu khi `owner_type = user`; các trường hợp còn lại để trống. |
| `owner_organization_id` | ID của Organization sở hữu khi `owner_type = organization`; còn lại để trống. |
| `collection_id` | ID của `resource` đóng vai trò ngân hàng/bộ sưu tập chứa tài nguyên này; có thể trống. |
| `partition_key` | Khoá phân vùng suy ra từ workspace và chủ sở hữu theo mục 3; không nhận từ client. |
| `custody_kind` | `owned` hoặc `licensed`: thuộc hẳn về chủ sở hữu, hay bản nhận chỉ được cấp quyền giữ và dùng. |
| `license_grant_id` | ID của grant cấp phép giữ và dùng bản nhận; bắt buộc khi `custody_kind = licensed`. |
| `inherited_usage_limits` | Giới hạn sử dụng kế thừa từ nguồn (Organization và/hoặc hành động được phép). |
| `source_resource_id` | ID tài nguyên nguồn nếu tài nguyên này là bản sao hoặc bản bàn giao; có thể trống. |
| `source_version_id` | ID phiên bản nguồn tương ứng; có thể trống. |
| `created_by_sub` | `sub` của người tạo hàng này; chỉ dùng để truy vết, không tham gia quyết định quyền. |
| `created_at`, `updated_at` | Mốc thời gian tạo và cập nhật theo khuôn các bảng hiện có. |

Ràng buộc dữ kiện bắt buộc:

- `personal` đi cùng `owner_type = user` và `owner_user_sub` khác trống; `center` đi cùng
  `owner_type = organization` và `owner_organization_id` khác trống; `system` đi cùng `owner_type = platform`
  và cả hai trường chủ sở hữu cụ thể đều trống.
- `custody_kind = licensed` chỉ đi cùng `owner_type = organization`, và khi đó `license_grant_id`,
  `source_resource_id` cùng `source_version_id` đều khác trống. Mọi tài nguyên còn lại có
  `custody_kind = owned` và `license_grant_id` trống.
- `collection_id`, nếu có, phải trỏ tới một `resource` cùng workspace và cùng chủ sở hữu; với tài nguyên thuộc
  trung tâm, bộ sưu tập phải thuộc đúng Organization sở hữu tài nguyên.
- Chủ sở hữu, `workspace_kind` và `partition_key` không thay đổi bằng bất kỳ thao tác cập nhật tài nguyên thông
  thường nào của lõi. Muốn tài nguyên xuất hiện ở workspace khác phải tạo một `resource` mới, theo quy trình của
  feat sở hữu thao tác đó.
- `source_resource_id` và `source_version_id` chỉ ghi truy vết nguồn. Việc một tài nguyên xuất phát từ một
  phiên bản không cấp cho bất kỳ ai quyền trên tài nguyên nguồn hoặc trên tài nguyên đích.
- Bản nhận `licensed` chỉ tồn tại cùng một grant cấp phép còn nhận diện được qua `license_grant_id`. Trung tâm
  giữ bản nhận chỉ được dùng nó trong đúng điều kiện của grant đó, kể cả thời hạn; việc giữ bản không tự sinh
  quyền sửa, phát hành lại hoặc chia sẻ tiếp, trừ khi grant ghi rõ hành động tương ứng. Bản nhận `licensed` có
  `owner_type = organization` của trung tâm nhận và khoá phân vùng là Organization nhận, nhưng không phải bản
  trung tâm sở hữu: chủ sở hữu của tài nguyên nguồn không thay đổi và được truy vết qua `source_resource_id`.
- Giới hạn sử dụng kế thừa ràng buộc mọi quyết định trên tài nguyên, kể cả quyết định cho chính chủ sở hữu của
  nó. Tạo một bản sao từ một tài nguyên có giới hạn kế thừa thì sao chép lại đúng các giới hạn đó vào bản sao;
  không thao tác nào trong lõi được nới lỏng hoặc xoá giới hạn kế thừa.
- Một ngân hàng/bộ sưu tập là một `resource` có `collection_id` trống và loại do feat tài nguyên định nghĩa.
  Trong feat này, tài nguyên bộ sưu tập thuộc trung tâm được tạo để gắn phân công và kiểm chứng; nó không có
  phiên bản nội dung.

### 3. Workspace, chủ sở hữu và khoá phân vùng

| Workspace | Chủ sở hữu | Khoá phân vùng | Ngữ cảnh được dùng |
| --- | --- | --- | --- |
| Cá nhân | Một tài khoản, theo `owner_user_sub` | `user:<owner_user_sub>` | Mọi trang, theo thành phần `sub` |
| Trung tâm | Một Organization, theo ID | Organization ID | Chỉ trang của đúng Organization đó |
| Hệ thống | Nền tảng | `system` | Chỉ ngữ cảnh nền tảng của `platform-admin` |

Workspace cá nhân đi theo tài khoản, không đi theo Organization. Một giáo viên đang mở trang của một trung tâm
vẫn có thể được quyết định quyền trên tài nguyên cá nhân của chính mình khi endpoint nghiệp vụ khai báo nó phục
vụ workspace cá nhân; ngữ cảnh trung tâm của trang không biến tài nguyên đó thành tài nguyên của trung tâm.

Workspace trung tâm chỉ được quyết định trong request có ngữ cảnh của đúng Organization sở hữu. Việc token của
người gọi còn chứa membership hoặc vai ở Organization đó không đủ nếu trang đang mở thuộc Organization khác.

Workspace hệ thống không suy ra từ vai theo Organization. Chỉ cờ `platform-admin` trong `TenantContext` mới mở
quyền quản trị trên tài nguyên hệ thống, và vai nền tảng đó không tự mở quyền trên tài nguyên cá nhân hoặc tài
nguyên của bất kỳ trung tâm nào.

Khi một tài nguyên cá nhân được cấp cho một trung tâm, Organization nhận chỉ xuất hiện trên grant và, nếu có
bản nhận, trên bản nhận đó. Workspace, chủ sở hữu và khoá phân vùng của tài nguyên nguồn không hề thay đổi vì
việc cấp grant; một tài nguyên cá nhân được chia sẻ cho A rồi cho B vẫn là tài nguyên cá nhân của giáo viên.


### 4. Bảng `resource_version`

Một hàng `resource_version` đại diện cho một phiên bản xác định của một `resource`. Các cột lõi gồm:

| Cột | Ý nghĩa |
| --- | --- |
| `id` | Khoá chính UUID của phiên bản. |
| `resource_id` | ID của `resource` sở hữu phiên bản. |
| `partition_key` | Bằng khoá phân vùng của `resource` sở hữu; dùng cho khoá ngoại hợp thành và RLS. |
| `version_number` | Số nguyên dương, tăng theo từng tài nguyên và không trùng trong một tài nguyên. |
| `status` | Một trong hai giá trị `draft` hoặc `published`. |
| `content_hash` | Dấu vết nội dung do tầng loại tài nguyên cung cấp; feat này dùng giá trị mẫu để kiểm chứng. |
| `created_by_sub` | `sub` của người tạo phiên bản; chỉ dùng để truy vết, không tự sinh quyền sửa. |
| `locked_at` | Mốc thời gian phiên bản bị khoá; có giá trị khi đã phát hành hoặc đã được grant. |
| `published_at` | Mốc thời gian phát hành; chỉ có giá trị khi `status = published`. |
| `created_at`, `updated_at` | Mốc thời gian tạo và cập nhật theo khuôn các bảng hiện có. |

Quy tắc phiên bản bắt buộc:

- Khoá ngoại từ `resource_version` tới `resource` gồm cả `resource_id` và `partition_key`, để một phiên bản
  không thể gắn sang tài nguyên thuộc phân vùng khác.
- Tạo phiên bản đầu tiên cùng lúc với tài nguyên nội dung và mang số 1, trạng thái `draft`, chưa bị khoá.
- Hành động sửa một phiên bản chưa phát hành và chưa bị khoá thuộc về tầng nội dung chuyên biệt của feat sau.
  Ở lõi này, thao tác sửa được kiểm chứng bằng cách tạo một phiên bản nháp mới; lõi không cung cấp thao tác ghi
  đè `content_hash` của một phiên bản đã tồn tại.
- Phát hành chuyển một phiên bản từ `draft` sang `published`, đặt `published_at` và `locked_at`. Sau thời điểm
  đó, quyết định cho hành động `edit_draft` trên chính phiên bản đó luôn là từ chối.
- Tạo grant trên một phiên bản đặt `locked_at` của phiên bản đó nếu chưa có. Thu hồi grant hoặc grant hết hạn
  không mở khoá lại phiên bản đã từng được chia sẻ. Điều kiện bất biến của một phiên bản được quyết bởi dấu
  khoá `locked_at` đã ghi trên chính phiên bản, không suy ra từ việc còn grant nào đang hiệu lực hay không.
- Phiên bản mới không thay đổi trạng thái, dấu vết nội dung hoặc quyền đã cấp trên các phiên bản trước.
- Trạng thái nghiệp vụ ngoài `draft` và `published`, ví dụ trạng thái chờ duyệt của một bản nhận, không thêm
  vào cột `status` này. Các trạng thái đó là dữ kiện của feat loại tài nguyên và đi vào quyết định qua điểm
  tích hợp điều kiện nghiệp vụ ở mục 8.

### 5. Tập hành động lõi và các nguồn quyền

Tập hành động lõi dùng chung gồm:

| Mã hành động | Ý nghĩa ở mức lõi |
| --- | --- |
| `view_content` | Xem nội dung của đúng phiên bản hoặc thông tin nội dung của tài nguyên. |
| `view_answer` | Xem đáp án, lời giải hoặc tiêu chí chấm đi cùng phiên bản. |
| `use` | Dùng đúng phiên bản trong phạm vi mà feat loại tài nguyên sẽ định nghĩa cụ thể. |
| `copy` | Tạo một tài nguyên riêng từ đúng phiên bản. |
| `share` | Tạo grant trên đúng phiên bản cho một chủ thể khác. |
| `submit` | Đưa đúng phiên bản vào quy trình nộp/bàn giao của feat sở hữu quy trình đó. |
| `create_in_collection` | Tạo tài nguyên mới trong đúng ngân hàng/bộ sưu tập. |
| `edit_draft` | Biên tập bản nháp hoặc tạo phiên bản nháp mới của tài nguyên. |
| `review` | Đọc gói được giao và ghi quyết định duyệt hoặc yêu cầu sửa ở feat sở hữu quy trình. |
| `publish` | Phát hành một phiên bản nháp đã đủ điều kiện theo quy tắc của lõi và của loại tài nguyên. |
| `manage_collection` | Quản lý chính ngân hàng/bộ sưu tập và các tài nguyên trong phạm vi của nó. |
| `manage_assignment` | Tạo, thay đổi hoặc gỡ phân công trong đúng phạm vi được quản lý. |

Các hành động là độc lập ở mức quyết định. Được `view_content` không có nghĩa được `view_answer`; được `use`
không có nghĩa được `copy`, `share`, `submit` hoặc `edit_draft`; được `review` không có nghĩa được sửa nội
dung. Riêng `view_answer` không bao giờ được suy ra từ `edit_draft` hoặc `review`: người biên tập hoặc duyệt
một tài nguyên chỉ xem được đáp án khi có một nguồn quyền ghi tường minh hành động `view_answer`. Các feat
loại tài nguyên phải ánh xạ từng thao tác nghiệp vụ của chúng vào các hành động này hoặc định nghĩa hành động
chuyên biệt riêng, không gộp nhiều quyền vào một cờ chung.

Các nguồn quyền được công nhận, theo đúng phạm vi của từng nguồn:

- Chủ sở hữu cá nhân: người có `sub` bằng `owner_user_sub` của một tài nguyên cá nhân được thực hiện các hành
  động `view_content`, `view_answer`, `use`, `copy`, `share`, `submit`, `edit_draft` và `publish` trên tài
  nguyên của chính mình, với ràng buộc trạng thái phiên bản ở mục 4 và giới hạn sử dụng kế thừa từ nguồn ở
  mục 2. Giới hạn kế thừa ràng buộc cả chủ sở hữu: một bản sao cá nhân có nguồn chỉ được dùng trong A thì chủ
  của bản sao cũng không chia sẻ hoặc dùng nó tại B được. Quyền này không áp dụng lên bản sao đã thuộc trung
  tâm vì bản sao là một `resource` khác có chủ sở hữu khác.
- Quyền soạn nền tảng (`personal-author`): điều kiện để tạo tài nguyên cá nhân mới. Chỉ tài khoản có quyền này
  mới tạo được tài nguyên trong workspace cá nhân của chính mình; quyền này không cấp bất kỳ quyền nào trên
  tài nguyên của người khác và không thay thế các nguồn quyền còn lại cho thao tác trên tài nguyên đã tồn tại.
- `platform-admin`: được quản lý và thực hiện các hành động lõi trên tài nguyên thuộc workspace hệ thống.
  Không có quyền mặc định trên tài nguyên cá nhân hoặc tài nguyên của trung tâm.
- `tenant-admin` của Organization hiện hành: được `manage_collection`, `manage_assignment`,
  `create_in_collection`, `view_content`, `edit_draft`, `review`, `publish` và `share` trên tài nguyên mà
  Organization đó sở hữu (`custody_kind = owned`). Trên bản nhận `licensed`, vai này chỉ quản lý việc tiếp
  nhận và phân công; các hành động nội dung trên bản nhận đi theo điều kiện của grant cấp phép giao với phân
  công như ở mục 6. Vai này không cấp quyền trên tài nguyên cá nhân, không cấp `view_answer` ở mức lõi và
  không cho đổi chủ sở hữu của bất kỳ tài nguyên nào.
- Vai `tenant-teacher` hoặc `tenant-member` đứng một mình: không cấp quyền nào trên tài nguyên của trung tâm
  ngoài quyền suy ra từ phân công hoặc grant khớp.
- Phân công: áp dụng theo bảng ở mục 7.
- Grant: áp dụng theo quy tắc ở mục 6.

Hành động `create_in_collection` có phép kiểm riêng, khác các hành động còn lại: nó được quyết định trên cặp
(bộ sưu tập đích C, loại tài nguyên X sắp tạo), không phải quyết định trên chính bộ sưu tập như một tài nguyên
thường — bộ sưu tập có loại khác loại sắp tạo và không có phiên bản nào liên quan. Người gọi được tạo loại X
trong C khi có phân công còn hiệu lực bao phủ hành động này trên C cho loại X, hoặc là `tenant-admin` của
Organization sở hữu C. Phép kiểm này không yêu cầu và không suy ra quyền nào trên nội dung của C.

Không nguồn quyền nào được suy ra từ `created_by_sub`, từ việc cùng là thành viên của một Organization, từ
việc xuất hiện trong thông tin nguồn của một bản sao, hoặc từ `realm_access.roles` ở cấp cao nhất của token.

### 6. Bảng `resource_grant` và quy tắc grant

Ngữ nghĩa nghiệp vụ đã chốt của grant: một grant tường minh theo đúng tài nguyên, đúng phiên bản, đúng chủ
thể nhận và đúng hành động. Lựa chọn triển khai của feat này: mỗi hàng `resource_grant` mang đúng một hành
động, muốn cấp nhiều hành động thì có nhiều hàng và mỗi hàng được đánh giá độc lập. Hình thức lưu trữ này là
lựa chọn triển khai, không phải yêu cầu nghiệp vụ. Các cột lõi gồm:

| Cột | Ý nghĩa |
| --- | --- |
| `id` | Khoá chính UUID của grant. |
| `resource_id` | ID của tài nguyên được cấp. |
| `resource_version_id` | ID của phiên bản được cấp; bắt buộc và phải thuộc đúng `resource_id`. |
| `grantee_type` | Một trong hai giá trị `user` hoặc `organization`. |
| `grantee_user_sub` | `sub` của người nhận khi `grantee_type = user`; trường hợp còn lại để trống. |
| `grantee_organization_id` | ID của Organization nhận khi `grantee_type = organization`; còn lại để trống. |
| `receiving_collection_id` | ID bộ sưu tập tại trung tâm nhận đã tiếp nhận grant; do `tenant-admin` nhận đặt. |
| `action` | Đúng một mã trong tập hành động lõi hoặc hành động chuyên biệt đã được feat loại định nghĩa. |
| `mode` | `temporary` hoặc `long_term` khi chủ thể nhận là Organization; grant cho người dùng để trống. |
| `starts_at` | Mốc thời gian grant bắt đầu có hiệu lực. |
| `expires_at` | Mốc thời gian grant hết hiệu lực; bắt buộc có với `mode = temporary`. |
| `revocation_terms` | Điều kiện thu hồi được ghi khi cấp grant dùng lâu dài; có thể trống. |
| `grantor_sub` | `sub` của người tạo grant; chỉ dùng để truy vết và kiểm tra thao tác thu hồi tạm thời. |
| `source_partition_key` | Khoá phân vùng của tài nguyên nguồn, do server sao chép khi tạo grant. |
| `partition_key` | Khoá phân vùng của phía nhận: Organization ID hoặc `user:<grantee_user_sub>`. |
| `revoked_at` | Mốc thời gian grant bị thu hồi; grant còn giá trị này thì không còn hiệu lực. |
| `created_at`, `updated_at` | Mốc thời gian tạo và cập nhật theo khuôn các bảng hiện có. |

Quy tắc đánh giá grant bắt buộc:

- Grant chỉ có hiệu lực khi `revoked_at` trống, thời điểm đánh giá không trước `starts_at` và, nếu có
  `expires_at`, thời điểm đánh giá phải trước mốc hết hạn đó.
- Grant cho Organization chỉ áp dụng khi Organization của ngữ cảnh hiện tại bằng đúng
  `grantee_organization_id`. Việc người gọi là thành viên của Organization được cấp trong một ngữ cảnh khác
  không làm grant có hiệu lực tại trang đang mở.
- Grant cho Organization xác lập phạm vi quyền tối đa mà trung tâm được nhận trên phiên bản đó; nó không trực
  tiếp cấp quyền cho từng thành viên của trung tâm. Một thành viên chỉ thực hiện được một hành động qua grant
  này khi đồng thời: grant còn hiệu lực cho đúng hành động đó, grant đã được tiếp nhận vào một bộ sưu tập của
  trung tâm (`receiving_collection_id` khác trống) và chính thành viên đó có phân công còn hiệu lực bao phủ
  hành động đó trên bộ sưu tập tiếp nhận, theo mục 7. Hành động được công nhận là giao của hai nguồn này.
  Chưa được tiếp nhận thì chưa thành viên nào dùng được qua grant; `tenant-admin` của trung tâm nhận chỉ thấy
  grant để tiếp nhận và quản lý phân công.
- Tạo grant không bao giờ thay đổi chủ sở hữu, workspace hoặc khoá phân vùng của tài nguyên nguồn. Organization
  nhận chỉ được ghi trên grant và trên bản nhận nếu có, không ghi lên tài nguyên nguồn.
- Grant cho người dùng chỉ áp dụng khi `sub` của người gọi bằng đúng `grantee_user_sub`; nó không lan cho các
  thành viên khác của cùng một trung tâm.
- Grant không áp dụng cho phiên bản khác, tài nguyên khác, hành động khác hoặc Organization khác, kể cả khi
  các đối tượng đó có cùng chủ sở hữu hoặc cùng người tạo.
- Grant dùng lâu dài không phụ thuộc vào việc người cấp còn là thành viên của Organization được cấp hay còn
  giữ một phân công tại đó ở thời điểm đánh giá. Grant xem tạm thời bị thu hồi hoặc hết hạn thì không còn
  hiệu lực; cơ chế tự kết thúc grant tạm thời khi người cấp rời trung tâm thuộc feat xử lý vòng đời đó.
- Tạo grant không thay đổi chủ sở hữu của tài nguyên, không sao chép các grant khác của phiên bản nguồn và
  không tạo bản sao nội dung tại phía nhận. Bản sao hoặc snapshot đi kèm grant dùng lâu dài thuộc F10/F11.
- Người được tạo grant trên một phiên bản khi chính họ có hành động `share` trên đúng phiên bản đó theo một
  nguồn quyền ở mục 5, và đồng thời grant sắp tạo nằm trong phạm vi người đó được phép cấp lại. Phạm vi cấp
  lại gồm cả hành động của grant mới và phạm vi chủ thể nhận: hành động mới không được vượt quá các hành động
  mà giới hạn sử dụng kế thừa của nguồn cho phép cấp tiếp, và chủ thể nhận mới phải nằm trong phạm vi nguồn
  cho phép. Một người có quyền trên phiên bản chỉ nhờ một grant thì mặc định không có quyền cấp lại; việc cấp
  lại chỉ được phép khi grant hoặc giới hạn của nguồn ghi rõ quyền cấp lại cho đúng hành động và phạm vi đó.
  Việc tạo grant đặt `locked_at` cho phiên bản như quy tắc ở mục 4 và sao chép khoá phân vùng của tài nguyên
  nguồn vào `source_partition_key`; client không được tự cung cấp giá trị này.
- Ở bề mặt kiểm chứng của feat này, grant cho Organization nhận đúng Organization của ngữ cảnh hiện tại; thân
  request không mang ID của Organization nhận. Grant cho người dùng mang `sub` của người nhận trong thân
  request vì đó là dữ kiện đích của thao tác.
- Thao tác thu hồi ở feat này chỉ áp dụng cho grant tạm thời và chỉ do đúng người đã cấp thực hiện. Với grant
  dùng lâu dài, phần đã chốt là: bản nhận dùng lâu dài tồn tại sau khi giáo viên rời trung tâm và người đã
  chia sẻ không đơn phương thu hồi hay xoá được grant và bản nhận đó. Feat này ghi `revocation_terms` của
  grant dùng lâu dài và tôn trọng `revoked_at` khi đánh giá; ngoại lệ thu hồi và thẩm quyền xử lý ngoại lệ
  chưa chốt, ghi ở mục Điểm để ngỏ.

### 7. Bảng `collection_assignment` và quy tắc phân công

Một hàng `collection_assignment` phân công một người thực hiện một vai trên các tài nguyên thuộc một ngân
hàng/bộ sưu tập và một loại tài nguyên, trong đúng một Organization. Các cột lõi gồm:

| Cột | Ý nghĩa |
| --- | --- |
| `id` | Khoá chính UUID của phân công. |
| `organization_id` | ID của Organization mà phân công thuộc về. |
| `partition_key` | Bằng `organization_id`; dùng cho khoá ngoại hợp thành và RLS. |
| `collection_id` | ID của `resource` đóng vai trò ngân hàng/bộ sưu tập được phân công. |
| `resource_type` | Loại tài nguyên mà phân công áp dụng trong bộ sưu tập đó. |
| `assignee_sub` | `sub` của người được phân công. |
| `assignment_role` | Một vai trong bảng ánh xạ ngay dưới đây. |
| `assigned_by_sub` | `sub` của người tạo phân công; chỉ dùng để truy vết. |
| `revoked_at` | Mốc thời gian phân công bị gỡ; phân công còn giá trị này thì không còn hiệu lực. |
| `created_at`, `updated_at` | Mốc thời gian tạo và cập nhật theo khuôn các bảng hiện có. |

Ánh xạ vai phân công thành hành động lõi:

| Vai phân công | Hành động được công nhận trong đúng phạm vi phân công |
| --- | --- |
| `bank-manager` | `manage_collection`, `view_content` |
| `bank-editor` | `create_in_collection`, `view_content`, `edit_draft` |
| `bank-publisher` | `view_content`, `review`, `publish` |
| `resource-editor` | `view_content`, `edit_draft` |
| `resource-reviewer` | `view_content`, `review` |
| `resource-publisher` | `view_content`, `publish` |

Không vai nào trong gói phân công lõi ở trên gồm `view_answer`. Khi công việc biên tập hoặc duyệt của một
loại tài nguyên cần xem đáp án, feat sở hữu loại đó phải ghi tường minh `view_answer` đến từ đâu: một grant
riêng cho hành động này, hoặc một gói vai mở rộng do chính feat đó định nghĩa. Lõi này không tự bổ sung
`view_answer` cho editor hoặc reviewer.

Quy tắc đánh giá phân công bắt buộc:

- Phân công chỉ áp dụng khi đồng thời khớp cả bốn dữ kiện: Organization của ngữ cảnh hiện tại,
  `collection_id` của tài nguyên, `resource_type` của tài nguyên và `sub` của người gọi. Riêng quyết định qua
  grant cho Organization ở mục 6 thì `collection_id` dùng để đối chiếu là bộ sưu tập tiếp nhận ghi trên grant.
- Phân công không áp dụng cho tài nguyên cá nhân hoặc tài nguyên hệ thống, kể cả khi người được phân công là
  chủ sở hữu cá nhân hoặc `platform-admin`.
- Quyết định theo phân công còn yêu cầu request đang ở chế độ thành viên của đúng Organization đó trong
  `TenantContext`. Một hàng phân công tồn tại trong database không tự biến một khách thành thành viên.
- `bank-manager` không được sửa nội dung, duyệt hoặc phát hành chỉ nhờ vai quản lý ngân hàng; các quyền đó cần
  phân công biên tập, duyệt hoặc phát hành tương ứng. Tương tự, editor không được phát hành, reviewer không
  được sửa nội dung và publisher không được sửa nội dung chỉ nhờ vai của mình.
- Ở bề mặt kiểm chứng của feat này, thao tác tạo và gỡ phân công do `tenant-admin` của Organization hiện
  hành thực hiện. Việc `bank-manager` có được uỷ quyền tạo hoặc gỡ từng loại phân công nào, đặc biệt là vai
  `bank-manager`, phải được chốt cùng F10 trước khi triển khai chức năng phân công; chừng nào chưa chốt, bề
  mặt kiểm chứng chỉ cho `tenant-admin` tạo và gỡ phân công và nghiệm thu của feat này không phụ thuộc điểm đó.


### 8. Thuật toán quyết định quyền

Với mỗi yêu cầu quyết định, dịch vụ thực hiện đúng thứ tự sau:

1. Lấy danh tính và ngữ cảnh từ `TenantContext`. Nếu request chưa xác thực thì dừng ở tầng xác thực của F0/F3;
   dịch vụ quyết định không tự dựng danh tính thay thế.
2. Xác định các phân vùng ứng viên chỉ từ `TenantContext`: phân vùng cá nhân của người gọi, Organization hiện
   hành nếu request đang ở chế độ thành viên, và phân vùng hệ thống nếu người gọi là `platform-admin`. Tìm tài
   nguyên theo ID trong các phân vùng ứng viên đó. Nếu tìm thấy, kiểm tra phiên bản thuộc đúng tài nguyên.
3. Nếu không tìm thấy tài nguyên ở bước 2, tìm grant theo ID tài nguyên trong các phân vùng của phía nhận mà
   người gọi đại diện: phân vùng cá nhân của người gọi và Organization hiện hành. Một grant tìm thấy cung cấp
   `source_partition_key`; dùng khoá đó mở một phiên riêng của phân vùng nguồn để đọc tài nguyên và phiên bản
   rồi kiểm tra grant trỏ đúng hai đối tượng đó. Không có grant phù hợp thì trả từ chối.
4. Kiểm tra nguồn quyền của chủ sở hữu và vai toàn cục có phạm vi hẹp: chủ sở hữu cá nhân đối với tài nguyên
   cá nhân, `platform-admin` đối với tài nguyên hệ thống. Không áp dụng hai nguồn này cho tài nguyên đã thuộc
   một Organization.
5. Nếu tài nguyên thuộc một Organization với `custody_kind = owned`, chỉ tiếp tục các nguồn quyền theo trung
   tâm khi Organization của ngữ cảnh hiện tại bằng đúng Organization sở hữu. Trong phạm vi đó, lần lượt đánh
   giá vai `tenant-admin` và các phân công còn hiệu lực.
6. Đánh giá đường grant cho Organization, áp dụng cho tài nguyên thuộc bất kỳ workspace nào, kể cả tài nguyên
   cá nhân của giáo viên: nếu có grant còn hiệu lực mà chủ thể nhận là Organization của ngữ cảnh hiện tại,
   khớp đúng tài nguyên, phiên bản và hành động, thì kiểm tra tiếp phân công của chính người gọi trên bộ sưu
   tập tiếp nhận ghi trên grant, theo mục 6. Hành động chỉ được công nhận khi nằm trong giao của grant và
   phân công. Đây là đường cho phép ca cốt lõi: tài nguyên cá nhân được grant cho Organization A thì người
   của A dùng được theo grant cộng phân công, trong khi tài nguyên vẫn ở phân vùng cá nhân của giáo viên.
7. Đánh giá các grant mà chủ thể nhận là chính người gọi, theo đúng tài nguyên, phiên bản và hành động.
8. Áp dụng các ràng buộc thu hẹp, kể cả khi một nguồn quyền ở các bước trên đã khớp: trạng thái phiên bản
   (`edit_draft` bị từ chối trên phiên bản đã phát hành hoặc đã bị khoá theo dấu `locked_at` đã ghi; `publish`
   chỉ được công nhận trên phiên bản đang là `draft`; các hành động khác không tự bị mở chỉ vì phiên bản đang
   ở trạng thái `published`); giới hạn sử dụng kế thừa từ nguồn ở mục 2 (hành động hoặc Organization hiện tại
   nằm ngoài giới hạn thì từ chối, kể cả với chủ sở hữu); và điều kiện nghiệp vụ do feat loại tài nguyên gắn
   lên, ví dụ bản nhận đang chờ duyệt thì hành động `use` bị chặn và `view_content` chỉ còn cho người duyệt
   theo ánh xạ điều kiện đó. Một tài nguyên ở trạng thái bản nhận `licensed` còn phải qua điều kiện của grant
   cấp phép: grant đó hết hiệu lực thì mọi hành động nội dung trên bản nhận đều bị từ chối.
9. Nếu không có nguồn quyền nào khớp đầy đủ sau các ràng buộc ở bước 8 thì trả từ chối mặc định.

Mỗi nguồn dữ kiện nằm ở một phân vùng được đọc trong phiên dữ kiện của đúng phân vùng đó: tài nguyên và phiên
bản trong phân vùng của chủ sở hữu, phân công trong phân vùng của Organization, grant trong phân vùng của phía
nhận. Một phiên không đổi khoá tenant giữa chừng. Dịch vụ điều phối các bước đọc riêng này và không dùng một
phiên "root" hoặc tắt bộ lọc tenant để đọc chéo.

Với thao tác ghi, bước quyết định phải chạy trước thao tác ghi, và giao dịch ghi đánh giá lại toàn bộ quyết
định bằng dữ kiện tại thời điểm ghi — gồm quyền, grant và phân công — chứ không chỉ kiểm lại sự tồn tại của
tài nguyên, quan hệ của phiên bản và trạng thái phiên bản. Một grant hoặc phân công bị thu hồi giữa lần kiểm
trước và lúc ghi thì thao tác ghi bị từ chối và không ghi một phần.

Tạo grant là thao tác ghi qua hai phân vùng: ghi hàng grant ở phân vùng của phía nhận và đặt `locked_at` ở
phân vùng của tài nguyên nguồn. Quy tắc hoàn tất: đặt khoá phiên bản ở phân vùng nguồn trước; nếu bước này
thất bại thì dừng, không tạo grant. Nếu đã khoá mà bước tạo grant ở phân vùng nhận thất bại thì grant không
tồn tại, thao tác báo thất bại, và phiên bản giữ nguyên `locked_at` đã đặt — khoá dư là trạng thái an toàn,
không mở khoá lại theo mục 4. Thử lại thao tác sau thất bại là an toàn: việc đặt khoá có tính lũy đẳng và
quyết định tạo grant được đánh giá lại từ đầu.

### 9. Row Level Security và quyền của tài khoản runtime

Các bảng lõi tiếp tục khuôn RLS của F3: tài khoản Flyway là tài khoản quản trị sở hữu schema, tài khoản runtime
`lms_server_app` không phải superuser, không có `BYPASSRLS`, không sở hữu bảng và chỉ có quyền
`SELECT/INSERT/UPDATE/DELETE` trên các bảng của ứng dụng.

Yêu cầu nghiệp vụ đã chốt là cách ly theo phân vùng ở cả đường đọc và đường ghi: mỗi bảng lõi có cột
`partition_key`, bật `ENABLE ROW LEVEL SECURITY`, và policy so sánh `partition_key` của hàng với biến giao
dịch `app.partition_key` bằng cả `USING` và `WITH CHECK`. Lựa chọn triển khai của feat này là dùng đúng một
policy cho mọi lệnh trên mỗi bảng; số lượng policy là chi tiết triển khai, không phải yêu cầu nghiệp vụ.

- `resource` và `resource_version`: khoá là khoá phân vùng của chủ sở hữu tài nguyên; với bản nhận `licensed`
  là Organization nhận.
- `collection_assignment`: khoá là ID của Organization được phân công.
- `resource_grant`: khoá là khoá phân vùng của phía nhận grant.
- `resource_dependency`: khoá là khoá phân vùng của chủ sở hữu tài nguyên phụ thuộc.
- `handover_request`, `submission` và `submission_item`: khoá là ID của Organization nhận bàn giao.

Điểm chặn đặt biến ngữ cảnh theo giao dịch của F3 được dùng lại cho từng phiên dữ kiện ở mục 8. Không đặt biến
thì policy không cho thấy hàng nào và không cho ghi hàng nào, kể cả khi truy vấn được thực hiện trực tiếp bằng
tài khoản runtime.

Migration của feat này tạo khoá chính, khoá ngoại, ràng buộc kiểm tra và chỉ mục tối thiểu cho các quan hệ đã
nêu. Không tạo chỉ mục hoặc bảng phụ cho nhu cầu tìm kiếm chưa xuất hiện trong feat này.

### 10. Bề mặt kiểm chứng API

Các endpoint dưới đây là bề mặt kiểm chứng của khung, không phải bề mặt sản phẩm và không phải endpoint
nghiệp vụ của câu hỏi, đề hoặc ma trận. Chúng chỉ thao tác trên loại tài nguyên mẫu của feat này và chỉ được
bật ở môi trường dev/test; ở môi trường production chúng không được bật. Tất cả đều yêu cầu xác thực như các
endpoint khác của `apps/server` và đều đi qua dịch vụ quyết định ở mục 8, trừ thao tác tạo tài nguyên đầu
tiên vốn là điểm vào để lập dữ kiện mẫu theo quy tắc workspace.

- `POST /api/access-check/resources`: tạo một tài nguyên mẫu và, nếu không phải bộ sưu tập, tạo phiên bản nháp
  số 1. Thân request gồm `resourceType`, `workspace`, `collectionId` khi cần và `contentHash` của phiên bản
  đầu. Chủ sở hữu, người tạo và khoá phân vùng do server suy ra từ `TenantContext` và workspace được khai báo.
  Tạo tài nguyên cá nhân yêu cầu quyền soạn nền tảng `personal-author` của chính người gọi. Tạo tài nguyên
  trung tâm khác bộ sưu tập yêu cầu quyết định `create_in_collection` trên cặp (bộ sưu tập đích, `resourceType`
  sắp tạo) theo phép kiểm riêng ở mục 5; tạo chính bộ sưu tập thuộc trung tâm yêu cầu vai `tenant-admin` của
  Organization hiện hành; tạo tài nguyên hệ thống yêu cầu `platform-admin`.
- `GET /api/access-check/resources/{resourceId}`: trả metadata của tài nguyên và chỉ các phiên bản mà người
  gọi có `view_content` trên đúng phiên bản đó. Nếu không có quyền xem bất kỳ phần nào thì trả 403.
- `POST /api/access-check/resources/{resourceId}/versions`: tạo phiên bản nháp tiếp theo với `contentHash`
  trong thân request. Thao tác yêu cầu `edit_draft` trên tài nguyên và không sửa bất kỳ phiên bản cũ nào.
- `POST /api/access-check/resources/{resourceId}/versions/{versionId}/publish`: phát hành đúng phiên bản
  được nêu. Thao tác yêu cầu `publish` trên đúng phiên bản đó và áp dụng quy tắc khoá ở mục 4.
- `POST /api/access-check/assignments`: tạo một phân công trong Organization của ngữ cảnh hiện tại. Thân
  request gồm `collectionId`, `assigneeSub`, `resourceType` và `assignmentRole`. Thao tác chỉ dành cho
  `tenant-admin` của Organization hiện hành theo giới hạn ở mục 7.
- `POST /api/access-check/assignments/{assignmentId}/revoke`: gỡ đúng phân công đó bằng cách đặt `revoked_at`.
  Thao tác chỉ dành cho `tenant-admin` của đúng Organization sở hữu phân công.
- `POST /api/access-check/grants`: tạo một grant trên đúng một phiên bản. Thân request gồm `resourceId`,
  `resourceVersionId`, `action`, chủ thể nhận, `mode` và thời hạn áp dụng. Thao tác yêu cầu `share` trên đúng
  phiên bản đó và kiểm tra phạm vi được phép cấp lại theo quy tắc ở mục 6 trước khi ghi.
- `POST /api/access-check/grants/{grantId}/reception`: `tenant-admin` của trung tâm nhận đặt
  `receiving_collection_id` cho một grant cho Organization của mình, tức tiếp nhận grant vào một bộ sưu tập
  của trung tâm để phân công theo mục 6 bắt đầu áp dụng được.
- `POST /api/access-check/handover-requests`: `tenant-admin` của Organization hiện hành tạo một yêu cầu nộp
  theo mục 13. Thân request gồm `sub` của giáo viên phải nộp, phạm vi loại tài nguyên và bộ sưu tập đích.
- `POST /api/access-check/handover-requests/{requestId}/submissions`: giáo viên được yêu cầu nộp các phiên
  bản đã chọn theo mục 13. Lõi kiểm `submit` trên từng mục, tạo các tài nguyên mẫu thuộc trung tâm cùng biên
  nhận; một mục không đủ quyền thì toàn bộ lần nộp bị từ chối.
- `POST /api/access-check/grants/{grantId}/revoke`: thu hồi một grant tạm thời do chính người gọi đã cấp.
  Thao tác đặt `revoked_at`; grant dùng lâu dài không đi qua endpoint này trong feat này.
- `POST /api/access-check/decisions`: hỏi quyết định cho một hành động mà không thực hiện hành động đó. Thân
  request gồm `resourceId`, `resourceVersionId` khi hành động áp dụng lên phiên bản và `action`. Đáp ứng gồm
  `allowed` và mã lý do. Endpoint này không trả nội dung của tài nguyên.

Khi quyết định là từ chối, các endpoint thao tác ở trên trả 403 và không thay đổi dữ kiện. Khi định danh tài
nguyên hoặc phiên bản không tồn tại, endpoint trả 404. Khi quan hệ giữa tài nguyên và phiên bản không khớp,
thao tác bị từ chối và không ghi dữ kiện.

### 11. Điều kiện phía Keycloak và dữ kiện thử

Feat này không thay đổi cấu trúc Keycloak ngoài các điều kiện đã có của F3:

- Client scope `organization` có mapper Organization Membership với "Add organization id" và mapper
  Organization Group Membership với "Add group role mappings".
- Vai `tenant-admin`, `tenant-teacher` và `tenant-member` đến với người dùng qua Organization groups của đúng
  Organization; `platform-admin` là realm role của nền tảng.
- Quyền soạn nền tảng `personal-author` được cấp ở cấp nền tảng cho tài khoản giáo viên, không đến từ
  Organization groups và không gắn với membership của bất kỳ trung tâm nào.
- Các tài khoản và ngữ cảnh thử của F3 được dùng lại: hai trung tâm tương ứng với hai Organization khác nhau,
  tài khoản thành viên từng trung tâm, tài khoản có vai khác nhau ở hai trung tâm, tài khoản tự do và tài khoản
  `platform-admin`.

Dữ kiện mẫu của lõi quyền được tạo qua các endpoint kiểm chứng. Riêng ca cần chứng minh `created_by` không
sinh quyền, dữ kiện sắp xếp được phép chèn trực tiếp một tài nguyên thuộc trung tâm có `created_by_sub` khác
chủ sở hữu Organization, vì API tạo tài nguyên luôn tự đặt `created_by_sub` bằng người gọi và không cho giả
lập giá trị đó. Việc chèn này chỉ là sắp xếp dữ kiện kiểm chứng, không phải một đường ghi của ứng dụng.

### 12. Bảng `resource_dependency`

Một hàng `resource_dependency` ghi việc một phiên bản của một tài nguyên dùng tới một phiên bản xác định của
một tài nguyên khác, ví dụ một phiên bản đề dùng các phiên bản câu cụ thể. Các cột lõi gồm:

| Cột | Ý nghĩa |
| --- | --- |
| `id` | Khoá chính UUID của quan hệ phụ thuộc. |
| `resource_id` | ID của tài nguyên có phiên bản phụ thuộc. |
| `resource_version_id` | ID của phiên bản phụ thuộc; phải thuộc đúng `resource_id`. |
| `depends_on_resource_id` | ID của tài nguyên được dùng tới. |
| `depends_on_version_id` | ID của phiên bản được dùng tới; phải thuộc đúng `depends_on_resource_id`. |
| `partition_key` | Khoá phân vùng của chủ sở hữu tài nguyên phụ thuộc. |
| `created_at`, `updated_at` | Mốc thời gian tạo và cập nhật theo khuôn các bảng hiện có. |

Quan hệ phụ thuộc chỉ phục vụ truy vết và làm căn cứ để các feat loại tài nguyên áp giới hạn sử dụng kế thừa
khi đóng gói, chia sẻ hoặc bàn giao một tài nguyên có thành phần phụ thuộc. Quan hệ này tự nó không cấp bất
kỳ quyền nào trên tài nguyên được dùng tới; mọi quyết định vẫn qua thuật toán ở mục 8.

### 13. Phần bàn giao dùng chung: `handover_request`, `submission`, `submission_item`

Phần bàn giao dùng chung thuộc feat này; F10 và F11 chỉ định nghĩa phạm vi theo loại trên phần dùng chung
này và tham chiếu tới nó, không định nghĩa lại.

Bảng `handover_request` là yêu cầu nộp do một trung tâm tạo cho một giáo viên:

| Cột | Ý nghĩa |
| --- | --- |
| `id` | Khoá chính UUID của yêu cầu. |
| `organization_id` | ID của Organization tạo yêu cầu và là nơi nhận bàn giao. |
| `partition_key` | Bằng `organization_id`. |
| `requested_teacher_sub` | `sub` của giáo viên được yêu cầu nộp. |
| `resource_types` | Phạm vi loại tài nguyên yêu cầu nộp, do trung tâm ghi. |
| `destination_collection_id` | ID của bộ sưu tập tại trung tâm mà bản nhận sẽ vào. |
| `reuse_rights` | Quyền tái sử dụng mà trung tâm cần nhận, do trung tâm ghi. |
| `status` | `open` hoặc `closed`. |
| `created_by_sub` | `sub` của người tạo yêu cầu; chỉ dùng để truy vết. |
| `created_at`, `updated_at` | Mốc thời gian tạo và cập nhật theo khuôn các bảng hiện có. |

Bảng `submission` là biên nhận của một lần nộp theo một yêu cầu, và `submission_item` là từng mục trong biên
nhận đó:

| Cột | Ý nghĩa |
| --- | --- |
| `id` | Khoá chính UUID của lần nộp / của mục nộp. |
| `handover_request_id` | ID của yêu cầu mà lần nộp đáp ứng; chỉ có ở `submission`. |
| `submission_id` | ID của lần nộp chứa mục này; chỉ có ở `submission_item`. |
| `partition_key` | ID của Organization nhận bàn giao. |
| `submitter_sub` | `sub` của giáo viên nộp; chỉ có ở `submission`. |
| `source_resource_id`, `source_version_id` | Tài nguyên và phiên bản nguồn của mục; chỉ có ở `submission_item`. |
| `received_resource_id`, `received_version_id` | Bản thuộc trung tâm được tạo cho mục; chỉ có ở `submission_item`. |
| `submitted_at` | Mốc thời gian nộp thành công; chỉ có ở `submission`. |
| `created_at`, `updated_at` | Mốc thời gian tạo và cập nhật theo khuôn các bảng hiện có. |

Quy tắc của phần bàn giao dùng chung:

- Yêu cầu nộp không cấp cho trung tâm bất kỳ quyền đọc nào trên kho cá nhân của giáo viên. Người của trung tâm
  chỉ thấy yêu cầu và các biên nhận thuộc trung tâm mình.
- Chỉ giáo viên được nêu trong yêu cầu mới nộp được theo yêu cầu đó. Giáo viên chọn các phiên bản cụ thể;
  dịch vụ kiểm hành động `submit` trên từng mục đã chọn. Một mục không đủ quyền thì toàn bộ lần nộp bị từ
  chối và không tạo bản nhận hay biên nhận nào.
- Nộp thành công tạo cho mỗi mục một `resource` mới thuộc Organization nhận với `custody_kind = owned`, nội
  dung lấy từ đúng phiên bản nguồn đã chọn, đồng thời ghi `submission` và các `submission_item` làm biên nhận.
  Bản gốc cá nhân của giáo viên giữ nguyên.
- Nội dung biên nhận — yêu cầu, người nộp, thời điểm, phiên bản nguồn, bản đã nhận và phạm vi đã chọn — là
  bất biến sau khi tạo. Bản nhận bắt đầu ở trạng thái chờ duyệt, được áp qua điểm tích hợp điều kiện nghiệp
  vụ ở mục 8: duyệt sau đó chỉ quyết định bản nhận có được đưa vào sử dụng hay không, không quyết định quyền
  sở hữu, vì sở hữu đã chuyển tại thời điểm nộp thành công.
- Phạm vi một lần nộp gồm những gì theo từng loại tài nguyên — nộp một đề có gồm các câu trong đề hay không —
  do F10/F11 định nghĩa và thực thi trên phần dùng chung này.

## Việc con dự kiến

- [ ] Migration Flyway cho `resource`, `resource_version`, `resource_grant`, `collection_assignment`,
      `resource_dependency`, `handover_request`, `submission` và `submission_item`, gồm ràng buộc
      workspace/chủ sở hữu/`custody_kind`, khoá ngoại hợp thành và RLS theo khuôn F3.
- [ ] Entity, repository và dịch vụ quản lý tối thiểu cho tài nguyên mẫu, phiên bản, grant, phân công, quan hệ
      phụ thuộc và phần bàn giao dùng chung.
- [ ] Dịch vụ quyết định quyền dùng chung, nhận `TenantContext` của F3 và đánh giá chủ sở hữu, vai theo
      Organization, phân công, grant, trạng thái phiên bản, giới hạn kế thừa và điều kiện nghiệp vụ theo đúng
      thuật toán ở mục 8.
- [ ] Phần bàn giao dùng chung theo mục 13: yêu cầu nộp, kiểm quyền từng mục, tạo bản thuộc trung tâm và biên
      nhận bất biến, kiểm chứng bằng tài nguyên mẫu.
- [ ] Các endpoint `/api/access-check/...` cho tài nguyên mẫu, phiên bản, phân công, grant, bàn giao và hỏi
      quyết định.
- [ ] Tích hợp quy tắc khoá phiên bản khi phát hành hoặc tạo grant, bảo đảm sửa luôn tạo phiên bản nháp mới.
- [ ] Test tự động cho mô hình dữ kiện, thuật toán quyết định, RLS và các endpoint kiểm chứng.
- [ ] Kiểm chứng runtime bằng hai ngữ cảnh trung tâm, tài khoản tự do và tài khoản `platform-admin` theo mục
      Nghiệm thu; đồng thời chạy hồi quy cho `GET /api/profile` và các endpoint kiểm chứng của F3.

## Quyết định liên quan

Các quyết định dưới đây được viết lại trọn vẹn; đặc tả này dựa trên đúng các nội dung này.

1. Có một realm Keycloak duy nhất tên `giaokhoa`; mỗi trung tâm là một Organization trong realm. Keycloak giữ
   danh tính, membership và vai theo Organization. LMS không đọc hoặc ghi trực tiếp database của Keycloak và
   chỉ tích hợp qua các kênh Keycloak công bố. Cấu trúc Keycloak ở môi trường dev được dựng tay trong Admin
   Console, không viết code cấu hình hoặc đồng bộ Keycloak.
2. Quyền trên tài nguyên nằm ở API của LMS. Keycloak không chấm quyền theo từng tài nguyên, không đăng ký tài
   nguyên lên Authorization Services, không tạo role theo từng tài nguyên và không giữ grant hoặc phân công
   của LMS. Quyết định quyền tại API dùng danh tính và membership từ token, vai theo Organization, cùng dữ
   kiện tài nguyên, phiên bản, grant và phân công lưu trong database của LMS.
3. Ngữ cảnh của request do khung F3 cung cấp. Vai theo Organization đọc tại
   `organization[<alias>].realm_access.roles` của đúng Organization của trang đang mở. Claim `realm_access` ở
   cấp cao nhất chỉ dùng cho vai nền tảng `platform-admin`, không dùng để suy ra vai trong bất kỳ Organization
   nào. Khoá dữ kiện của trung tâm là Organization ID; alias chỉ dùng để chọn Organization và hiển thị.
4. Tài nguyên có ba workspace. Workspace cá nhân gắn với tài khoản và dùng được khi chủ sở hữu làm tự do hoặc
   tham gia nhiều trung tâm. Workspace trung tâm gắn với đúng một Organization. Workspace hệ thống thuộc nền
   tảng. Tài nguyên do giáo viên soạn mặc định thuộc workspace cá nhân của giáo viên, kể cả khi thao tác diễn
   ra tại trang của một trung tâm; nơi thao tác và người yêu cầu công việc không tự quyết định chủ sở hữu.
   Việc soạn tài nguyên cá nhân cần một quyền nền tảng riêng (`personal-author`) cấp cho tài khoản giáo viên,
   độc lập với membership và vai `tenant-teacher`; không phải tài khoản nào đăng nhập được cũng là người soạn.
5. Mỗi tài nguyên có đúng một chủ sở hữu. "Thuộc trung tâm", "do giáo viên tạo" và "được chia sẻ" là ba thuộc
   tính khác nhau. `created_by` chỉ phục vụ truy vết; quyền sửa không suy ra từ `created_by`. Chủ sở hữu không
   bị thay đổi bằng một thao tác cập nhật tài nguyên thông thường.
6. Nội dung theo phiên bản là lõi của mô hình. Phiên bản đã chia sẻ, đã nộp hoặc đã phát hành là bất biến ở
   mức không được ghi đè để dùng thay cho bản cũ; sửa bằng phiên bản nháp mới hoặc bằng một tài nguyên riêng.
   Tính bất biến của một phiên bản được quyết bởi dấu khoá đã ghi trên phiên bản, không suy từ việc còn grant
   nào đang hiệu lực. Một bản sao là một tài nguyên mới có ID mới và truy vết nguồn; bản sao không kế thừa
   grant hoặc phân công của nguồn, nhưng giữ nguyên các giới hạn sử dụng của nguồn và các giới hạn đó ràng
   buộc cả chủ của bản sao.
7. Quyền tại trung tâm theo phân công lưu trong database của LMS, theo ngân hàng/bộ sưu tập và loại tài
   nguyên. Các vai phân công gồm `bank-manager`, `bank-editor`, `bank-publisher`, `resource-editor`,
   `resource-reviewer` và `resource-publisher`. Vai `tenant-teacher` không tự cho quyền sửa tài nguyên của
   trung tâm. `tenant-admin` quản lý phân công và tài nguyên đã thuộc trung tâm của mình, nhưng không được
   xem hoặc sửa tài nguyên cá nhân của giáo viên chỉ nhờ vai admin.
8. Share là cấp grant, không phải chuyển chủ sở hữu. Grant phải tường minh theo tài nguyên, phiên bản, chủ
   thể nhận và hành động, và không bao giờ thay đổi chủ sở hữu, workspace hay phân vùng của tài nguyên nguồn;
   Organization nhận nằm trên grant và trên bản nhận. Grant cho Organization là phạm vi quyền trung tâm được
   nhận; thành viên của trung tâm chỉ truy cập qua phân công, không phải mọi thành viên đều có quyền trực
   tiếp. Share cho trung tâm có ba chế độ cần phân biệt: xem tạm thời; dùng lâu dài; và đã thuộc trung tâm đối
   với bản đã nộp. Bản trung tâm được cấp quyền giữ và dùng (share lâu dài) khác bản trung tâm sở hữu: trung
   tâm chỉ dùng bản nhận trong đúng điều kiện của grant cấp phép, kể cả thời hạn, và không tự có quyền sửa,
   phát hành lại hoặc chia sẻ tiếp trừ khi grant ghi rõ. Bản nhận dùng lâu dài tồn tại sau khi giáo viên rời
   trung tâm; người đã chia sẻ không đơn phương thu hồi hay xoá được grant và bản nhận dùng lâu dài. Grant ở
   trung tâm này không cấp quyền ở trung tâm khác và không mở quyền trên các phiên bản chưa được cấp.
9. Tài nguyên chỉ thuộc về trung tâm qua hành động nộp. Nộp là một quy trình bàn giao: trung tâm tạo yêu cầu,
   giáo viên chọn phiên bản cụ thể, hệ thống kiểm quyền trên toàn bộ danh sách, tạo bản độc lập thuộc trung
   tâm và ghi biên nhận bất biến. Giáo viên giữ nguyên bản gốc cá nhân; nộp không bao giờ là đổi chủ sở hữu
   tại chỗ của bản gốc. Phần bàn giao dùng chung — `handover_request`, `submission`, `submission_item` và cơ
   chế lõi ở mục 13 — thuộc feat này; phạm vi bàn giao theo từng loại tài nguyên thuộc F10/F11.
10. Câu hỏi hệ thống thuộc một catalog riêng ở feat câu hỏi, với bảng và repository riêng. Lõi quyền của feat
    này chỉ cung cấp workspace hệ thống và quyết định quyền dùng chung; nó không gộp catalog hệ thống vào
    bảng nội dung của tài nguyên cá nhân hoặc trung tâm.
11. Giai đoạn hiện tại chỉ xây bộ khung có kiểm chứng cho mỗi feat. Không mang logic của module `access` cũ
    sang, không dựng cơ chế cho nhu cầu chưa có nơi dùng thật, không viết backfill hoặc tương thích ngược cho
    dữ kiện cũ. Schema do Flyway sở hữu và Hibernate giữ `ddl-auto=none`. Database phục vụ test là database
    riêng của từng lượt test trên test-pg — một PostgreSQL test riêng (hostname `test-pg.fuckr.net`), tách hẳn
    khỏi stack DEV: mỗi lượt test tạo một database trống của riêng nó trên test-pg và xoá database đó khi test
    xong; không dựng hạ tầng test tại local. Stack DEV đang chạy trên Dokploy nằm im, không dựng lại, và dữ
    kiện của nó không làm bãi test.
12. Service account và Admin REST API không thuộc feat này. Feat này không tạo Organization, không thêm hoặc
    gỡ thành viên và không thay đổi vòng đời membership. Đóng một trung tâm ở các feat quản trị sau là
    disable Organization, không xoá Organization và dữ kiện của nó.

### Quyết định trước đây không còn áp dụng

- Module `access` của `apps/api` không được chuyển sang `apps/server`. Mọi feat sau gọi dịch vụ quyết định
  quyền tại API của LMS được dựng ở feat này.
- Hướng dùng Keycloak Authorization Services, Protection API và policy enforcer để chấm quyền trên ngân
  hàng, câu hỏi, đề và ma trận không còn áp dụng. Keycloak chỉ cung cấp danh tính, membership và vai theo
  Organization cho các quyết định đó.

### Điểm để ngỏ

- Loại member cho tài khoản học viên do trung tâm cấp chưa được chốt. Hai loại theo Keycloak là unmanaged,
  trong đó tài khoản tồn tại độc lập với Organization, và managed, trong đó vòng đời tài khoản gắn với
  Organization. Feat này không tạo tài khoản và không xử lý vòng đời membership nên không phụ thuộc điểm này.
- Với grant dùng lâu dài, phần đã chốt đã ghi thành quyết định ở QĐ8: bản nhận dùng lâu dài tồn tại sau khi
  giáo viên rời trung tâm và người chia sẻ không đơn phương thu hồi hay xoá được. Chỉ phần ngoại lệ thu hồi
  — trường hợp nào được thu hồi trước hạn — và thẩm quyền xử lý ngoại lệ đó là chưa chốt. Feat này ghi
  `revocation_terms`, tôn trọng `revoked_at` khi đánh giá và không mở thao tác thu hồi grant dùng lâu dài.
- Giới hạn uỷ quyền phân công của `bank-manager`, đặc biệt là việc vai này có được tạo hoặc gỡ phân công
  `bank-manager` khác hay không, chưa được chốt đến mức phân cấp uỷ quyền. Điểm này phải chốt cùng F10 trước
  khi triển khai chức năng phân công; bề mặt kiểm chứng của feat này chỉ cho `tenant-admin` tạo và gỡ phân
  công nên nghiệm thu của feat này không phụ thuộc điểm đó.
- Mã hành động chuyên biệt và ánh xạ thao tác triển khai cho từng loại câu hỏi, đề và ma trận ngoài tập hành
  động lõi ở mục 5 chưa được chốt. Ngữ nghĩa tách hành động thì đã chốt: xem, xem đáp án, dùng, sao chép, nộp
  và chia sẻ là các quyền riêng, không suy ra lẫn nhau. Các feat F10 và F11 định nghĩa mã và ánh xạ trên cùng
  dịch vụ quyết định, không dựng hệ quyền riêng.


## Nghiệm thu

Trong các tiêu chí dưới đây, A là Organization của `trung-tam-1` và B là Organization của `trung-tam-2`. Mọi
kiểm chứng runtime dùng token thật và ngữ cảnh trang tương ứng; kết quả của `POST /api/access-check/decisions`,
mã trạng thái HTTP và truy vấn trực tiếp vào database là bằng chứng.

- [ ] Migration và schema: dựng database test trống trên test-pg từ đầu (xoá sau lượt chạy) sau các migration
      của F3, migration của feat này áp sạch. Các bảng lõi tồn tại với các ràng buộc
      workspace/chủ sở hữu/`custody_kind`, khoá ngoại và chỉ mục đã nêu; `resource_version` không gắn được sang
      một `resource` thuộc phân vùng khác; một grant không trỏ được tới phiên bản thuộc tài nguyên khác; một bản
      nhận `licensed` không tồn tại được khi thiếu grant cấp phép.
- [ ] Quyết định chỉ dùng ngữ cảnh đã xác thực: gọi `POST /api/access-check/decisions` kèm các trường giả về
      người gọi, Organization, vai, chủ sở hữu và khoá phân vùng trong thân request cho cùng một trường hợp đã
      biết kết quả. Kết quả không đổi so với lần gọi không có các trường giả đó.
- [ ] Chủ sở hữu cá nhân và cách ly cá nhân: tài khoản tự do có quyền soạn nền tảng `personal-author` tạo một
      tài nguyên cá nhân, xem được nội dung, tạo được phiên bản nháp mới và phát hành được phiên bản của chính
      mình. Một tài khoản không có `personal-author`, ví dụ tài khoản học viên, bị từ chối ngay ở thao tác tạo
      tài nguyên cá nhân. Một giáo viên khác, một `tenant-admin` của A và một khách của A đều bị từ chối
      `view_content` và `edit_draft` trên tài nguyên đó.
- [ ] Thành viên không có phân công: một thành viên của A có vai `tenant-teacher` nhưng không có phân công và
      không có grant bị từ chối `view_content`, `edit_draft`, `review` và `publish` trên một tài nguyên mẫu đã
      thuộc A. Vai giáo viên đứng một mình không tạo được tài nguyên trong bộ sưu tập của A.
- [ ] `created_by` không sinh quyền: sắp xếp một tài nguyên thuộc A có `created_by_sub` là `sub` của thành
      viên vừa nêu, trong khi chủ sở hữu là Organization A và người đó không có phân công hoặc grant. Mọi quyết
      định `view_content`, `edit_draft`, `review`, `publish` và `share` cho người đó đều là từ chối; truy vấn
      database xác nhận `created_by_sub` đúng là `sub` của người đó.
- [ ] Phân công biên tập theo đúng bộ sưu tập: `tenant-admin` của A tạo bộ sưu tập C1 và gán `bank-editor`
      cho một thành viên trên loại tài nguyên mẫu. Người đó tạo được tài nguyên trong C1 và tạo được phiên bản
      nháp mới của tài nguyên đó, nhưng bị từ chối `review` và `publish`; cùng người đó bị từ chối
      `create_in_collection` và `edit_draft` trên tài nguyên của bộ sưu tập C2 không có phân công tương ứng.
- [ ] Phân công duyệt và phát hành tách nhau: trên một bộ sưu tập khác, người có `resource-reviewer` được
      `review` nhưng bị từ chối `edit_draft` và `publish`; người có `resource-publisher` phát hành thành công
      một phiên bản nháp nhưng bị từ chối `edit_draft` và `review`. Quyết định `view_answer` cho cả hai người
      đều là từ chối khi không có nguồn quyền ghi tường minh hành động này; `view_answer` không được suy ra từ
      `edit_draft` hay `review`. Cả hai đều bị từ chối khi phân công đã bị gỡ bằng `revoked_at`.
- [ ] Vai admin không rò giữa hai trung tâm: tài khoản có `tenant-admin` tại A và `tenant-member` tại B tạo
      được phân công và quản lý được tài nguyên của A trong ngữ cảnh A. Trong ngữ cảnh B, cùng tài khoản đó bị
      từ chối `manage_assignment`, `edit_draft` và `publish` trên đúng tài nguyên của A; `platform-admin`
      cũng bị từ chối các hành động đó trên tài nguyên của A.
- [ ] Grant khớp đúng phiên bản, hành động và trung tâm: chủ sở hữu cá nhân tạo tài nguyên có phiên bản V1 và
      V2, rồi cấp cho A hai grant trên V1 với hành động `view_content` và `use`. Trước khi grant được tiếp
      nhận, mọi thành viên của A đều bị từ chối cả hai hành động. `tenant-admin` của A tiếp nhận grant vào bộ
      sưu tập C1 và phân công một thành viên trên C1 cho loại tài nguyên mẫu với các hành động tương ứng; từ
      đó thành viên đó trong ngữ cảnh A được hai hành động đã cấp trên V1, bị từ chối `copy`, `share` và
      `edit_draft` trên V1, và bị từ chối `view_content` trên V2. Một thành viên khác của A không có phân
      công vẫn bị từ chối trên V1. `GET` tài nguyên đó với thành viên có phân công chỉ trả V1. Một thành viên
      của B trong ngữ cảnh B bị từ chối trên V1. Database xác nhận chủ sở hữu và khoá phân vùng của tài nguyên
      nguồn không đổi sau khi cấp grant.
- [ ] Grant tạm thời và grant dùng lâu dài: một grant tạm thời cho A, đã tiếp nhận và có phân công như tiêu
      chí trên, cho phép hành động đã cấp trước khi thu hồi và bị từ chối ngay sau khi người cấp thu hồi; một
      grant khác có `expires_at` được dữ kiện kiểm chứng đặt về quá khứ cũng bị từ chối. Một grant `long_term`
      cho A vẫn cho một thành viên có phân công phù hợp của A thực hiện đúng hành động đã cấp trong ngữ cảnh
      A, không phụ thuộc việc người cấp có phải là người đang gọi hay không, và không cho thành viên của B
      thực hiện hành động đó trong ngữ cảnh B. Người đã cấp không thu hồi được grant `long_term` qua bề mặt
      kiểm chứng.
- [ ] Khoá phiên bản: phát hành V1 của một tài nguyên mẫu; quyết định `edit_draft` trên V1 bị từ chối, thao
      tác tạo phiên bản mới tạo V2 ở trạng thái `draft`, và database cho thấy `content_hash`, `status` cùng
      `locked_at` của V1 không đổi. Cấp grant trên V2 đặt `locked_at` cho V2 và làm quyết định `edit_draft`
      trên V2 bị từ chối. Thu hồi hoặc cho hết hạn grant trên V2 không làm `edit_draft` trên V2 được chấp
      nhận trở lại, vì khoá được quyết bởi `locked_at` đã ghi.
- [ ] Giới hạn sử dụng kế thừa và phạm vi cấp lại: sắp xếp một bản sao cá nhân mang giới hạn kế thừa chỉ cho
      phép dùng trong A. Chủ của bản sao bị từ chối `share` cho B và bị từ chối `use` trong ngữ cảnh B, nhưng
      dùng được trong ngữ cảnh A. Một người chỉ có grant `view_content` trên một phiên bản bị từ chối khi tạo
      grant mới trên phiên bản đó; một người có `share` nhưng phạm vi cấp lại không gồm `view_answer` cũng bị
      từ chối khi tạo grant `view_answer`.
- [ ] Token cũ sau khi gỡ membership: một thành viên của A lấy token và đọc được một tài nguyên của A theo
      phân công của mình. Sau khi membership của tài khoản đó tại A bị gỡ, dùng lại chính token cũ đó thì mọi
      quyết định `view_content` và `edit_draft` trên tài nguyên của A đều là từ chối, theo tính hiện hành của
      membership mà F3 xác lập; cùng token đó vẫn dùng được tài nguyên cá nhân của chính tài khoản.
- [ ] Điều kiện nghiệp vụ ngoài trạng thái phiên bản: gắn điều kiện mẫu chờ duyệt lên một tài nguyên mẫu của
      A thì hành động `use` bị từ chối với mọi người, kể cả người có phân công hoặc grant, và `view_content`
      chỉ còn được chấp nhận cho người có nguồn quyền duyệt theo ánh xạ của điều kiện. Gỡ điều kiện thì các
      quyết định trở lại đúng như trước khi gắn.
- [ ] Bàn giao dùng chung: `tenant-admin` của A tạo yêu cầu nộp cho một giáo viên và yêu cầu đó không cho
      admin bất kỳ quyền đọc nào trên tài nguyên cá nhân của giáo viên. Giáo viên nộp đúng phiên bản V2 đã
      chọn thì một `resource` mới thuộc A với `custody_kind = owned` được tạo, biên nhận `submission` và
      `submission_item` ghi đúng phiên bản nguồn và bản đã nhận, và bản gốc cá nhân không đổi. Nộp một phiên
      bản mà giáo viên không có `submit` thì toàn bộ lần nộp bị từ chối và không tạo bản nhận nào. Một người
      khác không được nêu trong yêu cầu cũng bị từ chối khi nộp thay.
- [ ] Workspace hệ thống: `platform-admin` tạo và phát hành được một tài nguyên hệ thống mẫu. Tài khoản tự
      do, thành viên của A và `tenant-admin` của A đều bị từ chối `view_content`, `edit_draft` và `publish`
      trên tài nguyên đó khi không có grant; `platform-admin` đồng thời bị từ chối trên tài nguyên cá nhân và
      tài nguyên của A ở các tiêu chí trên.
- [ ] Khách và workspace cá nhân tại trang trung tâm: tài khoản tự do mở ngữ cảnh A với tư cách khách không
      nhận được quyền nào trên tài nguyên của A, nhưng vẫn được `view_content` trên tài nguyên cá nhân của
      chính mình khi gọi bề mặt kiểm chứng dành cho workspace cá nhân. `GET /api/profile` của chính tài khoản
      đó vẫn trả 200 và đúng hồ sơ.
- [ ] RLS ở mức database: kết nối bằng role runtime `lms_server_app` khi chưa đặt biến ngữ cảnh thì `SELECT`
      trên các bảng lõi trả 0 hàng. Đặt `app.partition_key` bằng ID của A thì chỉ thấy tài nguyên, phiên bản
      và phân công của A cùng các grant có phía nhận là A và các yêu cầu nộp, biên nhận thuộc A; chèn một hàng
      mang khoá phân vùng của B bị từ chối bởi `WITH CHECK`. Đặt khoá bằng `user:<sub>` của chủ sở hữu cá
      nhân thì chỉ thấy tài nguyên cá nhân của đúng tài khoản đó và các grant có phía nhận là tài khoản đó.
- [ ] Không có đường quyền qua Keycloak theo tài nguyên: mã và cấu hình của `apps/server` không chứa tích hợp
      Authorization Services, policy enforcer, Protection API hoặc lời gọi tạo role/object Keycloak cho tài
      nguyên; toàn bộ kết quả ở các tiêu chí trên đến từ dịch vụ quyết định tại API và dữ kiện trong database
      của LMS.
- [ ] Hồi quy và test tự động: bộ test của `apps/server` đạt, gồm test cho mô hình dữ kiện, thuật toán quyết
      định, RLS và endpoint kiểm chứng; phần cần database thật chạy trên test-pg theo quyết định 11.
      `GET /api/profile` và các endpoint `/api/tenancy-check/...` của F3 giữ nguyên kết quả trên các tài khoản
      thử đã có.

## Phụ thuộc và thứ tự

- Phụ thuộc bắt buộc: F3, issue #57, phải hoàn thành trước. Feat này dùng `TenantContext`, cách đọc vai tại
  `organization[<alias>].realm_access.roles`, kiểm chứng `aud`, discriminator, khuôn RLS và tài khoản runtime
  của F3; không dựng lại bất kỳ thành phần nào trong số đó.
- Phụ thuộc nền đã xong: F0 cho khung `apps/server` và kiểm chứng JWT; F2 cho Hibernate/JPA, Flyway, bảng
  `app_user` và `GET /api/profile`.
- Thứ tự việc con: migration và RLS cho các bảng lõi trước; sau đó tới dịch vụ quyết định quyền; sau đó tới
  bề mặt kiểm chứng và quy tắc khoá phiên bản; cuối cùng là test tự động và kiểm chứng runtime theo mục
  Nghiệm thu.
- Feat này là nền cho F5 ở phần quyền quản trị khoá học và là nền trực tiếp cho F10/F11. Các feat tài nguyên
  dùng dịch vụ quyết định của feat này và định nghĩa nội dung, hành động chuyên biệt cùng quy trình share, nộp
  của chúng trên cùng lõi, không dựng hệ quyền riêng. Phần bàn giao dùng chung ở mục 13 là của feat này;
  F10/F11 không định nghĩa lại mà chỉ định nghĩa phạm vi theo loại trên phần dùng chung đó.
- F10/F11 không phải chờ feat này hoàn thành toàn bộ mới bắt đầu: sau khi hợp đồng lõi của feat này cố định —
  các bảng lõi, tập hành động, thuật toán quyết định và phần bàn giao dùng chung — F10/F11 được làm song song
  phần schema nội dung, hợp đồng API và kiểm thử của chúng. Chỉ việc tích hợp với dịch vụ quyết định thật và
  nghiệm thu cuối của F10/F11 mới phụ thuộc feat này hoàn thành.
