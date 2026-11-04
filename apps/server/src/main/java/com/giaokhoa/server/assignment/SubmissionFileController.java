package com.giaokhoa.server.assignment;

import java.util.List;
import java.util.UUID;

import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bề mặt tệp bài làm của một lượt làm: thân request chỉ mang định danh phiên tải lên của nền tệp; danh tính,
 * Organization của trang và khoá phân vùng luôn lấy từ {@link TenantContext} đã kiểm chứng, và byte của tệp
 * chỉ đi qua đường đọc có kiểm quyền tại thời điểm truy cập, không qua URL công khai hay ký sẵn.
 */
@RestController
@RequestMapping("/api")
public class SubmissionFileController {

	private final SubmissionFileService submissionFiles;

	public SubmissionFileController(SubmissionFileService submissionFiles) {
		this.submissionFiles = submissionFiles;
	}

	public record AttachFileRequest(UUID uploadSessionId) {
	}

	@PostMapping("/attempts/{id}/files")
	public SubmissionFileService.FileView attachFile(@PathVariable UUID id,
			@RequestBody AttachFileRequest request) {
		return this.submissionFiles.attach(context(), id, request.uploadSessionId());
	}

	@GetMapping("/attempts/{id}/files")
	public List<SubmissionFileService.FileView> listFiles(@PathVariable UUID id) {
		return this.submissionFiles.list(context(), id);
	}

	@GetMapping("/attempts/{id}/files/{fileId}/content")
	public ResponseEntity<byte[]> fileContent(@PathVariable UUID id, @PathVariable UUID fileId) {
		SubmissionFileService.FileContent content = this.submissionFiles.content(context(), id, fileId);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(content.contentType()))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(content.displayName()).build().toString())
				.body(content.bytes());
	}

	@DeleteMapping("/attempts/{id}/files/{fileId}")
	public List<SubmissionFileService.FileView> detachFile(@PathVariable UUID id, @PathVariable UUID fileId) {
		return this.submissionFiles.detach(context(), id, fileId);
	}

	private TenantContext context() {
		return TenantContexts.current();
	}

}
