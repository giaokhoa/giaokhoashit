package com.giaokhoa.server.exam;

import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Cổng đọc một phiên bản câu cụ thể: điểm duy nhất mà lát soạn đề dùng để lấy nội dung và dữ kiện quyền của câu
 * khi soạn, khi xem trước bản nháp và khi đóng băng snapshot. Khi feat câu hỏi/ngân hàng nối bảng nội dung câu
 * của nó vào cổng này, cài đặt đọc qua bảng câu thay thế cài đặt mặc định mà không đổi hành vi tại các endpoint.
 */
public interface QuestionVersionSource {

	Optional<QuestionVersionInfo> find(UUID questionVersionId);

	/**
	 * Dữ kiện của một phiên bản câu mà quyết định quyền và việc đóng băng cần: định danh câu và phiên bản câu,
	 * loại câu, workspace và chủ sở hữu của câu, cờ công bố catalog, và toàn bộ nội dung của phiên bản (thân
	 * câu, đáp án, lời giải/tiêu chí chấm) theo hình nội dung của loại câu — {@code single_choice} mang
	 * {@code stem}, {@code choices}, {@code correctChoiceId}, {@code solution}; {@code essay_text} mang
	 * {@code stem}, {@code rubric}, {@code solution} khi có.
	 */
	record QuestionVersionInfo(UUID questionId, UUID questionVersionId, String questionType, String workspaceKind,
			String ownerUserSub, String ownerOrganizationId, boolean catalogPublished, JsonNode content) {
	}

}
