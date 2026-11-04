package com.giaokhoa.server.assignment;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Điểm nối duy nhất của tệp bài làm sang nền tệp (lát L1): lát này chỉ cần đọc trạng thái của một phiên tải
 * lên và byte của một blob đã chốt, không tự dựng đường tải lên thứ hai. Người tải lên của phiên là dữ kiện
 * quyết quyền gắn — phiên của người khác không gắn được vào bài làm của mình.
 */
public interface SubmissionFileBlobs {

	/**
	 * Một phiên tải lên cùng blob của nó khi phiên đã chốt: `blobId` rỗng khi phiên còn đang xử lý,
	 * `blobCreatedAt` là mốc server ghi khi blob được chốt — mốc quyết tệp có hoàn tất trong hạn hay không.
	 */
	record SessionInfo(UUID sessionId, String sessionStatus, UUID blobId, String blobStatus, String displayName,
			String contentType, Long sizeBytes, String uploadedBySub, OffsetDateTime blobCreatedAt) {
	}

	Optional<SessionInfo> findSession(UUID sessionId);

	Optional<byte[]> readBlob(UUID blobId);

}
