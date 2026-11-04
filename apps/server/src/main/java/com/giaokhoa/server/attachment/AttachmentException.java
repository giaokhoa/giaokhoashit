package com.giaokhoa.server.attachment;

import org.springframework.http.HttpStatus;

// Lỗi nghiệp vụ của lát tệp đính kèm, cùng khuôn lỗi của feat câu hỏi: thân đáp ứng {code, message} và đáp
// ứng bị từ chối không kèm bất kỳ dữ kiện nào của tài nguyên hay của tệp.
public class AttachmentException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	private AttachmentException(HttpStatus status, String code, String message) {
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

	public static AttachmentException notFound() {
		return new AttachmentException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
				"Tài nguyên không tồn tại trong phạm vi của bạn.");
	}

	public static AttachmentException forbidden() {
		return new AttachmentException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN",
				"Bạn không có quyền thực hiện hành động này trên tài nguyên.");
	}

	public static AttachmentException validation(String message) {
		return new AttachmentException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
	}

	public static AttachmentException fileRejected(String message) {
		return new AttachmentException(HttpStatus.UNPROCESSABLE_CONTENT, "FILE_REJECTED", message);
	}

	public static AttachmentException conflict(String code, String message) {
		return new AttachmentException(HttpStatus.CONFLICT, code, message);
	}

}
