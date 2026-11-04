import { describe, expect, it } from 'vitest';
import { renderMathHtml, validateMathLatex } from './math.js';

/**
 * Spec của điểm nối KaTeX: HTML giao cho người xem là bản trình bày thuần — không kênh nào của kết quả render được
 * chứa mã TeX gốc, kể cả annotation MathML và fallback hiển thị nguồn khi công thức lỗi.
 */
describe('Điểm nối KaTeX (math.ts)', () => {
  it('render công thức hợp lệ ra HTML không chứa annotation TeX và không chứa chuỗi nguồn', () => {
    const latex = String.raw`\frac{a}{b} + \sqrt{x}`;

    const html = renderMathHtml(latex, false);

    expect(html).toContain('class="katex"');
    expect(html).not.toContain('application/x-tex');
    expect(html).not.toContain('<annotation');
    expect(html).not.toContain(latex);
    expect(html).not.toContain(String.raw`\frac`);
    expect(html).not.toContain(String.raw`\sqrt`);
  });

  it('render công thức khối ra HTML cũng không chứa annotation TeX và không chứa chuỗi nguồn', () => {
    const latex = String.raw`\sum_{i=1}^{n} i`;

    const html = renderMathHtml(latex, true);

    expect(html).toContain('katex-display');
    expect(html).not.toContain('application/x-tex');
    expect(html).not.toContain(latex);
    expect(html).not.toContain(String.raw`\sum`);
  });

  it('công thức lỗi không ném lỗi ra ngoài và kết quả là placeholder không lộ mã TeX', () => {
    const latex = String.raw`\frac{abc}`;

    const html = renderMathHtml(latex, false);

    expect(html).toContain('math-render-error');
    expect(html).not.toContain(latex);
    expect(html).not.toContain(String.raw`\frac`);
    expect(html).not.toContain('application/x-tex');
  });

  it('công thức hoá học qua mhchem vẫn render được mà không chứa annotation TeX', () => {
    const html = renderMathHtml(String.raw`\ce{H2O}`, false);

    expect(html).toContain('class="katex"');
    expect(html).not.toContain('application/x-tex');
    expect(html).not.toContain(String.raw`\ce`);
  });

  it('validateMathLatex vẫn phân biệt công thức hợp lệ và công thức lỗi cho tác giả', () => {
    expect(validateMathLatex('x^2 + y^2 = z^2')).toBeNull();
    expect(validateMathLatex(String.raw`\frac{abc}`)).not.toBeNull();
  });
});
