/**
 * Spec của phép kiểm sớm phía client cho tệp đính kèm (issue #119): phép kiểm chỉ báo lỗi sớm cho các tệp
 * chắc chắn bị từ chối (vượt 5 MiB, ngoài ba họ ảnh), còn mọi tệp khác được để server quyết bằng nội dung
 * thật — gồm cả tệp WebP mà trình duyệt để trống `type`.
 */
import { describe, expect, it } from 'vitest';
import { ATTACHMENT_MAX_BYTES, earlyAttachmentError } from './attachments';

describe('phép kiểm sớm tệp đính kèm phía client', () => {
  it('tệp vượt 5 MiB bị báo lỗi sớm dù đúng họ ảnh', () => {
    expect(earlyAttachmentError({ size: ATTACHMENT_MAX_BYTES + 1, type: 'image/png' })).toContain('5 MiB');
  });

  it('tệp đúng trần 5 MiB và tệp thuộc ba họ ảnh qua được phép kiểm sớm', () => {
    expect(earlyAttachmentError({ size: ATTACHMENT_MAX_BYTES, type: 'image/png' })).toBeNull();
    expect(earlyAttachmentError({ size: 10, type: 'image/jpeg' })).toBeNull();
    expect(earlyAttachmentError({ size: 10, type: 'image/webp' })).toBeNull();
  });

  it('tệp ngoài ba họ ảnh bị báo lỗi sớm, tệp không khai loại thì để server quyết bằng nội dung thật', () => {
    expect(earlyAttachmentError({ size: 10, type: 'application/pdf' })).toContain('PNG, JPEG hoặc WebP');
    expect(earlyAttachmentError({ size: 10, type: '' })).toBeNull();
  });
});
