package com.giaokhoa.server.grading;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Bản đọc thật của {@link ExamSnapshotSource}: đọc các hàng đã đóng băng trong bảng {@code exam_item_snapshot}
 * của feat đề và lắp lại đúng hình mà khối chấm tiêu thụ — thân câu, lựa chọn và đáp án đúng lấy từ văn bản nội
 * dung đã đóng băng của chính mục, điểm và thứ tự lấy từ cột của hàng snapshot. Việc tiêu thụ luôn neo vào một
 * phiên bản đề cụ thể đã được một lần giao thuộc phân vùng của người gọi ghim, nên khoá đọc ở đây là chính định
 * danh phiên bản đề đó; hàng snapshot thuộc phân vùng của đề nên đường đọc này cho kết quả trong phạm vi phân
 * vùng mà giao dịch hiện hành đã mở, ngoài phạm vi đó nó trả rỗng (fail-closed) thay vì dữ kiện của nơi khác.
 */
@Component
public class JdbcExamSnapshotSource implements ExamSnapshotSource {

	private final JdbcTemplate jdbc;

	private final ObjectMapper objectMapper;

	public JdbcExamSnapshotSource(JdbcTemplate jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	@Override
	public List<SnapshotItem> itemsOf(UUID examVersionId) {
		return this.jdbc.query("""
				select exam_item_id, question_type, position, score, content
				from exam_item_snapshot where exam_version_id = ? order by position
				""", (rs, row) -> toItem(rs.getObject("exam_item_id", UUID.class), rs.getString("question_type"),
						rs.getInt("position"), rs.getBigDecimal("score"), rs.getString("content")),
				examVersionId);
	}

	private SnapshotItem toItem(UUID examItemId, String questionType, int position, BigDecimal maxScore,
			String contentJson) {
		JsonNode content;
		try {
			content = this.objectMapper.readTree(contentJson);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Nội dung snapshot của một mục đề không phải JSON hợp lệ.", ex);
		}
		List<SnapshotItem.Choice> choices = new ArrayList<>();
		JsonNode choicesNode = content.get("choices");
		if (choicesNode != null && choicesNode.isArray()) {
			choicesNode.forEach(choice -> choices.add(new SnapshotItem.Choice(text(choice, "id"), text(choice, "text"))));
		}
		return new SnapshotItem(examItemId, questionType, position, maxScore, text(content, "stem"),
				List.copyOf(choices), text(content, "correctChoiceId"), text(content, "rubric"));
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && value.isTextual() ? value.asText() : null;
	}

}
