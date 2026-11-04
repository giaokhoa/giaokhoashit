package com.giaokhoa.server.exam;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Cài đặt mặc định của cổng đọc phiên bản câu trong khi feat câu hỏi/ngân hàng chưa nối bảng nội dung câu: mọi
 * tra cứu đều không thấy. Hệ quả có chủ đích: thao tác soạn và phát hành bị chặn ở thành phần (422 khi sửa bản
 * nháp, 403 khi phát hành) thay vì suy diễn dữ kiện câu hỏi từ bất kỳ nguồn nào khác.
 */
@Component
public class EmptyQuestionVersionSource implements QuestionVersionSource {

	@Override
	public Optional<QuestionVersionInfo> find(UUID questionVersionId) {
		return Optional.empty();
	}

}
