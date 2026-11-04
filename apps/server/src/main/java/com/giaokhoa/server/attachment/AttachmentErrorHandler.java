package com.giaokhoa.server.attachment;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

// Không giới hạn theo package của controller: AttachmentException còn được cổng phát hành của lát này ném
// ra từ trong endpoint phát hành của feat câu hỏi, nên handler phải áp cho mọi controller.
@RestControllerAdvice
public class AttachmentErrorHandler {

	public record ErrorBody(String code, String message) {
	}

	@ExceptionHandler(AttachmentException.class)
	ResponseEntity<ErrorBody> attachment(AttachmentException ex) {
		return ResponseEntity.status(ex.status()).body(new ErrorBody(ex.code(), ex.getMessage()));
	}

	// Vượt trần multipart ở tầng servlet (cấu hình spring.servlet.multipart cao hơn giới hạn nghiệp vụ một
	// khoảng nhỏ để bước kiểm tra nội dung thật vẫn tự quyết phần lớn ca từ chối) vẫn là lỗi kiểm tra hợp lệ
	// của hợp đồng, không phải lỗi máy chủ.
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ErrorBody> maxUploadSize(MaxUploadSizeExceededException ex) {
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
				.body(new ErrorBody("VALIDATION_FAILED", "Tệp vượt quá giới hạn dung lượng cho phép."));
	}

}
