package com.giaokhoa.server.grading;

import org.springframework.http.HttpStatus;

/**
 * Lỗi nghiệp vụ của khối chấm điểm, mang đúng thân lỗi thống nhất {@code {code, message}}: tầng web hiển thị 403
 * và 404 bằng một thông báo gộp nên hai mã này không bao giờ kèm dữ kiện của tài nguyên bị từ chối.
 */
public final class GradingErrors {

	private GradingErrors() {
	}

	public static GradingException forbidden() {
		return new GradingException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN",
				"Không có quyền thực hiện thao tác này trong ngữ cảnh hiện tại");
	}

	public static GradingException notFound() {
		return new GradingException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Không tìm thấy tài nguyên");
	}

	public static GradingException validation(String message) {
		return new GradingException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", message);
	}

	public static GradingException conflict(String message) {
		return new GradingException(HttpStatus.CONFLICT, "STATE_CONFLICT", message);
	}

	public static final class GradingException extends RuntimeException {

		private final HttpStatus status;

		private final String code;

		private GradingException(HttpStatus status, String code, String message) {
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
