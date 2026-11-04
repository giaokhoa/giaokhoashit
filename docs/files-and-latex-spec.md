# Đặc tả phần tệp và LaTeX

## Căn cứ và quy ước truy nguồn

Đặc tả này tổng kết từ bốn nguồn, mọi quy tắc đều truy được về một trong các nguồn đó:

- **[QĐ]** Ba quyết định của chủ repo về LaTeX: (1) LaTeX được render ra ảnh rồi mới giao — người xem và học viên
  nhận ảnh render đóng băng cùng bản chụp, không nhận bản trình bày chứa cấu trúc toán; (2) chủ sở hữu câu hỏi là
  người được sửa LaTeX của nó — theo diễn đạt đã sửa ở lượt chấm lại: chủ sở hữu quyết quyền sửa nguồn mà tài
  nguyên đó có hợp lệ; sở hữu tài nguyên không mặc nhiên làm phát sinh nguồn hoặc quyền nhận nguồn từ bản gốc;
  (3) câu hỏi của hệ thống tuyệt đối không share source LaTeX, kể cả khi copy: bản sao không mang nguồn, ai muốn
  sửa phải tự soạn lại.
- **[A1]** Tham vấn Astra về phần tệp, lượt 1: mô hình ba lớp tệp, luồng tải lên, luồng đọc theo ngữ cảnh, danh
  sách bất biến nghiệm thu, xử lý LaTeX và thứ tự ưu tiên.
- **[A2]** Tham vấn Astra lượt 2 và tài liệu yêu cầu nghiệm thu đi kèm (18 ca T01–T18): điều chỉnh theo ba quyết
  định trên, hai dạng công thức có-nguồn và chỉ-có-ảnh, luồng render, hợp đồng API hai mặt.
- **[MG]** Mô hình gốc của dự án và hợp đồng đã khoá của đợt 07/11: ba workspace (cá nhân, trung tâm, hệ thống),
  một chủ sở hữu duy nhất cho mỗi tài nguyên, phiên bản bất biến, nộp là bàn giao tạo bản sao thuộc trung tâm, ba
  chế độ chia sẻ (xem tạm thời, dùng lâu dài qua bản sao/snapshot, đã thuộc trung tâm), tập hành động lõi và dịch
  vụ quyết định quyền duy nhất của lõi quyền, snapshot đề đóng băng khi phát hành.

Chỗ nào là suy diễn nối giữa các nguồn, văn bản ghi rõ **[Suy diễn]**. Chỗ nào là đề xuất của Astra chưa được chủ
repo chốt, văn bản ghi rõ **[Đề xuất — chờ chốt]** và tập hợp lại ở mục Điểm còn mở và các quyết định đã chốt;
đề xuất không phải luật của đặc tả này.

Hai nguồn bổ sung sau khi bản thảo đầu tiên được tổng kết:

- **[Astra chấm]** Lượt Astra chấm lại chính đặc tả này (2026-11-05): các sửa đổi về diễn đạt, ma trận quyền, hợp
  đồng dữ liệu, tiêu chí nghiệm thu và hướng đóng các điểm mở. Các nhãn [Suy diễn] được lượt chấm xác nhận hợp lý
  vẫn giữ nhãn, kèm ghi chú "đã xác nhận ở lượt chấm lại" tại chỗ.
- **[QĐ-2026-11-05]** Quyết định của chủ repo ngày 2026-11-05, theo khuyến nghị của Astra trong lượt chấm: đóng
  M1, M2, M3 theo khuyến nghị đó (xem mục Điểm còn mở và các quyết định đã chốt). Các điểm đã đóng theo quyết
  định này không còn là [Đề xuất — chờ chốt].

Một ranh giới phải nói thẳng ngay từ đầu [A1]: giữ kín mã nguồn với người không được cấp quyền là khả thi; bảo đảm
không ai sao chép được công thức đã nhìn thấy thì không khả thi — người xem vẫn chụp ảnh, OCR hoặc gõ lại được.
Tiêu chí bảo vệ của đặc tả này là **không cấp và không làm lộ mã nguồn bị bảo vệ**, cộng với hạn chế lấy hàng loạt
và dấu vết khi nội dung bị phát tán. Không tiêu chí nào trong đặc tả này hứa "không sao chép được công thức đã
hiển thị", và không dùng các biện pháp kiểu chặn chụp màn hình, tắt chọn văn bản, hay mã hoá TeX rồi giải mã ở
JavaScript như một bảo đảm giữ kín.

## Bối cảnh và vấn đề

Phần tệp chưa được xây trong `apps/server`. Các đặc tả câu hỏi và đề đã dùng khái niệm tham chiếu tới blob bất
biến (tệp đính kèm của một phiên bản là định danh blob, tên tệp và loại nội dung; blob có thể dùng chung về vật lý
giữa các phiên bản và bản sao), nhưng pipeline tải tệp lên kho tệp chưa được đặc tả, và hợp đồng nội dung câu hỏi
hiện tại vẫn để LaTeX nằm trong văn bản câu hỏi — nghĩa là bất kỳ đường đọc nội dung nào cũng trả nguyên nguồn
TeX cho người xem. [MG; A2]

Đặc tả này lấp đúng hai khoảng đó: (1) phần tệp là một phần của tài nguyên có phiên bản, chịu cùng cơ chế quyết
quyền của API — không dựng một kho tải lên/tải xuống có hệ quyền riêng rồi nối vào sau [A1]; (2) LaTeX được tách
thành dữ liệu soạn thảo riêng tư và dữ liệu giao cho người xem là ảnh, theo ba quyết định [QĐ].

## Phạm vi

### Làm

- Mô hình dữ liệu tệp ba lớp: tài nguyên/phiên bản nghiệp vụ; liên kết tệp theo ngữ cảnh gắn với đúng phiên bản
  và đúng vị trí nội dung; blob bất biến trong kho lưu trữ riêng tư. Phiên tải lên là thành phần điều phối vòng
  đời của tệp, không phải một lớp dữ liệu. [A1; Astra chấm: thống nhất nghĩa "ba lớp tệp" theo cách này]
- Luồng tải lên an toàn: xin phiên, nhận vào vùng tạm, kiểm tra và xử lý, chốt nội dung bất biến, gắn vào bản
  nháp có kiểm lại quyền. Áp dụng cho tệp đính kèm của câu hỏi/đề và cho tệp bài làm của học viên (mục đích sử
  dụng khác nhau, cùng một luồng). [A1; Suy diễn — đã xác nhận ở lượt chấm lại: A1 nêu cả hai ngữ cảnh trong
  cùng một luồng tải lên; điều kiện được gắn hay nộp tệp vẫn riêng theo nghiệp vụ của đích]
- Cấu trúc nội dung theo khối cho các phần văn bản của một phiên bản câu hỏi, và hai dạng công thức (có-nguồn,
  chỉ-có-ảnh) như một khác biệt ở dữ liệu và ở API. [A2]
- Luồng render LaTeX ra ảnh ở phía server bằng worker bị giới hạn, kết quả đóng băng cùng snapshot. [QĐ-1; A2]
- Ma trận quyền cho tệp và nguồn: đọc tệp, đọc nguồn, sửa nguồn — tách quyền nội dung và quyền đáp án tới từng
  tệp. [A1; A2]
- Quy tắc sao chép, chia sẻ, nộp đối với tệp và nguồn LaTeX, gồm chuỗi sao chép nhiều vòng và quy tắc riêng cho
  nguồn hệ thống. [QĐ-3; A2; MG]
- Dấu vết kiểm toán cho thao tác trên tệp và nguồn; quy tắc giữ và dọn tệp; phục hồi dữ liệu nhất quán. [A1]

### Không làm

- Không dựng hệ quyền riêng cho tệp: mọi quyết định đi qua dịch vụ quyết định quyền của lõi quyền với ngữ cảnh
  của F3; không tạo role hay policy trong Keycloak cho từng tệp, từng ảnh hay từng công thức. [A1; A2; MG]
- Không phục vụ tệp qua URL công khai hoặc URL ký sống lâu như đường mặc định. [A1]
- Không làm watermark hay cá nhân hoá bản trình bày, không nhận PDF như một loại tệp tải lên — các phần này thuộc
  nhóm để sau ở mục Ưu tiên, chỉ làm khi có nhu cầu và đường xử lý đã được kiểm chứng. [A1]
- Không làm chức năng khôi phục nguồn từ ảnh (OCR/AI) trong đợt này: đây là lựa chọn phạm vi của đợt, không phải
  lệnh cấm vĩnh viễn suy ra từ ba quyết định. Riêng việc khôi phục nguồn hệ thống trong luồng sao chép/sửa bị cấm
  theo QĐ-3. [A2; Astra chấm]
- Không làm chức năng bàn giao nguồn LaTeX trong bản 07 [QĐ-2026-11-05]: bản 07 không bật bất kỳ đường chuyển
  nguồn nào sang chủ khác — sao chép và nộp sang chủ khác chỉ giao ảnh (xem nhóm B ở mục Điểm còn mở và các quyết
  định đã chốt). API không nhận yêu cầu chuyển nguồn. Điều kiện đồng ý rõ ràng của giáo viên tại M1 là điều kiện
  bắt buộc khi chức năng bàn giao nguồn được bật về sau.
- Phạm vi phát hành đã chốt [QĐ-2026-11-05]: toàn bộ gói của đặc tả này — tệp đính kèm, tệp bài làm, render
  LaTeX ra ảnh và giữ kín nguồn — thuộc bản phát hành 07/11/2026 (điểm M4 đã chốt ở mục Điểm còn mở và các quyết
  định đã chốt). Không phần nào của gói được hoãn khỏi bản 07; mục Ưu tiên chỉ còn tách các phần để sau ngoài
  gói này.

## Nguyên tắc nền

1. **Ba lớp tệp.** Lớp tài nguyên/phiên bản nghiệp vụ là nơi quyết chủ sở hữu, trạng thái và quyền. Lớp liên kết
   tệp trong ngữ cảnh xác định tệp được gắn vào phiên bản nào, ở phần nào (nội dung câu, lựa chọn, đáp án, lời
   giải, tài liệu nguồn, bài làm). Lớp blob là dãy byte bất biến trong kho lưu trữ riêng tư, có định danh nội bộ,
   kích thước, loại đã kiểm chứng, checksum và trạng thái xử lý. Biết định danh blob không tự cấp quyền truy cập
   nào. [A1]
2. **Quyền quyết theo nơi sử dụng.** Người tải lên chỉ là thông tin truy vết; quyền trên tệp được quyết theo ngữ
   cảnh sử dụng tệp (bản gốc, đáp án, bản chụp đề đã giao, bài làm, bản trung tâm đã nhận, tài liệu nguồn), không
   quyết theo người đã tải tệp lên. [A1]
3. **Hai hợp đồng dữ liệu tách biệt.** Hợp đồng soạn thảo trả dữ liệu soạn thảo (gồm nguồn LaTeX) cho người có
   quyền tương ứng. Hợp đồng hiển thị chỉ trả văn bản đã tách nguồn cùng tham chiếu ảnh được phép xem. API xem
   không được tiếp tục trả trường văn bản cũ còn chứa LaTeX, dù giao diện đã hiển thị ảnh. [A2]
4. **Bất biến gồm cả tệp.** Phiên bản đã phát hành, chia sẻ hoặc nộp giữ nguyên cả danh sách tệp lẫn byte của
   tệp; sửa đổi đi qua phiên bản nháp mới hoặc bản sao mới, theo mô hình phiên bản của lõi quyền. [A1; MG]
5. **Bản chụp tự tồn tại.** Snapshot của đề đóng băng cả tham chiếu ảnh và tệp cần thiết; xoá hay lưu trữ nguồn,
   hoặc giáo viên rời trung tâm, không làm mất nội dung của bản đã nhận và đã giao hợp lệ. [A1; MG]

## Mô hình dữ liệu

Tên thành phần dưới đây là gợi ý triển khai theo tài liệu của Astra; ràng buộc mới là phần bắt buộc, tên gọi
không phải yêu cầu nghiệp vụ. [A2]

### Blob và kho lưu trữ

- Byte lưu trong object storage riêng tư (S3 hoặc hệ tương thích); metadata và liên kết nghiệp vụ lưu trong
  PostgreSQL. Không dùng filesystem tạm của container ứng dụng làm nơi lưu tệp dài hạn. [A1]
- Một blob gồm: định danh nội bộ; khoá lưu trữ do server sinh (khoá không chứa nội dung nguồn, không chứa LaTeX);
  kích thước; loại nội dung đã kiểm chứng; checksum; trạng thái xử lý (đang xử lý, sẵn sàng, bị từ chối, cách
  ly); người tải lên và thời điểm, chỉ để truy vết. [A1; A2]
- Blob là bất biến: không thao tác nào ghi đè byte của một blob đã chốt. Hai liên kết ở hai bản khác nhau có thể
  dùng chung một blob về vật lý, nhưng quyền truy cập và thời gian giữ của mỗi liên kết được quản lý độc lập.
  Quan hệ phiên bản — khối — ảnh nằm ở liên kết và bản ghi render, không nằm ở blob: một blob dùng chung không
  thuộc duy nhất một phiên bản và không mang quyền sở hữu của riêng phiên bản nào. [A1; Astra chấm]

### Liên kết tệp theo ngữ cảnh

- Một liên kết tệp gồm: phiên bản tài nguyên được gắn; vị trí sử dụng (khối nội dung và vai trò: nội dung câu,
  lựa chọn, đáp án, lời giải, tài liệu, bài làm); blob được liên kết; tên hiển thị. Mỗi bản sao và bản nhận có
  liên kết riêng của mình. [A1]
- Xoá liên kết khỏi một bản nháp không xoá byte vật lý; blob chỉ bị thu gom khi không còn liên kết hợp lệ nào
  cần giữ, đã hết thời hạn lưu và đã qua khoảng chờ an toàn. Không xoá toàn bộ tệp của một giáo viên khi họ rời
  trung tâm. [A1]
- Ảnh xem trước, thumbnail và bản render của một tệp là các biểu diễn dẫn xuất của blob đó và phải được bảo vệ
  như chính nội dung chúng thể hiện, theo đúng ngữ cảnh của liên kết gốc. [A1]
- Gắn một tệp vào một tài nguyên yêu cầu đồng thời: quyền sử dụng nguồn (tệp/blob nguồn thuộc phạm vi người gọi
  được dùng) và quyền ghi tại đích. Không gắn được tệp của người khác chỉ bằng cách gửi định danh của nó. [A1]

### Phiên tải lên

- Một phiên tải lên khoá: người gọi, workspace, mục đích sử dụng (đính kèm vào bản nháp nào, hay nộp vào bài làm
  nào) và giới hạn tải lên được áp. Phiên chưa hoàn tất được dọn sau một thời hạn cấu hình. [A1]
- Khoá tạm của phiên tải lên khác khoá lưu trữ chuẩn của blob đã chốt; một URL/khoá tải lên cũ không ghi đè được
  blob đang được một phiên bản hay bản chụp sử dụng. [A1; A2]
- Hoàn tất tải lên là thao tác chống trùng khi retry: retry không tạo liên kết thừa và không gắn hai lần. [A1]

### Khối nội dung và hai dạng công thức

Hợp đồng nội dung của phiên bản câu hỏi thay đổi tại đặc tả này: mỗi phần văn bản của nội dung (thân câu, từng
lựa chọn, đáp án mẫu, lời giải, tiêu chí chấm dạng văn bản) là một dãy khối, gồm khối văn bản thường và khối công
thức, thay vì một chuỗi trộn lẫn văn bản với LaTeX. Khối công thức có id ổn định trong phiên bản. [A2; Suy diễn
— đã xác nhận ở lượt chấm lại: áp cấu trúc khối cho từng phần văn bản theo hợp đồng nội dung tối thiểu đã khoá;
việc này không có nghĩa chuyển cả dữ liệu chấm điểm có cấu trúc thành văn bản]

Mỗi khối công thức thuộc đúng một trong hai dạng, và đây là khác biệt ở dữ kiện, không phải khác biệt ở giao diện:

| Dạng | Dữ kiện được giữ | Hệ quả ở API |
| --- | --- | --- |
| Có nguồn (`source-backed`) | Nguồn riêng tư của khối + ảnh đã render | Nhận nguồn qua hợp đồng soạn thảo |
| Chỉ có ảnh (`render-only`) | Ảnh bất biến; không có nguồn ở phía nhận | Không đường nào trả nguồn cho khối này |

- Nguồn riêng tư lưu tách khỏi dữ liệu giao cho người xem; không có đường tải nguồn bằng định danh tuỳ ý. [A2]
- Yêu cầu bắt buộc ở mức khối là: không chuyển nguồn hệ thống, và phân biệt được nguồn mới tự soạn với ảnh kế
  thừa. Một cờ nguồn gốc hệ thống ở mức khối là lựa chọn triển khai để thực hiện yêu cầu đó, không phải yêu cầu
  bắt buộc tự thân: nếu dùng cờ, server quản lý cờ và client không được đổi cờ để mở quyền; cờ không khoá vĩnh
  viễn một định danh khối tới mức người dùng không thể thay công thức bằng nguồn mới của chính họ. Khối do người
  dùng tự soạn mới trên một bản sao là nguồn mới của chính họ và không bị cấm theo nguồn gốc của câu. Không đánh
  dấu cấm nguồn cho toàn bộ câu hỏi chỉ vì câu từng được sao chép từ hệ thống. [A2; Astra chấm]
- Cả hai dạng đều xuất bản được khi ảnh hợp lệ và quyền sử dụng đầy đủ; khối chỉ-có-ảnh không cần nguồn để được
  dùng hay xuất bản nguyên trạng. [A2]
- Dấu vết sao chép (tài nguyên nguồn, phiên bản nguồn) ghi ở mức tài nguyên theo mô hình gốc; quan hệ truy vết
  chỉ phục vụ kiểm toán nội bộ, không tạo quyền đọc nguồn và không cho truy cập ngược tài nguyên gốc. [A2; MG]

### Hợp đồng dữ liệu của khối công thức

Mẫu dữ kiện của một khối công thức tách hai mặt; tên trường là gợi ý triển khai, phần phân vai trường mới là bắt
buộc. [Astra chấm]

- Mặt soạn thảo, chỉ trả qua hợp đồng soạn thảo cho người có quyền tương ứng: dạng khối (có-nguồn hay
  chỉ-có-ảnh); nguồn LaTeX (chỉ có ở khối có-nguồn, chỉ server cấp theo quyền); trạng thái render (đang chờ, sẵn
  sàng, lỗi); mô tả bằng ngôn ngữ tự nhiên.
- Mặt hiển thị, trả cho người xem: tham chiếu ảnh, kích thước hiển thị, độ lệch đường cơ sở và mô tả; không có
  trường nguồn ở bất kỳ dạng nào.
- Loại khối và trạng thái render là hai dữ kiện tách biệt: một bản nháp có nguồn mới nhưng ảnh đang chờ hoặc
  render lỗi vẫn lưu được bình thường; trạng thái đó chỉ chặn ở cổng phát hành, không chặn việc lưu nháp.
- Trường do server quyết, client không được tự đặt để mở quyền: dạng khối sau sao chép, nguồn gốc hệ thống của
  khối, revision và hash của nguồn, định danh blob ảnh. Client chỉ sửa được văn bản thường, nguồn của khối thuộc
  quyền sửa của mình trên bản nháp, và mô tả.

### Bản ghi render

- Mỗi ảnh công thức là một blob, kèm: định danh khối và phiên bản chứa nó; revision (hoặc hash) của nguồn và toàn
  bộ cấu hình render mà ảnh được sinh ra từ đó; phiên bản của bộ render và font; kích thước hiển thị và độ lệch
  đường cơ sở để ảnh không lệch dòng; trạng thái sẵn sàng. [A2]
- Chỉ dịch vụ render được ghi ảnh công thức chuẩn; ảnh do trình duyệt tự gửi không được tin là kết quả đúng với
  nguồn. [A2]
- HTML hoặc SVG, nếu dùng, chỉ là bước trung gian riêng tư trong xử lý; trình duyệt của người nhận chỉ nhận ảnh.
  Định dạng ảnh giao ban đầu là PNG. [A2]

## Ma trận quyền

### Hành động chuyên biệt cho nguồn

Đặc tả này định nghĩa hai hành động chuyên biệt trên cùng dịch vụ quyết định quyền của lõi quyền, theo khuôn lõi
quyền dành cho hành động chuyên biệt của feat loại tài nguyên [MG]:

- `read_math_source`: đọc nguồn LaTeX của một khối công thức có-nguồn, qua hợp đồng soạn thảo. Tên là đề xuất của
  Astra [A2]; quyền vẫn do API quyết, không tạo role Keycloak cho từng công thức hay từng ảnh.
- `edit_math_source`: sửa nguồn LaTeX trên bản nháp, gồm cả việc tự soạn nguồn mới cho một khối chỉ-có-ảnh.

Đọc nguồn và sửa nguồn là hai hành động độc lập với `view_content`, `view_answer`, `edit_draft` và `review`:
được xem nội dung không suy ra được đọc nguồn; được biên tập nội dung không tự suy ra được sửa nguồn của tài
nguyên không thuộc mình; `view_answer` không bao giờ suy ra từ `edit_draft` hay `review`, và cũng không suy ra
`read_math_source`. [MG; A2]

Quyền đọc/sửa nguồn chỉ đến từ tư cách chủ sở hữu hiện hành hoặc phân công biên tập thay mặt chủ sở hữu tổ chức;
hai hành động này không cấp qua grant chia sẻ. [QĐ-2; A2; Astra chấm: chính sách không cấp nguồn qua grant là đề
xuất của Astra, không tự suy ra chỉ từ câu "chủ sở hữu được sửa"; chủ repo đã chốt nó thành hành vi hiện hành
tại M3 [QĐ-2026-11-05]]

### Bảng quyền theo chủ thể và ngữ cảnh

"Câu" dưới đây là tài nguyên câu hỏi; cùng một khuôn áp cho tệp của đề và các loại tài nguyên khác có tệp. Quyền
đọc tệp luôn được quyết trên đúng bộ bốn dữ kiện: tài nguyên — phiên bản — vị trí nội dung — tệp. [A2]

| Chủ thể / ngữ cảnh | Đọc tệp nội dung | Đọc tệp đáp án | Đọc nguồn | Sửa nguồn |
| --- | --- | --- | --- | --- |
| Chủ sở hữu cá nhân (tài nguyên của mình) | Có | Có | Có, khối có-nguồn | Có, trên bản nháp |
| Phân công biên tập của trung tâm | Có, theo phân công | Chỉ khi có `view_answer` | Có (ghi chú 5) | Có, trên nháp |
| Phân công duyệt/phát hành của trung tâm | Có, theo phân công | Chỉ khi có `view_answer` | Không | Không |
| `tenant-admin` của trung tâm sở hữu | Có, mức quản lý | Không tự có | Không | Không |
| Thành viên qua grant tạm thời | Chỉ khi grant ghi + phân công | Chỉ khi grant ghi `view_answer` | Không | Không |
| Bản nhận `licensed` (lâu dài) | Theo grant + phân công | Chỉ khi grant ghi `view_answer` | Không (ghi chú 2) | Không |
| Chủ một bản sao hợp lệ | Có, trên bản sao | Theo ghi chú 1 | Khối có-nguồn của bản sao | Có, trên bản nháp |
| Người biên tập tài nguyên hệ thống | Có, ngữ cảnh hệ thống | Có, ngữ cảnh hệ thống | Có, khối có-nguồn | Có (nháp) |
| Chủ bản sao có nguồn gốc hệ thống | Có, trên bản sao | Theo bản sao | Theo từng khối (ghi chú 3) | Tự soạn mới |
| Học viên qua bản chụp được giao | Chỉ tệp trong bản chụp | Chỉ khi đã công bố | Không | Không |
| Giáo viên dạy/chấm, tệp bài làm | Có, lớp được phân công | Theo quyền chấm | Không áp dụng | Không |

Ghi chú 1: đáp án và tệp đáp án chỉ có mặt trên bản sao khi lần sao chép có `view_answer` trên phiên bản nguồn;
quyền đọc chúng sau đó theo quyền trên chính bản sao. Ghi chú 2: bản nhận `licensed` giữ công thức ở dạng ảnh để
dùng trong giới hạn đã cấp, không sửa nguồn và không thành bản trung tâm sở hữu. Ghi chú 3 (hàng chủ bản sao có
nguồn gốc hệ thống, xét theo từng khối): không được đọc nguồn hệ thống của khối kế thừa; được đọc và sửa nguồn tự
soạn hợp lệ của bản sao. Ghi chú 4: các ô "Không" trong bảng nghĩa là chủ thể không tự được cấp quyền theo hàng
đó, không phải lệnh cấm thắng các phân công khác — một `tenant-admin` đồng thời được phân công biên tập thì được
xét theo phân công biên tập đó. Ghi chú 5: đọc nguồn của khối thuộc phần đáp án hay lời giải cần đồng thời
`read_math_source` và `view_answer`; xem quy tắc kiểm quyền tới từng thuộc tính ở dưới. Ghi chú 6: hàng học viên
áp cho ngữ cảnh đáp án chưa được công bố; nếu nghiệp vụ cho xem lại sau chấm, quyền xem ảnh đáp án đi qua cơ chế
công bố của lượt làm, không bị hàng này cấm vĩnh viễn. Các ô sửa nguồn ghi "Có" đều hiểu là bản nháp chưa khoá
của đúng tài nguyên đó. Hàng "chủ bản sao có nguồn gốc hệ thống" áp theo từng khối ở mọi vòng của chuỗi sao
chép: khối kế thừa từ hệ thống luôn ở dạng chỉ-có-ảnh và không mở nguồn; khối tự soạn mới trên bản sao là nguồn
của chủ bản sao. [Astra chấm]

Quy tắc đi kèm bảng:

- Sửa nguồn chỉ áp trên bản nháp chưa khoá. Bản đã xuất bản, chia sẻ hoặc nộp không ai sửa trực tiếp; chủ sở hữu
  tạo phiên bản nháp mới, phiên bản cũ và các bản chụp giữ nguyên. [A2; MG]
- Kiểm quyền tới từng thuộc tính của đối tượng: việc tách các hành động không có nghĩa một hành động được bỏ qua
  quyền của trường dữ liệu mà nó truy cập. Đọc nguồn của một khối nằm trong đáp án hoặc lời giải cần đồng thời
  `read_math_source` và `view_answer` (cùng quyền xem phần nội dung tương ứng); sửa nguồn trong phần đó cần thêm
  `edit_math_source`, quyền sửa bản nháp và quyền trên phần lời giải tương ứng. Mọi điều kiện áp trên đúng tài
  nguyên, phiên bản và khối. [Astra chấm]
- Người tạo câu, người tải tệp lên, người cùng trung tâm và người được chia sẻ không tự động có quyền sửa nguồn;
  quyết định do API kiểm trên chủ sở hữu hiện hành, phân công và trạng thái phiên bản, không suy từ `created_by`.
  [A2; MG]
- Vai trò quản trị của trung tâm không cho đọc nguồn của tài nguyên cá nhân của người khác. [A2]
- Tách quyền tới từng tệp: người chỉ được xem câu không lấy được ảnh đáp án hay tệp lời giải qua bất kỳ đường
  nào — API, thumbnail, preview, export hay đường tải gốc. Đáp án và lời giải bị loại ngay ở API khi chưa được
  phép xem, không lọc ở phía trình duyệt. [A1]
- Học viên chỉ nhận các câu và tệp thuộc bài/lượt làm được cấp; API không trả cả ngân hàng rồi lọc ở giao diện.
  [A1]

## Luồng tải lên an toàn

Luồng tải lên gồm năm bước, đúng thứ tự [A1]:

1. **Xin phiên tải lên.** API kiểm người gọi được sửa bản nháp đích hoặc được nộp tệp vào bài làm đó; khoá
   workspace, mục đích và giới hạn tải lên vào phiên.
2. **Nhận vào vùng tạm.** Tệp vào vùng tạm bằng tên/khoá do server sinh. Tệp ở vùng tạm chưa được sử dụng trong
   bất kỳ tài nguyên đã phát hành nào.
3. **Kiểm tra và xử lý.** Xác minh nội dung thực và khả năng giải mã, không chỉ đuôi tệp hay `Content-Type` do
   client khai; kiểm kích thước và các giới hạn; kiểm an toàn; chuẩn hoá nếu cần. Không dùng trực tiếp khoá tải
   lên làm tệp chính thức: với kho lưu trữ có URL ký để tải lên, URL có thể dùng lại đến khi hết hạn và ghi đè
   object cùng khoá, nên byte phải được chốt sang khoá khác sau khi kiểm tra xong.
4. **Chốt nội dung bất biến.** Lưu đúng byte đã kiểm tra vào khoá lưu trữ cuối cùng mà client không thể ghi đè;
   blob chuyển sang trạng thái sẵn sàng.
5. **Gắn vào bản nháp.** Kiểm lại quyền và trạng thái tài nguyên tại thời điểm gắn — người bị gỡ phân công giữa
   lúc tải lên không hoàn tất được việc gắn tệp bằng phiên cũ. Chỉ tạo liên kết có hiệu lực khi tệp đã sẵn sàng;
   thao tác hoàn tất chống trùng khi retry.

Giới hạn và danh sách loại tệp [A1]:

- Danh sách cho phép ban đầu chỉ gồm ảnh PNG, JPEG, WebP cho nội dung câu và lời giải. Chưa nhận HTML, SVG tuỳ ý,
  tệp thực thi, ZIP hay tài liệu có macro. Cấm tải tệp `.tex` hoặc gói dữ liệu chứa nguồn vào luồng giao ảnh để
  né chính sách nguồn. [A1; A2]
- Bộ xử lý tệp chạy với quyền hạn chế, tách khỏi API chính, có giới hạn byte, kích thước ảnh/số trang, thời gian
  xử lý và bộ nhớ. Kiểm chữ ký tệp hay quét mã độc riêng lẻ không đủ thay cho cả chuỗi kiểm tra.
- Bộ số nghiệm thu khởi đầu do Astra đề xuất đã được chốt làm mặc định khởi đầu, đổi được bằng cấu hình [M5a;
  QĐ-2026-11-05]: ảnh tối đa 5 MiB và 20 megapixel; phiên tải lên chưa hoàn tất được dọn sau 24 giờ. Hạn mức
  tổng theo workspace, số lượt tải đồng thời và các giới hạn render do đội kỹ thuật xác định trong cấu hình
  triển khai, và phải có phép kiểm trước nghiệm thu — không để trống các hạn mức đó dưới nhãn "cấu hình được".
  Các con số này không phải chuẩn ngành.
- Ảnh sau chuẩn hoá được gắn vào bản nháp để người tải lên xem trước; bản ảnh được dùng khi chốt nội dung phải
  là đúng bản người tải lên đã xác nhận khi chốt. Không âm thầm nén hoặc chuyển đổi làm mờ dấu, số mũ hay ký hiệu
  toán học sau khi đã xác nhận, và không thêm một quy trình duyệt riêng cho từng lần gắn ảnh. [A1; Astra chấm]
- Nội dung của tài nguyên không phụ thuộc tuỳ ý vào ảnh hay URL bên ngoài hệ thống: tệp cần dùng phải đi qua
  đường tiếp nhận có kiểm soát này, tránh nội dung thay đổi sau khi giao bài và tránh việc máy chủ tự tải URL do
  người dùng định. [A1]

Tệp phát hiện nguy hiểm muộn (sau khi đã gắn và dùng) được cách ly và chặn phục vụ theo quy trình có dấu vết;
không sửa byte của một phiên bản cũ rồi coi như không có thay đổi. [A1]

### Hợp đồng riêng cho tệp bài làm

Tệp bài làm dùng chung pipeline tải lên với tệp đính kèm, nhưng điều kiện sử dụng của nó là hợp đồng riêng, không
suy ra từ pipeline. [Astra chấm]

- Người được đọc lại tệp bài làm: chính học viên chủ bài làm (khi lượt làm còn mở hoặc khi được xem lại bài của
  mình theo cơ chế công bố), và giáo viên được phân công dạy hoặc chấm đúng lớp/bài đó. Người khác, kể cả giáo
  viên cùng trung tâm không có phân công, không đọc được.
- Tệp chỉ gắn được vào bài làm khi lượt làm còn mở và học viên còn quyền nộp; một lượt tải lên hoàn tất sau hạn
  nộp không được gắn vào bài và không tính là đã nộp.
- Khi bài đã nộp và khoá, tệp của bài làm là bất biến như một phần của bản đã giao: không thay, không xoá, không
  gắn thêm; quyền đọc sau khoá đi theo quyền chấm và quyền xem lại kết quả.
- Định dạng áp dụng cho tệp bài làm giống tệp đính kèm: chỉ PNG, JPEG, WebP. Không nhận định dạng khác trong bản
  07. [QĐ-2026-11-05: đóng hợp đồng theo nhóm D của lượt duyệt cuối]
- Số lượng và dung lượng mặc định (mặc định kỹ thuật do đội L0 chốt, đổi được bằng cấu hình): tối đa 10 tệp cho
  một lượt làm; mỗi tệp tối đa 5 MiB (khớp giới hạn ảnh ở mục Luồng tải lên an toàn); tổng dung lượng tệp của
  một lượt làm tối đa 20 MiB.
- Mốc tính hợp lệ của tệp bài làm là thời gian của server tại bước chốt gắn/nộp, đối chiếu với hạn hiệu lực của
  lượt làm: tệp hoàn tất bước chốt khi lượt làm còn hiệu lực thì hợp lệ; tệp chưa kiểm tra và xử lý xong thì
  chưa được tính là đã nộp, dù byte đã truyền đủ. Giao diện phải thể hiện đúng trạng thái của từng tệp (đang
  tải, đang kiểm tra/xử lý, đã chốt, bị từ chối) và không báo nộp thành công khi mới truyền đủ byte.

## Luồng render LaTeX

1. **Nhận thay đổi.** API nhận thay đổi nguồn của người có `edit_math_source` trên bản nháp, kiểm quyền và ghi
   revision mới. Nguồn được giữ riêng ở vùng soạn thảo, không chèn lại vào nội dung giao cho người xem. [A2]
2. **Tạo tác vụ render.** Tác vụ khoá theo revision (hoặc hash) của nguồn và toàn bộ cấu hình ảnh. Bộ render là
   bộ render toán học theo danh sách cú pháp được hỗ trợ, chạy trên máy chủ trong tiến trình hoặc worker bị giới
   hạn, rồi raster hoá thành PNG. Người dùng không được chạy một hệ thống TeX đầy đủ. [A2]
3. **Giới hạn worker.** Ghi cụ thể và thực thi: giới hạn độ dài đầu vào, số khối công thức, mức mở rộng macro,
   kích thước ảnh, thời gian, bộ nhớ và số tác vụ đồng thời. Ranh giới của worker được ghi theo vùng cho phép:
   đầu vào LaTeX không được điều khiển việc đọc tệp tuỳ ý hay kết nối ra ngoài của worker; bộ render được phép
   dùng font và tài nguyên tin cậy đã đóng gói sẵn; phần điều phối được phép nhận tác vụ và lưu kết quả vào kho.
   Ngoài các vùng cho phép đó, worker không mở kết nối mạng và không chạy lệnh hệ thống. Dùng cấu hình không tin
   cậy (với KaTeX: `trust` tắt, `maxExpand`, `maxSize`), danh sách macro cho phép và trạng thái macro riêng cho
   từng tác vụ — không dùng chung trạng thái macro có thể thay đổi giữa nội dung của những người dùng khác nhau.
   Giới hạn cấu hình của thư viện chỉ là một lớp; tiến trình vẫn cần giới hạn tài nguyên và quyền thực thi riêng.
   [A1; A2; Astra chấm: viết lại ranh giới worker theo vùng cho phép]
4. **Lưu kết quả.** Worker tạo ảnh, kiểm chữ và kích thước, bỏ metadata mang nguồn, lưu blob ở khoá bất biến kèm
   bản ghi render. Chỉ dịch vụ render được ghi ảnh công thức chuẩn. [A2]
5. **Gắn kết quả đúng revision.** API chỉ gắn kết quả khi revision vẫn khớp. Tác vụ cũ hoàn tất sau tác vụ mới
   không được ghi đè kết quả mới. Retry an toàn: không tạo liên kết thừa, không phát hành hai lần, không để phiên
   bản tham chiếu tới blob chưa tồn tại. [A2]
6. **Cổng phát hành.** Chỉ xuất bản, chia sẻ hoặc nộp một phiên bản khi mọi ảnh bắt buộc đã sẵn sàng và đúng
   revision; khối chỉ-có-ảnh chỉ cần ảnh hợp lệ và quyền sử dụng. Chốt nội dung và các liên kết tệp trong cùng
   lần phát hành. Phiên bản có tệp đang tải, đang kiểm tra, bị từ chối hoặc mất object không được phát hành hay
   giao bài. [A1; A2]
7. **Đóng băng cùng bản chụp.** Đề và lượt làm giữ bản chụp cùng các tham chiếu ảnh bất biến. Sửa câu hỏi nguồn
   hay nâng cấp bộ render sau này không làm đổi ảnh trong đề đã giao; bản render đã kiểm tra được giữ cùng thông
   tin phiên bản của bộ render. [A1; A2]

### Lỗi render phải an toàn, không lộ nguồn

- Nguồn sai cú pháp được giữ ở bản nháp và trả lỗi an toàn cho người đang soạn. Cấm tự thay ảnh bằng chuỗi LaTeX
  để người nhận "tạm xem": cấu hình render hiển thị nguồn khi gặp lỗi (ví dụ chế độ hiển thị nguồn của KaTeX khi
  công thức không hợp lệ) không đáp ứng hợp đồng giao ảnh và bị cấm trên đường giao. [A2]
- Không đưa nguồn vào: phản hồi lỗi công khai, thông báo lỗi, nhật ký truy cập, công cụ theo dõi lỗi, tên tác vụ,
  hay bất kỳ kênh phụ nào khác. Điểm dễ sót nhất: đầu ra render kiểu MathML của KaTeX đưa nguyên biểu thức TeX vào
  `annotation` với `encoding="application/x-tex"`, và fallback lỗi của nó có thể hiển thị nguyên TeX — chuyển
  render sang server nhưng giữ nguyên đầu ra đó vẫn lộ nguồn. [A1; A2]
- Nguồn không được nằm trong bất kỳ chỗ "ẩn" nào của bản giao: thuộc tính `alt`, `title` hay thuộc tính DOM,
  metadata của PNG, lớp MathML, phương trình Word có thể sửa, hay lớp dữ liệu phụ trong tệp xuất. Mô tả hỗ trợ
  đọc cho một công thức được viết bằng ngôn ngữ tự nhiên, không chép nguyên mã LaTeX và không dùng LaTeX ẩn làm
  cách bù cho việc giao ảnh. [A2]
- Kiểm chất lượng ảnh ở mức sử dụng đã quy định: công thức không cắt chữ, lệch dòng hay mất nét; kiểm phân số,
  căn, ma trận, công thức nhiều dòng, chỉ số, chữ tiếng Việt trong văn bản quanh công thức, trên màn hình nhỏ và
  trên bản in xuất từ hệ thống. [A2]

### Đường đọc ảnh và tệp

- Đường đọc mặc định đi qua API có xác thực, kiểm quyền tại thời điểm truy cập trên đúng bộ bốn dữ kiện tài
  nguyên — phiên bản — vị trí nội dung — tệp. Với giao diện web dùng Bearer token, ảnh được tải bằng request có
  xác thực rồi hiển thị bằng blob URL; blob URL chỉ là dữ liệu đã được cấp trong phiên, không phải quyền truy
  cập, và không đặt access token vào URL. Cache cho nội dung riêng tư phải là cache riêng tư; với nội dung nhạy
  cảm ưu tiên không lưu qua bộ nhớ đệm trung gian, để bản render của người có quyền không bị phục vụ lại cho
  người không có quyền. [A1; A2]
- Nếu về sau chọn URL ký sẵn cho một loại tệp nào đó, phải ghi rõ khoảng thời gian URL còn dùng được và chấp
  nhận: URL ký là quyền truy cập cho người giữ URL, dùng lại được tới khi hết hạn; gỡ quyền hay gỡ membership
  trong LMS không tự làm URL đó hết hiệu lực, nên không được tuyên bố thu hồi tức thì. Byte đã tải về máy người
  nhận thì không thu hồi được. [A1; A2]

## Quy tắc sao chép, chia sẻ, nộp đối với tệp và nguồn

Các quy tắc dưới đây đứng trên mô hình gốc: sao chép tạo tài nguyên mới có id mới và chủ sở hữu mới; bản sao
không kế thừa ACL của nguồn nhưng giữ nguyên giới hạn tái sử dụng của nguồn; chia sẻ không chuyển sở hữu; nộp tạo
bản thuộc trung tâm kèm biên nhận bất biến. [MG]

### Sao chép

- **Sao chép hợp lệ một câu không có nguồn gốc hệ thống.** Tệp: sao theo các tệp cần thiết qua liên kết mới;
  đáp án và tệp đáp án chỉ sao theo khi người sao chép có `view_answer` trên phiên bản nguồn [MG]. Nguồn: quyền
  sao chép tài nguyên tách khỏi quyền nhận nguồn — việc sao chép không tự chuyển nguồn sang bản sao. Nguồn chỉ
  chuyển khi có cơ chế bàn giao nguồn được chấp thuận kèm đồng ý rõ ràng của chủ sở hữu; bản 07 không bật bất
  kỳ cơ chế bàn giao nguồn nào [QĐ-2026-11-05] — sao chép và nộp sang chủ khác chỉ giao ảnh, API không nhận yêu
  cầu chuyển nguồn — nên các khối công thức trong bản sao ở dạng chỉ-có-ảnh cho tới khi chủ bản sao tự soạn
  nguồn mới. Nguyên tắc này là một nguyên
  tắc thống nhất cho cả sao chép và nộp. [Astra chấm; QĐ-2026-11-05]
- **Sao chép câu hỏi hệ thống, ở bất kỳ vòng nào của chuỗi sao chép.** Tệp: sao theo ảnh và các tệp được phép
  dùng, qua liên kết mới của bản sao; có thể dùng chung blob vật lý, quyền và vòng đời tính riêng. Nguồn: không
  sao chép nguồn ở bất kỳ khối nào có nguồn gốc hệ thống; trường nguồn của khối trong bản sao để trống, khối
  thành chỉ-có-ảnh; dấu vết bản gốc không mở quyền đọc nguồn. [QĐ-3; A2]
- **Sao chép một đề.** Theo cơ chế snapshot của đề: bản sao mang snapshot và tệp theo quyền trên từng thành phần
  của đề [MG]. Từng khối công thức trong các câu thành phần tuân theo đúng hai quy tắc trên, theo nguồn gốc của
  chính khối đó; việc sao chép đề không được dùng làm đường truy ngược lấy nguồn câu hỏi mà người gọi không được
  nhận. [A2; Suy diễn — đã xác nhận ở lượt chấm lại: áp quy tắc theo khối vào thành phần của đề]

- Luồng sao chép câu hệ thống gồm đúng các bước: tạo tài nguyên mới với id và chủ sở hữu mới; sao chép phần nội
  dung được phép nhận, kèm các công thức ở dạng chỉ-có-ảnh; không sao chép nguồn hệ thống sang dữ liệu của bản
  sao; khi người dùng chọn "Soạn lại công thức", họ thấy ảnh hiện tại và một ô nhập nguồn mới để trống; nguồn tự
  nhập chỉ thay đúng công thức tương ứng trên bản nháp của họ, không mở khoá các khối kế thừa khác. [A2]
- Tạo nguồn mới trên bản sao không xoá các hạn chế sử dụng còn áp cho phần câu hỏi hay ảnh được kế thừa. [A2]
- Bản sao chỉ-có-ảnh dùng và xuất bản được nguyên trạng: không bị bắt nhập LaTeX chỉ để tiếp tục dùng. [A2]

### Chia sẻ

- Chia sẻ tạm thời: người nhận nhận bản trình bày (ảnh công thức) và các tệp theo đúng tập hành động ghi trong
  grant, qua đường đọc có kiểm quyền; không nhận nguồn. Quyền bị thu hồi thì lần đọc có kiểm quyền tiếp theo bị
  chặn. [A1; MG]
- Chia sẻ dùng lâu dài: trung tâm nhận giữ bản sao hoặc snapshot đủ nội dung (gồm ảnh và tệp cần thiết) theo cơ
  chế bản nhận `licensed` của lõi quyền; bản nhận dùng để sử dụng trong giới hạn đã cấp, không sửa nguồn và
  không thành bản trung tâm sở hữu. Công thức có nguồn gốc hệ thống trong bản nhận vẫn ở dạng chỉ-có-ảnh. [MG;
  A2]
- Sao chép không mở rộng quyền nguồn: tệp và ảnh từ một bản chỉ được dùng trong trung tâm A không trở thành tài
  sản tự do chia sẻ sang trung tâm B khi người dùng tạo bản sao cá nhân từ bản đó; giới hạn sử dụng của nguồn
  ràng buộc cả chủ bản sao. [A1; MG]

### Nộp về trung tâm

- Nộp một câu hỏi hay bộ câu hỏi: trung tâm nhận bản sao của đúng các phiên bản đã chọn, gồm đáp án, lời giải,
  tiêu chí chấm và các tệp đính kèm cần thiết qua liên kết mới thuộc bản nhận; toàn bộ danh sách bàn giao được
  kiểm quyền trước, một mục thiếu quyền thì cả lần nộp bị từ chối. Các phiên bản khác và phần còn lại của ngân
  hàng cá nhân không được bàn giao. [MG]
- Nộp một đề: trung tâm nhận bản đề độc lập kèm snapshot; các câu trong gói đề không tự thành câu tái sử dụng
  trong ngân hàng của trung tâm, và ma trận nguồn không được bàn giao theo đề. [MG]
- Khi bản sao của một câu có khối kế thừa từ hệ thống nằm trong danh sách nộp, việc xử lý nguồn xét theo từng
  khối: khối kế thừa từ hệ thống luôn được giao dưới dạng ảnh; khối tự soạn mới tuân theo chính sách nguồn của
  M1/M3. Không lấy nguồn gốc của toàn câu để cấm nguồn mới của người dùng. [QĐ-3; A2; Astra chấm]
- Nguồn khi nộp (M1 đã chốt [QĐ-2026-11-05], theo khuyến nghị của Astra ở lượt chấm): bản nhận giữ ảnh, không tự
  nhận nguồn. Chủ mới không mặc nhiên nhận nguồn chỉ vì đã sở hữu bản sao. Trong bản 07, chức năng bàn giao
  nguồn chưa bật [QĐ-2026-11-05] nên lần nộp không chuyển nguồn ở bất kỳ khối nào; điều kiện "phạm vi nguồn nằm
  trong nội dung bàn giao được chính giáo viên xác nhận khi nộp" là điều kiện bắt buộc khi chức năng bàn giao
  nguồn được bật về sau, không phải hành vi của bản 07. Phần tệp và ảnh của lần nộp áp dụng đầy đủ các quy tắc
  trên và không phụ thuộc điểm này.
- Xoá bản cá nhân không xoá bản trung tâm đã nhận; bản đã nộp ở lại hẳn khi giáo viên rời trung tâm. [MG]

## Bất biến nghiệp vụ

Mỗi bất biến dưới đây phải kiểm chứng được bằng test tự động hoặc kiểm chứng đo được; cách kiểm cụ thể nằm ở mục
Nghiệm thu.

- B1. Tệp luôn có ngữ cảnh sử dụng và mục đích rõ ràng. Đổi định danh liên kết, phiên bản hoặc workspace trong
  request không đọc được tệp của người khác; biết định danh blob không đủ để tải tệp. [A1]
- B2. Quyền nội dung và quyền đáp án tách biệt tới từng tệp, trên mọi đường đọc gồm API, thumbnail, preview,
  export và đường tải gốc. [A1]
- B3. Không gắn được tệp của người khác chỉ bằng cách gửi định danh: gắn tệp kiểm cả quyền sử dụng nguồn và
  quyền ghi tại đích. [A1]
- B4. Bất biến bao gồm cả danh sách tệp và byte của tệp: sau khi một phiên bản đã chia sẻ, nộp hoặc phát hành,
  không thay được hình bằng cách ghi đè object, đổi liên kết hay thay URL. [A1]
- B5. Bản chụp đề đủ nội dung để tồn tại độc lập: xoá hay lưu trữ câu nguồn, hoặc giáo viên nghỉ trung tâm,
  không làm mất tệp và ảnh của đề đã được nhận và giao hợp lệ. [A1]
- B6. Chưa xử lý xong thì chưa được phát hành: phiên bản có tệp đang tải, đang kiểm tra, bị từ chối hoặc mất
  object không được phát hành hay giao bài. [A1]
- B7. Quyền được kiểm lại khi hoàn tất tải lên và khi gắn tệp: phiên tải lên cũ không giúp người đã bị gỡ phân
  công hoàn tất việc gắn tệp vào tài nguyên trung tâm. [A1]
- B8. Sao chép không mở rộng quyền nguồn: nguồn hệ thống không xuất hiện ở bất kỳ vòng nào của chuỗi sao chép —
  nộp — chia sẻ — sao chép tiếp; tệp từ bản chỉ được dùng trong một trung tâm không thành tệp tự do chia sẻ sang
  trung tâm khác qua một bản sao cá nhân. Nguồn của tài nguyên cá nhân hay trung tâm cũng không tự chuyển sang
  chủ khác khi chưa có bàn giao được chấp thuận theo M1/M3. [A1; A2; QĐ-3; QĐ-2026-11-05]
- B9. Xoá liên kết khác với xoá byte vật lý: gỡ hình khỏi một bản nháp không xoá hình còn được một phiên bản,
  bản nhận hoặc bài đã giao sử dụng. [A1]
- B10. Thu hồi chỉ tác động đúng quyền đã thu hồi: người rời một trung tâm không còn tải tệp qua quyền tại trung
  tâm đó; người khác ở trung tâm đó vẫn đọc được bản trung tâm được giữ hợp lệ; kho cá nhân của người nghỉ vẫn
  còn nguyên. [A1]
- B11. Đường đọc và bộ nhớ đệm không rò giữa người dùng: đọc đáp án bằng tài khoản giáo viên rồi đọc cùng một
  URL bằng tài khoản học viên không nhận được nội dung từ cache của giáo viên. [A1]
- B12. Phục hồi dữ liệu phải nhất quán: khôi phục database và object storage vẫn mở được các bản đề và bài làm
  đã ghi nhận, không chỉ kiểm "backup thành công". [A1]
- B13. Kết quả render gắn đúng revision: tác vụ render cũ hoàn tất sau không ghi đè được kết quả của revision
  mới; bản được phát hành khớp đúng nguồn đã chốt. [A2]
- B14. Nguồn bị bảo vệ không xuất hiện ở bất kỳ kênh giao nào: phản hồi API, dữ liệu khởi tạo trang, DOM, thuộc
  tính `alt`/`title`, metadata của tệp ảnh, tệp xuất, thông báo lỗi và nhật ký. [A1; A2]
- B15. Bản sao chỉ-có-ảnh sử dụng và xuất bản được nguyên trạng, không bị bắt nhập LaTeX cho các khối kế thừa.
  [A2]
- B16. Ảnh của một bản đã giao không đổi khi nguồn được sửa hay xoá, hoặc khi thư viện render được nâng cấp.
  [A1]

## Nghiệm thu

Các tiêu chí NT-01 đến NT-18 phủ đúng 18 ca T01–T18 của tài liệu yêu cầu nghiệm thu của Astra [A2]; bảng đối
chiếu nằm ở Phụ lục. NT-19 đến NT-24 bổ sung từ lượt 1 [A1] cho các hành vi mà bộ 18 ca chưa phủ. NT-25 đến
NT-31 bổ sung từ lượt Astra chấm lại đặc tả này cho các ca thao tác đồng thời, ca gắn blob của người khác bằng
định danh, ca dùng chung đường đọc qua hai tài khoản và ca biên tập viên không có `view_answer`. NT-32 đến
NT-35 bổ sung từ lượt Astra duyệt cuối cho chính sách M1/M3 trên câu tự soạn, ca M2, nhóm ca tệp bài làm và phép
kiểm hiển thị/đọc màn hình thực tế của NT-16a/M5c. Mọi ca chạy
trên API thật và, với ca liên quan hiển thị, trên trình duyệt; việc ẩn nút trên giao diện không được coi là bằng
chứng phân quyền — ca quyền phải gọi trực tiếp API bằng tài khoản không có quyền. [A2; Astra chấm]

- NT-01 (T01). Chủ sở hữu sửa nguồn trong bản nháp: ảnh mới sinh ra đúng revision của nguồn đã sửa; người khác
  gọi trực tiếp API sửa nguồn bị từ chối và không dữ kiện nào thay đổi. Kiểm: test API hai tài khoản, đối chiếu
  hash của bản ghi render với hash nguồn.
- NT-02 (T02). Sửa một bản đã xuất bản, chia sẻ hoặc nộp chỉ tạo bản nháp mới: hash nội dung và ảnh của phiên bản
  cũ, của đề cũ và của lượt làm bài cũ không đổi. Kiểm: test tự động đối chiếu hash trước và sau thao tác sửa.
- NT-03 (T03). Quyền biên tập của trung tâm: người được phân công biên tập sửa được nguồn thuộc trung tâm; giáo
  viên cùng trung tâm không có phân công và quản trị viên trung tâm không có phân công biên tập bị từ chối cả đọc
  nguồn lẫn sửa nguồn. Kiểm: test API ba tài khoản.
- NT-04 (T04). Sao chép câu hỏi hệ thống: bản sao có id mới, chủ sở hữu mới, ảnh dùng được ngay; dữ liệu soạn
  thảo của bản sao không chứa nguồn hệ thống ở bất kỳ khối nào; quan hệ truy vết không cho đọc nguồn của bản gốc.
  Kiểm: test API đọc hợp đồng soạn thảo của bản sao và thử đọc nguồn qua định danh bản gốc; đồng thời kiểm cả dữ
  kiện đã lưu của bản sao trong kho dữ liệu, không chỉ phản hồi API — chép nguồn vào dữ liệu lưu rồi giấu ở lớp
  trả về vẫn là không đạt. [Astra chấm]
- NT-05 (T05). Chủ bản sao chọn "Soạn lại công thức": ô nhập nguồn để trống; nguồn mới chỉ thuộc đúng khối được
  sửa trên bản nháp của bản sao; các khối chỉ-có-ảnh còn lại vẫn không có nguồn; bản gốc hệ thống không đổi.
  Kiểm: kiểm trên trình duyệt rằng ô nhập nguồn để trống khi mở; test API kiểm dữ kiện từng khối sau thao tác;
  sau khi tự soạn, lưu và mở lại phải đọc và sửa được chính nguồn mới đó. [Astra chấm]
- NT-06 (T06). Chuỗi sao chép, rồi nộp cho trung tâm, rồi chia sẻ, rồi sao chép tiếp: nguồn hệ thống không xuất
  hiện ở bất kỳ tài nguyên trung gian nào và bất kỳ tệp xuất nào; truy vết gốc không cấp quyền ngược ở mỗi bước.
  Kiểm: test API chạy trọn chuỗi, quét dữ kiện soạn thảo và dữ kiện đã lưu của mọi tài nguyên sinh ra, không chỉ
  phản hồi API. [Astra chấm]
- NT-07 (T07). Xuất bản một bản sao chỉ-có-ảnh thành công khi đủ quyền và mọi ảnh đã sẵn sàng; hệ thống không
  đòi nguồn LaTeX cho khối chỉ-có-ảnh. Kiểm: test API phát hành bản sao từ NT-04.
- NT-08 (T08). Hai ca tách riêng. Ca xoá bản gốc: ảnh của bản sao và bản chụp có quyền giữ vẫn tồn tại và đọc
  được bởi người còn quyền. Ca gỡ membership: sau khi giáo viên bị gỡ khỏi trung tâm, dùng lại access token còn
  hạn của họ để thử đọc tệp của trung tâm — lần đọc có kiểm quyền tiếp theo phải bị chặn; bản trung tâm đã nhận
  vẫn đọc được bởi người còn ở trung tâm; kho cá nhân của người bị gỡ vẫn hoạt động bình thường. Kiểm: test API
  hai phía trước và sau từng thao tác. [Astra chấm]
- NT-09 (T09). Học viên mở đề và sửa định danh tệp trong request: chỉ thấy ảnh thuộc bản chụp được giao; không
  đọc được nguồn câu hỏi, ảnh lời giải hay tệp của người khác hoặc trung tâm khác, và đáp ứng từ chối không kèm
  dữ kiện nào của tài nguyên. Kiểm: test API bằng tài khoản học viên với định danh tệp của lời giải và của trung
  tâm khác.
- NT-10 (T10). Nguồn chứa một dấu nhận diện kiểm thử riêng (chuỗi duy nhất chỉ có trong nguồn): dấu không xuất
  hiện trong phản hồi cho người nhận, dữ liệu khởi tạo trang, DOM, metadata ảnh, tệp xuất, nhật ký và thông báo
  lỗi; nguồn gốc vẫn còn nguyên ở vùng riêng tư cho chủ sở hữu. Quét chuỗi là cần nhưng chưa đủ: phải kiểm thêm
  cấu trúc của phản hồi và metadata, để nguồn bị mã hoá hay biến đổi dạng vẫn bị phát hiện thay vì lọt qua phép
  tìm chuỗi. Kiểm: quét chuỗi trên mọi kênh sau khi giao cho một tài khoản chỉ được xem, cộng kiểm cấu trúc phản
  hồi và metadata. [Astra chấm]
- NT-11 (T11). Render lỗi hoặc vượt giới hạn: phiên bản không phát hành được, không có ảnh thay thế bằng LaTeX ở
  bất kỳ kênh nào, thông báo lỗi không chứa nguồn; worker bị giới hạn đúng cấu hình và các tác vụ render khác vẫn
  hoạt động. Kiểm: test với nguồn sai cú pháp và nguồn vượt giới hạn độ dài/macro, chạy song song một tác vụ hợp
  lệ.
- NT-12 (T12). Hai revision render hoàn tất ngược thứ tự: kết quả của revision cũ không gắn vào revision mới;
  bản được xuất bản khớp đúng nguồn đã chốt. Kiểm: test với hai tác vụ được điều khiển thứ tự hoàn tất, đối
  chiếu hash.
- NT-13 (T13). Worker dừng rồi chạy lại giữa chừng: tác vụ được thử lại an toàn; không nhân đôi lần phát hành;
  không phiên bản nào tham chiếu tới blob chưa tồn tại. Kiểm: test dừng worker giữa tác vụ, khởi động lại, kiểm
  dữ kiện liên kết và trạng thái phát hành.
- NT-14 (T14). Đầu vào chứa macro nguy hiểm, URL ngoài hoặc đường dẫn tệp của máy chủ: render không mở kết nối
  mạng, không đọc tệp máy chủ, không chạy lệnh; trạng thái macro không truyền sang tác vụ của người dùng khác.
  Phép kiểm phải thử truy cập ra ngoài các vùng cho phép đã ghi ở bước 3 của mục Luồng render (chỉ font và tài
  nguyên tin cậy đã đóng gói sẵn, và đường lưu kết quả vào kho, là vùng cho phép) và xác nhận mọi truy cập ngoài
  các vùng đó đều bị chặn.
  Kiểm: quan sát trực tiếp việc truy cập mạng, tệp và lệnh bị chặn trong lúc render (chỉ kiểm ảnh kết quả không
  chứng minh được sự cô lập), chạy kèm một tác vụ của người khác và đối chiếu kết quả của cả hai. [Astra chấm]
- NT-15 (T15). Dùng lại URL tải lên cũ hoặc liên kết tệp cũ: không sửa được blob chuẩn đang được một bản chụp sử
  dụng; đường đọc qua API từ chối người đã mất quyền. Kiểm: test ghi đè qua khoá tải lên cũ sau khi blob đã chốt,
  đối chiếu checksum blob chuẩn trước và sau; và kiểm việc đọc lại nội dung sau khi quyền đã bị thu hồi, không
  chỉ kiểm ghi đè bằng URL cũ. [Astra chấm]
- NT-16a (T16 — hiển thị web, bắt buộc ngay). Xem trên web: công thức là ảnh, đủ nét và không cắt chữ ở mức sử
  dụng quy định; không có nguồn ở bất kỳ kênh giao nào của trang. Kiểm: như NT-10 trên bề mặt web. Tiêu chí này
  bắt buộc ngay khi có chức năng giao công thức, không treo theo điểm M5b.
- NT-16b (T16 — xuất PDF, khi có đường xuất PDF). Tệp PDF xuất ra: công thức là ảnh; tệp không chứa LaTeX hay
  lớp dữ liệu ẩn mang nguồn. Kiểm: đọc tệp bằng công cụ đọc định dạng PDF (các lớp dữ liệu, metadata, tệp nhúng);
  tìm chuỗi trên byte thô không đủ làm bằng chứng.
- NT-16c (T16 — xuất DOCX, khi có đường xuất DOCX). Tệp DOCX xuất ra: công thức là ảnh; tệp không chứa LaTeX,
  MathML hay phương trình có thể sửa — gồm cả phương trình Word dạng OMML (ví dụ `m:oMath`). Kiểm: trích xuất
  toàn bộ cấu trúc của tệp xuất và quét chuỗi nguồn cùng các cấu trúc phương trình. [Astra chấm: tách NT-16
  thành ba tiêu chí theo bề mặt]
- NT-17 (T17). Dọn rác và phục hồi sao lưu: chỉ blob hết tham chiếu hợp lệ và hết thời hạn lưu mới bị xoá; sau
  khi phục hồi sao lưu, mở lại được cả nội dung và ảnh của các phiên bản đã phát hành. Kiểm: chạy dọn rác trên
  dữ kiện có cả blob còn tham chiếu và blob hết tham chiếu; thực hiện một lượt khôi phục thật và mở lại bản chụp
  mẫu.
- NT-18 (T18) — **tiêu chí điều kiện.** Áp dụng cho mọi hành vi chuyển nguồn thật sự được bật, không chỉ một nút
  mang tên "Bàn giao nguồn". Bản 07 không bật chức năng bàn giao nguồn [QĐ-2026-11-05], nên phần kiểm chuyển
  nguồn thành công của tiêu chí này **chưa áp dụng trong bản 07**. Khi chức năng được bật về sau (gồm lần nộp có
  phạm vi nguồn được giáo viên xác nhận theo M1, và chức năng bàn giao nguồn theo M3): chỉ các khối có nguồn
  thuộc phạm vi đã được chủ sở hữu đồng ý mới chuyển; khối kế thừa từ hệ thống không bao giờ chuyển; việc chuyển
  có bản ghi phạm vi. Các ca cấm chuyển nguồn trái phép vẫn bắt buộc ngay trong bản 07: không chuyển nguồn hệ
  thống và không chuyển nguồn trái phép — phần đó đã là bắt buộc qua NT-04, NT-06 và các bất biến B8, B14.
  [Astra chấm; QĐ-2026-11-05]
- NT-19 (bổ sung từ A1). Kiểm loại tệp thực khi tải lên: tệp giả mạo loại bằng cách đổi đuôi hoặc `Content-Type`
  bị từ chối; tệp ngoài danh sách cho phép (HTML, SVG tuỳ ý, ZIP, tệp thực thi, tài liệu có macro, tệp `.tex`)
  bị từ chối; tệp vượt giới hạn byte hoặc megapixel bị từ chối. Kiểm: test tải lên với bộ tệp mẫu từng loại.
- NT-20 (bổ sung từ A1). Phiên tải lên dở và đổi quyền giữa chừng: phiên chưa hoàn tất bị dọn sau thời hạn cấu
  hình; người bị gỡ phân công giữa lúc tải lên không hoàn tất được việc gắn tệp vào tài nguyên trung tâm bằng
  phiên cũ. Kiểm: test tạo phiên rồi gỡ phân công trước bước gắn; kiểm dọn phiên bằng thời hạn cấu hình rút ngắn
  ở môi trường test.
- NT-21 (bổ sung từ A1, đã nới theo Astra chấm). Ảnh chuẩn hoá được xác nhận khi chốt nội dung: ảnh bị nén hoặc
  chuyển đổi trong bước xử lý được gắn vào bản nháp để người tải lên xem trước; khi chốt nội dung, bản ảnh được
  dùng phải là đúng bản người tải lên đã xác nhận; hệ thống không thay ảnh đã xác nhận bằng một bản xử lý khác
  khi phát hành, và không thêm một quy trình duyệt riêng cho từng lần gắn ảnh. Kiểm: test tải ảnh bị chuẩn hoá,
  đối chiếu checksum của ảnh đã xác nhận với ảnh trong bản phát hành.
- NT-22 (bổ sung từ A1 và A2). Mọi bề mặt đọc áp cùng một hình chiếu dữ liệu: trang xem, xem trước, bài thi,
  chia sẻ, bản sao, nộp, lịch sử, tìm kiếm, xuất tệp và công cụ đọc nội dung khác (gồm MCP nếu có đường đọc nội
  dung) đều không trả nguồn bị bảo vệ; quyền gọi công cụ hay OAuth không thay thế quyền đọc nguồn; đáp án và lời
  giải bị loại ở API cho người không có `view_answer` dù nội dung đã thành ảnh. Kiểm: test quét cùng một tài
  nguyên qua từng bề mặt bằng tài khoản chỉ được xem.
- NT-23 (bổ sung từ A1). Không phụ thuộc nguồn ngoài: nội dung phiên bản chứa tham chiếu tệp dạng URL ngoài hệ
  thống bị từ chối khi tạo hoặc sửa phiên bản (đáp ứng lỗi kiểm tra hợp lệ theo quy ước mã lỗi của hợp đồng đã
  khoá). Kiểm: test API gửi nội dung chứa URL ảnh ngoài. [Suy diễn: ánh xạ hành vi cấm URL ngoài của A1 vào mã
  lỗi `VALIDATION_FAILED` của hợp đồng — ánh xạ kỹ thuật, không thêm nghiệp vụ; đã xác nhận ở lượt chấm lại]
- NT-24 (bổ sung từ A1). Tệp nguy hiểm phát hiện muộn và dấu vết kiểm toán: blob bị phát hiện nguy hiểm được
  cách ly và chặn phục vụ, byte của các phiên bản cũ không bị sửa; nhật ký kiểm toán ghi được người tải lên, nơi
  gắn, lần chia sẻ/nộp và lần đọc hoặc sửa nguồn, nhưng không chứa token, URL bí mật hay toàn bộ nội dung đề.
  Kiểm: test cách ly một blob đang được dùng và đọc lại nhật ký của các thao tác mẫu.
- NT-25 (bổ sung từ Astra chấm — thao tác đồng thời). Nguồn bị sửa đúng lúc đang phát hành: lần phát hành không
  chốt nội dung mới với ảnh của revision cũ; kết quả hoặc khớp trọn revision mới, hoặc bị chặn để phát hành lại.
  Kiểm: test sửa nguồn giữa tác vụ render và thao tác phát hành, đối chiếu hash của bản phát hành.
- NT-26 (bổ sung từ Astra chấm — thao tác đồng thời). Tệp tạm bị ghi đè sau bước kiểm tra nhưng trước khi chốt:
  chỉ đúng byte đã kiểm tra mới được đưa vào blob chuẩn. Kiểm: test thay byte ở vùng tạm giữa hai bước, đối
  chiếu checksum của blob đã chốt với byte đã kiểm tra.
- NT-27 (bổ sung từ Astra chấm — thao tác đồng thời). Dọn rác chạy đồng thời với tạo bản sao hoặc bản chụp: blob
  vừa được một bản sao hay bản chụp giữ hợp lệ không bị xoá. Kiểm: test chạy dọn rác song song với thao tác sao
  chép và mở lại bản sao sau đó.
- NT-28 (bổ sung từ Astra chấm — thao tác đồng thời). Retry hoàn tất tải lên: không tạo thêm liên kết và không
  gắn tệp sang một đích khác với đích của phiên. Kiểm: test gọi hoàn tất hai lần và gọi lại sau khi đã gắn, đếm
  liên kết sinh ra; đồng thời thật sự thử đổi đích — gọi hoàn tất với một đích khác đích đã khoá trong phiên —
  và xác nhận bị từ chối, không liên kết nào gắn sang đích mới.
- NT-29 (bổ sung từ Astra chấm — biên tập viên không có `view_answer`). Người được phân công biên tập có quyền
  sửa phần câu nhưng không có `view_answer`: bị từ chối cả đọc nguồn lẫn sửa nguồn của khối nằm trong lời giải
  hoặc đáp án, dù sửa được nguồn của khối thuộc phần thân câu. Kiểm: test API bằng tài khoản biên tập viên thiếu
  `view_answer` — bộ ba tài khoản ở NT-03 chưa phủ ca này.
- NT-30 (bổ sung từ Astra chấm — ca của B3). Gắn blob của người khác bằng định danh: gửi định danh blob thuộc
  tài nguyên của người khác vào thao tác gắn bị từ chối, không liên kết nào được tạo; người gọi trong ca này
  phải là người không có quyền sử dụng blob nguồn. Kiểm: test API hai tài khoản; đồng thời kiểm ca đối chứng —
  người có quyền sử dụng hợp lệ một blob vẫn gắn được blob đó, để phép kiểm không cấm nhầm tái sử dụng hợp lệ.
- NT-31 (bổ sung từ Astra chấm — ca của B11). Dùng cùng một đường đọc qua hai tài khoản: nội dung đã đọc bằng
  tài khoản có quyền không rò sang tài khoản không có quyền, kể cả khi đổi tài khoản trong cùng một trình duyệt.
  Kiểm: test đọc đáp án bằng tài khoản giáo viên, đăng xuất, đăng nhập học viên trong cùng trình duyệt và đọc
  lại cùng đường đọc.
- NT-32 (bổ sung từ lượt duyệt cuối — nhóm M1/M3 cho câu tự soạn). Sao chép và nộp mặc định một câu hoàn toàn do
  giáo viên tự soạn (không có nguồn gốc hệ thống ở bất kỳ khối nào): bản sao và bản nhận không có nguồn trong cả
  dữ kiện đã lưu ở kho dữ liệu lẫn phản hồi API của hợp đồng soạn thảo; bản gốc vẫn giữ nguyên nguồn của mình.
  Kiểm: test API chạy cả hai đường sao chép và nộp, kiểm dữ kiện đã lưu của bản nhận và gọi hợp đồng soạn thảo
  của bản nhận, đối chiếu nguồn của bản gốc trước và sau.
- NT-33 (bổ sung từ lượt duyệt cuối — ca M2). Chỉ sửa lời dẫn của một bản sao chỉ-có-ảnh: sửa văn bản thường của
  phần chứa công thức, không chạm khối công thức; ảnh công thức giữ nguyên (đối chiếu định danh blob ảnh và
  hash), hệ thống không yêu cầu nhập nguồn cho khối đó, và bản sao vẫn phát hành được. Kiểm: test API sửa lời
  dẫn, đối chiếu ảnh trước và sau, rồi phát hành bản sao.
- NT-34 (bổ sung từ lượt duyệt cuối — nhóm ca tệp bài làm). Bốn ca trên hợp đồng riêng cho tệp bài làm: (a) đọc
  đúng chủ thể — học viên chủ bài làm và giáo viên được phân công đọc được, giáo viên cùng trung tâm không phân
  công không đọc được; (b) hoàn tất sau hạn — tệp chốt sau hạn hiệu lực của lượt làm không được gắn và không
  tính là đã nộp; (c) sau khi bài đã nộp và khoá — gắn thêm, thay và xoá tệp đều bị từ chối; (d) trạng thái nộp
  trên web khớp dữ liệu đã chốt ở server — giao diện chỉ báo đã nộp với các tệp đã chốt, tệp đang kiểm tra/xử
  lý hiển thị đúng trạng thái chờ. Kiểm: test API cho (a), (b), (c); kiểm trên trình duyệt đối chiếu trạng thái
  hiển thị với dữ kiện đã chốt cho (d).
- NT-35 (bổ sung từ lượt duyệt cuối — NT-16a/M5c kiểm thực tế). Kiểm hiển thị và đọc màn hình thực tế trên bộ mẫu
  công thức của phụ lục cấu hình và bộ mẫu nghiệm thu: trên môi trường trình duyệt và đọc màn hình đã ghi ở phụ
  lục đó, công thức hiển thị là ảnh đủ nét, không cắt chữ, đúng đường cơ sở; mô tả của công thức được trình đọc
  màn hình đọc thành ý nghĩa toán học. Quét rò nguồn theo NT-10 là phép kiểm riêng và không thay thế phép kiểm
  này. Kiểm: chạy bộ mẫu trên môi trường đã ghi, đối chiếu từng điều kiện đạt của M5c ở phụ lục.

## Ưu tiên

### Bắt buộc đúng ngay từ lát đầu, theo thứ tự

Thứ tự dưới đây hợp nhất thứ tự ưu tiên của lượt 1 [A1] và thứ tự thi công của tài liệu nghiệm thu [A2]; khi hai
nguồn sắp xếp khác nhau, thứ tự này ưu tiên việc chốt hợp đồng dữ liệu trước, vì mọi lát sau đều đứng trên nó.

1. Chốt hai quy tắc nền và hợp đồng dữ liệu: tách hợp đồng soạn thảo khỏi hợp đồng hiển thị; định nghĩa cấu trúc
   khối công thức hai dạng và quy tắc nguồn gốc hệ thống theo từng khối (không chuyển nguồn hệ thống; phân biệt
   nguồn mới tự soạn với ảnh kế thừa). Các bất biến phải chốt trước khi chia việc.
2. Liên kết tệp đúng quyền và đúng phiên bản: mô hình ba lớp, quyết quyền tại API qua dịch vụ quyết định của lõi
   quyền, đường đọc kiểm đúng bốn dữ kiện tài nguyên — phiên bản — vị trí — tệp.
3. Tải lên an toàn trọn luồng: phiên có kiểm soát, vùng tạm, kiểm nội dung thực, chốt bất biến, gắn có kiểm lại
   quyền.
4. Tệp bất biến và vòng đời giữ/dọn đúng tham chiếu, gồm bản chụp tự tồn tại khi nguồn bị xoá hoặc chủ nguồn rời
   trung tâm.
5. Render worker bị giới hạn với lỗi an toàn, ảnh đóng băng cùng snapshot; đồng thời kiểm tra ngay API và đầu ra
   của bộ render hiện tại về rò rỉ TeX (annotation MathML và fallback lỗi), vì đây là đường rò có thể đã tồn tại
   trước khi phần tệp được xây.
6. Nối sao chép, nộp và bản chụp đề thi vào cùng các quy tắc trên; chạy toàn bộ ca không lộ nguồn và ca giữ ảnh
   khi xoá bản gốc — chưa mở giao nội dung nếu các ca này chưa đạt.

### Để sau

- Watermark và cá nhân hoá bản trình bày (ảnh/PDF có dấu vết theo người nhận) — tăng khả năng truy vết khi phát
  tán, không phải bằng chứng tuyệt đối về người phát tán. [A1]
- PDF như một loại tệp được tải lên và đường hiển thị PDF, chỉ khi có nhu cầu thật và đường xử lý/hiển thị đã
  được kiểm chứng. [A1]
- Các loại tệp ngoài danh sách cho phép ban đầu, mở rộng theo cùng quy trình kiểm chứng. [A1]
- Giới hạn tốc độ đọc/xuất hàng loạt theo tài khoản và hành động, cùng theo dõi mẫu truy cập bất thường — biện
  pháp hỗ trợ hạn chế lấy hàng loạt, nên có khi mở sử dụng rộng, không thay thế được các ranh giới dữ liệu ở nhóm
  bắt buộc. [A1]
- Chức năng bàn giao nguồn (gồm cả bàn giao ra ngoài hệ thống) — tính năng để sau theo M3 đã chốt: chỉ xây khi
  có thiết kế cơ chế đồng ý rõ ràng của chủ sở hữu; hành vi hiện hành là không tự chuyển nguồn. [QĐ-2026-11-05]

## Điểm còn mở và các quyết định đã chốt

M1, M2, M3 đã được chủ repo chốt ngày 2026-11-05 theo khuyến nghị của Astra trong lượt chấm [QĐ-2026-11-05];
M5a và M5c cũng đã đóng ở mức khởi đầu theo cùng lượt quyết định. M4 đã được chủ repo chốt ngày 2026-11-05
[QĐ-2026-11-05]: toàn bộ gói tệp + LaTeX thuộc bản phát hành 07/11/2026. Chỉ còn M5b mở. Đội thi công không tự
chốt điểm còn mở.

### Đã chốt

- **M1 — Nguồn LaTeX khi nộp câu hỏi cá nhân về trung tâm. ĐÃ CHỐT** [QĐ-2026-11-05, theo khuyến nghị của Astra
  ở lượt chấm]: bản nhận giữ ảnh, không tự nhận nguồn. Nguồn chỉ chuyển vào bản nhận khi phạm vi nguồn nằm trong
  nội dung bàn giao được chính giáo viên xác nhận khi nộp — đây là điều kiện của chức năng bàn giao nguồn khi
  được bật; trong bản 07 chức năng đó chưa bật nên không lần nộp nào chuyển nguồn (xem quyết định B ở trên).
  Chủ mới không mặc nhiên nhận nguồn chỉ vì đã sở hữu bản sao. Cách đọc cũ của bản thảo đầu (bản sao tại trung
  tâm mặc nhiên mang nguồn cho biên tập viên của trung tâm sửa) bị lượt chấm bác và đã bỏ.
- **M2 — Sửa lời dẫn không buộc soạn lại công thức. ĐÃ CHỐT** [QĐ-2026-11-05, theo khuyến nghị của Astra]: được
  sửa văn bản thường và giữ nguyên ảnh công thức đang có; chỉ công thức nào bị thay mới cần nguồn mới. Chốt này
  có hiệu lực trước khi làm giao diện và phép kiểm chỉnh sửa.
- **M3 — Bàn giao nguồn. ĐÃ CHỐT hành vi hiện hành** [QĐ-2026-11-05, theo khuyến nghị của Astra]: "bàn giao
  nguồn" hiểu gồm mọi đường chuyển nguồn sang chủ sở hữu khác, kể cả sao chép và nộp ngay trong Giaokhoa, không
  chỉ xuất tệp ra ngoài. Hiện hành không có cơ chế tự chuyển nguồn sang chủ khác; bàn giao nguồn là tính năng để
  sau và chỉ khi có đồng ý rõ ràng của chủ sở hữu kèm bản ghi phạm vi. Nguồn hệ thống luôn bị loại khỏi mọi bàn
  giao — phần này là luật cứng theo QĐ-3. NT-18 gắn với điểm này theo dạng điều kiện đã sửa.
- **M4 — Phạm vi phát hành 07/11. ĐÃ CHỐT** [QĐ-2026-11-05]: toàn bộ gói của đặc tả này thuộc bản phát hành
  07/11/2026 — tệp đính kèm, tệp bài làm, render LaTeX ra ảnh và giữ kín nguồn đều phải có, không phần nào của
  gói được hoãn. Ràng buộc kèm theo từ lượt chấm vẫn đứng: phát hành có giao câu chứa LaTeX thì render ra ảnh và
  giữ kín nguồn là không hoãn được. Cấu trúc lát thi công theo bản chia lát chính thức tại
  `~/workspace/plans/lms-0711-20261104/files-slices-draft.md`.
- **Bàn giao nguồn trong bản 07 — ĐÃ CHỐT trạng thái duy nhất** [QĐ-2026-11-05, theo khuyến nghị của Astra ở
  lượt duyệt cuối]: bản 07 không bật chức năng bàn giao nguồn; sao chép/nộp sang chủ khác chỉ giao ảnh; API
  không nhận yêu cầu chuyển nguồn; điều kiện đồng ý của giáo viên tại M1 là điều kiện bắt buộc khi chức năng
  bàn giao nguồn được bật về sau. Xem các mục Không làm, Sao chép, Nộp về trung tâm và NT-18.
- **M5a — Giới hạn và hạn mức. ĐÃ CHỐT ở mức khởi đầu** [QĐ-2026-11-05, theo khuyến nghị của Astra]: dùng 5 MiB,
  20 megapixel và 24 giờ làm mặc định khởi đầu có thể đổi bằng cấu hình. Hạn mức tổng theo workspace, số lượt
  đồng thời và giới hạn render do đội kỹ thuật xác định trong cấu hình triển khai và kiểm thử trước nghiệm thu.
- **M5c — Mức nghiệm thu khả năng tiếp cận của ảnh công thức. ĐÃ CHỐT ở mức ban đầu** [theo hướng của lượt chấm]:
  nghiệm thu ban đầu gồm một bộ mẫu công thức hỗ trợ, một môi trường đọc màn hình được kiểm, và yêu cầu mô tả
  phải truyền đạt được ý nghĩa toán học của công thức và đi theo bản sao/bản chụp. Nhãn chung "ảnh công thức"
  không đủ; không tuyên bố đáp ứng toàn bộ một chuẩn nào khi chưa kiểm.

### Còn mở

- **M5b — Định dạng xuất.** Định dạng của hành động xuất (PDF/DOCX) chưa được chốt ở đặc tả đề và hợp đồng đã
  khoá; điểm này có thể để mở hoặc ngoài phạm vi. Điều kiện không lộ nguồn áp dụng ngay cho bất kỳ đường xuất nào
  thực sự được bật, kể cả đường cũ còn hoạt động — xem NT-16b và NT-16c. [Astra chấm]

## Phụ lục — Hợp đồng API dùng chung cho tệp và khối công thức

Phụ lục này đóng nhóm C của lượt duyệt cuối: một schema giao tiếp chung để web và các nhánh server triển khai
độc lập không tự chọn khác nhau. Tên trường và đường dẫn dưới đây là mẫu hợp đồng; ràng buộc là phân vai trường,
trạng thái và hành vi từ chối. [QĐ-2026-11-05: phụ lục được bổ sung khi khóa hợp đồng ở lát L0]

### Hai mặt của một khối công thức

Mặt hiển thị — mọi người xem được cấp đều nhận dạng này, không có trường nguồn ở bất kỳ dạng nào:

```json
{
  "blockId": "…",
  "kind": "formula",
  "form": "render-only",
  "renderState": "ready",
  "image": { "fileRef": "…", "width": 132, "height": 40, "baselineOffset": 6 },
  "description": "phân số x cộng một phần hai"
}
```

Mặt soạn thảo — chỉ trả cho người có quyền tương ứng trên bản nháp; khối có-nguồn kèm nguồn, khối chỉ-có-ảnh
không có trường nguồn:

```json
{
  "blockId": "…",
  "kind": "formula",
  "form": "source-backed",
  "renderState": "ready",
  "source": "\\frac{x}{2} + 1",
  "image": { "fileRef": "…", "width": 132, "height": 40, "baselineOffset": 6 },
  "description": "phân số x cộng một phần hai",
  "revision": 7
}
```

- `form` có đúng hai giá trị: `source-backed` (có nguồn) và `render-only` (chỉ có ảnh). Người xem không phân
  biệt được hai dạng qua mặt hiển thị ngoài việc cả hai đều chỉ có ảnh.
- Dữ liệu editor nhận để hiển thị ảnh cũ của một khối chỉ-có-ảnh là đúng mặt hiển thị ở trên (ảnh, kích thước,
  độ lệch đường cơ sở, mô tả) cộng `blockId` — đủ để giữ nguyên ảnh khi sửa phần văn bản quanh nó (M2).
- Trường `source` chỉ tồn tại trong mặt soạn thảo, chỉ ở khối `source-backed`, và chỉ khi người gọi có
  `read_math_source` trên đúng tài nguyên/phiên bản/khối (kèm `view_answer` nếu khối nằm trong đáp án/lời giải).

### Trạng thái xử lý

- Trạng thái render của một khối công thức: `pending` (đang chờ render), `ready` (ảnh sẵn sàng và khớp
  revision), `failed` (render lỗi — chỉ người soạn nhận mã lỗi an toàn không chứa nguồn).
- Trạng thái xử lý của một tệp (đính kèm và bài làm): `uploading` (đang nhận byte), `processing` (đang kiểm tra
  và xử lý), `ready` (đã chốt, gắn/nộp được), `rejected` (bị từ chối kèm lý do an toàn), `quarantined` (cách ly
  sau phát hiện nguy hiểm muộn).
- Chỉ trạng thái `ready` mới cho gắn vào bản phát hành hoặc tính là tệp bài làm đã nộp (xem mục Hợp đồng riêng
  cho tệp bài làm).

### Các thao tác và xung đột revision

- Sửa văn bản thường: gửi dãy khối mới của phần văn bản kèm `revision` của phiên bản nháp đã đọc. Server tăng
  revision sau mỗi lần lưu thành công.
- Tự soạn nguồn mới cho một khối chỉ-có-ảnh: gửi nguồn mới cho đúng `blockId` đó; thao tác này không đòi quyền
  đọc nguồn cũ (khối không có nguồn để đọc) và chỉ cần `edit_math_source` trên bản nháp. Khối chuyển thành
  `source-backed` của chủ sở hữu hiện hành, render chạy lại cho khối đó.
- Sửa nguồn của khối có-nguồn: gửi nguồn mới kèm `revision` của khối; kết quả render chỉ gắn khi revision còn
  khớp (bất biến B13).
- Gắn tệp: gửi định danh phiên tải lên đã `ready` và vị trí gắn (phiên bản nháp, khối/vai trò); server kiểm lại
  quyền sử dụng tệp nguồn và quyền ghi tại đích tại thời điểm gắn (bất biến B3, B7). Hoàn tất tải lên chống
  trùng khi retry và không đổi đích được (NT-28).
- Chốt phiên bản (phát hành/chia sẻ/nộp): server kiểm mọi khối công thức và tệp bắt buộc đều `ready` và khớp
  revision; còn trạng thái khác thì từ chối chốt, bản nháp vẫn lưu được.
- Phát hiện xung đột revision: nếu `revision` client gửi không khớp revision hiện hành của server, server từ
  chối toàn bộ thao tác với đáp ứng lỗi xung đột (theo quy ước mã lỗi của hợp đồng đã khoá) và không ghi một
  phần nào; client phải đọc lại bản nháp rồi áp lại thay đổi.
- Tạo nháp mới của cùng một tài nguyên không phải bàn giao sang chủ khác: nguồn hợp lệ của chủ sở hữu hiện hành
  được giữ nguyên trong bản nháp mới để họ sửa tiếp. Quy tắc không chuyển nguồn chỉ áp khi chủ sở hữu thay đổi
  (sao chép/nộp sang chủ khác).

### Trường server quản lý — client gửi lên phải bị từ chối

Client gửi bất kỳ trường nào sau đây trong request sửa/gắn/chốt đều bị từ chối, không âm thầm bỏ qua: dạng khối
sau sao chép (`form` của bản sao/bản nhận), nguồn gốc hệ thống của khối, `revision` và hash của nguồn do server
tính, định danh blob ảnh và khoá lưu trữ, trạng thái xử lý/render, chủ sở hữu và workspace của tài nguyên đích,
thời gian chốt do server ghi. Server là phía duy nhất quyết các trường này.

## Phụ lục — Cấu hình mặc định và bộ mẫu nghiệm thu

Phụ lục này đóng nhóm E của lượt duyệt cuối. Mọi con số dưới đây là mặc định kỹ thuật do đội L0 chốt, đổi được
bằng cấu hình triển khai, không phải chuẩn ngành và không phải quyết định sản phẩm; đổi cấu hình không đổi hợp
đồng. Nhóm đã chốt trước [QĐ-2026-11-05, M5a]: ảnh tối đa 5 MiB và 20 megapixel; phiên tải lên chưa hoàn tất
được dọn sau 24 giờ.

### Giới hạn mặc định

| Hạng mục | Mặc định L0 | Ghi chú |
| --- | --- | --- |
| Dung lượng một tệp ảnh | 5 MiB | Đã chốt ở M5a |
| Số điểm ảnh một ảnh | 20 megapixel | Đã chốt ở M5a |
| Thời hạn phiên tải lên dở | 24 giờ | Đã chốt ở M5a |
| Tệp bài làm một lượt làm | 10 tệp, tổng 20 MiB | Nhóm D của phụ lục hợp đồng bài làm |
| Hạn mức lưu trữ một workspace | 5 GiB | Tính trên tổng blob đang được giữ của workspace |
| Hạn mức lưu trữ một người dùng | 500 MiB | Chặn tải mới khi vượt, không xoá tệp cũ |
| Lượt xử lý tệp đồng thời | 8 / tiến trình xử lý | Xếp hàng khi vượt |
| Tác vụ render đồng thời | 4 / worker | Xếp hàng khi vượt |
| Độ dài nguồn một khối công thức | 2.000 ký tự | Vượt thì từ chối ở API |
| Số khối công thức một phiên bản | 50 khối | Tính tổng mọi phần văn bản |
| Mở rộng macro khi render | `maxExpand` 1000 | Macro ngoài danh sách cho phép bị từ chối |
| Kích thước ảnh render | tối đa 4096 × 4096 px, 20 megapixel | Sinh ở hệ số phóng 2 so với kích thước hiển thị |
| Thời gian một tác vụ render | 10 giây | Hết hạn thì tác vụ `failed` an toàn |
| Bộ nhớ một tác vụ render | 512 MiB | Giới hạn ở tiến trình worker |

### Đường render và bộ mẫu M5c

- Đường render chốt cho bản 07: MathJax phía server (input TeX → output SVG thuần, không nạp extension hỗ trợ
  tiếp cận sinh cấu trúc toán kèm theo) rồi raster SVG → PNG bằng resvg (ưu tiên) hoặc sharp, trong worker bị
  giới hạn theo bước 3 của mục Luồng render. Căn cứ: báo cáo nền render
  `~/workspace/research_notes/files-latex-prep-20261105/render-groundwork.md` (đường B — kênh rò nguồn hẹp
  nhất, không cần tiến trình trình duyệt, có tiền lệ Mathoid/Wikimedia). Ảnh xem trước trong trình soạn thảo
  hiện dùng KaTeX là bề mặt soạn thảo, không phải hợp đồng giao; hợp đồng giao là ảnh đạt NT-16a/NT-35.
- Ghim phiên bản: bản ghi render ghi chính xác phiên bản MathJax, phiên bản resvg/sharp và cấu hình render đã
  sinh ảnh (bất biến B13, B16). Số phiên bản cụ thể được ghim trong cấu hình triển khai tại thời điểm dựng
  worker và không tự nâng cấp giữa bản phát hành. Vì đầu ra SVG của MathJax vẽ glyph bằng đường path, bước
  raster không phụ thuộc font hệ thống; font dùng ở bước soạn thảo xem trước là font KaTeX đóng gói cùng gói
  editor hiện hành.
- Bộ mẫu công thức được hỗ trợ (bộ mẫu nghiệm thu ban đầu): phân số và phân số lồng; căn bậc hai và căn lồng;
  tổng và tích phân có cận; giới hạn; ma trận và hệ phương trình (cases); chỉ số trên/dưới đồng thời; chữ Hy
  Lạp và ký hiệu quan hệ; công thức nhiều dòng căn lề; công thức hoá học dạng `\ce{...}` nếu bộ render có
  extension tương đương đã kiểm chứng trong bản kiểm kê cú pháp, ngược lại nằm ngoài danh sách hỗ trợ của bản
  07 và bị từ chối an toàn khi render; văn bản tiếng Việt có dấu trong phần quanh công thức.
- Môi trường kiểm: trình duyệt Chrome bản ổn định hiện hành trên Windows, đọc màn hình NVDA bản ổn định hiện
  hành; kiểm thêm hiển thị trên khung hình nhỏ (chiều rộng 360 px) không qua đọc màn hình.
- Điều kiện đạt cho từng công thức trong bộ mẫu: (a) mô tả bằng ngôn ngữ tự nhiên được NVDA đọc thành ý nghĩa
  toán học đúng của công thức, không đọc mã LaTeX; (b) không cắt chữ ở bất kỳ cạnh nào của ảnh ở cả hai khung
  hình; (c) đường cơ sở của ảnh lệch không quá 2 px so với dòng văn bản quanh nó ở kích thước hiển thị chuẩn;
  (d) độ nét: ảnh sinh ở hệ số phóng 2, chữ không mờ tới mức mất nét phân biệt dấu, số mũ và chỉ số khi phóng
  200%. Kết quả kiểm ghi theo từng công thức của bộ mẫu, đạt/không đạt, ở NT-35.

## Phụ lục — Đối chiếu 18 ca của tài liệu Astra với tiêu chí của spec

| Ca của Astra | Tiêu chí của spec | Ghi chú |
| --- | --- | --- |
| T01 | NT-01 | Nhận nguyên vẹn |
| T02 | NT-02 | Nhận nguyên vẹn |
| T03 | NT-03 | Nhận nguyên vẹn |
| T04 | NT-04 | Nhận nguyên vẹn |
| T05 | NT-05 | Nhận nguyên vẹn |
| T06 | NT-06 | Nhận nguyên vẹn |
| T07 | NT-07 | Nhận nguyên vẹn |
| T08 | NT-08 | Nhận nguyên vẹn |
| T09 | NT-09 | Nhận nguyên vẹn |
| T10 | NT-10 | Nhận nguyên vẹn |
| T11 | NT-11 | Nhận nguyên vẹn |
| T12 | NT-12 | Nhận nguyên vẹn |
| T13 | NT-13 | Nhận nguyên vẹn |
| T14 | NT-14 | Nhận nguyên vẹn |
| T15 | NT-15 | Nhận nguyên vẹn; phần URL ký sẵn chỉ áp khi chọn cơ chế đó |
| T16 | NT-16a, NT-16b, NT-16c | Tách theo bề mặt: web bắt buộc ngay; PDF/DOCX khi có đường xuất (M5b) |
| T17 | NT-17 | Nhận nguyên vẹn |
| T18 | NT-18 | Dạng điều kiện (M1/M3); không chuyển nguồn hệ thống luôn bắt buộc qua NT-04/NT-06 |
