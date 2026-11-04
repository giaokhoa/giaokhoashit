package com.giaokhoa.server.attachment;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// Đường tệp đính kèm của một phiên bản câu hỏi. Mọi đường đều qua xác thực Bearer như các endpoint khác;
// đường đọc trả byte kèm Cache-Control riêng tư không lưu đệm, không có đường URL công khai hay URL ký nào
// và access token không bao giờ nằm trong URL.
@RestController
@RequestMapping("/api/questions/{questionId}/versions/{versionId}/attachments")
public class AttachmentController {

	private final AttachmentService service;

	public AttachmentController(AttachmentService service) {
		this.service = service;
	}

	@PostMapping("/sessions")
	public AttachmentService.SessionView openSession(@PathVariable UUID questionId, @PathVariable UUID versionId,
			@RequestBody AttachmentService.OpenSessionRequest request) {
		return this.service.openSession(questionId, versionId, request);
	}

	@PostMapping(value = "/sessions/{sessionId}/content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public AttachmentService.SessionView uploadContent(@PathVariable UUID questionId, @PathVariable UUID versionId,
			@PathVariable UUID sessionId, @RequestParam("file") MultipartFile file) {
		return this.service.uploadContent(questionId, versionId, sessionId, file);
	}

	@GetMapping("/sessions/{sessionId}/preview")
	public ResponseEntity<byte[]> previewContent(@PathVariable UUID questionId, @PathVariable UUID versionId,
			@PathVariable UUID sessionId) {
		return content(this.service.previewContent(questionId, versionId, sessionId));
	}

	@PostMapping("/sessions/{sessionId}/complete")
	public AttachmentService.LinkView complete(@PathVariable UUID questionId, @PathVariable UUID versionId,
			@PathVariable UUID sessionId,
			@RequestBody(required = false) AttachmentService.CompleteRequest request) {
		return this.service.complete(questionId, versionId, sessionId, request);
	}

	@GetMapping
	public List<AttachmentService.LinkView> listLinks(@PathVariable UUID questionId, @PathVariable UUID versionId) {
		return this.service.listLinks(questionId, versionId);
	}

	@GetMapping("/{linkId}/content")
	public ResponseEntity<byte[]> readContent(@PathVariable UUID questionId, @PathVariable UUID versionId,
			@PathVariable UUID linkId) {
		return content(this.service.readContent(questionId, versionId, linkId));
	}

	private ResponseEntity<byte[]> content(AttachmentService.Content content) {
		String fileName = content.displayName().replaceAll("[\"\\r\\n]", "_");
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(content.contentType()))
				.cacheControl(CacheControl.noStore().cachePrivate())
				.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
				.body(content.bytes());
	}

}
