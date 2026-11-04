## Bối cảnh và vấn đề

`apps/server` đã có khung xác thực JWT của realm `giaokhoa` (F0), khung dữ kiện Hibernate/JPA + Flyway (F2) và khung
tenancy với resolver ngữ cảnh, discriminator và Row Level Security (F3). Trên nền đó, F4 dựng lõi quyền tại API dùng
chung cho mọi loại tài nguyên (`resource`, `resource_version`, `resource_grant`, `collection_assignment`,
`resource_dependency`, dịch vụ quyết quyền, quy trình bàn giao) và F10 dựng câu hỏi/ngân hàng theo ba workspace cá
nhân — trung tâm — hệ thống. Đề thi và ma trận hiện vẫn chỉ có ở `apps/api` (module `exams`).

Đề và ma trận là loại tài nguyên thứ hai sau câu hỏi, và là loại đầu tiên *tiêu thụ* tài nguyên khác: một đề chứa các
câu hỏi cụ thể, một ma trận là khuôn để sinh đề từ các nguồn câu. Nếu mô hình của đề không chốt trước, ba lỗi cấu trúc
sẽ xảy ra ngay: đề tham chiếu sống tới câu hỏi thì việc sửa hoặc xoá câu nguồn làm thay đổi đề đã phát hành và kết quả
chấm về sau; thao tác nộp đề tự kéo các câu trong đề vào ngân hàng tái sử dụng của trung tâm thì giáo viên bị mất quyền
kiểm soát phạm vi bàn giao; sinh đề từ ma trận mà đọc thẳng ngân hàng của tác giả ma trận thì kho cá nhân của tác giả
bị rò cho người sinh đề. Các feat làm bài, giao bài và điểm (F12–F14) đều tiêu thụ đề đã phát hành, nên mô hình chủ sở
hữu, phiên bản, snapshot và phạm vi bàn giao của đề/ma trận phải chốt tại feat này, trước khi các feat đó bắt đầu.

Feat này dựng lại chức năng đề thi và ma trận trong `apps/server` trên lõi tài nguyên của F4 và câu hỏi của F10: nội
dung chuyên biệt của đề và ma trận, snapshot của đề khi phát hành/chia sẻ/nộp, sinh đề từ ma trận có kiểm quyền trên
nguồn câu thật, và hai thao tác share/nộp áp cho đề và ma trận với phạm vi tường minh theo loại.

## Mục tiêu

- Đề và ma trận là tài nguyên trên lõi của F4, áp ba workspace như câu hỏi: do giáo viên soạn thì mặc định thuộc
  workspace cá nhân của giáo viên, kể cả khi soạn tại trang của một trung tâm hoặc soạn theo yêu cầu của trung tâm.
- Đề đã chia sẻ (kể cả chia sẻ tạm thời), đã nộp hoặc đã phát hành giữ nội dung cố định — snapshot đủ nội dung câu,
  đáp án, tiêu chí chấm và tệp cần thiết, hoặc chỉ tham chiếu phiên bản câu đã bất biến: sửa hoặc xoá câu nguồn sau
  đó không làm thay đổi đề đã phát hành hay kết quả chấm dựa trên nó.
- Phạm vi bàn giao theo loại được API thực thi, không chỉ hiển thị trên giao diện: nộp đề không đưa các câu trong đề
  thành câu tái sử dụng trong ngân hàng của trung tâm và không bàn giao ma trận nguồn; nộp ma trận không cấp quyền đọc
  các ngân hàng mà người soạn từng dùng.
- Sinh đề từ ma trận kiểm quyền dùng-để-sinh-đề trên đúng phiên bản ma trận và quyền trên từng nguồn câu thực tế
  tại thời điểm sinh; đề sinh ra mặc định thuộc workspace của người thực hiện.
- Quyền trên đề và ma trận tách riêng theo từng hành động (xem nội dung, xem đáp án, tạo bản riêng, chia sẻ, dùng
  để sinh đề, nộp, biên tập, duyệt, phát hành...) và được quyết tại API của LMS qua dịch vụ quyết quyền của F4 —
  không có object quyền nào của đề/ma trận nằm trong Keycloak.

## Phạm vi

### Làm

- Nội dung chuyên biệt của đề trên lõi tài nguyên của F4: cấu trúc đề (phần/mục, thứ tự, điểm số), các mục đề trỏ tới
  phiên bản câu cụ thể của F10, snapshot của mục đề khi đề được phát hành, chia sẻ dạng dùng lâu dài hoặc nộp, và cố
  định nội dung phụ thuộc khi chia sẻ tạm thời theo mục 2.
- Ma trận như một loại tài nguyên riêng trên cùng lõi: cấu trúc gồm chủ đề, mức độ, số câu, điểm và các ràng buộc sinh
  đề; ma trận tách khỏi ngân hàng được chọn cho một lần sinh đề cụ thể.
- Thao tác sinh đề từ ma trận: kiểm quyền dùng-để-sinh-đề trên đúng phiên bản ma trận, chọn nguồn câu tại thời điểm
  sinh, kiểm quyền dùng-trong-đề trên từng nguồn câu, tạo đề nháp mới thuộc workspace của người thực hiện.
- Phiên bản cho đề và ma trận: bản nháp được biên tập theo quyền (có chống ghi đè đồng thời); bản đã chia sẻ, đã nộp
  hoặc đã phát hành là bất biến — sửa bằng phiên bản nháp mới hoặc tạo bản riêng.
- Share cho trung tâm với đề và ma trận theo đúng ba chế độ của lõi F4/F10: xem tạm thời; dùng lâu dài bằng grant
  tường minh kèm bản sao/snapshot tại trung tâm; đã thuộc trung tâm đối với bản đã nộp.
- Nộp đề và nộp ma trận qua quy trình bàn giao của lõi F4 (yêu cầu nộp, danh sách bàn giao, biên nhận bất biến, trạng
  thái chờ duyệt), với phạm vi tường minh theo loại như ở mục Chi tiết việc cần làm.
- Phân công tại trung tâm cho đề và ma trận (`resource-editor`, `resource-reviewer`, `resource-publisher`) qua dữ
  kiện phân công của F4.
- Áp khung của F3 cho mọi bảng mới của feat này: khoá phân vùng theo workspace, Row Level Security với policy riêng
  cho từng bảng, tài khoản runtime không vượt được RLS.
- Các lát của ca nghiệm thu trọng tâm xuyên suốt thuộc về đề và ma trận (xem mục Nghiệm thu): tài nguyên là đề/ma
  trận được share đúng một phiên bản cho một trung tâm, rồi giáo viên rời trung tâm đó.

### Không làm

- Không làm luồng làm bài, giao bài và chấm điểm: không ghi nhận bài làm của học viên, không tính điểm, không có đợt
  giao bài. Các việc đó thuộc F12 (hoàn thành và điểm), F13 (làm bài) và F14 (giao bài). Hành động "giao cho lớp" chỉ
  được định nghĩa như một hành động phân biệt trong mô hình quyền của đề tại feat này; luồng giao bài thuộc F14.
- Không làm câu hỏi và ngân hàng (F10) và không dựng lại lõi quyền (F4): feat này chỉ tiêu thụ lõi và dữ kiện của hai
  feat đó, không tạo hệ ACL riêng cho đề/ma trận.
- Không dựng chức năng render câu hỏi trong phiên làm bài: móc nối từ câu hỏi sang phần làm bài thuộc F13.
- Không tạo resource, permission hay role trong Keycloak theo từng đề/ma trận; không bật Authorization Services,
  không tích hợp policy enforcer.
- Không dựng chức năng xuất tệp đề ra định dạng ngoài trong feat này: hành động xuất (có đáp án / không có đáp án) chỉ
  được định nghĩa ở tầng quyền để các feat sau sử dụng; định dạng xuất chưa chốt (xem mục Điểm để ngỏ).
- Không di trú dữ kiện đề/ma trận từ `apps/api`: database phục vụ test là database riêng của từng lượt test trên
  test-pg, dựng lại từ đầu khi cần (mỗi lượt một database, xoá sau lượt chạy; không dựng hạ tầng test tại local)
  và stack DEV đang chạy nằm im; migration của feat này chỉ tạo cấu trúc mới trên nền các migration đã có.

## Chi tiết việc cần làm

### 1. Mô hình dữ kiện: nội dung chuyên biệt trên lõi tài nguyên chung

Đề và ma trận là hai loại tài nguyên (`resource.type` tương ứng) trên lõi của F4: chủ sở hữu, workspace, phiên bản,
grant và phân công đều dùng các bảng và dịch vụ của lõi (`resource`, `resource_version`, `resource_grant`,
`collection_assignment`, `resource_dependency`); feat này không tạo bảng quyền riêng. Nội dung chuyên biệt nằm ở
các bảng riêng của feat:

- Bảng nội dung đề: cấu trúc của một phiên bản đề — các phần/mục theo thứ tự, điểm số của từng mục và của toàn đề.
- Bảng mục đề: mỗi mục trỏ tới đúng một phiên bản câu cụ thể của F10 (không trỏ mơ hồ tới "phiên bản mới nhất" của
  câu) và giữ snapshot của mục theo mục 2 dưới đây khi phiên bản đề được phát hành, chia sẻ dạng dùng lâu dài hoặc
  nộp; khi chia sẻ tạm thời, nội dung phụ thuộc được cố định theo mục 2.
- Bảng nội dung ma trận: các hàng/ô của một phiên bản ma trận — chủ đề, mức độ, số câu yêu cầu, điểm và các ràng buộc
  sinh đề. Ma trận không chứa tham chiếu sở hữu tới một ngân hàng cụ thể; ngân hàng/nguồn câu là lựa chọn của từng
  lần sinh đề (mục 3).

Mọi bảng mới mang khoá phân vùng theo khung của F3, ánh xạ từ workspace của tài nguyên sở hữu: Organization ID cho
tài nguyên thuộc trung tâm, `user:<sub>` cho tài nguyên cá nhân, `system` cho tài nguyên hệ thống; `tenant_id` của
một tài nguyên chỉ có khi tài nguyên thuộc trung tâm. Grant không gắn hay chuyển `tenant_id` và chủ sở hữu của tài
nguyên nguồn: tài nguyên cá nhân được grant cho một trung tâm vẫn là tài nguyên cá nhân, và Organization nhận được
ghi trên grant cùng trên bản nhận đi kèm nếu có. Cách đặt biến ngữ cảnh database và policy RLS theo đúng mẫu của
F3 (mục 8 dưới đây). API tự đặt chủ sở hữu của tài nguyên cá nhân từ
principal đã xác thực; không tin `owner` do client gửi, và không cho đổi loại chủ sở hữu bằng một lệnh cập nhật tài
nguyên thông thường.

### 2. Snapshot của đề

Mục đề ở bản nháp tham chiếu phiên bản câu cụ thể. Khi một phiên bản đề được phát hành, được chia sẻ dạng dùng lâu
dài hoặc được nộp, hệ thống đóng băng snapshot cho từng mục đề, gồm: nội dung câu, đáp án, lời giải/tiêu chí chấm,
điểm của mục và các tệp đính kèm cần thiết để sử dụng và chấm đề. Tệp đính kèm là blob bất biến theo cơ chế tệp của
F9/F10: bản sao có thể dùng chung dữ kiện vật lý, nhưng quyền tải đi qua tài nguyên/phiên bản được cấp quyền, và việc
sửa một bản sao không được thay đổi tệp mà bản gốc đang dùng.

Chia sẻ tạm thời cũng phải cố định nội dung phụ thuộc, kể cả khi đề còn là bản nháp: hoặc đóng băng snapshot cho
từng mục như trên, hoặc mục đề chỉ được tham chiếu các phiên bản câu đã bất biến (đã phát hành hoặc đã khoá). Một
đề mà mục đề còn trỏ tới phiên bản câu nháp có thể sửa tại chỗ thì không được chia sẻ tạm thời cho tới khi nội
dung phụ thuộc được cố định bằng một trong hai cách đó; khoá phiên bản đề một mình không đủ, vì câu nháp được
tham chiếu vẫn có thể bị sửa tại chỗ và làm đổi nội dung đề đã chia sẻ.

Hệ quả bắt buộc: sau khi snapshot đã đóng băng, việc sửa câu nguồn (tạo phiên bản câu mới), xoá hoặc lưu trữ câu
nguồn, hay thu hồi quyền trên câu nguồn đều không làm thay đổi nội dung của phiên bản đề đó; phiên bản đề đã phát
hành hiển thị và chấm được mà không cần quyền đọc tiếp tục trên câu nguồn. Ngược lại, snapshot trong gói đề là dữ
kiện *dùng trong đề*: nó không tạo cho bên nhận quyền tái sử dụng từng câu như câu độc lập trong ngân hàng (phân
biệt này được thực thi ở mục 6 và mục 7).

### 3. Ma trận và sinh đề

Sinh đề là một thao tác tạo tài nguyên mới, gồm các bước kiểm soát tại API:

- Trước hết, API kiểm quyền dùng-để-sinh-đề trên đúng phiên bản ma trận được chọn; người không có quyền này trên
  phiên bản ma trận đó thì thao tác bị chặn toàn bộ ngay từ bước này. Người sinh đề chọn ma trận (theo đúng phiên
  bản) và chọn (các) nguồn câu cho lần sinh này: ngân hàng cá nhân của chính họ, ngân hàng của trung tâm trong ngữ
  cảnh hiện tại, các câu được cấp quyền dùng-trong-đề, và câu hệ thống đã công bố qua đường đọc catalog của F10.
- API kiểm quyền dùng-trong-đề trên *từng nguồn câu thực tế* sẽ đưa vào đề. Được dùng ma trận không suy ra được đọc
  hay dùng các ngân hàng mà tác giả ma trận từng dùng: hệ thống không tự truy vấn ngân hàng cá nhân của tác giả ma
  trận trong bất kỳ trường hợp nào. Khi bất kỳ ràng buộc nào của lần sinh không thỏa — thiếu quyền trên ma trận,
  thiếu quyền dùng-trong-đề trên một nguồn câu bắt buộc, hoặc nguồn câu không đủ số lượng theo ràng buộc của ma
  trận — thao tác sinh thất bại toàn bộ: không tạo đề, không âm thầm thay bằng câu khác và không ra đề dở dang;
  kết quả trả về nêu rõ lý do và phần bị chặn.
- Đề sinh ra là một tài nguyên mới ở trạng thái nháp, thuộc workspace của người thực hiện (mặc định là workspace cá
  nhân), có truy vết tới phiên bản ma trận và các phiên bản câu đã dùng qua `resource_dependency` của lõi F4 (thành
  phần do F4 định nghĩa và dựng; feat này chỉ ghi dữ kiện phụ thuộc qua nó). Việc dùng ma trận thuộc trung tâm làm
  đầu vào không tự khiến đề sinh ra thuộc trung tâm. Các giới hạn sử dụng đã ghi trên từng câu nguồn và trên ma
  trận (ví dụ chỉ được dùng trong một trung tâm) được ghi thành dữ kiện giới hạn trên đề đầu ra và tiếp tục ràng
  buộc đề đầu ra qua dịch vụ quyết quyền của F4 ở mọi quyết định sau đó — phát hành, chia sẻ, nộp đều phải đối
  chiếu các giới hạn này — chứ không chỉ lưu truy vết phụ thuộc.

### 4. Phiên bản và biên tập đồng thời

Bản nháp của đề/ma trận được sửa tại chỗ theo quyền biên tập, với khoá lạc quan bằng cột version (`@Version`) để hai
người cùng sửa một bản nháp không ghi đè thầm lặng lên nhau: người lưu sau bị từ chối vì xung đột phiên bản. [Docs —
Hibernate ORM User Guide, chương "Locking", mục Optimistic: cơ chế khoá lạc quan qua `@Version`; entity manager dùng
cột version để phát hiện cập nhật xung đột và ngăn mất cập nhật, ứng dụng không được tự thay đổi số version]

Bản đã chia sẻ, đã nộp hoặc đã phát hành là bất biến: không lệnh cập nhật nào ghi đè nội dung của chúng. Muốn sửa:
người có phân công biên tập trên tài nguyên đó tạo phiên bản nháp mới từ phiên bản hiện hành; người có quyền tạo
bản riêng — hành động `copy` — trên đúng phiên bản đó thì tạo bản riêng (tài nguyên mới, ID mới, thuộc workspace
của họ). Quyền xem hoặc dùng, kể cả qua grant xem tạm thời, không cho tạo bản riêng: thiếu hành động `copy` thì
thao tác tạo bản riêng bị từ chối. Bản riêng không kế thừa ACL của nguồn nhưng giữ truy vết nguồn và các giới hạn
tái sử dụng của nguồn. Các ngân hàng, đề và lần bàn giao đang trỏ tới một phiên bản không tự chạy theo phiên bản
mới.

### 5. Quyền tách riêng theo hành động

Mọi quyết định quyền đi qua dịch vụ quyết quyền của F4, kết hợp: danh tính và membership từ token, vai theo
Organization của ngữ cảnh trang (đọc tại `organization[<alias>].realm_access.roles` theo khung F3), workspace/chủ sở
hữu của tài nguyên, grant tường minh, phân công áp dụng và trạng thái phiên bản. Hành động tách riêng, không gộp
thành một cờ quyền chung:

- Đề: xem nội dung; xem đáp án; tạo bản riêng; chia sẻ; nộp; biên tập; duyệt; phát hành; giao cho lớp; xuất kèm
  đáp án; xuất không kèm đáp án.
- Ma trận: xem; tạo bản riêng; dùng để sinh đề; chia sẻ; nộp; biên tập; duyệt; phát hành.

Tạo bản riêng ánh xạ hành động `copy`, chia sẻ ánh xạ hành động `share` và nộp ánh xạ hành động `submit` của lõi
F4; các hành động còn lại ánh xạ vào tập hành động lõi của F4 hoặc được định nghĩa chuyên biệt tại feat này trên
cùng dịch vụ quyết quyền. Quyền xuất bị ràng buộc bởi phần nội dung được phép xuất: xuất kèm đáp án chỉ gồm các
thành phần mà người gọi có quyền xem đáp án, xuất không kèm đáp án chỉ gồm phần nội dung người gọi được xem, và
quyền xuất trên một đề không tự mở quyền xem đáp án đối với bất kỳ thành phần nào trong đề.

Các ranh giới phải giữ: xem đề không mặc nhiên cho xem đáp án và không cho lấy từng câu trong snapshot ra tái sử
dụng; dùng ma trận không mặc nhiên cho đọc mọi ngân hàng mà ma trận nhắc tới; sở hữu một đề không cấp quyền xem bài
làm, điểm hay chấm bài của học viên (thuộc các feat làm bài/điểm); `created_by` chỉ là truy vết — tác giả của bản
nguồn không tự có quyền sửa bản đã thuộc trung tâm.

### 6. Share cho trung tâm

Share áp cho đề và ma trận qua `resource_grant` của lõi, đúng ba chế độ:

- **Xem tạm thời:** grant chỉ cho đọc đúng phiên bản được chỉ định trong thời gian cho phép; thu hồi được; mất hiệu
  lực khi giáo viên rời trung tâm.
- **Dùng lâu dài:** grant tường minh theo từng phiên bản và từng trung tâm, ghi rõ phiên bản nguồn, trung tâm được
  cấp, phạm vi sử dụng, thời hạn và điều kiện thu hồi nếu có; tồn tại độc lập với membership của giáo viên; đi kèm
  bản sao hoặc snapshot đủ nội dung tại trung tâm (đối với đề là snapshot theo mục 2) để việc thu hồi hay mất liên
  kết tới bản gốc không làm trung tâm mất nội dung đã được cấp dùng lâu dài. Bản sao/snapshot đó là bản trung tâm
  được cấp quyền giữ và dùng theo đúng phạm vi của grant, không phải bản thuộc sở hữu của trung tâm: giữ bản này
  không tự cho trung tâm sửa nội dung, phát hành lại hay chia sẻ tiếp — các hành động đó chỉ có khi grant ghi rõ;
  quyền biên tập và phát hành đầy đủ trên bản của trung tâm chỉ áp cho bản đã thuộc trung tâm qua nộp (mục 7).
- **Đã thuộc trung tâm:** bản bàn giao độc lập do thao tác nộp tạo ra (mục 7).

Share không chuyển chủ sở hữu của bản gốc, không gắn hay chuyển `tenant_id` của bản gốc (Organization nhận được
ghi trên grant và trên bản nhận đi kèm), không tự lan sang trung tâm khác, và grant chỉ gồm đúng các hành động đã
ghi: grant dùng một đề không tự cấp quyền tái sử dụng từng câu trong đề và không cấp quyền dùng độc lập ma trận
nguồn; grant dùng một ma trận không cấp quyền đọc ngân hàng nguồn — khi trung tâm sinh đề từ ma trận đó, quyền trên
nguồn câu vẫn được kiểm riêng theo mục 3. Khi phát hành, chia sẻ hoặc nộp một đề, API còn kiểm phạm vi được phép
đóng gói và phân phối toàn bộ thành phần của đề — từng câu, đáp án và lời giải đi kèm, tệp đính kèm — theo quyền
trên từng thành phần: có quyền dùng một câu trong đề không tự cho quyền mang đề chứa câu đó sang trung tâm khác,
và thành phần nào nằm ngoài phạm vi được phép thì thao tác bị từ chối.

### 7. Nộp đề và nộp ma trận: phạm vi bàn giao tường minh theo loại

Nộp dùng đúng quy trình bàn giao dùng chung của lõi F4 — `handover_request`, `submission`, `submission_item` là
thành phần do F4 định nghĩa và dựng, feat này chỉ tham chiếu và định nghĩa phạm vi theo loại: trung tâm tạo
`handover_request` (người phải nộp, loại tài nguyên, phạm vi, nơi nhận, quyền tái sử dụng cần bàn giao — yêu cầu
không cấp quyền đọc kho cá nhân); giáo viên chọn phiên bản cụ thể và xác nhận; API lập danh sách bàn giao, kiểm
quyền trên toàn bộ danh sách, tạo các tài nguyên mới thuộc trung tâm, sao nội dung và ánh xạ lại các liên kết
trong phạm vi đã xác nhận; ghi biên nhận bất biến (`submission`, `submission_item`). Điểm riêng của feat này là
phạm vi theo loại, do API thực thi:

- **Nộp đề:** trung tâm nhận bản đề độc lập — cấu trúc, điểm số, snapshot nội dung câu, đáp án, tiêu chí chấm và tệp
  cần để sử dụng/chấm đề. Không tự bàn giao: quyền đưa từng câu nguồn vào ngân hàng tái sử dụng của trung tâm, và
  quyền sử dụng độc lập ma trận nguồn.
- **Nộp ma trận:** trung tâm nhận bản ma trận — chủ đề, mức độ, số câu, điểm và ràng buộc sinh đề. Không tự bàn giao:
  quyền đọc các ngân hàng hoặc câu hỏi mà người soạn từng dùng.
- **Nộp bộ câu hỏi kèm theo** (chỉ khi yêu cầu ghi rõ): trung tâm nhận các phiên bản câu được chọn thành tài nguyên
  tái sử dụng của trung tâm, theo đúng quy trình nộp của F10. Không tự bàn giao: các phiên bản và câu không được
  chọn, và toàn bộ ngân hàng cá nhân.

Nếu trung tâm cần cả đề, bộ câu hỏi và ma trận, yêu cầu nộp phải ghi cả ba và giáo viên thấy rõ phạm vi trước khi
xác nhận. Mốc chuyển chủ quản của bản bàn giao là lúc nộp thành công; bản nhận bắt đầu ở trạng thái chờ duyệt, chỉ
người được phân công tiếp nhận/duyệt mới thấy; duyệt sau đó quyết định việc đưa vào sử dụng. Trạng thái chờ duyệt
và điều kiện chặn dùng trước khi duyệt không phải trạng thái mới của `resource_version` của lõi: chúng được đưa
vào quyết định quyền qua điểm tích hợp điều kiện nghiệp vụ của F4, và feat này khai báo điều kiện của mình tại đó.

Collection đích và người tiếp nhận: bộ sưu tập đề và bộ sưu tập ma trận là `resource` bộ sưu tập theo hợp đồng của
F4 (loại do feat này định nghĩa), do `tenant-admin` của trung tâm tạo và quản lý; phân công
`resource-editor`/`resource-reviewer`/`resource-publisher` gắn trên đúng bộ sưu tập và đúng loại tài nguyên theo
`collection_assignment` của F4. Yêu cầu nộp ghi rõ bộ sưu tập đích; bản nhận chờ duyệt thuộc bộ sưu tập đích đó,
và người tiếp nhận là `resource-reviewer` được phân công trên bộ sưu tập đích (hoặc `tenant-admin` của trung
tâm). Bị yêu cầu sửa thì giáo
viên nộp một lần mới — không ghi đè lịch sử lần trước. Bản bàn giao không được phụ thuộc vào câu hoặc tệp riêng tư
của giáo viên sau khi nộp; xoá bản cá nhân không xoá bản trung tâm đã nhận. Thao tác nộp phải chống nộp trùng khi
bấm lại hoặc retry, và chỉ báo thành công khi gói bàn giao đã đủ nội dung, tệp và liên kết.

### 8. Phân vùng, RLS và ngữ cảnh

Mọi bảng mới của feat này áp đúng khung của F3: mang khoá phân vùng của workspace sở hữu; bật Row Level Security và
có đúng một policy theo mẫu của F3 trên cột khoá của chính bảng đó (bảng con không dựa hơi policy của bảng cha);
biến ngữ cảnh database (`app.partition_key`, `app.current_sub`) được đặt theo giao dịch tại điểm chặn duy nhất của
khung F3; datasource runtime dùng role `lms_server_app` không superuser, không `BYPASSRLS`, không sở hữu bảng. Mỗi
endpoint nghiệp vụ của feat tự khai báo workspace nó phục vụ và lấy thành phần tương ứng từ `TenantContext` của F3;
không endpoint nào tự dựng khoá phân vùng ngoài các thành phần của context, và không lấy tenant, Organization hay
người thực hiện từ thân request.

## Việc con dự kiến

- [ ] Bảng nội dung chuyên biệt của đề (cấu trúc, mục đề trỏ phiên bản câu cụ thể) và của ma trận (chủ đề, mức độ, số
      câu, điểm, ràng buộc) trên lõi tài nguyên của F4, kèm migration và RLS theo mẫu của F3.
- [ ] Snapshot của mục đề khi phát hành/chia sẻ dạng dùng lâu dài/nộp, gồm tệp đính kèm theo cơ chế blob bất biến;
      cố định nội dung phụ thuộc khi chia sẻ tạm thời (snapshot hoặc chỉ tham chiếu phiên bản câu đã bất biến).
- [ ] Biên tập bản nháp với khoá lạc quan; tạo phiên bản nháp mới theo phân công biên tập và tạo bản riêng chỉ khi
      có quyền `copy` trên đúng phiên bản; bất biến của bản đã chia sẻ/nộp/phát hành.
- [ ] Sinh đề từ ma trận: kiểm quyền dùng-để-sinh-đề trên đúng phiên bản ma trận, chọn nguồn câu, kiểm quyền
      dùng-trong-đề trên từng nguồn câu, ghi giới hạn của nguồn thành dữ kiện ràng buộc đầu ra qua F4, thất bại
      toàn bộ kèm lý do khi ràng buộc không thỏa, tạo đề nháp thuộc workspace của người thực hiện kèm truy vết.
- [ ] Kiểm quyền tách theo hành động cho đề và ma trận qua dịch vụ quyết quyền của F4, gồm các ranh giới xem
      đề/xem đáp án/tái sử dụng câu và dùng ma trận/đọc ngân hàng.
- [ ] Share ba chế độ cho đề và ma trận qua grant của lõi, gồm snapshot đi kèm grant dùng lâu dài.
- [ ] Nộp đề/ma trận qua quy trình bàn giao của lõi với phạm vi tường minh theo loại và biên nhận bất biến.
- [ ] Bộ sưu tập đề và ma trận theo hợp đồng bộ sưu tập của F4: tạo/quản lý bởi `tenant-admin` của trung tâm, phân
      công trên đúng bộ sưu tập và loại tài nguyên, bộ sưu tập đích và người tiếp nhận cho bản chờ duyệt (trạng
      thái chờ duyệt qua điểm tích hợp điều kiện nghiệp vụ của F4).
- [ ] Test tự động cho `apps/server` (snapshot, sinh đề có kiểm quyền, phạm vi bàn giao, cách ly workspace/RLS) và
      kiểm chứng runtime theo mục Nghiệm thu; phần cần database thật chạy trên test-pg online theo quyết định 12.

## Quyết định liên quan

Các quyết định dưới đây được viết lại trọn vẹn; đặc tả này dựa trên đúng các nội dung này.

1. Một realm Keycloak duy nhất tên `giaokhoa`; mỗi trung tâm là một Organization trong realm. Keycloak giữ danh
   tính, membership và vai theo Organization; vai theo Organization đọc tại
   `organization[<alias>].realm_access.roles` trong token. Quyền trên từng tài nguyên nằm ở API của LMS: không đăng ký
   tài nguyên lên Keycloak Authorization Services, không tạo role Keycloak theo từng đề/ma trận. LMS không đọc/ghi
   trực tiếp database của Keycloak.
2. Tài nguyên có ba workspace: cá nhân — gắn với tài khoản giáo viên, không gắn Organization, dùng được cả khi giáo
   viên làm tự do ở trang nền tảng lẫn khi tham gia nhiều trung tâm; trung tâm — gắn với một Organization; hệ thống —
   của nền tảng. Tài nguyên do giáo viên soạn mặc định thuộc workspace cá nhân của giáo viên, kể cả khi soạn tại trang
   của một trung tâm hoặc soạn theo yêu cầu của trung tâm: nơi thao tác và người yêu cầu không tự quyết định chủ sở
   hữu. `tenant_id` của một tài nguyên chỉ có khi tài nguyên thuộc trung tâm. Grant không thay đổi `tenant_id` hay
   chủ sở hữu của tài nguyên nguồn: tài nguyên cá nhân được cấp cho một trung tâm vẫn là tài nguyên cá nhân, và
   Organization nhận được ghi trên grant cùng trên bản nhận đi kèm (bản sao, snapshot hoặc bản bàn giao) nếu có.
3. Mọi loại tài nguyên dùng chung một lõi quyền tại API: `resource` (loại, workspace/chủ sở hữu, Organization khi gắn
   với trung tâm, người tạo, truy vết nguồn), `resource_version` (phiên bản và trạng thái; bản đã chia sẻ, đã nộp hoặc
   đã phát hành không bị ghi đè), `resource_grant` (chủ thể được cấp, tài nguyên/phiên bản, hành động và phạm vi, thời
   hạn và điều kiện thu hồi nếu có), `collection_assignment` (phân công theo ngân hàng/bộ sưu tập và loại tài
   nguyên), `resource_dependency` (quan hệ phụ thuộc giữa các tài nguyên), cùng phần bàn giao dùng chung
   (`handover_request`, `submission`, `submission_item`) và một dịch vụ quyết quyền tập trung.
   `resource_dependency` và phần bàn giao dùng chung thuộc lõi F4; feat này chỉ tham chiếu, không định nghĩa lại.
   Không dựng hệ ACL riêng cho từng loại tài nguyên. `created_by`
   chỉ phục vụ truy vết; quyền sửa không suy ra từ người tạo. API tự đặt chủ sở hữu từ principal đã xác thực, không
   tin dữ kiện chủ sở hữu do client gửi, và không cho đổi loại chủ sở hữu bằng lệnh cập nhật tài nguyên thông thường.
4. Nội dung đã chia sẻ, đã nộp hoặc đã phát hành là bất biến: sửa là tạo phiên bản nháp mới (với người có phân công
   biên tập) hoặc tạo bản riêng (chỉ với người có quyền tạo bản riêng — hành động `copy` — trên đúng phiên bản đó;
   quyền xem hoặc dùng không cho tạo bản riêng). Bản riêng là tài nguyên mới có truy vết nguồn,
   không kế thừa ACL của nguồn nhưng giữ các giới hạn tái sử dụng của nguồn. Tệp đính kèm là blob bất biến: có thể
   dùng chung dữ kiện vật lý, nhưng quyền tải đi qua tài nguyên/phiên bản được cấp quyền, và sửa một bản sao không
   thay đổi tệp mà bản gốc đang dùng.
5. Đề giữ snapshot của các câu đã dùng: khi một phiên bản đề được phát hành, chia sẻ dạng dùng lâu dài hoặc nộp,
   snapshot đóng băng nội dung câu, đáp án, tiêu chí chấm và tệp cần thiết. Khi chia sẻ tạm thời, nội dung phụ
   thuộc cũng phải cố định — bằng snapshot như trên hoặc bằng cách chỉ tham chiếu phiên bản câu đã bất biến. Sửa
   hoặc xoá câu nguồn không làm thay đổi đề đã phát hành hoặc kết quả chấm dựa trên nó. Dữ kiện câu đóng gói trong
   đề (dùng trong đề) và câu được phép tái sử dụng trong ngân hàng là hai phạm vi khác nhau và phải được phân biệt
   trong dữ kiện lẫn kiểm quyền. Khi phát hành, chia sẻ hoặc nộp một đề, phạm vi được phép đóng gói và phân phối
   được kiểm trên toàn bộ thành phần của đề (từng câu, đáp án, tệp) theo quyền trên từng thành phần, không chỉ
   quyền trên chính đề đó.
6. Phạm vi bàn giao là tường minh theo loại tài nguyên và do API thực thi: nộp một đề không tự nộp các câu trong đề
   vào ngân hàng tái sử dụng và không tự nộp ma trận nguồn; nộp một ma trận không cấp quyền đọc các ngân hàng/câu hỏi
   mà người soạn từng dùng. Nếu trung tâm cần nhiều loại, yêu cầu nộp phải ghi rõ từng loại và giáo viên thấy rõ phạm
   vi trước khi xác nhận.
7. Nộp là một giao dịch bàn giao có biên nhận: trung tâm tạo yêu cầu (yêu cầu không cấp quyền đọc kho cá nhân); giáo
   viên chọn phiên bản cụ thể; API lập danh sách bàn giao và kiểm quyền trên toàn bộ danh sách (có quyền xem hoặc dùng
   một câu của người khác không gồm quyền bàn giao câu đó); giáo viên xác nhận; API tạo các ID mới thuộc trung tâm,
   sao nội dung và ánh xạ lại liên kết trong phạm vi đã xác nhận, rồi ghi biên nhận bất biến gồm người nộp, yêu cầu,
   thời điểm, phiên bản nguồn, bản nhận và phạm vi bàn giao. Mốc chuyển chủ quản của bản bàn giao là lúc nộp thành
   công; bản nhận ở trạng thái chờ duyệt — trạng thái này và điều kiện chặn dùng trước khi duyệt đi qua điểm tích
   hợp điều kiện nghiệp vụ của F4, không phải trạng thái mới của `resource_version`; bị yêu cầu sửa thì nộp lần
   mới, không ghi đè lịch sử. Bản trung tâm đã nhận
   không phụ thuộc vào câu/tệp riêng tư của giáo viên, và xoá bản cá nhân không xoá bản trung tâm.
8. Share cho trung tâm có ba chế độ phân biệt: xem tạm thời — đọc đúng phiên bản được chỉ định trong thời gian cho
   phép, thu hồi được, mất khi giáo viên rời trung tâm; dùng lâu dài — grant tường minh theo từng phiên bản và từng
   trung tâm, tồn tại độc lập với membership của giáo viên, đi kèm bản sao hoặc snapshot đủ nội dung tại trung tâm —
   bản đi kèm đó là bản được cấp quyền giữ và dùng theo phạm vi của grant, khác bản thuộc sở hữu của trung tâm, và
   giữ bản không tự sinh quyền sửa, phát hành lại hay chia sẻ tiếp; đã thuộc trung tâm — bản bàn giao do nộp tạo
   ra. Share không bao giờ làm trung tâm thành chủ sở hữu của bản gốc cá
   nhân, không tự lan sang trung tâm khác, và grant chỉ gồm đúng các hành động đã ghi — không tự mở rộng thành quyền
   chia sẻ lại, quyền nộp hoặc quyền sửa bản gốc.
9. Giáo viên là thành viên không bị quản lý vòng đời theo Organization (unmanaged member): rời một trung tâm không
   xoá tài khoản và không động vào kho cá nhân. Khi giáo viên rời trung tâm: quyền xem tạm thời và các phân công của
   họ trong trung tâm bị mất; các grant dùng lâu dài và các bản đã thuộc trung tâm vẫn ở lại và tiếp tục dùng được,
   không phụ thuộc tài khoản của tác giả.
10. Được dùng một ma trận không có nghĩa được đọc mọi ngân hàng mà ma trận nhắc tới: cấu trúc/ràng buộc của ma trận
    tách khỏi ngân hàng được chọn cho một lần sinh đề, và lúc sinh đề hệ thống phải kiểm quyền trên các nguồn câu
    thực tế. Đề sinh từ ma trận mặc định thuộc workspace cá nhân của người thực hiện; việc dùng ma trận của trung
    tâm làm đầu vào không tự khiến đầu ra thuộc trung tâm.
11. Quyền sửa tài nguyên đã thuộc trung tâm theo phân công lưu trong database của LMS: `resource-editor` biên tập
    bản nháp/tạo phiên bản mới, `resource-reviewer` đọc gói được giao, nhận xét, yêu cầu sửa và phê duyệt,
    `resource-publisher` phát hành tài nguyên đã đạt điều kiện; phân công gắn với loại tài nguyên và ngân hàng/bộ
    sưu tập cụ thể. `tenant-admin` quản lý yêu cầu nộp, phân công và tài nguyên đã thuộc trung tâm, nhưng không tự
    đọc, sửa hay đổi chủ sở hữu tài nguyên cá nhân. Sở hữu một đề không cấp quyền trên bài làm và điểm của học viên.
12. Giai đoạn hiện tại chỉ xây đúng phần có nơi dùng thật theo mô hình cuối: không mang logic của module cũ sang
    nguyên vẹn, không viết backfill hay tương thích ngược cho dữ kiện cũ (database phục vụ test là database
    riêng của từng lượt test trên test-pg — một PostgreSQL test riêng (hostname `test-pg.fuckr.net`), tách hẳn
    khỏi stack DEV: mỗi lượt test tạo một database trống của riêng nó trên test-pg và xoá database đó khi test
    xong; không dựng hạ tầng test tại local. Stack DEV đang chạy nằm im, không ai đụng vào, và dữ kiện của nó
    không làm bãi test). Chức năng của module cũ — kể cả `site-config` — chỉ
    được dựng khi nghiệp vụ của feat này thực sự cần tới nó; không rà soát module cũ để quyết định phạm vi của
    feat. Schema do Flyway sở hữu, Hibernate `ddl-auto=none`. Khung phân vùng và RLS của F3 áp cho mọi bảng mới:
    mỗi bảng có cột phân vùng đều bật RLS và có policy riêng theo mẫu của F3, kiểm chứng bằng chính role runtime.

### Quyết định trước đây bị thay bởi đặc tả này

- Phân quyền cho đề/ngân hàng bằng Keycloak Authorization Services (đăng ký đề/ngân hàng thành resource trong
  Keycloak, policy enforcer chấm quyền): thay bằng mô hình quyền tại API của LMS theo các điểm 1, 3 và 11 ở trên.
  Hệ quả đã áp cho toàn phương án chuyển đổi: các phần viết cho F4, F10 và F11 theo hướng Authorization Services
  không còn hiệu lực.

### Điểm để ngỏ

- Thuật toán sinh đề từ ma trận: cách chọn câu thoả các ràng buộc của ma trận (mức độ ngẫu nhiên, khả năng tái lập
  đúng một đề đã sinh) chưa được chốt. Hành vi tối thiểu khi ràng buộc không thỏa đã chốt tại mục 3: sinh thất bại
  toàn bộ kèm lý do, không ra đề dở dang. Đặc tả này chốt các bất biến quanh thao tác sinh: ma trận tách khỏi ngân
  hàng của lần sinh, kiểm quyền trên từng nguồn câu thực tế, không đọc ngân hàng cá nhân của tác giả ma trận, và đề
  sinh ra mặc định thuộc workspace của người thực hiện. Thuật toán chọn câu sẽ do chủ repo quyết khi có yêu cầu cụ
  thể.
- Định dạng xuất đề cho hành động xuất kèm đáp án / không kèm đáp án (định dạng tệp, kênh xuất) chưa được chốt; feat
  này chỉ định nghĩa hai hành động đó ở tầng quyền.
- Loại member cho tài khoản học viên do trung tâm cấp (unmanaged hay managed) chưa được chốt; điểm này đã ghi để ngỏ
  từ F3 và không chặn feat này vì feat này không tạo tài khoản và không xử lý vòng đời thành viên.

## Nghiệm thu

- [ ] Mặc định cá nhân: giáo viên soạn một đề và một ma trận tại trang `trung-tam-1` — cả hai thuộc workspace cá
      nhân của giáo viên: không xuất hiện trong danh sách tài nguyên của trung tâm, và `tenant-admin` của
      `trung-tam-1` không đọc được chúng.
- [ ] Snapshot không đổi khi nguồn đổi: phát hành một đề có câu lấy từ ba nguồn (câu cá nhân, câu được cấp quyền
      dùng-trong-đề, câu hệ thống đã công bố); sau đó tạo phiên bản mới cho một câu nguồn và xoá/lưu trữ một câu
      nguồn khác — nội dung, đáp án, tiêu chí chấm và điểm của bản đề đã phát hành không đổi, đối chiếu bằng dữ kiện
      snapshot trong database và nội dung API trả về.
- [ ] Chia sẻ tạm thời đề nháp giữ nguyên nội dung: giáo viên chia sẻ tạm thời một đề nháp đã cố định nội dung phụ
      thuộc theo mục 2 (snapshot hoặc chỉ tham chiếu phiên bản câu đã bất biến); sau đó sửa câu nguồn bằng cách tạo
      phiên bản câu mới — bên nhận mở đề đã chia sẻ vẫn thấy đúng nội dung tại thời điểm chia sẻ. Một đề nháp còn
      mục trỏ tới phiên bản câu nháp chưa bất biến và chưa đóng snapshot thì thao tác chia sẻ tạm thời bị từ chối.
- [ ] Xem đề không suy ra xem đáp án và tái sử dụng: người chỉ có grant xem tạm thời trên một đề đọc được nội dung
      đề nhưng không xem được đáp án, không lấy được từng câu trong đề thành câu tái sử dụng của mình, và không sửa
      được đề.
- [ ] Tạo bản riêng bắt buộc có quyền copy: người chỉ có quyền xem/dùng trên một phiên bản đề (kể cả qua grant xem
      tạm thời) gọi thao tác tạo bản riêng thì bị từ chối và không tài nguyên nào được tạo; khi được cấp thêm hành
      động `copy` trên đúng phiên bản đó, thao tác tạo bản riêng thành công trong phạm vi cho phép: bản riêng thuộc
      workspace của người tạo, bản gốc không đổi.
- [ ] Sao chép đề không mang đáp án khi thiếu quyền xem đáp án: người có quyền `copy` và xem nội dung nhưng không
      có quyền xem đáp án trên một đề tạo bản riêng — bản sao gồm cấu trúc đề và nội dung câu, không chứa đáp án,
      lời giải hay tiêu chí chấm của đề nguồn.
- [ ] Sửa bản đã phát hành: `resource-editor` của trung tâm sửa một đề đã phát hành — hệ thống tạo phiên bản nháp
      mới, bản đã phát hành giữ nguyên nội dung, và các nơi đang trỏ tới phiên bản cũ không tự chuyển sang phiên bản
      mới.
- [ ] Chống ghi đè đồng thời: hai người cùng mở một bản nháp đề để sửa; người lưu sau bị từ chối vì xung đột phiên
      bản (khoá lạc quan), nội dung của người lưu trước không bị ghi đè thầm lặng.
- [ ] Nộp chỉ-đề: theo một yêu cầu chỉ gồm đề, giáo viên nộp một đề — trung tâm nhận bản đề độc lập ở trạng thái chờ
      duyệt kèm biên nhận bất biến; kiểm chứng: các câu trong đề không xuất hiện thành câu tái sử dụng trong ngân
      hàng của trung tâm, ma trận nguồn vẫn thuộc cá nhân và trung tâm không đọc được; sau khi giáo viên xoá bản gốc
      cá nhân, bản đề của trung tâm vẫn mở và dùng được đầy đủ (không phụ thuộc nguồn riêng tư).
- [ ] Nộp chỉ-ma-trận: theo một yêu cầu chỉ gồm ma trận, trung tâm nhận bản ma trận độc lập kèm biên nhận; trung tâm
      không đọc được các ngân hàng và câu hỏi mà tác giả từng dùng khi sinh đề từ ma trận đó.
- [ ] Sinh đề bị chặn khi thiếu quyền: người không có quyền dùng-để-sinh-đề trên đúng phiên bản ma trận thì thao
      tác sinh bị chặn toàn bộ ngay từ bước kiểm ma trận. Người có quyền trên ma trận nhưng không có quyền
      dùng-trong-đề trên nguồn câu bắt buộc của lần sinh, hoặc nguồn câu không đủ số lượng theo ràng buộc của ma
      trận — thao tác sinh thất bại toàn bộ kèm lý do rõ, không tạo tài nguyên đề nào và không ra đề dở dang; không
      câu nào từ ngân hàng cá nhân của tác giả ma trận bị đọc hoặc đưa vào đề. Khi người sinh có đủ quyền và đủ
      nguồn, đề sinh ra thuộc workspace cá nhân của người sinh, kể cả khi ma trận thuộc trung tâm.
- [ ] Giới hạn nguồn chặn đóng gói sang trung tâm khác: một câu chỉ được cấp quyền dùng trong đề tại trung tâm A
      được đưa vào một đề; thử chia sẻ hoặc nộp đề đó sang trung tâm B thì bị từ chối vì phạm vi đóng gói của
      thành phần vượt giới hạn của nguồn, dù người thực hiện có quyền chia sẻ trên chính đề đó.
- [ ] Ca trọng tâm xuyên suốt, lát đề/ma trận: giáo viên tự do tạo một đề trong workspace cá nhân, tham gia cả hai
      trung tâm A và B, share đúng một phiên bản của đề cho A dạng dùng lâu dài, rồi rời A — kết quả đồng thời đúng:
      giáo viên vẫn dùng trọn kho cá nhân; A vẫn dùng được bản đã nhận; B không được cấp quyền gì trên đề đó; A không
      thấy các phiên bản khác chưa được chia sẻ của cùng đề đó.
- [ ] Bản nhận dùng lâu dài không tự sinh quyền: trung tâm A giữ bản sao/snapshot của một đề qua grant dùng lâu
      dài chỉ gồm hành động dùng — người của A sửa nội dung, phát hành lại hoặc chia sẻ tiếp bản đó đều bị từ chối;
      chỉ bản đã thuộc trung tâm qua nộp mới được biên tập và phát hành theo phân công.
- [ ] Ca trọng tâm xuyên suốt, lát ma trận: giáo viên tự do tạo một ma trận trong workspace cá nhân, tham gia cả
      hai trung tâm A và B, share đúng một phiên bản của ma trận cho A dạng dùng lâu dài và một phiên bản khác cho
      A dạng xem tạm thời, rồi rời A — kết quả đồng thời đúng: giáo viên vẫn dùng trọn kho cá nhân; A vẫn dùng được
      bản ma trận đã nhận lâu dài để sinh đề với nguồn câu của A; grant xem tạm thời không còn hiệu lực; B không
      được cấp quyền gì trên ma trận đó; A không thấy các phiên bản khác chưa được chia sẻ của cùng ma trận.
- [ ] Quyền không rò chéo trung tâm: người có phân công `resource-editor` tại trung tâm A sang trung tâm B không còn
      quyền đó nếu B không có phân công tương ứng; một grant tại A không có hiệu lực tại B.
- [ ] Khách: tài khoản đăng nhập tại trang của một trung tâm mà mình không phải thành viên không thấy bất kỳ đề/ma
      trận nào của trung tâm đó khi chưa được cấp quyền cụ thể.
- [ ] RLS chặn ở mức database: bằng kết nối SQL với role runtime `lms_server_app` trên database của server, không
      đặt biến ngữ cảnh thì `SELECT` trên các bảng nội dung đề/ma trận của feat này trả 0 hàng; đặt biến ngữ cảnh của
      một phân vùng thì chỉ thấy hàng của phân vùng đó.
- [ ] Migration và test: dựng database test trống trên test-pg từ đầu (xoá sau lượt chạy), toàn bộ migration áp
      sạch; bộ test của `apps/server` đạt, gồm test mới của feat này và không làm gãy test của F3/F4/F10.

## Phụ thuộc và thứ tự

- Phụ thuộc: F3 (khung tenancy: resolver ngữ cảnh, discriminator, RLS và tách tài khoản database; issue #57) phải
  xong trước. F4 (lõi quyền tại API: `resource`, `resource_version`, `resource_grant`, `collection_assignment`,
  `resource_dependency`, phần bàn giao dùng chung `handover_request`/`submission`/`submission_item` và dịch vụ
  quyết quyền) là hợp đồng phải ổn định trước — feat này tiêu thụ lõi đó, không tự dựng lõi riêng. Với F10 (câu
  hỏi và ngân hàng theo ba workspace), phần phải ổn định trước là hợp đồng phiên bản câu và đường đọc catalog hệ
  thống.
- Thứ tự: mô hình ma trận, cấu trúc đề, kiểm thử quyền và thuật toán sinh đề được làm song song sau khi hợp đồng
  F4 và hợp đồng phiên bản câu của F10 ổn định; tích hợp cuối — tiêu thụ phiên bản câu thật và đường đọc catalog —
  mới cần F10 chạy được. Thứ tự việc con bên trong feat: mô hình dữ kiện kèm snapshot trước; rồi phiên bản/biên
  tập và sinh đề; rồi share và nộp; cuối cùng là kiểm chứng runtime theo mục Nghiệm thu.
- Feat này là nền cho F12 (hoàn thành và điểm), F13 (làm bài) và F14 (giao bài): các feat đó tiêu thụ đề đã phát hành
  cùng snapshot của nó.
