package com.giaokhoa.server.grading;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Controller này chỉ giữ hai thao tác ghi riêng của lát chấm. Hai đường đọc dùng chung với lát giao/làm bài
// (bảng bài nộp của một lần giao và đọc một lượt làm) có đúng một handler duy nhất ở controller của lát
// giao/làm bài; phần hình chiếu và phần điểm của lát chấm được tầng dịch vụ hợp nhất tại đó.
@RestController
public class GradingController {

	private final GradingService grading;

	public GradingController(GradingService grading) {
		this.grading = grading;
	}

	@PostMapping("/api/attempts/{id}/grade")
	public GradingService.GradeResultView grade(@PathVariable UUID id,
			@RequestBody GradingService.GradeRequest request) {
		return this.grading.grade(id, request);
	}

	@PostMapping("/api/assignments/{id}/publish-results")
	public GradingService.PublicationView publishResults(@PathVariable UUID id) {
		return this.grading.publish(id);
	}

	@ExceptionHandler(GradingErrors.GradingException.class)
	public ResponseEntity<Map<String, String>> gradingError(GradingErrors.GradingException ex) {
		return ResponseEntity.status(ex.status()).body(Map.of("code", ex.code(), "message", ex.getMessage()));
	}

}
