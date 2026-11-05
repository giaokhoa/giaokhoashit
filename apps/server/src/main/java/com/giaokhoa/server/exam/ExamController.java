package com.giaokhoa.server.exam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.giaokhoa.server.tenancy.TenantContexts;

/**
 * Bề mặt HTTP của lát soạn đề. Mọi dữ kiện danh tính, Organization của trang và vai đều lấy từ {@code
 * TenantContext} đã xác minh của khung tenancy và từ chính token; thân request không bao giờ cung cấp các dữ
 * kiện đó.
 */
@RestController
@RequestMapping("/api/exams")
public class ExamController {

	public record CreateExamRequest(String title, String workspace, UUID collectionId) {
	}

	public record UpdateDraftRequest(String title, Long lockVersion, List<ExamService.ItemInput> items) {
	}

	public record CreateVersionRequest(UUID sourceVersionId) {
	}

	private final ExamService service;

	public ExamController(ExamService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<ExamService.ExamView> create(@AuthenticationPrincipal Jwt jwt,
			@RequestBody CreateExamRequest request) {
		ExamService.ExamView view = this.service.createExam(TenantContexts.current(), personalAuthor(jwt),
				request.title(), request.workspace(), request.collectionId());
		return ResponseEntity.status(HttpStatus.CREATED).body(view);
	}

	@GetMapping("/{id}")
	public ExamService.ExamView get(@PathVariable UUID id, @RequestParam(required = false) UUID versionId) {
		return this.service.getExam(TenantContexts.current(), id, versionId);
	}

	@PutMapping("/{id}/versions/{versionId}")
	public ExamService.ExamView updateDraft(@PathVariable UUID id, @PathVariable UUID versionId,
			@RequestBody UpdateDraftRequest request) {
		return this.service.updateDraft(TenantContexts.current(), id, versionId, request.title(),
				request.lockVersion(), request.items());
	}

	@PostMapping("/{id}/versions")
	public ResponseEntity<ExamService.ExamView> createVersion(@PathVariable UUID id,
			@RequestBody(required = false) CreateVersionRequest request) {
		UUID sourceVersionId = request == null ? null : request.sourceVersionId();
		ExamService.ExamView view = this.service.createVersion(TenantContexts.current(), id, sourceVersionId);
		return ResponseEntity.status(HttpStatus.CREATED).body(view);
	}

	@PostMapping("/{id}/versions/{versionId}/publish")
	public ExamService.ExamView publish(@PathVariable UUID id, @PathVariable UUID versionId) {
		return this.service.publish(TenantContexts.current(), id, versionId);
	}

	@PostMapping("/{id}/versions/{versionId}/copies")
	public ResponseEntity<ExamService.ExamView> copy(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@PathVariable UUID versionId) {
		ExamService.ExamView view = this.service.copyExam(TenantContexts.current(), personalAuthor(jwt), id,
				versionId);
		return ResponseEntity.status(HttpStatus.CREATED).body(view);
	}

	// personal-author là realm role ở cấp nền tảng, đọc tại realm_access của chính token đã xác minh — cùng
	// khuôn đọc vai nền tảng của khung tenancy, không suy ra từ membership hay vai theo Organization.
	private static boolean personalAuthor(Jwt jwt) {
		if (jwt.getClaim("realm_access") instanceof Map<?, ?> realmAccess
				&& realmAccess.get("roles") instanceof List<?> roles) {
			return roles.contains("personal-author");
		}
		return false;
	}

}
