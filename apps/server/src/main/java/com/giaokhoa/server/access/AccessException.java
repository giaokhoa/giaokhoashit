package com.giaokhoa.server.access;

/** Lỗi của bề mặt lõi quyền, mang mã lỗi dùng chung để tầng web hiển thị thống nhất. */
public class AccessException extends RuntimeException {

	private final int status;

	private final String code;

	public AccessException(int status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public int status() {
		return this.status;
	}

	public String code() {
		return this.code;
	}

	public static AccessException forbidden(String message) {
		return new AccessException(403, "RESOURCE_FORBIDDEN", message);
	}

	public static AccessException notFound(String message) {
		return new AccessException(404, "RESOURCE_NOT_FOUND", message);
	}

	public static AccessException validation(String message) {
		return new AccessException(422, "VALIDATION_FAILED", message);
	}

	public static AccessException stateConflict(String message) {
		return new AccessException(409, "STATE_CONFLICT", message);
	}

}
