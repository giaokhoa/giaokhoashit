// Worker render một công thức LaTeX thành PNG. Giao thức: đọc một JSON từ stdin gồm source, display và config;
// ghi một JSON ra stdout gồm pngBase64 và các số đo, hoặc một JSON lỗi chỉ chứa mã lỗi an toàn. Nguồn LaTeX
// không bao giờ được ghi ra stdout, stderr hay tên tiến trình: mọi lỗi đều ánh xạ về mã chung trước khi trả.
// Tiến trình xử lý đúng một tác vụ rồi thoát, nên trạng thái macro của MathJax không thể truyền giữa hai
// người dùng khác nhau và một tác vụ treo chỉ chết theo timeout của phía gọi.
import { mathjax } from 'mathjax-full/js/mathjax.js';
import { TeX } from 'mathjax-full/js/input/tex.js';
import { SVG } from 'mathjax-full/js/output/svg.js';
import { liteAdaptor } from 'mathjax-full/js/adaptors/liteAdaptor.js';
import { RegisterHTMLHandler } from 'mathjax-full/js/handlers/html.js';
import { AllPackages } from 'mathjax-full/js/input/tex/AllPackages.js';
import { Resvg } from '@resvg/resvg-js';

const RENDERER_VERSION = 'mathjax-full@3.2.2';
const RASTERIZER_VERSION = '@resvg/resvg-js@2.6.2';
const EM_PX = 16;
const EX_PX = 8;

// Danh sách gói TeX: toàn bộ gói chính thức của MathJax trừ bốn gói bị loại có chủ đích — noundefined (bỏ qua
// macro lạ thay vì báo lỗi), html (cung cấp \href và các lệnh HTML), require và autoload (nạp extension theo
// yêu cầu của chính nội dung đầu vào). mhchem là điều kiện bắt buộc rút từ bản kiểm kê cú pháp: trình soạn
// thảo mở sẵn cú pháp \ce cho người soạn nên worker phải phủ được nó; thiếu mhchem là lỗi cấu hình, dừng ngay.
const EXCLUDED_PACKAGES = ['noundefined', 'html', 'require', 'autoload'];
const packages = AllPackages.filter((name) => !EXCLUDED_PACKAGES.includes(name));
if (!packages.includes('mhchem')) {
  process.stdout.write(JSON.stringify({ error: 'RENDERER_MISCONFIGURED' }));
  process.exit(1);
}

function fail(code) {
  process.stdout.write(JSON.stringify({ error: code }));
  process.exit(0);
}

let input = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  input += chunk;
});
process.stdin.on('end', () => {
  let task;
  try {
    task = JSON.parse(input);
  } catch {
    fail('RENDER_FAILED');
    return;
  }
  const config = task.config ?? {};
  const maxSourceLength = config.maxSourceLength ?? 2000;
  const maxBufferBytes = config.maxBufferBytes ?? 5120;
  const scale = config.scale ?? 2;
  const maxWidth = config.maxImageWidth ?? 4096;
  const maxHeight = config.maxImageHeight ?? 4096;
  const maxPixels = config.maxImagePixels ?? 20000000;
  if (typeof task.source !== 'string' || task.source.length === 0 || task.source.length > maxSourceLength) {
    fail('SOURCE_TOO_LONG');
    return;
  }
  try {
    const adaptor = liteAdaptor();
    RegisterHTMLHandler(adaptor);
    // formatError ném lỗi thay vì sinh phần tử lỗi chứa thông tin cú pháp: đầu ra lỗi của MathJax không bao
    // giờ trở thành nội dung giao cho người xem.
    const tex = new TeX({
      packages,
      maxBuffer: maxBufferBytes,
      formatError: (jax, err) => {
        throw err;
      },
    });
    const document = mathjax.document('', { InputJax: tex, OutputJax: new SVG({ fontCache: 'local' }) });
    const node = document.convert(task.source, {
      display: task.display === true,
      em: EM_PX,
      ex: EX_PX,
      containerWidth: 80 * EM_PX,
    });
    const svgNode = adaptor.tags(node, 'svg')[0];
    const svg = adaptor.serializeXML(svgNode);
    // SVG trung gian là dữ kiện riêng tư của worker, không rời tiến trình này; vẫn kiểm cấu trúc của nó trước
    // khi raster để một kênh mang nguồn lọt vào đầu ra thì tác vụ hỏng an toàn thay vì giao ảnh sai hợp đồng.
    if (svg.includes('application/x-tex') || svg.includes('<math')) {
      fail('RENDER_FAILED');
      return;
    }
    const style = adaptor.getAttribute(svgNode, 'style') ?? '';
    const align = /vertical-align:\s*(-?[0-9.]+)ex/.exec(style);
    const depthEx = align ? -Number.parseFloat(align[1]) : 0;
    const png = new Resvg(svg, {
      fitTo: { mode: 'zoom', value: scale },
      font: { loadSystemFonts: false },
    }).render();
    if (png.width > maxWidth * scale || png.height > maxHeight * scale || png.width * png.height > maxPixels * scale * scale) {
      fail('IMAGE_TOO_LARGE');
      return;
    }
    process.stdout.write(
      JSON.stringify({
        pngBase64: Buffer.from(png.asPng()).toString('base64'),
        width: Math.round(png.width / scale),
        height: Math.round(png.height / scale),
        baselineOffset: Math.round(depthEx * EX_PX),
        rendererVersion: RENDERER_VERSION,
        rasterizerVersion: RASTERIZER_VERSION,
        packages,
      }),
    );
    process.exit(0);
  } catch {
    fail('RENDER_FAILED');
  }
});
