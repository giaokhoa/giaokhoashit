package com.giaokhoa.server.grading;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Điểm hội tụ của mọi đường chấm: khi mọi mục trong snapshot của một lượt làm đã có điểm, giao dịch chấm hiện
 * hành tạo đúng một hàng tổng hợp (nếu chưa có) và chuyển trạng thái lượt làm — sang {@code PUBLISHED} ngay khi
 * lần giao đã có dấu công bố (công bố bổ sung), sang {@code GRADED} khi chưa. Cả chấm tự động tại thời điểm nộp
 * lẫn chấm tay đều đi qua đây nên điều kiện hoàn tất không tách nhau theo đường.
 */
@Component
class GradeCompletion {

	private final AttemptItemGradeRepository itemGrades;

	private final AttemptGradeSummaryRepository summaries;

	private final AssignmentPublicationRepository publications;

	private final AttemptData attemptData;

	GradeCompletion(AttemptItemGradeRepository itemGrades, AttemptGradeSummaryRepository summaries,
			AssignmentPublicationRepository publications, AttemptData attemptData) {
		this.itemGrades = itemGrades;
		this.summaries = summaries;
		this.publications = publications;
		this.attemptData = attemptData;
	}

	@Transactional
	boolean completeIfAllGraded(AttemptData.AttemptRow attempt, List<SnapshotItem> items, String completingSub,
			String overallComment) {
		if (items.isEmpty()) {
			return false;
		}
		Set<UUID> gradedItemIds = this.itemGrades.findByAttemptId(attempt.id())
			.stream()
			.map(AttemptItemGrade::getExamItemId)
			.collect(Collectors.toSet());
		boolean allGraded = items.stream().allMatch(item -> gradedItemIds.contains(item.examItemId()));
		if (!allGraded) {
			return false;
		}
		AttemptGradeSummary summary = this.summaries.findById(attempt.id()).orElse(null);
		if (summary == null) {
			summary = new AttemptGradeSummary();
			summary.setAttemptId(attempt.id());
			summary.setOverallComment(overallComment);
			summary.setGradedBySub(completingSub);
			summary.setGradedAt(OffsetDateTime.now());
			this.summaries.save(summary);
		}
		else if (overallComment != null) {
			summary.setOverallComment(overallComment);
			this.summaries.save(summary);
		}
		String targetStatus = this.publications.existsById(attempt.assignmentId()) ? "PUBLISHED" : "GRADED";
		if (!targetStatus.equals(attempt.status())) {
			this.attemptData.updateAttemptStatus(attempt.id(), targetStatus);
		}
		return true;
	}

}
