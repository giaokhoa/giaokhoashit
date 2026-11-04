package com.giaokhoa.server.question;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.giaokhoa.server.question")
public class QuestionErrorHandler {

	public record ErrorBody(String code, String message) {
	}

	@ExceptionHandler(QuestionException.class)
	ResponseEntity<ErrorBody> question(QuestionException ex) {
		return ResponseEntity.status(ex.status()).body(new ErrorBody(ex.code(), ex.getMessage()));
	}

	// Khoá lạc quan ở tầng JPA phát hiện ghi đè đồng thời lúc flush: ánh xạ về cùng mã VERSION_CONFLICT với
	// phép kiểm revision ở service, để client chỉ có một tín hiệu cho một tình huống.
	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ResponseEntity<ErrorBody> optimisticLock(ObjectOptimisticLockingFailureException ex) {
		QuestionException mapped = QuestionException.versionConflict();
		return ResponseEntity.status(mapped.status()).body(new ErrorBody(mapped.code(), mapped.getMessage()));
	}

	// Vi phạm ràng buộc duy nhất ở tầng database (ví dụ thêm trùng một phiên bản vào cùng ngân hàng khi hai
	// lượt chạy đồng thời) là xung đột trạng thái, không phải lỗi máy chủ.
	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ErrorBody> integrity(DataIntegrityViolationException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(new ErrorBody("STATE_CONFLICT", "Thao tác xung đột với trạng thái hiện tại của dữ kiện."));
	}

}
