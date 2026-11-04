package com.giaokhoa.server.grading;

import java.util.List;
import java.util.UUID;

/**
 * Điểm nối đọc snapshot của một phiên bản đề đã giao: điểm và đáp án dùng để chấm lấy từ đúng snapshot đã đóng
 * băng của phiên bản đó, không đọc câu nguồn sống. Bản cài đặt thật là {@link JdbcExamSnapshotSource}, đọc các
 * hàng snapshot mà feat đề đã đóng băng trong bảng của chính feat đó tại thời điểm phát hành.
 */
public interface ExamSnapshotSource {

	List<SnapshotItem> itemsOf(UUID examVersionId);

}
