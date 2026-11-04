# Worker render LaTeX

Worker Node render một công thức LaTeX thành ảnh PNG cho `apps/server`, theo đường đã chốt: MathJax phía
server (gọi trực tiếp các module của `mathjax-full`, TeX → SVG thuần) rồi raster SVG → PNG bằng
`@resvg/resvg-js`. Người xem chỉ nhận PNG; SVG chỉ là bước trung gian riêng tư bên trong worker và không rời
tiến trình.

## Cách chạy

- Cài dependencies đúng phiên bản đã ghim trong `package-lock.json`: `npm ci` trong thư mục này.
- Phía server gọi worker bằng tiến trình con: `node render.mjs`, truyền một JSON qua stdin (gồm `source`,
  `display` và `config` các giới hạn), đọc một JSON qua stdout (gồm `pngBase64`, kích thước hiển thị, độ lệch
  đường cơ sở, phiên bản bộ render) hoặc một JSON lỗi chỉ chứa mã lỗi an toàn.
- Mỗi tiến trình xử lý đúng một tác vụ rồi thoát: trạng thái macro không truyền giữa các tác vụ, và phía server
  áp timeout, trần bộ nhớ (`--max-old-space-size`) cùng số tác vụ đồng thời cho nhóm tiến trình này.

## Ranh giới an toàn

- Tập gói TeX là toàn bộ gói chính thức của MathJax trừ `noundefined`, `html`, `require` và `autoload`: macro
  lạ là lỗi render an toàn thay vì được hiển thị; các lệnh HTML như `\href` không tồn tại; nội dung đầu vào
  không tự nạp thêm extension. Gói `mhchem` là bắt buộc (worker tự kiểm tra lúc khởi động) vì trình soạn thảo
  mở sẵn cú pháp `\ce{...}` cho người soạn.
- Worker không mở kết nối mạng, không chạy lệnh hệ thống và không đọc tệp theo đầu vào LaTeX; bước raster tắt
  nạp font hệ thống (`loadSystemFonts: false`) vì glyph đã là đường path trong SVG.
- Mọi lỗi đều trả mã an toàn (`RENDER_FAILED`, `SOURCE_TOO_LONG`, `IMAGE_TOO_LARGE`,
  `RENDERER_MISCONFIGURED`); nguồn LaTeX không xuất hiện trong đáp ứng lỗi, nhật ký hay tên tác vụ. PNG sinh ra
  chỉ gồm các chunk dữ kiện ảnh, không mang metadata chứa nguồn.
