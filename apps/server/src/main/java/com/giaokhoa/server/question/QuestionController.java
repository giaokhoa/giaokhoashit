package com.giaokhoa.server.question;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/questions")
public class QuestionController {

	private final QuestionService service;

	public QuestionController(QuestionService service) {
		this.service = service;
	}

	@PostMapping
	public QuestionService.QuestionView createQuestion(@AuthenticationPrincipal Jwt jwt,
			@RequestBody QuestionService.CreateQuestionRequest request) {
		return this.service.createQuestion(jwt, request);
	}

	@GetMapping("/{questionId}")
	public QuestionService.QuestionView getQuestion(@PathVariable UUID questionId) {
		return this.service.getQuestion(questionId);
	}

	@PutMapping("/{questionId}/versions/{versionId}")
	public QuestionService.VersionView updateVersion(@PathVariable UUID questionId, @PathVariable UUID versionId,
			@RequestBody QuestionService.UpdateVersionRequest request) {
		return this.service.updateVersion(questionId, versionId, request);
	}

	@PostMapping("/{questionId}/versions")
	public QuestionService.VersionView newVersion(@PathVariable UUID questionId,
			@RequestBody QuestionService.NewVersionRequest request) {
		return this.service.newVersion(questionId, request);
	}

	@PostMapping("/{questionId}/versions/{versionId}/publish")
	public QuestionService.VersionView publish(@PathVariable UUID questionId, @PathVariable UUID versionId) {
		return this.service.publish(questionId, versionId);
	}

	@PostMapping("/{questionId}/versions/{versionId}/copies")
	public QuestionService.QuestionView copy(@PathVariable UUID questionId, @PathVariable UUID versionId,
			@RequestBody(required = false) QuestionService.CopyRequest request) {
		return this.service.copy(questionId, versionId, request);
	}

}
