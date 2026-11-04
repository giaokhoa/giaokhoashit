/**
 * Spec của bề mặt xem công thức (phần web của NT-16a).
 *
 * Hành vi được kiểm chứng là hợp đồng giao ảnh: thuộc tính ảnh chỉ sinh khi bản render đã sẵn sàng, kích
 * thước và độ lệch đường cơ sở lấy đúng số đo của server, và văn bản thay thế là mô tả ngôn ngữ tự nhiên —
 * dữ kiện hiển thị không có đường nào mang nguồn LaTeX.
 */
import { describe, expect, it } from 'vitest';
import { formulaImageAttributes, type FormulaDisplayBlock } from './formula-image';

const readyBlock: FormulaDisplayBlock = {
  blockId: 'f1',
  kind: 'formula',
  form: 'render-only',
  renderState: 'ready',
  image: { fileRef: 'render-1', width: 132, height: 40, baselineOffset: 6 },
  description: 'phân số x cộng một phần hai',
};

describe('formulaImageAttributes (hợp đồng giao ảnh công thức)', () => {
  it('sinh thuộc tính ảnh với đúng số đo của server và mô tả làm văn bản thay thế', () => {
    const attributes = formulaImageAttributes(readyBlock, (blockId, fileRef) => `/api/blocks/${blockId}/image?ref=${fileRef}`);
    expect(attributes).toEqual({
      src: '/api/blocks/f1/image?ref=render-1',
      width: 132,
      height: 40,
      style: { verticalAlign: '-6px' },
      alt: 'phân số x cộng một phần hai',
    });
  });

  it('khối chưa sẵn sàng hoặc render lỗi thì không sinh ảnh nào', () => {
    const url = (blockId: string) => `/api/blocks/${blockId}/image`;
    expect(formulaImageAttributes({ ...readyBlock, renderState: 'pending' }, url)).toBeNull();
    expect(formulaImageAttributes({ ...readyBlock, renderState: 'failed', image: null }, url)).toBeNull();
  });

  it('thiếu mô tả thì văn bản thay thế là nhãn trung tính, không suy từ bất kỳ dữ kiện nào khác của khối', () => {
    const attributes = formulaImageAttributes({ ...readyBlock, description: null }, (blockId) => `/api/blocks/${blockId}/image`);
    expect(attributes?.alt).toBe('Công thức toán học');
  });
});
