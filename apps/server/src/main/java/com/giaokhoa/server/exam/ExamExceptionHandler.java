package com.giaokhoa.server.exam;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Trả lỗi của lát soạn đề theo thân {@code {code, message}} dùng chung. Xung đột khoá lạc quan lọt qua lớp kiểm
 * tra tường minh trong service (hai giao dịch đua nhau thật) cũng ánh xạ về cùng mã {@code VERSION_CONFLICT}
 * thay vì lộ lỗi 500 của tầng ORM.
 */
@RestControllerAdvice
public class ExamExceptionHandler {

	@ExceptionHandler(ExamException.class)
	ResponseEntity<Map<String, String>> examException(ExamException ex) {
		return ResponseEntity.status(ex.status()).body(Map.of("code", ex.code(), "message", ex.getMessage()));
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ResponseEntity<Map<String, String>> optimisticLockingFailure(ObjectOptimisticLockingFailureException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(Map.of("code", "VERSION_CONFLICT",
					"message", "Phiên bản đề đã bị thay đổi bởi một lượt ghi khác; đọc lại trước khi ghi tiếp."));
	}

}
