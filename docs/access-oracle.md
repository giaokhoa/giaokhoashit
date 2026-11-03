# Oracle bốn trạng thái của engine quyết quyền

Tài liệu này chốt ngữ nghĩa quyết quyền mà mọi việc triển khai
quyền (tại API, và các đợt đối chiếu chính sách về sau) phải áp
dụng cùng một cách. Kiểm kê capability nằm ở
`docs/capability-inventory.md`; dữ kiện kiểm chứng dạng ca nằm ở
`docs/access-verification-fixtures.md`.

## 1. Bốn trạng thái

| Trạng thái | Giá trị | Ý nghĩa |
| --- | --- | --- |
| `INHERIT` | `0` | Không ý kiến tại điểm này — quyết định đi lên tổ tiên |
| `ALLOW` | `1` | Cho phép |
| `PREVENT` | `-1` | Ngăn chặn — có thể bị một `ALLOW` ở ngữ cảnh gần hơn (trong cùng nhánh) thay thế |
| `PROHIBIT` | `-1000` | Cấm hẳn — phủ quyết tuyệt đối, không điểm nào gần hơn gỡ được |

Hằng số tương ứng trong mã hiện tại: `CAP_INHERIT`, `CAP_ALLOW`,
`CAP_PREVENT`, `CAP_PROHIBIT` tại
`apps/api/src/modules/access/domain/permission.ts`.

## 2. Cây ngữ cảnh và nhánh cấp quyền

- Quyền được xét trên **cây context** của đối tượng đích. Thứ tự
  các cấp (từ gốc xuống lá): hệ thống (10) → tenant (20) → user
  (30) → danh mục khoá (40) → lớp (45) → khoá (50) → module (70) →
  block (80). Path của một context là chuỗi tổ tiên từ gốc tới
  chính nó.
- Một **nhánh cấp quyền** là một vai (role) mà principal được gán:
  tập hợp các điểm ghi đè (override) của đúng vai đó dọc path từ lá
  lên gốc, cộng điểm mặc định theo archetype của vai tại context hệ
  thống.
- Ghi đè đặt tại một context áp dụng cho context đó và toàn cây con
  của nó, trừ khi có ghi đè khác gần lá hơn trong cùng nhánh.

## 3. Thuật toán quyết định (chuẩn)

Với một (principal, capability, context đích):

1. **Cổng tenant trước.** Nếu context đích nằm trong cây của một
   tenant trung tâm (trường/trung tâm), principal phải là thành
   viên `active` của tenant đó và chỉ các gán vai neo trong cây
   tenant mới được tính. Tenant bị khoá/lưu trữ thì không ai còn
   quyền trong cây của nó. Platform không có quyền ngầm trong
   tenant.
2. **Từng nhánh độc lập.** Với mỗi nhánh vai, duyệt từ lá lên gốc:
   điểm `PROHIBIT` ở bất kỳ tổ tiên nào làm cả nhánh thành
   `PROHIBIT` (phủ quyết, kể cả khi có `ALLOW` ở lá); nếu không có
   `PROHIBIT`, giá trị của nhánh là điểm **không phải `INHERIT`
   gần lá nhất** (`ALLOW` hoặc `PREVENT`); toàn `INHERIT` thì
   nhánh là `INHERIT`.
3. **Tổng hợp các nhánh.** Có bất kỳ nhánh nào `PROHIBIT` → **từ
   chối**. Không nhánh nào `PROHIBIT` và có ít nhất một nhánh
   `ALLOW` → **cho phép**. Còn lại (chỉ `PREVENT`/`INHERIT`) →
   **từ chối**.

Hệ quả bắt buộc:

- `PREVENT` ở cha + `ALLOW` ở con (cùng nhánh) → **cho phép**:
  điểm gần nhất thắng trong nhánh.
- `PROHIBIT` ở cha + `ALLOW` ở con (cùng nhánh) → **từ chối**:
  `PROHIBIT` ở tổ tiên phủ quyết cả nhánh.
- Hai nhánh quyền: nhánh A `PREVENT`, nhánh B `ALLOW` → **cho
  phép** (nhiều nhánh cùng tồn tại, không `PROHIBIT` thì một
  `ALLOW` là đủ).
- Admin trung tâm vẫn chịu `PROHIBIT`: quyền nền rộng của vai
  chính `admin` là các `ALLOW` theo nhánh, không phải lối tắt bỏ
  qua oracle; một `PROHIBIT` đúng capability tại tổ tiên chặn cả
  admin.

## 4. Thứ tự ghi đè cá nhân so với role

"Ghi đè cá nhân" là ghi đè áp cho riêng một người (qua vai được gán
riêng cho người đó, hoặc ghi đè đặt tại context của chính người
dùng). Chuẩn áp dụng:

1. **Trong cùng một nhánh vai**, thứ tự duy nhất là **theo độ gần
   của context**: điểm ghi đè gần lá nhất thắng, bất kể điểm đó do
   quản trị vai đặt chung hay đặt riêng cho cá nhân. Ghi đè cá
   nhân đặt ở context gần hơn thắng ghi đè role ở tổ tiên trong
   cùng nhánh; ngược lại ghi đè cá nhân ở tổ tiên bị ghi đè role
   gần lá hơn thay thế (trừ `PROHIBIT` — phủ quyết không bị thay
   thế ở mọi trường hợp).
2. **Khác nhánh vai thì không có thứ tự ưu tiên giữa cá nhân và
   role**: mỗi ghi đè thuộc về nhánh của nó và kết quả là tổng hợp
   theo mục 3. Hệ quả cụ thể:
   - Ghi đè cá nhân `PREVENT` **không** chặn một `ALLOW` của nhánh
     role khác.
   - Ghi đè cá nhân `ALLOW` cho phép dù nhánh role là `PREVENT`,
     miễn không nhánh nào `PROHIBIT`.
   - Muốn chặn tuyệt đối một người bất kể các nhánh khác, công cụ
     đúng là `PROHIBIT` trên một nhánh người đó mang — `PROHIBIT`
     ở bất kỳ nhánh nào cũng phủ quyết toàn bộ.

Cách hiểu bị cấm: không tồn tại quy tắc "cá nhân luôn thắng role"
hay "role luôn thắng cá nhân" xuyên nhánh; mọi so sánh thắng/thua
chỉ xảy ra bên trong một nhánh theo độ gần context, cộng phủ quyết
`PROHIBIT` toàn cục.

## 5. Đối chiếu với engine hiện tại

- Hàm thuần hiện tại (`evaluateSingleRole`,
  `aggregateRoleResults` trong
  `apps/api/src/modules/access/domain/permission.ts`) đã thực thi
  đúng mục 3 và 4 ở tầng hàm; bảng chân trị của chúng có spec tại
  `apps/api/src/modules/access/domain/permission.spec.ts` và được
  mở rộng bằng bộ dữ kiện oracle tại
  `apps/api/src/modules/access/domain/oracle-fixtures.spec.ts`.
- Cổng tenant ở mục 3.1 đã có trong `PermissionEvaluator` hiện tại
  (membership `active`, thu hẹp gán vai trong cây tenant,
  fail-closed khi không xác minh được tenant).
- **Sai lệch còn tồn tại (chưa phải hành vi đích):** bên ngoài cây
  tenant trung tâm, evaluator hiện tại còn lối tắt danh sách
  `siteadmins` bỏ qua mọi kiểm tra capability, và danh sách
  platform được hợp nhất một chiều vào ngữ cảnh kho riêng. Theo
  hợp đồng đích, `platform-admin` là client role của Keycloak và
  không có lối tắt bỏ qua oracle trong nghiệp vụ tenant; việc gỡ
  lối tắt thuộc các việc triển khai sau hợp đồng này. Dữ kiện phân
  biệt hai hành vi nằm ở
  `docs/access-verification-fixtures.md`.
