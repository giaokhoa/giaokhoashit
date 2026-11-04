/**
 * Lỗi có cấu trúc của các lời gọi `apps/server` và phép ánh xạ lỗi sang thông điệp hiển thị (issue #102).
 *
 * Server trả lỗi theo thân `{code, message}` kèm status HTTP. Tầng hiển thị không hiện nguyên `message` của
 * server cho mọi status: theo hợp đồng web↔server của bản phát hành 07/11, 403 và 404 phải hiển thị bằng CÙNG
 * một thông điệp để không lộ tài nguyên tồn tại hay không; 401 dẫn về luồng đăng nhập; 409 yêu cầu tải lại bản
 * mới nhất thay vì ghi đè; 503 là lỗi tạm thời do chưa xác minh được tư cách thành viên, không được suy diễn
 * thành đã bị gỡ khỏi trung tâm. Chỉ 422 (dữ kiện không hợp lệ) hiện `message` của server vì đó là phản hồi về
 * chính dữ kiện người dùng vừa nhập.
 */

/**
 * Lỗi của một lời gọi `apps/server`: giữ status HTTP và `code` máy đọc của server để tầng hiển thị và test phân
 * biệt được từng tình huống, trong khi `message` là thông điệp gốc của server.
 */
export class ClassroomApiError extends Error {
  /** Status HTTP của đáp ứng lỗi. */
  readonly status: number;
  /** Mã lỗi máy đọc của server (`RESOURCE_NOT_FOUND`, `VERSION_CONFLICT`...), rỗng khi thân lỗi không đọc được. */
  readonly code: string | null;

  /**
   * @param status - Status HTTP của đáp ứng.
   * @param code - Mã lỗi của server, hoặc `null` khi không có.
   * @param message - Thông điệp gốc của server hoặc thông điệp dự phòng theo status.
   */
  constructor(status: number, code: string | null, message: string) {
    super(message);
    this.name = 'ClassroomApiError';
    this.status = status;
    this.code = code;
  }
}

/**
 * Ánh xạ một lỗi bất kỳ sang thông điệp hiển thị cho người dùng theo hợp đồng lỗi của bản phát hành.
 *
 * @param error - Lỗi bắt được từ tầng gọi API hoặc React Query.
 * @returns Thông điệp tiếng Việt sẵn sàng hiển thị; 403 và 404 luôn cho cùng một thông điệp chống liệt kê.
 */
export function displayErrorMessage(error: unknown): string {
  if (!(error instanceof ClassroomApiError)) {
    return 'Đã xảy ra lỗi. Hãy thử lại.';
  }
  switch (error.status) {
    case 401:
      return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để tiếp tục.';
    case 403:
    case 404:
      return 'Không tồn tại hoặc không thuộc phạm vi của bạn.';
    case 409:
      return error.message
        ? `${error.message} Hãy tải lại trang để xem bản mới nhất trước khi làm tiếp.`
        : 'Nội dung đã thay đổi ở nơi khác. Hãy tải lại trang để xem bản mới nhất trước khi làm tiếp.';
    case 422:
      return error.message || 'Dữ kiện chưa hợp lệ. Hãy kiểm tra lại các trường đã nhập.';
    case 503:
      return 'Hệ thống tạm thời chưa xác minh được tư cách của bạn. Hãy thử lại sau ít phút.';
    default:
      return 'Đã xảy ra lỗi. Hãy thử lại.';
  }
}
