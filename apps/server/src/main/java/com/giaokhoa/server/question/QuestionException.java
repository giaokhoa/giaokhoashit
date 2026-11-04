package com.giaokhoa.server.question;

import org.springframework.http.HttpStatus;

// Lỗi nghiệp vụ của feat câu hỏi, mang đúng mã và trạng thái HTTP của hợp đồng lỗi dùng chung: thân đáp ứng lỗi
// có dạng {code, message} và đáp ứng bị từ chối không kèm bất kỳ dữ kiện nào của tài nguyên.
public class QuestionException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	private QuestionException(HttpStatus status, String code, String message) {
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

	public static QuestionException notFound() {
		return new QuestionException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
				"Tài nguyên không tồn tại trong phạm vi của bạn.");
	}

	public static QuestionException forbidden() {
		return new QuestionException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN",
				"Bạn không có quyền thực hiện hành động này trên tài nguyên.");
	}

	public static QuestionException locked() {
		return new QuestionException(HttpStatus.CONFLICT, "VERSION_LOCKED",
				"Phiên bản đã khoá, không sửa được; hãy tạo phiên bản nháp mới.");
	}

	public static QuestionException versionConflict() {
		return new QuestionException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
				"Phiên bản đã bị sửa bởi một lượt khác; hãy đọc lại trước khi sửa.");
	}

	public static QuestionException stateConflict(String message) {
		return new QuestionException(HttpStatus.CONFLICT, "STATE_CONFLICT", message);
	}

	public static QuestionException validation(String message) {
		return new QuestionException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
	}

}
