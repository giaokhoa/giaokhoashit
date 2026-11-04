package com.giaokhoa.server.organization;

import org.springframework.http.HttpStatus;

/**
 * Lỗi nghiệp vụ của khối tổ chức dạy học, mang đúng thân lỗi thống nhất {@code {code, message}}: tầng web hiển thị
 * 403 và 404 bằng một thông báo gộp nên hai mã này không bao giờ kèm dữ liệu của tài nguyên bị từ chối.
 */
public final class OrganizationErrors {

	private OrganizationErrors() {
	}

	public static OrgException forbidden() {
		return new OrgException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN",
				"Không có quyền thực hiện thao tác này trong ngữ cảnh hiện tại");
	}

	public static OrgException notFound() {
		return new OrgException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Không tìm thấy tài nguyên");
	}

	public static OrgException validation(String message) {
		return new OrgException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", message);
	}

	public static OrgException conflict(String message) {
		return new OrgException(HttpStatus.CONFLICT, "STATE_CONFLICT", message);
	}

	public static OrgException membershipUnverifiable() {
		return new OrgException(HttpStatus.SERVICE_UNAVAILABLE, "MEMBERSHIP_UNVERIFIABLE",
				"Không xác minh được tư cách thành viên tại hệ thống danh tính");
	}

	public static final class OrgException extends RuntimeException {

		private final HttpStatus status;

		private final String code;

		private OrgException(HttpStatus status, String code, String message) {
			super(message);
			this.status = status;
			this.code = code;
		}

		public HttpStatus status() {
			return this.status;
		}

		public String code() {
			return this.code;
		}

	}

}
