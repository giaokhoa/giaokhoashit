package com.giaokhoa.server.attachment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Cổng lưu trữ của lát tệp đính kèm, đúng hình dạng hợp đồng của nền tệp (issue #117): phiên tải lên khoá
// đích (câu, phiên bản, vị trí) ngay từ lúc mở; blob là bất biến và chỉ được tạo từ đúng byte đã kiểm tra;
// liên kết gắn blob vào đúng một phiên bản ở một vị trí nội dung. Nền L1 hiện thực cổng này bằng các bảng
// blob/phiên/liên kết và kho byte riêng tư của nó; lát L2a chỉ làm việc qua cổng này, không tự dựng bảng.
public interface AttachmentStore {

	String STATUS_OPEN = "OPEN";

	String STATUS_READY = "READY";

	String STATUS_COMPLETED = "COMPLETED";

	String STATUS_EXPIRED = "EXPIRED";

	String BLOB_READY = "READY";

	String BLOB_QUARANTINED = "QUARANTINED";

	record Session(UUID id, String uploaderSub, UUID questionId, UUID versionId, String position, String blockId,
			String displayName, String status, String contentType, Long sizeBytes, Integer width, Integer height,
			String stagedChecksum, UUID blobId, UUID linkId, OffsetDateTime expiresAt) {

		Session withProcessing(String newStatus, String newContentType, long newSizeBytes, int newWidth,
				int newHeight, String newStagedChecksum) {
			return new Session(this.id, this.uploaderSub, this.questionId, this.versionId, this.position,
					this.blockId, this.displayName, newStatus, newContentType, newSizeBytes, newWidth, newHeight,
					newStagedChecksum, this.blobId, this.linkId, this.expiresAt);
		}

		Session withStatus(String newStatus) {
			return new Session(this.id, this.uploaderSub, this.questionId, this.versionId, this.position,
					this.blockId, this.displayName, newStatus, this.contentType, this.sizeBytes, this.width,
					this.height, this.stagedChecksum, this.blobId, this.linkId, this.expiresAt);
		}

		Session completed(UUID newBlobId, UUID newLinkId) {
			return new Session(this.id, this.uploaderSub, this.questionId, this.versionId, this.position,
					this.blockId, this.displayName, STATUS_COMPLETED, this.contentType, this.sizeBytes, this.width,
					this.height, this.stagedChecksum, newBlobId, newLinkId, this.expiresAt);
		}

	}

	record Blob(UUID id, String contentType, long sizeBytes, String checksumSha256, int width, int height,
			String status) {
	}

	record Link(UUID id, UUID questionId, UUID versionId, String position, String blockId, UUID blobId,
			String displayName, UUID sessionId) {
	}

	Session saveSession(Session session);

	Optional<Session> findSession(UUID sessionId);

	List<Session> findSessionsForVersion(UUID versionId);

	Blob saveBlob(Blob blob, byte[] bytes);

	Optional<Blob> findBlob(UUID blobId);

	Optional<byte[]> readBlob(UUID blobId);

	void stage(UUID sessionId, byte[] bytes);

	Optional<byte[]> staged(UUID sessionId);

	void dropStaged(UUID sessionId);

	Link saveLink(Link link);

	Optional<Link> findLink(UUID linkId);

	List<Link> findLinksForVersion(UUID versionId);

}
