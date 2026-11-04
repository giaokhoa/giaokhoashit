package com.giaokhoa.server.question;

import java.util.regex.Pattern;

/**
 * Nội dung của một phiên bản không được phụ thuộc vào tệp hay ảnh ở URL ngoài hệ thống: tệp cần dùng phải đi
 * qua đường tiếp nhận có kiểm soát của nền tệp, để nội dung không thay đổi sau khi đã giao và máy chủ không
 * phải tự tải URL do người dùng định.
 */
final class ExternalContentReferences {

	private static final Pattern EXTERNAL_REFERENCE = Pattern
			.compile("(?i)(<img\\b|!\\[[^\\]]*\\]\\(\\s*https?://|src\\s*=\\s*[\"']\\s*https?://)");

	private ExternalContentReferences() {
	}

	static void rejectIfPresent(String text) {
		if (text != null && EXTERNAL_REFERENCE.matcher(text).find()) {
			throw QuestionException
					.validation("Nội dung chứa tham chiếu tệp ở URL ngoài hệ thống; hãy tải tệp qua nền tệp");
		}
	}

}
