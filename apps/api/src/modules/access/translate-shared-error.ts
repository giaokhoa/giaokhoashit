import { BadRequestException, NotFoundException } from '@nestjs/common';
import { SharedDomainError } from '@lms/enrol';

/**
 * Cầu dịch lỗi giữa lõi dùng chung `@lms/enrol` và bề mặt HTTP của API.
 *
 * Các lõi ở tầng dữ liệu dùng chung ném `SharedDomainError` mang mã kỹ thuật + thông điệp tiếng Anh (package không phụ
 * thuộc Nest). Bề mặt API từ trước tới nay trả các lỗi đó dưới dạng exception HTTP của Nest mang ĐÚNG mã và thông điệp
 * trong thân ProblemDetails — hàm dịch này giữ nguyên ánh xạ đó để hành vi quan sát được từ ngoài (status, mã, thông
 * điệp) không đổi sau khi trích xuất: các mã "không tìm thấy" (`access.role_not_found`, `access.context_not_found`,
 * `course.context_missing`) → 404, mọi mã nghiệp vụ còn lại → 400, như các service trước đây tự ném.
 */

const NOT_FOUND_CODES = new Set(['access.role_not_found', 'access.context_not_found', 'course.context_missing']);

export function translateSharedDomainError(err: unknown): unknown {
  if (err instanceof SharedDomainError) {
    const body = { message: err.message, code: err.code };
    return NOT_FOUND_CODES.has(err.code) ? new NotFoundException(body) : new BadRequestException(body);
  }
  return err;
}

export async function delegateToSharedCore<T>(fn: () => Promise<T>): Promise<T> {
  try {
    return await fn();
  } catch (err) {
    throw translateSharedDomainError(err);
  }
}
