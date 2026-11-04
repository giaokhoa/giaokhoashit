package com.giaokhoa.server.assignment;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Bản mặc định của điểm nối snapshot khi feat đề chưa hợp nhất: không có bảng nội dung đề nào để đọc, nên mọi
 * tra cứu đều rỗng và thao tác giao dừng ở "phiên bản không tồn tại" (404) thay vì tự dựng dữ kiện. Feat đề
 * thay lớp này bằng bản đọc snapshot thật tại cùng điểm nối.
 */
@Component
public class EmptyExamVersionSnapshots implements ExamVersionSnapshots {

	@Override
	public Optional<Snapshot> findByVersionId(UUID versionId) {
		return Optional.empty();
	}

}
