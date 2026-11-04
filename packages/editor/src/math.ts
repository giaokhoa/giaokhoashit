import { renderToString } from 'katex';
import 'katex/contrib/mhchem';

/**
 * Điểm nối KaTeX duy nhất của package: mọi lần render công thức (panel soạn, trình xem tĩnh, node view của extension)
 * đều phải cho ra cùng một cấu hình an toàn — `trust: false` để LaTeX của người soạn không chèn được HTML tùy ý vào
 * trang người học.
 *
 * Gói `mhchem` (contrib của KaTeX, cùng giấy phép MIT) được nạp ở đây để công thức hoá học dùng được cú pháp `\ce{...}`
 * — nạp một lần khi module này được import, trước mọi lần render.
 */

/**
 * Render một công thức LaTeX thành HTML bằng KaTeX.
 *
 * Hai chốt chặn rò nguồn theo tài liệu KaTeX: kết quả chỉ lấy phần HTML (`output: 'html'`) vì phần MathML luôn nhúng
 * nguyên mã TeX vào annotation `encoding="application/x-tex"`; và lỗi cú pháp được bắt tại đây thay vì dùng
 * `throwOnError: false` — chế độ đó tự hiển thị mã TeX gốc của công thức lỗi. Công thức lỗi cho ra một placeholder
 * không chứa nguồn: bề mặt hiển thị không sập và người xem không thấy mã TeX, còn tác giả biết lỗi qua
 * {@link validateMathLatex} ở panel soạn.
 * @returns Chuỗi HTML của công thức đã render; placeholder không chứa nguồn khi công thức lỗi.
 */
export function renderMathHtml(latex: string, displayMode: boolean): string {
  try {
    return renderToString(latex, { displayMode, throwOnError: true, trust: false, output: 'html' });
  } catch {
    return '<span class="math-render-error">Công thức không hợp lệ</span>';
  }
}

/**
 * Kiểm tra một công thức LaTeX có hợp lệ để lưu hay không.
 *
 * Render thử với `throwOnError: true`: LaTeX sai cú pháp cho ra thông báo lỗi để panel soạn hiển thị và chặn xác nhận;
 * hợp lệ cho ra `null`.
 * @returns `null` nếu hợp lệ; ngược lại là thông báo lỗi cho người soạn.
 */
export function validateMathLatex(latex: string): string | null {
  if (!latex.trim()) {
    return 'Chưa nhập công thức.';
  }
  try {
    renderToString(latex, { displayMode: false, throwOnError: true, trust: false });
    return null;
  } catch (error) {
    return error instanceof Error ? error.message : 'Công thức LaTeX không hợp lệ.';
  }
}
