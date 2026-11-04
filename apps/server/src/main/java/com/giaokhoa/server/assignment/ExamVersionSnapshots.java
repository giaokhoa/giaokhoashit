package com.giaokhoa.server.assignment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Điểm nối đọc snapshot của một phiên bản đề theo đúng hợp đồng snapshot đã khoá: phiên bản đề được định danh
 * bằng `resource_version.id`; khi phiên bản được phát hành, snapshot của từng mục đề được đóng băng với đủ nội
 * dung câu, đáp án, lời giải/tiêu chí chấm, điểm và thứ tự của mục. Luồng làm bài chỉ đọc nội dung qua điểm nối
 * này — không đường đọc nào chạm tới câu hỏi sống hay phiên bản câu hiện hành. Feat đề cung cấp bản cài đặt đọc
 * thật khi hợp nhất; cờ `pendingReview` mang điều kiện nghiệp vụ "bản nhận đang chờ duyệt" của lõi quyền để
 * thao tác giao chặn hành động dùng trên bản đó.
 */
public interface ExamVersionSnapshots {

	Optional<Snapshot> findByVersionId(UUID versionId);

	record Snapshot(UUID versionId, UUID resourceId, String status, boolean pendingReview, List<Item> items) {
	}

	record Item(UUID itemId, String type, int position, BigDecimal points, String prompt, List<Choice> choices,
			String correctChoiceId, String rubric) {
	}

	record Choice(String choiceId, String text) {
	}

}
