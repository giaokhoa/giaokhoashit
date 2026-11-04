package com.giaokhoa.server.math;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/questions/{questionId}/versions/{versionId}/formula-blocks")
public class MathFormulaController {

	private final MathFormulaService service;

	public MathFormulaController(MathFormulaService service) {
		this.service = service;
	}

	@GetMapping
	public List<MathFormulaService.DisplayBlockView> listDisplay(@PathVariable UUID questionId,
			@PathVariable UUID versionId) {
		return this.service.listDisplay(questionId, versionId);
	}

	@GetMapping("/draft")
	public List<MathFormulaService.DraftBlockView> listDraft(@PathVariable UUID questionId,
			@PathVariable UUID versionId) {
		return this.service.listDraft(questionId, versionId);
	}

	@PutMapping("/{blockId}")
	public MathFormulaService.DraftBlockView putSource(@PathVariable UUID questionId,
			@PathVariable UUID versionId, @PathVariable String blockId,
			@RequestBody MathFormulaService.PutSourceRequest request) {
		return this.service.putSource(questionId, versionId, blockId, request);
	}

	@PostMapping("/{blockId}/author-source")
	public MathFormulaService.DraftBlockView authorSource(@PathVariable UUID questionId,
			@PathVariable UUID versionId, @PathVariable String blockId,
			@RequestBody MathFormulaService.AuthorSourceRequest request) {
		return this.service.authorSource(questionId, versionId, blockId, request);
	}

	@PostMapping("/{blockId}/render")
	public MathFormulaService.DraftBlockView retryRender(@PathVariable UUID questionId,
			@PathVariable UUID versionId, @PathVariable String blockId) {
		return this.service.retryRender(questionId, versionId, blockId);
	}

	// Đường đọc ảnh duy nhất của lát này: qua API có xác thực, kiểm quyền tại thời điểm truy cập và cấm cache
	// trung gian giữ bản render của người có quyền để phục vụ lại cho người khác.
	@GetMapping("/{blockId}/image")
	public ResponseEntity<byte[]> formulaImage(@PathVariable UUID questionId, @PathVariable UUID versionId,
			@PathVariable String blockId) {
		return ResponseEntity.ok()
			.contentType(MediaType.IMAGE_PNG)
			.cacheControl(CacheControl.noStore().cachePrivate())
			.body(this.service.formulaImage(questionId, versionId, blockId));
	}

}
