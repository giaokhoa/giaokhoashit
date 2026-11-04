package com.giaokhoa.server.grading;

import java.util.List;
import java.util.UUID;

/**
 * Điểm nối đọc snapshot của một phiên bản đề đã giao: điểm và đáp án dùng để chấm lấy từ đúng snapshot đã đóng
 * băng của phiên bản đó, không đọc câu nguồn sống. Feat đề sở hữu bảng snapshot và cung cấp bản đọc thật tại điểm
 * nối này khi hợp nhất; trước đó bản cài đặt mặc định trả danh sách rỗng.
 */
public interface ExamSnapshotSource {

	List<SnapshotItem> itemsOf(UUID examVersionId);

}
