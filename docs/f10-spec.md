## Bối cảnh và vấn đề

`apps/server` đã có khung xác thực JWT của realm `giaokhoa`, khung dữ kiện Hibernate/JPA + Flyway với hồ sơ người
dùng theo `sub` (F2), khung tenancy với resolver ngữ cảnh, discriminator và Row Level Security (F3), và lõi quyền
tại API (F4) gồm các bảng lõi `resource`, `resource_version`, `resource_grant`, `collection_assignment` và
các thành phần bàn giao dùng chung `handover_request`, `submission`, `submission_item`, `resource_dependency`,
cùng dịch vụ quyết quyền tập trung. Chưa có dữ kiện nghiệp vụ nào về câu hỏi hay
ngân hàng câu hỏi trong `apps/server`; các module cũ `questions`, `content-bank`, `system-bank` trong `apps/api`
đi theo mô hình quyền cũ và không được mang sang.

Câu hỏi là nghiệp vụ lõi đầu tiên có quyền theo từng tài nguyên và từng phiên bản. Mô hình nghiệp vụ đã chốt cho
tài nguyên của giáo viên: tài nguyên do giáo viên soạn mặc định thuộc workspace cá nhân gắn với tài khoản của
giáo viên — dùng được khi giáo viên làm tự do và khi tham gia một hoặc nhiều trung tâm; trung tâm chỉ dùng được
tài nguyên cá nhân khi được chia sẻ, và chỉ sở hữu khi nhận được bản bàn giao qua thao tác nộp hoặc khi giáo
viên chủ động dùng thao tác minh thị "soạn cho trung tâm"; câu hỏi hệ thống
nằm ở một catalog tách riêng của nền tảng. Nếu feat này không dựng đúng các ranh giới đó ngay từ đầu, các feat
dùng câu hỏi về sau (đề thi, làm bài) sẽ đứng trên một mô hình sở hữu sai và phải làm lại.

Feat này dựng câu hỏi và ngân hàng câu hỏi trong `apps/server` trên lõi quyền của F4 và khung tenancy của F3,
theo ba workspace cá nhân — trung tâm — hệ thống, kèm sao chép có truy vết, chia sẻ ba chế độ và quy trình nộp
dành cho câu hỏi và bộ câu hỏi.

## Mục tiêu

- Câu hỏi và ngân hàng câu hỏi tồn tại trong ba workspace với chủ sở hữu rõ ràng: cá nhân gắn với tài khoản giáo
  viên, trung tâm gắn với đúng một Organization, hệ thống thuộc nền tảng qua catalog riêng.
- Mọi quyết định quyền trên câu hỏi, phiên bản câu hỏi và ngân hàng đều đi qua dịch vụ quyết quyền của F4 với bộ
  hành động riêng của câu hỏi; không có đường truy cập nào vòng qua dịch vụ đó.
- Phiên bản là đơn vị bất biến của mô hình: ngân hàng trỏ tới phiên bản cụ thể; nội dung đã phát hành, đã chia
  sẻ hoặc đã nộp không bị ghi đè.
- Trung tâm dùng tài nguyên cá nhân của giáo viên đúng ba chế độ chia sẻ đã chốt, và chỉ sở hữu tài nguyên qua
  quy trình nộp có biên nhận hoặc thao tác minh thị "soạn cho trung tâm"; giáo viên rời trung tâm thì bản gốc
  cá nhân đi theo giáo viên, các bản trung tâm đã nhận vẫn ở lại.
- Lát của feat này trong ca nghiệm thu trọng tâm xuyên suốt đạt trọn: giáo viên tự do tạo câu hỏi, tham gia hai
  trung tâm, chia sẻ đúng một phiên bản cho một trung tâm — trung tâm đó dùng được bản đã nhận, trung tâm còn
  lại không có quyền gì, và trung tâm đã nhận không thấy các phiên bản chưa được chia sẻ.

## Phạm vi

### Làm

- Schema và API cho câu hỏi, phiên bản câu hỏi, ngân hàng câu hỏi và quan hệ ngân hàng – phiên bản, dựng trên
  lõi `resource` của F4; feat này chỉ thêm phần nội dung chuyên biệt của câu hỏi, không tạo hệ quyền riêng.
- Catalog câu hỏi hệ thống tách riêng: bảng và repository riêng, ghi bởi tài khoản nền tảng, đọc có kiểm soát.
- Sao chép câu hỏi (tạo bản riêng) có truy vết nguồn, không kế thừa quyền của nguồn.
- Chia sẻ phiên bản câu hỏi cho trung tâm theo ba chế độ: xem tạm thời, dùng lâu dài kèm bước trung tâm tiếp
  nhận bản được cấp quyền giữ và dùng, và đã thuộc trung tâm qua thao tác nộp.
- Quy trình nộp cho câu hỏi và bộ câu hỏi: yêu cầu nộp, danh sách bàn giao, kiểm quyền từng mục, tạo bản thuộc
  trung tâm ở trạng thái chờ duyệt, biên nhận bất biến.
- Phần dữ kiện khi giáo viên rời trung tâm: grant xem tạm thời mất hiệu lực; bản đã tiếp nhận ở chế độ dùng
  lâu dài ở lại và được dùng theo đúng điều kiện của grant; bản đã nộp ở lại trung tâm; phân công và kho cá
  nhân xử lý theo quy tắc ở mục Chi tiết.

### Không làm

- Không làm đề thi và ma trận (feat F11 riêng). Feat này chỉ định nghĩa hành động "dùng trong đề" trong vốn từ
  của grant; việc dựng đề và tiêu thụ hành động đó thuộc F11.
- Không làm render câu hỏi trong phiên làm bài, chấm điểm hay kết quả thi (feat F13 riêng).
- Không quản trị membership hay Organization qua Admin REST API: việc thêm/gỡ thành viên của Organization thực
  hiện ở Keycloak, ngoài feat này; feat này chỉ xử lý hệ quả trên dữ kiện câu hỏi khi một thành viên rời trung
  tâm.
- Không làm pipeline tải tệp lên kho tệp: tệp đính kèm của phiên bản được quản lý dưới dạng tham chiếu tới blob
  bất biến đã tồn tại; feat này quản lý tham chiếu và quyền tải xuống qua phiên bản, không quản lý việc tải lên.
- Không làm tìm kiếm toàn văn, nhập/xuất câu hỏi hàng loạt, và không sao chép các tính năng của module cũ ngoài
  phạm vi đã liệt kê ở mục Làm.
- Không đăng ký câu hỏi hay ngân hàng lên Keycloak: không Authorization Services, không role Keycloak theo từng
  tài nguyên, không phân công nào ngoài `collection_assignment` của F4.

## Chi tiết việc cần làm

### 1. Ánh xạ workspace lên lõi F4 và khung phân vùng F3

Câu hỏi và ngân hàng đều là `resource` trong lõi của F4: ngân hàng có loại `question_bank`, câu hỏi có loại
`question`. Chủ sở hữu và phạm vi cách ly của một resource suy ra từ workspace của nó:

| Workspace | Chủ sở hữu | Khoá phân vùng của F3 | `tenant_id` trên resource |
| --- | --- | --- | --- |
| Cá nhân | Tài khoản giáo viên (`owner_user_id` = `sub`) | `user:<sub>` | Không có |
| Trung tâm | Một Organization | Organization ID | Organization ID |
| Hệ thống | Nền tảng | Không dùng khoá phân vùng (catalog riêng) | Không có |

Các bảng thuộc phân vùng của feat này (nội dung phiên bản câu hỏi, quan hệ ngân hàng – phiên bản) mang cột khoá
phân vùng theo đúng mẫu của F3: discriminator `@TenantId` tự đóng dấu khi ghi và Row Level Security với policy
theo khoá phân vùng, chạy bằng role runtime không vượt được RLS. Khoá ngoại giữa các bảng thuộc trung tâm mang
cả `tenant_id` để một câu của trung tâm này không gắn được vào ngân hàng của trung tâm khác ở mức database.
`tenant_id` và người thực hiện luôn lấy từ `TenantContext` đã resolve của F3, không bao giờ từ thân request.
Cấp grant cho một trung tâm không đổi khoá phân vùng, `tenant_id` hay chủ sở hữu của tài nguyên nguồn;
Organization nhận được ghi trên grant và trên bản nhận tại trung tâm (nếu có).

### 2. Schema chuyên biệt của câu hỏi

- Bảng nội dung của phiên bản câu hỏi (một hàng cho mỗi `resource_version` của câu hỏi): thân câu hỏi, đáp án,
  lời giải và tiêu chí chấm dưới dạng dữ kiện có cấu trúc, kèm trạng thái của phiên bản lấy từ lõi F4 (nháp /
  đã phát hành). Nội dung tuân theo một hợp đồng nội dung tối thiểu có số phiên bản hợp đồng ghi trên từng
  phiên bản câu hỏi; server kiểm tra tính hợp lệ tối thiểu trước khi một phiên bản được phát hành, chia sẻ
  hoặc nộp: thân câu không rỗng, các phần đáp án, lời giải và tiêu chí chấm đúng cấu trúc của hợp đồng, và
  mọi tham chiếu tệp đều trỏ tới blob tồn tại. Schema nội dung theo từng loại câu hỏi cụ thể không chốt tại
  feat này (xem mục Điểm để ngỏ).
- Bảng tham chiếu tệp đính kèm của phiên bản: định danh blob bất biến, tên tệp và loại nội dung. Blob có thể
  dùng chung về vật lý giữa các phiên bản; quyền tải xuống luôn được quyết qua quyền trên phiên bản đang giữ
  tham chiếu, và sửa một bản sao không làm thay đổi blob mà bản gốc đang dùng.
- Quan hệ ngân hàng – phiên bản (`bank_entry`): ngân hàng, câu hỏi, và đúng một trong hai loại tham chiếu —
  tham chiếu tới một phiên bản nội bộ hoặc tham chiếu tới một phiên bản catalog — với ràng buộc chỉ một trong
  hai khoá được đặt. Một mục luôn trỏ tới một phiên bản cụ thể, không trỏ mơ hồ tới "phiên bản mới nhất". Gỡ
  một câu khỏi ngân hàng chỉ xoá mục của ngân hàng đó, không xoá câu hỏi.
- Ngân hàng chủ quản và ngân hàng tham chiếu: mỗi câu hỏi có đúng một ngân hàng chủ quản — ngân hàng mà câu
  được tạo trực tiếp vào, ghi ở `collection_id` của `resource` theo lõi F4 — và quyền biên tập câu được quyết
  theo phân công trên ngân hàng chủ quản đó. `bank_entry` ở các ngân hàng khác là mục tham chiếu để dùng câu;
  thêm một tham chiếu vào ngân hàng khác không cấp quyền sửa câu nguồn cho bất kỳ ai ở ngân hàng tham chiếu.
- Xoá nguồn và giữ tệp: xoá hoặc lưu trữ một câu nguồn không xoá các bản đã nhận, bản sao và truy vết nguồn
  của chúng; các bản đó giữ nguyên tham chiếu tệp của mình. Việc thu gom blob chỉ được xoá một blob khi
  không còn bất kỳ phiên bản, bản sao hay bản đã nhận nào còn tham chiếu tới nó, kể cả ở workspace khác.
- Catalog hệ thống: bảng `catalog_question` và `catalog_version` riêng, repository riêng, không có cột khoá
  phân vùng và không nằm chung bảng với dữ kiện cá nhân hay trung tâm. Phiên bản catalog theo cùng hợp đồng
  phiên bản của lõi chung: một `catalog_version` đã công bố là bất biến, sửa bằng cách tạo `catalog_version`
  mới; quan hệ của catalog với dịch vụ quyết quyền của F4 định nghĩa ở mục 5.

### 3. Ngân hàng câu hỏi

- Workspace thao tác tách khỏi trung tâm của trang đang mở: ngữ cảnh trang chỉ cho biết người gọi đang đứng
  ở đâu, không quyết định ngân hàng tạo ra hay liệt kê thuộc workspace nào. Mọi thao tác ở mục này nhận một
  workspace đích được chọn minh thị; mặc định của lựa chọn đó luôn là workspace cá nhân ("Của tôi").
- Tạo ngân hàng: mặc định ngân hàng tạo ra thuộc workspace cá nhân của người gọi, kể cả khi thao tác diễn ra
  tại trang của một trung tâm — khớp tiêu chí nghiệm thu "Mặc định cá nhân". Ngân hàng thuộc một trung tâm
  chỉ khi người gọi chọn minh thị workspace của trung tâm đó và là `tenant-admin` của trung tâm hoặc có phân
  công `bank-manager` áp dụng; ngân hàng trung tâm tạo ra thuộc trung tâm, không thuộc người tạo.
- Liệt kê và đọc ngân hàng gồm hai phần độc lập: ngân hàng cá nhân của chính người gọi — mở được ngay tại
  trang của một trung tâm, không cần rời trang — và ngân hàng của trung tâm theo quyền của người gọi tại
  trung tâm đó. Không ai liệt kê hay đọc được ngân hàng cá nhân của người khác ở bất kỳ ngữ cảnh trang nào.
- Thêm/gỡ mục trong ngân hàng trung tâm theo phân công của ngân hàng đó (mục 7); thêm/gỡ mục trong ngân hàng cá
  nhân do chủ sở hữu thực hiện. Thêm hay gỡ một mục tham chiếu không đổi quyền biên tập trên câu nguồn; quyền
  đó theo ngân hàng chủ quản của câu (mục 2).

### 4. Câu hỏi và vòng đời phiên bản

- Tạo câu hỏi: mặc định thuộc workspace cá nhân của người tạo, kể cả khi thao tác diễn ra tại trang của một
  trung tâm hay theo yêu cầu của trung tâm. Câu hỏi thuộc trung tâm ngay từ đầu chỉ qua một thao tác minh
  thị riêng tên "soạn cho trung tâm": người tạo chủ động chọn thao tác đó, chọn ngân hàng đích của trung
  tâm, và phải có phân công biên tập áp dụng trên ngân hàng đích đó. Quyền sở hữu không bao giờ được suy từ
  phân công đang có hoặc từ vị trí trang đang mở; ngoài thao tác minh thị này, tài nguyên chỉ thuộc trung
  tâm qua quy trình nộp (mục 9). Chủ sở hữu do API đặt từ principal, không nhận từ dữ kiện client gửi;
  không có thao tác cập nhật nào đổi được chủ sở hữu của một câu.
- Phiên bản nháp chưa phát hành và chưa từng được chia sẻ hay nộp thì sửa tại chỗ được bởi người có quyền biên
  tập trên câu đó; việc sửa dùng khoá lạc quan `@Version` của JPA để hai lượt sửa đồng thời không ghi đè nhau.
  [Docs — Hibernate ORM User Guide, chương Locking, mục Dedicated version number: cơ chế khoá lạc quan dùng
  annotation `@Version`, lượt commit sau bị báo xung đột thay vì ghi đè lượt trước]
- Một phiên bản trở thành bất biến ngay khi dấu khoá của nó đã được đặt theo lõi F4 (`locked_at`): đã phát
  hành, đã từng được tạo grant — kể cả khi grant đó sau đó hết hạn hoặc bị thu hồi — hoặc đã nằm trong một
  lần nộp đã ghi biên nhận. Tính bất biến đọc từ chính dấu khoá đã ghi, không suy từ việc còn hay không còn
  grant đang hiệu lực. Sửa một phiên bản bất biến chỉ có hai đường: người có quyền biên tập trên chính câu đó
  tạo phiên bản nháp mới của cùng câu; người không có quyền biên tập tạo bản riêng (mục 6).
- Phát hành một phiên bản do người có phân công `bank-publisher` của ngân hàng chứa câu thực hiện (với câu cá
  nhân, chủ sở hữu tự phát hành trong ngân hàng cá nhân của mình). Sau phát hành, ngân hàng muốn dùng nội dung
  mới hơn phải trỏ mục sang phiên bản mới một cách tường minh; các mục đang trỏ phiên bản cũ không tự đổi.

### 5. Catalog câu hỏi hệ thống

- Chỉ tài khoản có vai nền tảng `platform-admin` mới tạo, sửa bản nháp và công bố câu catalog. Mọi luồng khác
  chỉ đọc được các phiên bản catalog đã công bố, qua một đường đọc kiểm soát riêng của catalog; luồng của trung
  tâm và cá nhân không ghi được catalog trong bất kỳ trường hợp nào.
- Đưa câu catalog vào một ngân hàng bằng một mục tham chiếu catalog tới đúng phiên bản đã công bố. Muốn sửa
  nội dung câu catalog thì tạo bản riêng trong workspace đích (mục 6); bản riêng không còn là câu catalog.
- Việc đọc catalog không tắt bộ lọc tenant và không mở phiên "root": catalog nằm ở bảng riêng với repository
  riêng, và đường đọc kiểm soát chỉ trả các phiên bản đã công bố.
- Quan hệ với lõi chung của F4: catalog không có hệ kiểm quyền riêng. Khi cần quyết quyền, định danh của câu
  catalog và của phiên bản catalog được đưa vào dịch vụ quyết quyền của F4 như dữ kiện của workspace hệ
  thống: cùng dịch vụ đó quyết các hành động xem nội dung, xem đáp án, dùng trong đề và tạo bản riêng trên
  một phiên bản catalog, với nguồn quyền là `platform-admin` cho thao tác ghi và công bố, và trạng thái đã
  công bố cho thao tác đọc. ID câu catalog và ID phiên bản catalog là khoá tham chiếu ổn định để các feat
  sau (F11) tiêu thụ đúng phiên bản qua cùng một đường quyết quyền này.

### 6. Sao chép câu hỏi (tạo bản riêng)

- Điều kiện: người thực hiện có quyền đọc và quyền tạo bản riêng trên phiên bản nguồn, đồng thời có quyền tạo
  câu trong workspace/ngân hàng đích. Đích mặc định là workspace cá nhân của người thực hiện.
- Chống lộ đáp án qua sao chép: phần đáp án, lời giải và tiêu chí chấm của phiên bản nguồn chỉ được sao theo
  vào bản sao khi người sao chép có hành động xem đáp án trên đúng phiên bản nguồn đó. Người sao chép không
  có quyền xem đáp án thì bản sao chỉ gồm thân câu và các tham chiếu tệp không chứa đáp án; các tệp chứa đáp
  án không được sao theo. Không thao tác sao chép nào biến quyền chỉ xem thân câu thành quyền xem đáp án,
  kể cả khi người sao chép trở thành chủ sở hữu của bản sao.
- Kết quả: một câu hỏi mới với định danh mới ở trạng thái nháp, mang truy vết đầy đủ — câu nguồn, phiên bản
  nguồn, nguồn gốc ban đầu, người sao chép và thời điểm. Nội dung và các tham chiếu tệp cần thiết được sao theo.
- Bản sao không kế thừa bất kỳ grant hay phân công nào của nguồn; quyền trên bản sao chỉ đến từ workspace đích.
  Tuy nhiên bản sao giữ nguyên các giới hạn tái sử dụng đã ghi trên nguồn: ví dụ phiên bản nguồn bị giới hạn
  chỉ dùng trong một trung tâm thì bản sao của nó không được chia sẻ cho trung tâm khác và không được đóng góp
  lên catalog hệ thống. Các giới hạn của nguồn ràng buộc cả chủ sở hữu của bản sao; khi tạo grant từ một bản
  sao, hệ thống kiểm tra phạm vi cấp lại so với các giới hạn đã ghi trên nguồn của bản sao đó.

### 7. Bộ hành động của câu hỏi và phân công ngân hàng

Mọi quyết định quyền đi qua dịch vụ quyết quyền của F4, kết hợp `TenantContext` của F3, workspace/chủ sở hữu
của tài nguyên, grant tường minh, phân công áp dụng và trạng thái phiên bản. Bộ hành động riêng của câu hỏi,
không gộp thành một cờ quyền chung: xem nội dung; xem đáp án và lời giải; dùng trong đề; tạo bản riêng; chia
sẻ; nộp; biên tập bản nháp; phát hành vào ngân hàng. Grant chỉ mở đúng các hành động đã ghi trong nó: được xem
nội dung không tự cho xem đáp án, được dùng trong đề không tự cho đưa vào ngân hàng, và không hành động nào tự
mở thành quyền chia sẻ lại, nộp thay hay sửa bản gốc.

Phân công biên tập trên một câu áp dụng theo ngân hàng chủ quản của câu (mục 2); phân công trên một ngân
hàng chỉ chứa tham chiếu tới câu không cho quyền biên tập câu nguồn, chỉ cho các quyền dùng trên phiên bản
được tham chiếu theo đúng grant và phân công hiện có.

Phân công theo ngân hàng lưu trong `collection_assignment` của F4: `bank-manager` quản lý ngân hàng được giao
và các phân công trong phạm vi được uỷ quyền; `bank-editor` tạo câu, tạo và sửa phiên bản nháp trong ngân hàng
được giao; `bank-publisher` duyệt và phát hành phiên bản, không mặc nhiên có quyền sửa nội dung. `tenant-admin`
của trung tâm tạo ngân hàng, quản lý phân công và yêu cầu nộp, nhưng không vì thế đọc hay sửa được tài nguyên
cá nhân của giáo viên. `created_by` chỉ là truy vết tác giả: là tác giả của một câu thuộc trung tâm không tự
cho quyền sửa câu đó khi không có phân công áp dụng.

### 8. Chia sẻ phiên bản câu hỏi cho trung tâm — ba chế độ

Chia sẻ luôn hướng tới một trung tâm cụ thể và một phiên bản cụ thể. Người chia sẻ phải có hành động chia
sẻ trên đúng phiên bản đó — từ quyền chủ sở hữu, hoặc từ một grant hợp lệ có phạm vi cấp lại cho phép chia
sẻ tiếp; khi tạo grant, hệ thống kiểm tra phạm vi được phép cấp lại và các giới hạn đã ghi trên nguồn, kể
cả khi người cấp là chủ sở hữu. Quyền xem hoặc quyền dùng nhận qua grant không bao giờ tự sinh quyền chia
sẻ hay quyền nộp. Ba chế độ cho kết quả khác nhau và không tự chuyển đổi lẫn nhau:

- **Xem tạm thời:** một `resource_grant` trên đúng phiên bản được chỉ định, cho trung tâm đích, với tập hành
  động xem (xem nội dung, và xem đáp án/lời giải chỉ khi grant ghi rõ). Grant cho Organization là phạm vi
  quyền mà trung tâm đó được nhận; thành viên của trung tâm chỉ truy cập được trong phạm vi phân công áp
  dụng của mình tại trung tâm đó, grant không cấp quyền trực tiếp cho mọi thành viên. Grant tạm thời bắt
  buộc có thời hạn kết thúc, bị thu hồi tay bất cứ lúc nào bởi người cấp, và mất hiệu lực khi người cấp rời
  trung tâm đó (mục 10). Không có bản sao nào được tạo ở chế độ này.
- **Dùng lâu dài:** một `resource_grant` tường minh theo từng phiên bản và từng trung tâm, ghi rõ phạm vi dùng
  — xem, dùng trong đề, đưa vào ngân hàng dùng chung — kèm thời hạn (nếu có) và các điều kiện giữ và dùng
  bản được cấp. Grant ở bước này chưa tạo bản nào tại trung tâm. Người có thẩm quyền của trung tâm —
  `tenant-admin` của trung tâm hoặc `bank-manager` của ngân hàng đích — thực hiện bước tiếp nhận: chọn ngân
  hàng đích của trung tâm và xác nhận đúng các điều kiện giữ và dùng đã ghi trong grant; việc tiếp nhận và
  điều kiện đã xác nhận được ghi lại làm bằng chứng. Khi tiếp nhận, hệ thống tạo bản được cấp quyền giữ và
  dùng tại trung tâm: một câu hỏi mới mang truy vết về câu nguồn, phiên bản nguồn và grant đã tạo ra nó,
  mà phiên bản đầu là snapshot của phiên bản được cấp. Bản này không phải bản thuộc sở hữu của trung tâm:
  trung tâm chỉ dùng nó đúng điều kiện của grant — trong phạm vi dùng đã cấp và trong thời hạn đã cấp;
  việc giữ bản không tự sinh quyền sửa nội dung, phát hành lại, chia sẻ tiếp hay nộp thay, trừ khi grant
  ghi rõ hành động đó. Không ai ở trung tâm sửa được bản gốc cá nhân. Grant dùng lâu dài tồn tại độc lập
  với membership của người cấp. Thu hồi grant không xoá bản đã nhận và không xoá bằng chứng tiếp nhận;
  việc trung tâm còn dùng được bản đã nhận hay không tuân theo đúng điều kiện đã ghi trong grant: grant
  không ghi thời hạn và không có điều kiện thu hồi áp dụng thì bản đã nhận tiếp tục được dùng trong phạm
  vi đã cấp; grant có thời hạn thì hết thời hạn là hết quyền dùng — việc bản vật lý đã được tạo tại trung
  tâm không vô hiệu hoá thời hạn đó. Phạm vi dùng quyết định đúng việc trung tâm được làm: phiên bản được
  cấp chỉ để dùng trong đề thì không tự trở thành câu trong ngân hàng dùng chung của trung tâm.
- **Đã thuộc trung tâm:** chỉ sinh ra qua quy trình nộp (mục 9), không phải là một tuỳ chọn của thao tác chia
  sẻ. Chia sẻ ở hai chế độ trên không bao giờ chuyển chủ sở hữu của bản gốc và không lan sang trung tâm khác.

### 9. Nộp câu hỏi và bộ câu hỏi về trung tâm

Nộp là một giao dịch bàn giao trong hệ thống, dùng các thành phần bàn giao dùng chung của F4 —
`handover_request`, `submission`, `submission_item` và `resource_dependency`; feat này chỉ tham chiếu các
thành phần đó, không định nghĩa lại:

- Trung tâm tạo yêu cầu nộp nêu rõ: người phải nộp, loại tài nguyên (câu hỏi/bộ câu hỏi), các phiên bản hoặc
  phạm vi cần nộp, ngân hàng đích và quyền tái sử dụng cần bàn giao. Yêu cầu không cấp bất kỳ quyền đọc nào
  trên kho cá nhân của người nộp.
- Giáo viên chọn các phiên bản cụ thể để nộp. API lập danh sách bàn giao từ đúng các phiên bản đã chọn và kiểm
  quyền trên từng mục: người nộp phải có hành động nộp trên đúng phiên bản đó — từ quyền chủ sở hữu hoặc từ
  một grant hợp lệ có phạm vi cho phép nộp; quyền xem hay quyền dùng nhận qua grant trên câu của người khác
  không cho phép nộp câu đó.
- Giáo viên xác nhận; API tạo các câu hỏi mới thuộc trung tâm với định danh mới, sao nội dung và các tham chiếu
  tệp cần thiết, ánh xạ lại các liên kết trong gói (mục ngân hàng trỏ sang các bản đã nhận tương ứng), rồi ghi
  biên nhận bất biến: yêu cầu, người nộp, thời điểm, các phiên bản nguồn, các tài nguyên trung tâm đã nhận và
  phạm vi bàn giao. Từng mục bàn giao ghi ở `submission_item`; quan hệ giữa phiên bản nguồn và bản đã nhận
  ghi ở `resource_dependency`. Thao tác nộp có khoá chống trùng theo yêu cầu và danh sách phiên bản: bấm
  lại hoặc thử lại không tạo hai lần bàn giao cho cùng một danh sách.
- Bản đã nhận bắt đầu ở trạng thái chờ duyệt, chỉ người được phân công tiếp nhận/duyệt của ngân hàng đích
  (`bank-manager` hoặc phân công duyệt áp dụng) mới thấy. Duyệt đạt thì bản đã nhận dùng được trong trung tâm;
  bị từ chối thì giáo viên nộp một lần mới — lịch sử lần nộp trước không bị ghi đè.
- Phạm vi bàn giao đúng bằng danh sách đã chọn: nộp một bộ câu hỏi không tự gom toàn bộ ngân hàng cá nhân,
  không gồm các phiên bản khác của cùng câu, và không gồm câu không được chọn. Bản đã nhận không còn phụ thuộc
  vào bản gốc: sửa hay xoá bản gốc cá nhân sau đó không làm thay đổi bản trung tâm đã nhận.

### 10. Khi giáo viên rời một trung tâm

Việc gỡ membership của Organization thực hiện ở Keycloak, ngoài feat này. Phần dữ kiện của feat này khi một
giáo viên rời trung tâm:

- Mọi grant xem tạm thời do giáo viên đó cấp cho trung tâm đó mất hiệu lực. Feat này cung cấp một thao tác thu
  hồi hàng loạt theo người cấp và trung tâm (quyền `tenant-admin` của trung tâm) để luồng gỡ membership gọi;
  grant tạm thời cũng tự hết hiệu lực khi hết thời hạn đã ghi.
- Các phân công ngân hàng của giáo viên đó trong trung tâm đó không còn được thực thi, vì membership hiện
  hành của trung tâm không còn chứa tài khoản đó; phân công ở các trung tâm khác không bị ảnh hưởng. Việc
  chặn này áp dụng cả với token đã được cấp trước khi gỡ membership: quyết quyền luôn đối chiếu membership
  và phân công hiện hành theo cơ chế của F3/F4, không chỉ dựa vào claim trong token đã phát hành (nghiệm
  thu ở mục Nghiệm thu).
- Bản đã nộp ở lại trung tâm và tiếp tục dùng, biên tập, phát hành được bởi trung tâm theo phân công của
  trung tâm, không phụ thuộc tài khoản của tác giả. Bản được cấp quyền giữ và dùng ở chế độ dùng lâu dài
  cũng ở lại trung tâm và tiếp tục được dùng theo đúng điều kiện của grant đã cấp; việc ở lại không sinh
  thêm quyền biên tập hay phát hành trên bản đó. Biên nhận nộp giữ nguyên.
- Bản gốc cá nhân và toàn bộ kho cá nhân của giáo viên không bị xoá, không bị chuyển chủ và không bị thu hồi;
  giáo viên tiếp tục dùng kho cá nhân ở trang nền tảng và ở các trung tâm mình còn là thành viên.

### 11. Bề mặt API tối thiểu của feat

Các endpoint dưới đây là bề mặt nghiệp vụ của feat, đều yêu cầu xác thực và quyết quyền qua F4 như mọi endpoint
của `apps/server`; danh tính và ngữ cảnh trang lấy từ `TenantContext`, workspace đích do thao tác chọn minh
thị (mặc định cá nhân), cộng với định danh tài nguyên:

- `POST /api/question-banks`, `GET /api/question-banks`: tạo và liệt kê ngân hàng theo workspace đích được
  chọn minh thị (mặc định cá nhân); liệt kê gồm ngân hàng cá nhân của người gọi và ngân hàng của trung tâm
  theo quyền tại trung tâm đó.
- `POST /api/question-banks/{id}/entries`, `DELETE /api/question-banks/{id}/entries/{entryId}`: thêm mục trỏ
  tới một phiên bản cụ thể (nội bộ hoặc catalog) và gỡ mục khỏi ngân hàng.
- `POST /api/questions`: tạo câu hỏi kèm phiên bản nháp đầu tiên.
- `GET /api/questions/{id}`: đọc câu hỏi theo quyền; phần đáp án và lời giải chỉ có trong kết quả khi người gọi
  có hành động xem đáp án trên phiên bản tương ứng.
- `PUT /api/questions/{id}/versions/{versionId}`: sửa phiên bản nháp; `POST /api/questions/{id}/versions`: tạo
  phiên bản nháp mới từ một phiên bản bất biến của cùng câu; `POST` tới
  `/api/questions/{id}/versions/{versionId}/publish`: phát hành phiên bản.
- `POST /api/questions/{id}/versions/{versionId}/copies`: tạo bản riêng sang workspace đích.
- `GET /api/catalog/questions`, `POST /api/catalog/questions`, và publish của catalog: đường đọc kiểm soát và
  đường ghi dành cho `platform-admin`.
- `POST /api/questions/{id}/versions/{versionId}/shares`: tạo chia sẻ theo một trong ba chế độ (chế độ "đã
  thuộc trung tâm" không tạo qua endpoint này mà qua nộp); `POST /api/shares/{grantId}/accept`: người có
  thẩm quyền của trung tâm tiếp nhận bản được cấp quyền dùng lâu dài vào một ngân hàng của trung tâm, xác
  nhận điều kiện giữ và dùng đã ghi trong grant; `DELETE /api/shares/{grantId}`: thu hồi một grant;
  `POST /api/shares/revoke-by-grantor`: thu hồi hàng loạt grant tạm thời theo người cấp, trong ngữ cảnh trung
  tâm của trang.
- `POST /api/handover-requests`: trung tâm tạo yêu cầu nộp câu hỏi/bộ câu hỏi; `POST` tới
  `/api/handover-requests/{id}/submissions`: giáo viên nộp các phiên bản đã chọn theo yêu cầu; `POST` tới
  `/api/submissions/{id}/review`: duyệt hoặc từ chối bản đã nhận đang chờ duyệt.

### 12. Điều kiện kiểm chứng bổ sung

Ngoài các tài khoản thử và cấu trúc Organization đã có từ F3, feat này cần thêm một tài khoản thử
`gv2-trung-tam-1`: thành viên của `trung-tam-1` thuộc group giáo viên của trung tâm đó (vai `tenant-teacher`),
để kiểm chứng cách ly giữa hai giáo viên cùng trung tâm và việc nộp một phiên bản mình chỉ được xem. Việc thêm
và gỡ membership của tài khoản `gv-tu-do` vào hai Organization `trung-tam-1` và `trung-tam-2` trong ca nghiệm
thu xuyên suốt thực hiện tay trong Admin Console của Keycloak thử nghiệm, đúng mô hình vận hành hiện hành.

## Việc con dự kiến

- [ ] Migration Flyway cho schema chuyên biệt của câu hỏi: bảng nội dung phiên bản kèm số phiên bản hợp đồng
      nội dung và kiểm tra tính hợp lệ tối thiểu, bảng tham chiếu tệp đính kèm, quan hệ ngân hàng – phiên
      bản với hai loại tham chiếu, và catalog `catalog_question` / `catalog_version`; cột khoá phân vùng và
      policy RLS theo mẫu F3 cho các bảng thuộc phân vùng.
- [ ] Ngân hàng câu hỏi theo ba workspace trên lõi `resource` của F4: tạo và liệt kê theo workspace đích
      được chọn minh thị (mặc định cá nhân), thêm/gỡ mục trỏ phiên bản cụ thể.
- [ ] Câu hỏi và vòng đời phiên bản: tạo với chủ sở hữu mặc định cá nhân, sửa bản nháp có khoá lạc quan, quy
      tắc bất biến, tạo phiên bản nháp mới, phát hành theo phân công.
- [ ] Catalog hệ thống: repository riêng, đường ghi cho `platform-admin`, đường đọc kiểm soát chỉ trả bản đã
      công bố, mục ngân hàng tham chiếu catalog, định danh catalog đưa vào dịch vụ quyết quyền của F4.
- [ ] Sao chép câu hỏi: kiểm quyền nguồn và đích, truy vết, không kế thừa ACL, giữ giới hạn tái sử dụng của
      nguồn ràng buộc cả chủ bản sao, sao tham chiếu tệp, đáp án chỉ sao theo khi có quyền xem đáp án.
- [ ] Chia sẻ ba chế độ: grant xem tạm thời có thời hạn và thu hồi được; grant dùng lâu dài kèm bước tiếp
      nhận và bản được cấp quyền giữ và dùng tại trung tâm theo đúng điều kiện của grant; chặn mở rộng quyền
      ngoài grant và ngoài phạm vi cấp lại.
- [ ] Quy trình nộp câu hỏi/bộ câu hỏi: yêu cầu nộp, danh sách bàn giao, kiểm quyền từng mục, tạo bản trung
      tâm chờ duyệt, biên nhận bất biến, chống nộp trùng, duyệt/từ chối.
- [ ] Xử lý rời trung tâm cho dữ kiện câu hỏi: thu hồi hàng loạt grant tạm thời theo người cấp, giữ nguyên bản
      dùng lâu dài theo điều kiện của grant, bản đã nộp và biên nhận; chặn token cấp trước khi gỡ membership.
- [ ] Bộ hành động của câu hỏi nối vào dịch vụ quyết quyền của F4, kèm test cho từng hành động và từng chế độ
      chia sẻ.
- [ ] Test tại local cho `apps/server` và kiểm chứng runtime theo mục Nghiệm thu, gồm lát của feat này trong ca
      nghiệm thu trọng tâm xuyên suốt.

## Quyết định liên quan

Các quyết định dưới đây được viết lại trọn vẹn; đặc tả này dựa trên đúng các nội dung này.

1. Một realm Keycloak duy nhất tên `giaokhoa`; mỗi trung tâm là một Organization trong realm. Keycloak giữ
   danh tính, membership và vai theo Organization; quyền trên từng tài nguyên nằm ở API của LMS: Keycloak không
   chấm quyền theo từng câu hỏi hay ngân hàng, không đăng ký tài nguyên lên Authorization Services, không tạo
   role Keycloak theo từng tài nguyên. Vai theo Organization đọc tại `organization[<alias>].realm_access.roles`
   của Organization của trang; vai ở cấp cao nhất của token chỉ dùng cho vai nền tảng `platform-admin`.
2. Quyền tại API dùng một lõi chung cho mọi loại tài nguyên (F4): `resource` (loại, workspace/chủ sở hữu,
   Organization khi gắn với trung tâm, người tạo, truy vết nguồn), `resource_version` (phiên bản và trạng
   thái), `resource_grant` (chủ thể được cấp, tài nguyên/phiên bản, hành động, phạm vi, thời hạn và điều kiện
   thu hồi), `collection_assignment` (phân công theo ngân hàng/bộ sưu tập và loại tài nguyên),
   `handover_request`, `submission`, `submission_item` và `resource_dependency` (các thành phần bàn giao
   dùng chung và biên nhận bàn giao). Mọi feat tài nguyên dùng lõi này; không dựng hệ ACL riêng cho câu hỏi.
3. Tài nguyên có ba workspace: cá nhân — gắn với tài khoản giáo viên, không gắn Organization, dùng được cả khi
   giáo viên làm tự do ở trang nền tảng lẫn khi tham gia nhiều trung tâm; trung tâm — gắn với một Organization;
   hệ thống — của nền tảng. Tài nguyên do giáo viên soạn mặc định thuộc workspace cá nhân của giáo viên, kể cả
   khi soạn tại trang của một trung tâm; `tenant_id` của một tài nguyên chỉ có khi tài nguyên thuộc trung tâm.
   Cấp grant cho một trung tâm không gắn hay chuyển `tenant_id` và không đổi chủ sở hữu của tài nguyên
   nguồn; Organization nhận được ghi trên grant và trên bản nhận tại trung tâm. Khung phân vùng của F3
   (khoá Organization ID / `user:<sub>` / `system`, discriminator cộng RLS) là nền cách ly mà schema của
   feat này ánh xạ lên.
4. Câu hỏi hệ thống nằm ở một catalog riêng — bảng và repository riêng — không nằm chung bảng với dữ kiện của
   trung tâm hay cá nhân. Luồng của trung tâm và cá nhân chỉ đọc các bản catalog đã công bố qua đường đọc được
   kiểm soát; không tắt bộ lọc tenant và không mở phiên "root" để đọc catalog.
5. Phiên bản là lõi của mô hình: quan hệ ngân hàng – câu hỏi trỏ tới phiên bản cụ thể; nội dung đã phát hành,
   đã chia sẻ hoặc đã nộp là bất biến — sửa bằng cách tạo phiên bản nháp mới hoặc tạo bản riêng. Sao chép tạo
   một tài nguyên mới có định danh mới và truy vết nguồn; bản sao không kế thừa ACL của nguồn nhưng giữ các
   giới hạn tái sử dụng của nguồn.
6. Quyền sửa tài nguyên tại trung tâm theo phân công lưu trong database của LMS — `bank-manager`,
   `bank-editor`, `bank-publisher` theo ngân hàng; `resource-editor`, `resource-reviewer`, `resource-publisher`
   theo tài nguyên đã thuộc trung tâm — không suy ra từ người tạo: `created_by` chỉ phục vụ truy vết. Giáo viên
   chưa được phân công chỉ soạn trong workspace cá nhân và dùng/sao chép những gì được cấp quyền. `tenant-admin`
   quản lý ngân hàng, phân công, chính sách chia sẻ và tài nguyên đã thuộc trung tâm, nhưng không xem, sửa hay
   đổi chủ sở hữu tài nguyên cá nhân của giáo viên.
7. Hành động trên tài nguyên tách riêng theo loại, không gộp thành một cờ quyền chung. Với câu hỏi: xem nội
   dung; xem đáp án và lời giải; dùng trong đề; tạo bản riêng; chia sẻ; nộp; biên tập bản nháp; phát hành vào
   ngân hàng. Grant chỉ mở đúng các hành động đã ghi và không tự mở rộng thành quyền chia sẻ lại, nộp thay, sửa
   bản gốc hay đổi chủ sở hữu.
8. Chia sẻ cho trung tâm có ba chế độ phân biệt: xem tạm thời — đọc đúng phiên bản được chỉ định trong thời
   gian cho phép, thu hồi được, mất khi giáo viên rời trung tâm; dùng lâu dài — grant tường minh theo từng
   phiên bản và từng trung tâm, tồn tại độc lập với membership của giáo viên, đi kèm bản được cấp quyền giữ
   và dùng tại trung tâm sau bước tiếp nhận của người có thẩm quyền của trung tâm — bản này không thuộc sở
   hữu của trung tâm và chỉ được dùng đúng điều kiện của grant, kể cả thời hạn; đã thuộc trung tâm — bản
   bàn giao do thao tác nộp tạo ra, thuộc sở hữu của trung tâm. Chia sẻ không bao giờ làm trung tâm thành
   chủ sở hữu của bản gốc cá nhân, và tài nguyên được chia sẻ không tự xuất hiện ở trung tâm khác chỉ vì
   cùng một giáo viên tham gia nhiều nơi.
9. Tài nguyên thuộc về trung tâm qua hành động nộp; ngoại lệ duy nhất là thao tác minh thị "soạn cho trung
   tâm" (mục 4), trong đó người tạo chủ động chọn đích là một ngân hàng của trung tâm và có phân công biên
   tập trên ngân hàng đó — ngoài hai đường này, không thao tác tạo hay vị trí trang nào làm tài nguyên thuộc
   trung tâm. Nộp là một quy trình bàn giao trong hệ thống: trung tâm tạo yêu cầu (người nộp, loại tài
   nguyên, phiên bản/phạm vi, nơi nhận, quyền tái sử dụng cần bàn giao; yêu cầu không cấp quyền đọc kho cá
   nhân); giáo viên chọn các phiên bản cụ thể; hệ thống lập danh sách bàn
   giao và kiểm quyền trên toàn bộ danh sách; giáo viên xác nhận thì hệ thống tạo các tài nguyên độc lập thuộc
   trung tâm, sao nội dung và ánh xạ lại liên kết trong gói; biên nhận bất biến được ghi lại (ai nộp, yêu cầu
   nào, thời điểm, phiên bản nguồn, bản đã nhận, phạm vi bàn giao). Mốc thuộc trung tâm của bản bàn giao là lúc
   nộp thành công; duyệt sau đó quyết định bản đã nhận có được đưa vào sử dụng hay không. Bản bàn giao bắt đầu
   ở trạng thái chờ duyệt; bị từ chối thì nộp một lần mới, không ghi đè lịch sử.
10. Tài khoản giáo viên là thành viên không được quản lý vòng đời bởi Organization (unmanaged): gỡ membership
    không xoá tài khoản, tài khoản giữ phương thức đăng nhập độc lập và giữ trọn kho cá nhân. Khi giáo viên rời
    một trung tâm: bản gốc cá nhân vẫn thuộc giáo viên; trung tâm mất quyền truy cập tạm thời; bản đã thuộc
    trung tâm vẫn ở lại và tiếp tục dùng, biên tập, phát hành được bởi trung tâm; bản được cấp quyền giữ và
    dùng ở chế độ dùng lâu dài vẫn ở lại và tiếp tục được dùng theo đúng điều kiện của grant đã cấp.
11. Tệp đính kèm là blob bất biến, có thể dùng chung về vật lý giữa các phiên bản và bản sao; quyền tải xuống
    đi qua tài nguyên/phiên bản được cấp quyền. Không để việc sửa một bản sao thay đổi nội dung tệp mà bản gốc
    đang sử dụng.
12. Giai đoạn hiện tại không có dữ kiện thật phải di trú và không có yêu cầu tương thích ngược: schema dựng
    theo mô hình mới ngay từ đầu; database tại local/test dựng lại từ đầu khi cần, còn stack DEV đang chạy
    nằm im, không dựng lại. Schema do Flyway sở hữu, Hibernate `ddl-auto=none`. Mọi quyết định quyền được
    kiểm lại trong giao dịch tại API; các đường truy vấn native, xuất dữ kiện hay hàng loạt vẫn phải áp đúng
    điều kiện phân vùng và quyền như đường truy vấn thường.

### Điểm để ngỏ

- Danh mục loại câu hỏi và schema nội dung theo từng loại (ví dụ trắc nghiệm, tự luận, điền khuyết): chưa
  chốt. Phần không để ngỏ: feat này tự giữ một hợp đồng nội dung tối thiểu có số phiên bản — thân câu hỏi,
  đáp án, lời giải, tiêu chí chấm và tệp đính kèm — kèm kiểm tra tính hợp lệ tối thiểu trước khi một phiên
  bản được phát hành, chia sẻ hoặc nộp (mục 2). Chỉ danh mục loại câu và schema riêng cho từng loại là để
  ngỏ; việc chốt thuộc feat đầu tiên cần tới nó (dựng đề/làm bài) hoặc một quyết định riêng.
- Vốn từ điều kiện thu hồi của grant dùng lâu dài để hệ thống tự đánh giá, ngoài thời hạn: chưa chốt, và đây
  là điểm để ngỏ duy nhất về thu hồi. Ngữ nghĩa của thời hạn và của quyền dùng trên bản đã nhận không để
  ngỏ, đã ghi rõ ở mục 8: hết thời hạn là hết quyền dùng; thu hồi grant không xoá bản đã nhận; việc dùng
  tiếp bản đã nhận tuân theo đúng điều kiện đã ghi trong grant. Các điều kiện ngoài thời hạn được ghi dạng
  mô tả kèm grant; thao tác quản trị trên grant dùng lâu dài do `tenant-admin` của trung tâm nhận hoặc
  `platform-admin` thực hiện khi điều kiện đã ghi xảy ra, và tác động của nó lên bản đã nhận cũng tuân theo
  chính điều kiện đó: dừng quyền dùng theo điều kiện, không xoá bản đã nhận, không mở rộng quyền của trung
  tâm.

## Nghiệm thu

- [ ] Mặc định cá nhân: `gv-tu-do` (không là thành viên của Organization nào) tạo ngân hàng và câu hỏi tại
      trang nền tảng; các tài nguyên đó mang chủ sở hữu là chính tài khoản, không có `tenant_id`, và tạo lại
      cùng thao tác khi đang ở ngữ cảnh trang `trung-tam-1` vẫn cho kết quả cá nhân tương tự.
- [ ] Workspace thao tác tách khỏi trang đang mở: `gv-trung-tam-1` đang ở trang `trung-tam-1` vẫn liệt kê và
      đọc được ngân hàng cá nhân của chính mình (phần "Của tôi") mà không cần rời trang; khi `tenant-admin`
      của `trung-tam-1` chọn minh thị workspace của trung tâm lúc tạo ngân hàng thì ngân hàng đó thuộc
      `trung-tam-1`, không thuộc người tạo.
- [ ] Cách ly kho cá nhân: thành viên của `trung-tam-1` không liệt kê hay đọc được ngân hàng cá nhân của
      `gv-tu-do`; `gv-trung-tam-1` và `gv2-trung-tam-1` — hai giáo viên cùng là thành viên `trung-tam-1` — không
      đọc được ngân hàng cá nhân chưa chia sẻ của nhau (kiểm chứng cả hai chiều, kết quả rỗng hoặc 403).
- [ ] Phân công ngân hàng trung tâm: giáo viên không có phân công không tạo được câu trong ngân hàng của
      `trung-tam-1`; `bank-editor` qua thao tác minh thị "soạn cho trung tâm" tạo được câu thuộc trung tâm
      trong ngân hàng được giao và sửa bản nháp nhưng không phát hành được; `bank-publisher` phát hành được;
      người tạo câu thuộc trung tâm mà không có phân công áp dụng thì không sửa được chính câu đó.
- [ ] Ngân hàng chủ quản và ngân hàng tham chiếu: thêm một tham chiếu tới một câu vào ngân hàng thứ hai của
      trung tâm; người chỉ có phân công biên tập trên ngân hàng thứ hai không sửa được câu đó — quyền sửa vẫn
      theo phân công trên ngân hàng chủ quản của câu; gỡ tham chiếu ở ngân hàng thứ hai không ảnh hưởng tới
      câu và ngân hàng chủ quản.
- [ ] Bất biến phiên bản và mục ngân hàng: phát hành phiên bản V1 của một câu trong ngân hàng, thử sửa trực
      tiếp V1 thì bị từ chối; tạo phiên bản nháp V2 và sửa V2, mục ngân hàng vẫn trả nội dung V1 cho tới khi
      được trỏ tường minh sang V2; một ngân hàng khác đang trỏ V1 không đổi nội dung.
- [ ] Catalog hệ thống: tài khoản `platform-admin` tạo và công bố một câu catalog; giáo viên đọc được bản đã
      công bố qua đường đọc catalog và không đọc được bản catalog đang nháp; giáo viên sao chép câu catalog
      thành câu cá nhân có truy vết nguồn, sửa bản sao không đổi catalog; token của giáo viên gọi đường ghi
      catalog bị từ chối; mọi quyết định đọc và ghi catalog đều qua dịch vụ quyết quyền của F4.
- [ ] Sao chép không mang quyền của nguồn: giáo viên B sao chép một câu được chia sẻ cho mình thành bản cá
      nhân; một giáo viên C từng có quyền trên câu nguồn không có quyền gì trên bản sao của B; bản sao của một
      phiên bản bị giới hạn chỉ dùng trong `trung-tam-1` bị từ chối khi đem chia sẻ cho `trung-tam-2`, kể cả
      khi thao tác do chính chủ sở hữu của bản sao thực hiện.
- [ ] Sao chép không lộ đáp án: giáo viên chỉ có quyền xem nội dung và tạo bản riêng trên một phiên bản, không
      có quyền xem đáp án, sao chép phiên bản đó: bản sao không chứa đáp án, lời giải, tiêu chí chấm và tệp
      chứa đáp án, và chủ sở hữu của bản sao không xem được đáp án của câu nguồn qua bản sao; khi người sao
      chép có quyền xem đáp án trên phiên bản nguồn thì phần đáp án mới đi theo bản sao.
- [ ] Chia sẻ xem tạm thời: chủ sở hữu chia sẻ một phiên bản cho `trung-tam-1` với hành động chỉ xem nội dung;
      thành viên có phân công áp dụng của trung tâm đọc được thân câu nhưng kết quả không chứa đáp án và lời
      giải; thành viên không có phân công áp dụng không đọc được qua grant này; chủ sở hữu thu hồi grant thì
      lần đọc tiếp theo bị chặn; thành viên trung tâm không sửa và không chia sẻ lại được bản gốc.
- [ ] Chia sẻ dùng lâu dài và phạm vi dùng: chủ sở hữu chia sẻ phiên bản V2 của một câu cho `trung-tam-1` ở chế
      độ dùng lâu dài với phạm vi "đưa vào ngân hàng": trước khi tiếp nhận, trung tâm chưa dùng được phiên
      bản đó; `tenant-admin` (hoặc `bank-manager` của ngân hàng đích) tiếp nhận vào ngân hàng đích thì một bản
      được cấp quyền giữ và dùng xuất hiện tại `trung-tam-1` và thành viên được phân công của trung tâm dùng
      được bản đó trong phạm vi đã cấp; trung tâm sửa nội dung bản đã nhận, phát hành lại hoặc chia sẻ tiếp
      đều bị từ chối vì grant không ghi các hành động đó; cùng câu đó, phiên bản V1 và V3 không xuất hiện ở
      trung tâm dưới bất kỳ hình thức nào; một phiên bản khác chỉ được cấp phạm vi "dùng trong đề" thì không
      thêm được vào ngân hàng dùng chung của trung tâm.
- [ ] Thời hạn và thu hồi trên bản đã nhận dùng lâu dài: chia sẻ một phiên bản ở chế độ dùng lâu dài có thời
      hạn; sau khi hết thời hạn, trung tâm không dùng được bản đã nhận nữa dù bản vật lý còn lưu tại trung
      tâm; thu hồi grant theo điều kiện đã ghi không xoá bản đã nhận và không xoá bằng chứng tiếp nhận.
- [ ] Ca xuyên suốt — lát của feat này: giáo viên tự do tạo một câu hỏi có hai phiên bản trong kho cá nhân;
      thêm tài khoản đó làm thành viên của cả `trung-tam-1` và `trung-tam-2`; chia sẻ đúng phiên bản thứ hai
      cho `trung-tam-1` ở chế độ dùng lâu dài. Kết quả đồng thời đúng: `trung-tam-1` dùng được bản đã nhận; mọi
      tài khoản của `trung-tam-2` không đọc được câu gốc lẫn bản đã nhận; `trung-tam-1` không thấy phiên bản
      thứ nhất; kho cá nhân của giáo viên còn nguyên và chỉ mình giáo viên truy cập được.
- [ ] Rời trung tâm: tiếp ca trên, gỡ membership của giáo viên khỏi `trung-tam-1` trong Keycloak thử nghiệm và
      chạy thao tác thu hồi grant tạm thời theo người cấp: `trung-tam-1` vẫn dùng được bản đã nhận ở chế độ
      dùng lâu dài trong phạm vi và thời hạn của grant đã cấp, nhưng không tạo được phiên bản nháp mới trên
      bản đó; grant xem tạm thời khác của cùng giáo viên cho trung tâm đó không còn hiệu lực; giáo viên vẫn
      đọc và sửa trọn kho cá nhân của mình; phía `trung-tam-2` không thay đổi quyền.
- [ ] Rời trung tâm bằng token cũ: lấy token của giáo viên trong lúc còn là thành viên của `trung-tam-1`; sau
      khi gỡ membership, dùng lại chính token đó khi nó chưa hết hạn để đọc và sửa tài nguyên của
      `trung-tam-1`: mọi yêu cầu đều bị từ chối, theo cơ chế đối chiếu membership hiện hành của F3/F4.
- [ ] Nộp bộ câu hỏi: `trung-tam-1` tạo yêu cầu nộp một bộ câu hỏi về một ngân hàng đích; trước khi nộp, trung
      tâm không đọc được bất kỳ bản nháp cá nhân nào của giáo viên; giáo viên nộp đúng các phiên bản đã chọn
      (không phải toàn bộ ngân hàng); biên nhận tồn tại và tra được với ánh xạ phiên bản nguồn → bản đã nhận;
      bản đã nhận ở trạng thái chờ duyệt chỉ người được phân công duyệt thấy; sau khi duyệt, trung tâm dùng
      được bản đã nhận; giáo viên xoá bản gốc cá nhân, bản đã nhận không đổi và vẫn dùng được.
- [ ] Nộp trái quyền bị chặn: giáo viên thử nộp một phiên bản mà mình chỉ có quyền xem qua grant của người
      khác — thao tác bị từ chối và không tạo tài nguyên nào ở trung tâm; nộp lại cùng một yêu cầu với cùng
      danh sách phiên bản không tạo bản bàn giao thứ hai.
- [ ] Cách ly ở mức database: bằng kết nối SQL với role runtime trên database của server, đặt biến ngữ cảnh
      phân vùng của `trung-tam-1` thì `SELECT` trên bảng nội dung phiên bản câu hỏi chỉ trả hàng của trung tâm
      đó — không trả hàng cá nhân và không trả hàng của `trung-tam-2`; không đặt biến ngữ cảnh thì trả 0 hàng.
- [ ] Migration và test: dựng database trống từ đầu, toàn bộ migration (của F2, F3, F4 và feat này) áp sạch;
      bộ test của `apps/server` tại local đạt, gồm test cho ba workspace, vòng đời phiên bản, catalog, sao
      chép, ba chế độ chia sẻ, quy trình nộp và xử lý rời trung tâm; `GET /api/profile` và các endpoint kiểm
      chứng của F3 vẫn trả kết quả đúng như trước (hồi quy).

## Phụ thuộc và thứ tự

- Phụ thuộc: F3 — khung tenancy cho `apps/server` (issue #57): resolver ngữ cảnh bốn chế độ, discriminator theo
  khoá phân vùng, RLS và cách đọc vai theo Organization. Đặc tả này dùng đúng khung đó, không định nghĩa lại.
- Phụ thuộc: F4 — lõi quyền tại API: các bảng lõi `resource`, `resource_version`, `resource_grant`,
  `collection_assignment`, các thành phần bàn giao dùng chung `handover_request`, `submission`,
  `submission_item`, `resource_dependency`, dịch vụ quyết quyền tập trung và cơ chế phân công. Mọi bảng và cơ
  chế lõi nêu trong đặc tả là của F4, feat này chỉ thêm schema chuyên biệt của câu hỏi và hành vi nghiệp vụ
  trên lõi đó. Hợp đồng lõi của F4 (bảng lõi, tập hành động, dịch vụ quyết quyền) phải cố định trước; sau
  mốc đó, schema nội dung, hợp đồng API và kiểm thử nghiệp vụ của feat này được làm song song với phần còn
  lại của F4; việc tích hợp lên lõi và nghiệm thu toàn bộ tiêu chí phụ thuộc F4 hoàn thành.
- Thứ tự việc con: schema trước; ngân hàng, câu hỏi và vòng đời phiên bản tiếp theo; catalog, sao chép và chia
  sẻ sau đó; quy trình nộp và xử lý rời trung tâm cuối cùng, trước kiểm chứng runtime toàn bộ tiêu chí.
- Feat dùng tiếp: F11 (đề thi và ma trận) tiêu thụ hành động "dùng trong đề" và quan hệ tới phiên bản câu hỏi
  do feat này định nghĩa; F13 (làm bài) tiêu thụ đường đọc nội dung câu hỏi theo quyền xem đáp án.
