package com.giaokhoa.server.assignment;

import org.springframework.http.HttpStatus;

/**
 * Lỗi nghiệp vụ của khối giao/làm bài mang đúng cặp (HTTP status, mã lỗi) của quy ước mã lỗi dùng chung; thân
 * đáp ứng lỗi do {@link AssignmentExceptionHandler} dựng thành dạng `{code, message}` và không bao giờ kèm dữ
 * kiện nào của tài nguyên ngoài phạm vi của người gọi.
 */
public class AssignmentException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	public AssignmentException(HttpStatus status, String code, String message) {
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

	public static AssignmentException notFound(String message) {
		return new AssignmentException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
	}

	public static AssignmentException forbidden(String message) {
		return new AssignmentException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", message);
	}

	public static AssignmentException stateConflict(String message) {
		return new AssignmentException(HttpStatus.CONFLICT, "STATE_CONFLICT", message);
	}

	public static AssignmentException validation(String message) {
		return new AssignmentException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
	}

	public static AssignmentException unverifiable() {
		return new AssignmentException(HttpStatus.SERVICE_UNAVAILABLE, "MEMBERSHIP_UNVERIFIABLE",
				"Không xác minh được ngữ cảnh trung tâm của trang.");
	}

}
