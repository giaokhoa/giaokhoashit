package com.giaokhoa.server.exam;

import org.springframework.http.HttpStatus;

/**
 * Lỗi nghiệp vụ của lát soạn đề: mang trạng thái HTTP và mã lỗi ổn định của bộ mã dùng chung để tầng xử lý lỗi
 * trả thân {@code {code, message}} thống nhất.
 */
public class ExamException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	public ExamException(HttpStatus status, String code, String message) {
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
