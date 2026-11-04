package com.giaokhoa.server.grading;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Bản cài đặt mặc định của {@link ExamSnapshotSource} khi feat đề chưa hợp nhất: không có mục snapshot nào, nên
 * chấm tự động không ghi điểm nào và không lượt làm nào tự hoàn tất việc chấm — fail-closed thay vì chấm trên dữ
 * kiện không có thật.
 */
@Component
public class EmptyExamSnapshotSource implements ExamSnapshotSource {

	@Override
	public List<SnapshotItem> itemsOf(UUID examVersionId) {
		return List.of();
	}

}
