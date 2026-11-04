package com.giaokhoa.server.grading;

import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Đọc câu trả lời đã lưu của một mục: trắc nghiệm mang {@code choiceId} của lựa chọn đã chọn, tự luận mang
 * {@code text}. Chuỗi không đọc được được coi như không có câu trả lời thay vì làm hỏng cả lượt chấm.
 */
@Component
class AnswerJson {

	private final ObjectMapper objectMapper;

	AnswerJson(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	String choiceIdOf(String answerJson) {
		Object choiceId = parse(answerJson).get("choiceId");
		return choiceId instanceof String text ? text : null;
	}

	Map<String, Object> toView(String answerJson) {
		Map<String, Object> parsed = parse(answerJson);
		return parsed.isEmpty() ? null : parsed;
	}

	private Map<String, Object> parse(String answerJson) {
		if (answerJson == null || answerJson.isBlank()) {
			return Map.of();
		}
		try {
			return this.objectMapper.readValue(answerJson, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (RuntimeException ex) {
			return Map.of();
		}
	}

}
