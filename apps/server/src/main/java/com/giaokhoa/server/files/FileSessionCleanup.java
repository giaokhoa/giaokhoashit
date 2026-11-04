package com.giaokhoa.server.files;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dọn định kỳ byte tạm của các phiên tải lên dở: tệp tạm nằm trên đĩa quá thời hạn phiên (mặc định 24 giờ theo
 * cấu hình {@code app.files.session-ttl-hours}) bị xoá; phiên tương ứng bị đánh dấu hết hạn khi bị chạm tới lần
 * tiếp theo hoặc qua điểm vào dọn theo phân vùng của {@link FileService#expireStaleSessions}.
 */
@Component
public class FileSessionCleanup {

	private final BlobStorage storage;

	private final long sessionTtlHours;

	public FileSessionCleanup(BlobStorage storage,
			@Value("${app.files.session-ttl-hours:24}") long sessionTtlHours) {
		this.storage = storage;
		this.sessionTtlHours = sessionTtlHours;
	}

	@Scheduled(fixedDelayString = "${app.files.cleanup-delay-ms:3600000}")
	public void sweepStaleStaging() {
		this.storage.sweepStaging(Instant.now().minus(Duration.ofHours(this.sessionTtlHours)));
	}

}
