package com.giaokhoa.server.files;

import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.giaokhoa.server.files.FileService.AttachBlobRequest;
import com.giaokhoa.server.files.FileService.AttachRequest;
import com.giaokhoa.server.files.FileService.CreateSessionRequest;
import com.giaokhoa.server.files.FileService.FileContent;
import com.giaokhoa.server.files.FileService.LinkView;
import com.giaokhoa.server.files.FileService.SessionView;

/**
 * Bề mặt HTTP của nền tệp: xin phiên tải lên, nhận byte vào phiên, gắn tệp vào bản nháp, gắn một blob đã có
 * bằng định danh, đọc tệp qua liên kết và cách ly blob. Không đường nào phục vụ tệp qua URL công khai hay khoá
 * lưu trữ; đường đọc luôn đi qua kiểm quyền tại thời điểm truy cập và trả byte với cache riêng tư, không lưu.
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

	private final FileService service;

	public FileController(FileService service) {
		this.service = service;
	}

	@PostMapping("/upload-sessions")
	public SessionView createSession(@RequestBody CreateSessionRequest request) {
		return this.service.createSession(request);
	}

	@PutMapping(value = "/upload-sessions/{sessionId}/content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public SessionView uploadContent(@PathVariable UUID sessionId, @RequestParam("file") MultipartFile file) {
		return this.service.uploadContent(sessionId, file);
	}

	@PostMapping("/upload-sessions/{sessionId}/attach")
	public LinkView attach(@PathVariable UUID sessionId, @RequestBody(required = false) AttachRequest request) {
		return this.service.attach(sessionId, request);
	}

	@PostMapping("/links")
	public LinkView attachBlob(@RequestBody AttachBlobRequest request) {
		return this.service.attachBlob(request);
	}

	@GetMapping("/resources/{resourceId}/versions/{versionId}/links")
	public List<LinkView> listLinks(@PathVariable UUID resourceId, @PathVariable UUID versionId) {
		return this.service.listLinks(resourceId, versionId);
	}

	@GetMapping("/links/{linkId}/content")
	public ResponseEntity<byte[]> readContent(@PathVariable UUID linkId) {
		FileContent content = this.service.readContent(linkId);
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType()))
				.cacheControl(CacheControl.noStore().cachePrivate())
				.header("Content-Disposition", "inline; filename=\"" + content.displayName() + "\"")
				.body(content.bytes());
	}

	@PostMapping("/blobs/{blobId}/quarantine")
	public Map<String, String> quarantine(@PathVariable UUID blobId) {
		this.service.quarantine(blobId);
		return Map.of("status", "quarantined");
	}

}
