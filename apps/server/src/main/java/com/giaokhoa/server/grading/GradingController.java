package com.giaokhoa.server.grading;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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

	@GetMapping("/api/assignments/{id}/submissions")
	public List<GradingService.SubmissionRowView> submissions(@PathVariable UUID id) {
		return this.grading.submissions(id);
	}

	@GetMapping("/api/attempts/{id}")
	public Object attempt(@PathVariable UUID id) {
		return this.grading.attemptView(id);
	}

	@ExceptionHandler(GradingErrors.GradingException.class)
	public ResponseEntity<Map<String, String>> gradingError(GradingErrors.GradingException ex) {
		return ResponseEntity.status(ex.status()).body(Map.of("code", ex.code(), "message", ex.getMessage()));
	}

}
