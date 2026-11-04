/**
 * Hằng số và phép kiểm sớm phía client cho tệp đính kèm (issue #119).
 *
 * Các giới hạn ở đây chỉ để báo lỗi sớm cho người dùng trước khi tải lên; server là phía quyết định cuối
 * cùng bằng kiểm tra nội dung thật của tệp, và một tệp qua được phép kiểm này vẫn có thể bị server từ chối.
 */

/** Trần dung lượng một ảnh đính kèm: 5 MiB, khớp giới hạn mặc định của hợp đồng tệp phía server. */
export const ATTACHMENT_MAX_BYTES = 5 * 1024 * 1024;

/** Giá trị `accept` của ô chọn tệp: đúng ba định dạng ảnh của hợp đồng tệp. */
export const ATTACHMENT_ACCEPT = 'image/png,image/jpeg,image/webp';

const ATTACHMENT_MIME_TYPES: ReadonlySet<string> = new Set(['image/png', 'image/jpeg', 'image/webp']);

/**
 * Kiểm tra sớm một tệp người dùng vừa chọn: dung lượng và họ MIME do trình duyệt khai.
 *
 * @param file - Tệp cần kiểm tra (chỉ cần `size` và `type`).
 * @returns Thông điệp lỗi tiếng Việt khi tệp chắc chắn bị từ chối, hoặc `null` khi tệp qua được phép kiểm
 * sớm (kèm điều kiện loại tệp có khai — một số trình duyệt để trống `type` với WebP, khi đó để server quyết
 * bằng nội dung thật).
 */
export function earlyAttachmentError(file: { size: number; type: string }): string | null {
  if (file.size > ATTACHMENT_MAX_BYTES) {
    return 'Ảnh vượt quá 5 MiB. Hãy chọn ảnh nhỏ hơn.';
  }
  if (file.type !== '' && !ATTACHMENT_MIME_TYPES.has(file.type)) {
    return 'Chỉ nhận ảnh PNG, JPEG hoặc WebP.';
  }
  return null;
}
