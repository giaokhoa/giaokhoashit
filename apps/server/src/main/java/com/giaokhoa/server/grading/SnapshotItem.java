package com.giaokhoa.server.grading;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Một mục trong snapshot đã đóng băng của một phiên bản đề: đủ nội dung để hiển thị bài làm và chấm — thân câu,
 * các lựa chọn, đáp án đúng, tiêu chí chấm và điểm của mục. Đáp án và tiêu chí chỉ được đưa vào hình chiếu chấm
 * của giáo viên, không bao giờ vào hình chiếu của học viên.
 */
public record SnapshotItem(UUID examItemId, String questionType, int order, BigDecimal maxScore, String stem,
		List<Choice> choices, String correctChoiceId, String rubric) {

	public static final String TYPE_SINGLE_CHOICE = "single_choice";

	public static final String TYPE_ESSAY_TEXT = "essay_text";

	public record Choice(String id, String text) {
	}

}
