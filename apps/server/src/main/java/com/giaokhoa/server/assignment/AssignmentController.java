package com.giaokhoa.server.assignment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bề mặt giao bài và làm bài: thân request chỉ mang dữ kiện đích của thao tác (phiên bản đề, lớp, mốc thời
 * gian, câu trả lời); danh tính, Organization của trang và khoá phân vùng luôn lấy từ {@link TenantContext} đã
 * kiểm chứng của khung tenancy, không bao giờ từ thân hay query của request.
 */
@RestController
@RequestMapping("/api")
public class AssignmentController {

	private final AssignmentService assignments;

	private final AttemptService attempts;

	public AssignmentController(AssignmentService assignments, AttemptService attempts) {
		this.assignments = assignments;
		this.attempts = attempts;
	}

	public record CreateAssignmentRequest(UUID examVersionId, UUID classId, OffsetDateTime opensAt,
			OffsetDateTime dueAt) {
	}

	public record UpdateDueAtRequest(OffsetDateTime dueAt) {
	}

	public record SaveAnswersRequest(List<AnswerInput> answers) {
	}

	public record AnswerInput(UUID examItemId, JsonNode answer) {
	}

	@PostMapping("/assignments")
	public AssignmentService.AssignmentView createAssignment(@RequestBody CreateAssignmentRequest request) {
		return this.assignments.create(context(), request.examVersionId(), request.classId(), request.opensAt(),
				request.dueAt());
	}

	@GetMapping("/assignments")
	public List<AssignmentService.AssignmentView> listAssignments() {
		return this.assignments.list(context());
	}

	@PatchMapping("/assignments/{id}")
	public AssignmentService.AssignmentView updateDueAt(@PathVariable UUID id,
			@RequestBody UpdateDueAtRequest request) {
		return this.assignments.updateDueAt(context(), id, request.dueAt());
	}

	@DeleteMapping("/assignments/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAssignment(@PathVariable UUID id) {
		this.assignments.delete(context(), id);
	}

	@PostMapping("/assignments/{id}/attempts")
	public AttemptService.AttemptView startOrResumeAttempt(@PathVariable UUID id) {
		return this.attempts.startOrResume(context(), id);
	}

	@PutMapping("/attempts/{id}/answers")
	public AttemptService.AttemptView saveAnswers(@PathVariable UUID id,
			@RequestBody SaveAnswersRequest request) {
		List<AttemptService.AnswerInput> inputs = request.answers() == null ? List.of()
				: request.answers().stream()
						.map(input -> new AttemptService.AnswerInput(input.examItemId(), input.answer()))
						.toList();
		return this.attempts.saveAnswers(context(), id, inputs);
	}

	@PostMapping("/attempts/{id}/submit")
	public AttemptService.SubmitView submitAttempt(@PathVariable UUID id) {
		return this.attempts.submit(context(), id);
	}

	@GetMapping("/attempts/{id}")
	public AttemptService.AttemptView getAttempt(@PathVariable UUID id) {
		return this.attempts.get(context(), id);
	}

	@GetMapping("/assignments/{id}/submissions")
	public List<AttemptService.SubmissionRow> submissions(@PathVariable UUID id) {
		return this.attempts.submissions(context(), id);
	}

	private TenantContext context() {
		return TenantContexts.current();
	}

}
