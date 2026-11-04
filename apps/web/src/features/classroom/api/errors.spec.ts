/**
 * Spec của phép ánh xạ lỗi sang thông điệp hiển thị (issue #102).
 *
 * Hành vi được kiểm chứng là hợp đồng hiển thị lỗi của bản phát hành: 403 và 404 cho cùng một thông điệp chống
 * liệt kê; 409 luôn kèm yêu cầu tải lại; 503 là lỗi tạm thời; 422 hiện thông điệp của server; 401 dẫn về đăng
 * nhập; lỗi lạ rơi về thông điệp chung.
 */
import { describe, expect, it } from 'vitest';
import { ClassroomApiError, displayErrorMessage } from './errors';

describe('displayErrorMessage (hợp đồng hiển thị lỗi của bản phát hành)', () => {
  it('403 và 404 hiển thị cùng một thông điệp để không lộ tài nguyên tồn tại hay không', () => {
    const forbidden = displayErrorMessage(new ClassroomApiError(403, 'RESOURCE_FORBIDDEN', 'Không có quyền.'));
    const notFound = displayErrorMessage(new ClassroomApiError(404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy.'));
    expect(forbidden).toBe(notFound);
    expect(forbidden).toBe('Không tồn tại hoặc không thuộc phạm vi của bạn.');
  });

  it('401 báo phiên hết hạn và yêu cầu đăng nhập lại', () => {
    expect(displayErrorMessage(new ClassroomApiError(401, 'UNAUTHENTICATED', ''))).toContain('đăng nhập lại');
  });

  it('409 giữ thông điệp xung đột của server và luôn kèm yêu cầu tải lại bản mới nhất', () => {
    const message = displayErrorMessage(new ClassroomApiError(409, 'VERSION_CONFLICT', 'Phiên bản đã bị sửa.'));
    expect(message).toContain('Phiên bản đã bị sửa.');
    expect(message).toContain('tải lại');
    expect(displayErrorMessage(new ClassroomApiError(409, 'STATE_CONFLICT', ''))).toContain('tải lại');
  });

  it('422 hiển thị thông điệp kiểm tra dữ kiện của server', () => {
    expect(displayErrorMessage(new ClassroomApiError(422, 'VALIDATION_FAILED', 'Hạn nộp phải sau lúc mở.'))).toBe(
      'Hạn nộp phải sau lúc mở.',
    );
  });

  it('503 hiển thị lỗi tạm thời và không suy diễn người dùng đã bị gỡ khỏi trung tâm', () => {
    const message = displayErrorMessage(new ClassroomApiError(503, 'MEMBERSHIP_UNVERIFIABLE', ''));
    expect(message).toContain('tạm thời');
    expect(message).toContain('thử lại');
  });

  it('lỗi không có cấu trúc rơi về thông điệp chung', () => {
    expect(displayErrorMessage(new Error('boom'))).toBe('Đã xảy ra lỗi. Hãy thử lại.');
    expect(displayErrorMessage(new ClassroomApiError(500, null, 'Server error'))).toBe('Đã xảy ra lỗi. Hãy thử lại.');
  });
});
