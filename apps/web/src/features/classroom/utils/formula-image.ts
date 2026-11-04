/**
 * Bề mặt xem công thức trên web: dựng thuộc tính cho thẻ ảnh từ dữ kiện hiển thị của server.
 *
 * Flow: dữ kiện vào là một khối công thức ở mặt hiển thị (file này không định nghĩa đường lấy dữ kiện —
 * tầng queries của feature nạp từ API) → khối sẵn sàng thì thuộc tính ảnh được dựng với đường ảnh qua API,
 * kích thước và độ lệch đường cơ sở đúng số đo server đã ghi cùng bản render → dữ liệu ra là thuộc tính cho
 * thẻ `img`, không có side-effect. Dữ kiện vào không có trường nguồn ở bất kỳ dạng nào, và văn bản thay thế
 * của ảnh là mô tả bằng ngôn ngữ tự nhiên do người soạn viết — không bao giờ là mã LaTeX.
 */

/** Số đo của một ảnh công thức do server ghi cùng bản render. */
export interface FormulaImageData {
  fileRef: string;
  width: number;
  height: number;
  baselineOffset: number;
}

/** Một khối công thức ở mặt hiển thị: chỉ có ảnh và mô tả, không có nguồn. */
export interface FormulaDisplayBlock {
  blockId: string;
  kind: 'formula';
  form: 'source-backed' | 'render-only';
  renderState: 'pending' | 'ready' | 'failed';
  image: FormulaImageData | null;
  description: string | null;
}

/** Thuộc tính sẵn sàng để gắn vào thẻ `img` của một khối công thức. */
export interface FormulaImageAttributes {
  src: string;
  width: number;
  height: number;
  style: { verticalAlign: string };
  alt: string;
}

/**
 * Dựng thuộc tính ảnh cho một khối công thức.
 *
 * @param block - Khối công thức ở mặt hiển thị.
 * @param imageUrl - Hàm dựng đường ảnh có xác thực của chính khối từ mã khối và tham chiếu bản render.
 * @returns Thuộc tính ảnh khi bản render đã sẵn sàng; `null` khi khối đang chờ hoặc render lỗi, vì hai
 * trạng thái đó không có ảnh hợp lệ nào để giao. Độ lệch đường cơ sở dịch thành `vertical-align` âm để ảnh
 * khớp dòng văn bản quanh nó ở kích thước hiển thị chuẩn.
 */
export function formulaImageAttributes(
  block: FormulaDisplayBlock,
  imageUrl: (blockId: string, fileRef: string) => string,
): FormulaImageAttributes | null {
  if (block.renderState !== 'ready' || block.image === null) {
    return null;
  }
  return {
    src: imageUrl(block.blockId, block.image.fileRef),
    width: block.image.width,
    height: block.image.height,
    style: { verticalAlign: `${-block.image.baselineOffset}px` },
    alt: block.description ?? 'Công thức toán học',
  };
}
