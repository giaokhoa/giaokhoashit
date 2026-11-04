package com.giaokhoa.server.files;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.giaokhoa.server.access.AccessException;

/** Vượt giới hạn multipart của servlet là lỗi dữ liệu của client, ánh xạ về cùng thân lỗi dùng chung. */
@RestControllerAdvice
public class FileUploadExceptionHandler {

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<Map<String, String>> handle(MaxUploadSizeExceededException exception) {
		AccessException mapped = AccessException.validation("Tệp vượt quá dung lượng tối đa cho phép");
		return ResponseEntity.status(mapped.status())
				.body(Map.of("code", mapped.code(), "message", mapped.getMessage()));
	}

}
