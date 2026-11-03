# Dữ kiện kiểm chứng cho hợp đồng xác thực và quyết quyền

Tài liệu này là **bộ dữ kiện chuẩn**: mỗi ca gồm dữ kiện đầu vào,
câu hỏi quyết quyền, kết quả kỳ vọng theo mô hình đích (hợp đồng ở
`docs/keycloak-auth-contract.md`, oracle ở `docs/access-oracle.md`)
và hành vi hiện có tại head, kèm ghi rõ sai lệch.

> **Kỷ luật dữ kiện:** đây là dữ kiện đặc tả, **không phải bằng
> chứng đã chạy**. Chỉ các ca oracle ở tầng hàm thuần mới có spec
> chạy thật (`apps/api/src/modules/access/domain/permission.spec.ts`
> và `apps/api/src/modules/access/domain/oracle-fixtures.spec.ts`).
> Các ca nghiệp vụ dưới đây là đích để các việc triển khai sau biến
> thành test thật ở đúng tầng (integration/e2e); **không tuyên bố bộ
> ca nghiệp vụ đích đã chạy đạt** tại việc hợp đồng này. Cấm sửa
> "kỳ vọng đích" để hợp thức hoá một bypass đang tồn tại — sai lệch
> phải được ghi ở cột sai lệch và xử lý ở việc triển khai tương ứng.

Quy ước đọc bảng: "Nhánh" là vai + các ghi đè của vai đó dọc path
context theo `docs/access-oracle.md`. Giá trị ghi đè viết dạng
`ALLOW`/`PREVENT`/`PROHIBIT`/`INHERIT` (giá trị số tương ứng
`1`/`-1`/`-1000`/`0`).

## A. Ca oracle trên cây context

| # | Dữ kiện | Câu hỏi | Kỳ vọng đích | Hành vi hiện có | Sai lệch |
| --- | --- | --- | --- | --- | --- |
| A1 | Một nhánh vai: `PREVENT` tại context khoá (cha), `ALLOW` tại context module (con) cho `quiz:grade` | Có `quiz:grade` tại module? | **Cho phép** — điểm gần lá nhất thắng trong nhánh | Khớp: hàm thuần cho `ALLOW` (đã có spec) | Không |
| A2 | Một nhánh vai: `PROHIBIT` tại context tenant (cha), `ALLOW` tại context khoá (con) cho `grade:viewall` | Có `grade:viewall` tại khoá? | **Từ chối** — `PROHIBIT` ở tổ tiên phủ quyết cả nhánh | Khớp: hàm thuần trả `PROHIBIT` (đã có spec) | Không |
| A3 | Hai nhánh quyền trên cùng capability tại cùng context đích: nhánh vai A kết quả `PREVENT`, nhánh vai B kết quả `ALLOW`, không nhánh nào `PROHIBIT` | Được phép? | **Cho phép** — tổng hợp nhánh: một `ALLOW` là đủ khi không có `PROHIBIT` | Khớp ở tầng hàm thuần (đã có spec) | Không |
| A4 | Hai nhánh: nhánh A `ALLOW`, nhánh B `PROHIBIT` tại tổ tiên | Được phép? | **Từ chối** — bất kỳ `PROHIBIT` nào cũng phủ quyết toàn bộ | Khớp ở tầng hàm thuần (đã có spec) | Không |
| A5 | Ghi đè cá nhân (nhánh riêng của người dùng) = `PREVENT` tại context đích; nhánh role chung = `ALLOW` tại cùng context | Được phép? | **Cho phép** — cá nhân và role khác nhánh thì không có ưu tiên xuyên nhánh; `PREVENT` ở nhánh cá nhân không chặn `ALLOW` của nhánh role | Khớp ngữ nghĩa tổng hợp nhánh của evaluator hiện tại | Không |
| A6 | Ghi đè cá nhân đặt `PROHIBIT` tại context tenant cho đúng người dùng; nhánh role chung `ALLOW` tại context khoá | Được phép? | **Từ chối** — `PROHIBIT` ở bất kỳ nhánh nào cũng phủ quyết | Khớp ở tầng hàm thuần | Không |

## B. Ca vai chính và phân công

| # | Dữ kiện | Câu hỏi | Kỳ vọng đích | Hành vi hiện có | Sai lệch |
| --- | --- | --- | --- | --- | --- |
| B1 | Thành viên trung tâm có vai chính `teacher`; được phân công dạy khoá K (gán vai giảng dạy tại context khoá K) | Có quyền chấm bài tại K (vd `grade:edit` tại context khoá K)? | **Cho phép** — vai chính cho tư cách, phân công tại ngữ cảnh cho quyền cụ thể | Khớp về kết quả: engine hiện tại cấp qua gán vai tại context khoá | Mô hình vai chưa phải "một vai chính": runtime hiện tại dùng bảy role mặc định kiểu Moodle và một người có thể giữ nhiều role không qua khái niệm vai chính — việc áp vai chính thuộc các việc sau |
| B2 | Cùng giáo viên B1, không có phân công nào ở lớp L2/khoá K2 của trung tâm | Có `class:grade` tại L2, `grade:edit` tại K2? | **Từ chối** — quyền theo phân công không chảy sang ngữ cảnh không được phân công | Khớp: evaluator chỉ tính gán vai trên path của context đích | Không |
| B3 | Thành viên vai chính `admin` của trung tâm; tại context tenant có ghi đè `PROHIBIT` cho `grade:viewall` trên nhánh của admin; tại context khoá có `ALLOW` | Admin có `grade:viewall` tại khoá? | **Từ chối** — admin vẫn chịu `PROHIBIT` | Tầng hàm thuần khớp; ở tầng service, trong cây tenant trung tâm lối tắt site admin đã bị cổng tenant vô hiệu nên kết quả khớp; ngoài cây tenant xem ca E3 | Một phần: lối tắt `siteadmins` ngoài cây tenant (ca E3) |
| B4 | Người dùng có vai chính `member`, là học viên ghi danh khoá K, bài quiz Q của K đang mở | Có `quiz:attempt` tại module Q? | **Cho phép** theo ghi danh + trạng thái bài tại thời điểm bắt đầu | Khớp về cổng capability theo ghi danh | Không |

## C. Ca quan hệ phụ huynh

| # | Dữ kiện | Câu hỏi | Kỳ vọng đích | Hành vi hiện có | Sai lệch |
| --- | --- | --- | --- | --- | --- |
| C1 | Phụ huynh P có quan hệ giám hộ hợp lệ với học viên S; S học lớp L có thông báo lớp mới | P đọc thông báo lớp/khoá của S? | **Cho phép** | Khớp: bề mặt phụ huynh hiện có khối thông báo lớp của con (loại trừ thông báo điểm) | Không |
| C2 | Như C1; điểm bài kiểm tra của S đã được giáo viên công bố | P xem điểm/bài làm của S? | **Từ chối** — quan hệ phụ huynh không cấp quyền xem điểm/bài làm, kể cả điểm đã công bố | **Cho phép**: dịch vụ bề mặt phụ huynh hiện tại trả điểm các bài **đã công bố** của con cho phụ huynh | **Có — sai lệch đích/hiện có.** Thu hẹp thuộc việc giới hạn quyền phụ huynh; cấm sửa kỳ vọng này để hợp thức hoá hành vi hiện tại |
| C3 | Như C1; giáo viên G là giáo viên của lớp/khoá S đang học; giáo viên H không dạy lớp/khoá nào của S | P nhắn tin 1-1 với G? Với H? | **Cho phép với G; từ chối với H** — chỉ giáo viên có quan hệ hợp lệ với con | Một phần: luật nhắn tin hiện tại cho giáo viên nhắn thẳng học viên thuộc khoá/lớp của mình và bề mặt phụ huynh có kênh liên hệ; cổng "chỉ giáo viên có quan hệ với con" cho chiều phụ huynh → giáo viên chưa được kiểm chứng thành test riêng | Chưa có ca kiểm chứng riêng ở hiện tại; đích giữ nguyên |
| C4 | Người dùng X vừa là giáo viên được phân công dạy lớp L1 (vai chính `teacher`) vừa là phụ huynh của S học lớp L2 | Tại L1: quyền giáo viên đầy đủ theo phân công? Tại L2: quyền theo quan hệ phụ huynh (thông báo + nhắn giáo viên của S), không xem điểm S? Thu hẹp phía phụ huynh có làm mất quyền giáo viên ở L1? | **Đúng cả ba**: một vai chính `teacher`; quan hệ phụ huynh là quan hệ dữ liệu độc lập; hai phía xét độc lập, không phía nào thu hẹp phía kia | Một phần: engine hiện tại xét các gán vai/ngữ cảnh độc lập nên phía giáo viên khớp; phía phụ huynh lệch theo C2 (còn xem được điểm đã công bố) | Có — theo C2 |

## D. Ca tenant và danh tính

| # | Dữ kiện | Câu hỏi | Kỳ vọng đích | Hành vi hiện có | Sai lệch |
| --- | --- | --- | --- | --- | --- |
| D1 | Người dùng là thành viên `active` của trung tâm A, không phải thành viên của trung tâm B; context đích nằm trong cây của B | Mọi capability tại context của B? | **Từ chối toàn bộ** — cổng tenant chặn trước oracle, kể cả khi có gán vai ở cây hệ thống | Khớp: cổng tenant của evaluator hiện tại fail-closed và thu hẹp gán vai trong cây tenant | Không |
| D2 | Thành viên của trung tâm A bị đổi trạng thái membership sang không còn `active` ngay trước một request ghi | Request ghi tại context của A? | **Từ chối** — dữ kiện cổng phải hiệu lực ngay (đổi membership làm mất hiệu lực cache của context tenant) | Khớp theo thiết kế evaluator (bump version context tenant khi đổi membership) | Không |
| D3 | Hai tài khoản ở hai realm khác nhau cùng email `a@example.com`, `sub` khác nhau; chỉ tài khoản realm trung tâm X được liên kết với user nghiệp vụ U | Token của realm còn lại có đăng nhập thành U? | **Không** — liên kết bằng `(issuer, sub)`; email trùng không gộp danh tính | Chưa có: runtime hiện tại chưa có realm và chưa có liên kết `(issuer, sub)` (đăng nhập JWT tự phát theo tài khoản nội bộ) | Có — toàn bộ lớp danh tính Keycloak chưa triển khai; thuộc các việc danh tính/issuer |
| D4 | Access token có chữ ký đúng nhưng `aud` không chứa audience của API, hoặc `iss` không thuộc registry issuer đã đăng ký cho Host | API chấp nhận token? | **Từ chối** trước khi xét quyền | Chưa có ở dạng Keycloak; JWT tự phát hiện tại không theo registry issuer theo Host | Có — chưa triển khai; thuộc việc xác minh danh tính theo Host |
| D5 | Request tới Host của trung tâm A mang token do realm của trung tâm B phát hành | API chấp nhận? | **Từ chối** — Host đã xác minh chọn đúng một issuer; token chéo realm vô giá trị tại Host này | Chưa có (chưa có realm theo Host) | Có — chưa triển khai |

## E. Ca platform

| # | Dữ kiện | Câu hỏi | Kỳ vọng đích | Hành vi hiện có | Sai lệch |
| --- | --- | --- | --- | --- | --- |
| E1 | Tài khoản platform (`platform-admin` trong realm `giaokhoa`), không phải thành viên của trung tâm A | Có capability bất kỳ trong cây của A? | **Không** — platform không có quyền ngầm trong tenant; muốn hỗ trợ thì qua đăng nhập thay có lý do + audit hoặc được gán vai như thành viên | Khớp trong cây tenant trung tâm: cổng tenant chặn lối tắt site admin và đòi membership `active` | Không |
| E2 | Tài khoản demo giáo viên/học viên phục vụ review dữ liệu của trung tâm A | Tài khoản thuộc realm nào? | Realm **của trung tâm A** — không thuộc realm `giaokhoa`, không phải tài khoản platform | Chưa có realm; tài khoản demo là tài khoản nội bộ được seed trong tenant nền tảng hiện tại | Có — mô hình realm chưa triển khai; đính chính mô tả ở README liên quan |
| E3 | Người dùng nằm trong danh sách `siteadmins` (cấu hình site), context đích nằm **ngoài** cây tenant trung tâm (ngữ cảnh platform/hệ thống), capability không có `ALLOW` nào | Được phép? | Theo đích: platform-admin là client role Keycloak và quyền platform cũng qua oracle/capability tường minh của bề mặt platform, không có lối tắt "bỏ qua mọi capability" như cơ chế danh sách hiện tại | **Cho phép** — evaluator hiện tại cho site admin bỏ qua mọi kiểm tra capability ngoài cây tenant | **Có — sai lệch.** Gỡ lối tắt thuộc việc triển khai sau; dữ kiện này giữ nguyên kỳ vọng đích |

## F. Ca trạng thái bài tại thời điểm ghi

| # | Dữ kiện | Câu hỏi | Kỳ vọng đích | Hành vi hiện có | Sai lệch |
| --- | --- | --- | --- | --- | --- |
| F1 | Học viên bắt đầu làm quiz khi bài đang mở; giáo viên đóng bài (đổi trạng thái) ngay trước lúc học viên nộp | Lượt nộp được ghi nhận? | **Từ chối ghi** — kiểm tra trạng thái bài phải đọc lại **trong giao dịch ghi**, không dùng kết quả kiểm tra lúc bắt đầu làm bài | Chưa có ca kiểm chứng riêng cho kịch bản đổi trạng thái ngay trước lúc ghi trong bộ dữ kiện này; hành vi phải được đối chiếu khi triển khai ca ở tầng integration/e2e của module quiz | Chưa kiểm chứng — ghi nhận là dữ kiện đích, không tuyên bố đã đạt |
| F2 | Bài tập đã chấm xong nhưng giáo viên rút công bố điểm ngay trước lúc học viên/phụ huynh truy vấn | Điểm còn hiển thị? | Học viên chỉ thấy điểm **đã công bố** theo trạng thái hiện tại; phụ huynh không thấy điểm ở mọi trường hợp (ca C2) | Cổng công bố cho học viên đã có; phía phụ huynh lệch theo C2 | Có — theo C2 |
