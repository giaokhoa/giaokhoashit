package com.giaokhoa.server.assignment;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Dựng thân lỗi `{code, message}` cho các endpoint của khối giao/làm bài theo quy ước mã lỗi dùng chung, qua
 * cơ chế `@ExceptionHandler` của Spring MVC giới hạn đúng controller của khối này.
 */
@RestControllerAdvice(assignableTypes = AssignmentController.class)
public class AssignmentExceptionHandler {

	@ExceptionHandler(AssignmentException.class)
	public ResponseEntity<Map<String, String>> handle(AssignmentException ex) {
		return ResponseEntity.status(ex.status()).body(Map.of("code", ex.code(), "message", ex.getMessage()));
	}

}
