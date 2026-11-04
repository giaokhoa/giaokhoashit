package com.giaokhoa.server.access;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Ánh xạ {@link AccessException} thành thân lỗi dùng chung {@code {code, message}} cho các endpoint của lõi. */
@RestControllerAdvice
public class AccessExceptionHandler {

	@ExceptionHandler(AccessException.class)
	ResponseEntity<Map<String, String>> handle(AccessException exception) {
		return ResponseEntity.status(exception.status())
				.body(Map.of("code", exception.code(), "message", exception.getMessage()));
	}

}
