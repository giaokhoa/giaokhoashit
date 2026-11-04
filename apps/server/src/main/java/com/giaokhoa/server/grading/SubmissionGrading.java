package com.giaokhoa.server.grading;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Điểm nối chấm tự động tại thời điểm nộp: khối giao/làm bài gọi phương thức này bên trong đúng giao dịch nộp
 * (nộp tay và chốt hạn đều qua đây) ngay sau khi lượt làm chuyển {@code IN_PROGRESS} sang {@code SUBMITTED}, nên
 * điểm trắc nghiệm và trạng thái nộp cùng thành công hoặc cùng hồi lại. Phương thức tham gia giao dịch đang mở
 * theo propagation mặc định {@code REQUIRED} và có tính một-lần: lượt làm không ở trạng thái {@code SUBMITTED}
 * thì không làm gì, mục đã có điểm thì không ghi lại.
 */
@Component
public class SubmissionGrading {

	private final AttemptData attemptData;

	private final ExamSnapshotSource snapshotSource;

	private final AttemptItemGradeRepository itemGrades;

	private final GradeCompletion completion;

	private final AnswerJson answerJson;

	public SubmissionGrading(AttemptData attemptData, ExamSnapshotSource snapshotSource,
			AttemptItemGradeRepository itemGrades, GradeCompletion completion, AnswerJson answerJson) {
		this.attemptData = attemptData;
		this.snapshotSource = snapshotSource;
		this.itemGrades = itemGrades;
		this.completion = completion;
		this.answerJson = answerJson;
	}

	@Transactional
	public void gradeOnSubmit(UUID attemptId) {
		AttemptData.AttemptRow attempt = this.attemptData.findAttempt(attemptId).orElse(null);
		if (attempt == null || !"SUBMITTED".equals(attempt.status())) {
			return;
		}
		AttemptData.AssignmentRow assignment = this.attemptData.findAssignment(attempt.assignmentId())
			.orElse(null);
		if (assignment == null) {
			return;
		}
		List<SnapshotItem> items = this.snapshotSource.itemsOf(assignment.examVersionId());
		if (items.isEmpty()) {
			return;
		}
		Map<UUID, String> answers = this.attemptData.answersOf(attemptId);
		Set<UUID> alreadyGraded = this.itemGrades.findByAttemptId(attemptId)
			.stream()
			.map(AttemptItemGrade::getExamItemId)
			.collect(Collectors.toSet());
		OffsetDateTime now = OffsetDateTime.now();
		for (SnapshotItem item : items) {
			if (!SnapshotItem.TYPE_SINGLE_CHOICE.equals(item.questionType())
					|| alreadyGraded.contains(item.examItemId())) {
				continue;
			}
			String chosenChoiceId = this.answerJson.choiceIdOf(answers.get(item.examItemId()));
			BigDecimal score = item.correctChoiceId() != null && item.correctChoiceId().equals(chosenChoiceId)
					? item.maxScore() : BigDecimal.ZERO;
			AttemptItemGrade grade = new AttemptItemGrade();
			grade.setId(UUID.randomUUID());
			grade.setAttemptId(attemptId);
			grade.setExamItemId(item.examItemId());
			grade.setScore(score);
			grade.setMaxScore(item.maxScore());
			grade.setSource(AttemptItemGrade.SOURCE_AUTO);
			grade.setCreatedAt(now);
			grade.setUpdatedAt(now);
			this.itemGrades.save(grade);
		}
		this.completion.completeIfAllGraded(attempt, items, null, null);
	}

}
