package com.giaokhoa.server.grading;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

/**
 * Các thao tác của khối chấm: chấm tay tự luận, công bố theo lần giao, bảng bài nộp của lớp và hai hình chiếu
 * đọc lượt làm (học viên và giáo viên). Quyền chấm và công bố gắn với phân công dạy hiện hành của lớp
 * ({@code teaching_class.teacher_sub}) lấy qua điểm nối dữ kiện — không suy từ quyền sở hữu đề, không qua cơ chế
 * quyết quyền của khối quyền trên tài nguyên. Hình chiếu của học viên là kiểu dữ kiện riêng không có trường điểm,
 * đáp án đúng hay tiêu chí chấm, nên việc lộ chúng qua màn kết quả là không thể ở tầng kiểu.
 */
@Service
public class GradingService {

	private final AttemptData attemptData;

	private final ExamSnapshotSource snapshotSource;

	private final AttemptItemGradeRepository itemGrades;

	private final AttemptGradeSummaryRepository summaries;

	private final AssignmentPublicationRepository publications;

	private final GradeCompletion completion;

	private final AnswerJson answerJson;

	public GradingService(AttemptData attemptData, ExamSnapshotSource snapshotSource,
			AttemptItemGradeRepository itemGrades, AttemptGradeSummaryRepository summaries,
			AssignmentPublicationRepository publications, GradeCompletion completion, AnswerJson answerJson) {
		this.attemptData = attemptData;
		this.snapshotSource = snapshotSource;
		this.itemGrades = itemGrades;
		this.summaries = summaries;
		this.publications = publications;
		this.completion = completion;
		this.answerJson = answerJson;
	}

	@Transactional
	public GradeResultView grade(UUID attemptId, GradeRequest request) {
		TenantContext context = TenantContexts.current();
		AttemptData.AttemptRow attempt = this.attemptData.findAttempt(attemptId)
			.orElseThrow(GradingErrors::notFound);
		AttemptData.AssignmentRow assignment = this.attemptData.findAssignment(attempt.assignmentId())
			.orElseThrow(GradingErrors::notFound);
		if (!isAssignedTeacher(context, assignment.classId())) {
			throw GradingErrors.forbidden();
		}
		if ("IN_PROGRESS".equals(attempt.status())) {
			throw GradingErrors.conflict("Lượt làm chưa nộp nên chưa thể chấm");
		}
		if ("PUBLISHED".equals(attempt.status())) {
			throw GradingErrors.conflict("Kết quả của lượt làm đã công bố, điểm đã cố định");
		}
		List<SnapshotItem> items = sortedItems(assignment.examVersionId());
		List<GradeItemRequest> requested = request.items() != null ? request.items() : List.of();
		if (requested.isEmpty()) {
			if ("GRADED".equals(attempt.status()) && request.comment() != null) {
				this.summaries.findById(attemptId).ifPresent(summary -> {
					summary.setOverallComment(request.comment());
					this.summaries.save(summary);
				});
				return resultView(attemptId, attempt, items);
			}
			throw GradingErrors.validation("Chưa có mục nào được chấm");
		}
		validateGradeItems(items, requested);
		Map<UUID, SnapshotItem> itemsById = new HashMap<>();
		items.forEach(item -> itemsById.put(item.examItemId(), item));
		OffsetDateTime now = OffsetDateTime.now();
		for (GradeItemRequest gradeItem : requested) {
			SnapshotItem item = itemsById.get(gradeItem.examItemId());
			AttemptItemGrade grade = this.itemGrades
				.findByAttemptIdAndExamItemId(attemptId, gradeItem.examItemId())
				.orElseGet(() -> {
					AttemptItemGrade created = new AttemptItemGrade();
					created.setId(UUID.randomUUID());
					created.setAttemptId(attemptId);
					created.setExamItemId(gradeItem.examItemId());
					created.setCreatedAt(now);
					return created;
				});
			grade.setScore(gradeItem.score());
			grade.setMaxScore(item.maxScore());
			grade.setSource(AttemptItemGrade.SOURCE_MANUAL);
			grade.setGraderSub(context.sub());
			grade.setComment(gradeItem.comment());
			grade.setUpdatedAt(now);
			this.itemGrades.save(grade);
		}
		this.completion.completeIfAllGraded(attempt, items, context.sub(), request.comment());
		AttemptData.AttemptRow refreshed = this.attemptData.findAttempt(attemptId).orElse(attempt);
		return resultView(attemptId, refreshed, items);
	}

	@Transactional
	public PublicationView publish(UUID assignmentId) {
		TenantContext context = TenantContexts.current();
		AttemptData.AssignmentRow assignment = this.attemptData.findAssignment(assignmentId)
			.orElseThrow(GradingErrors::notFound);
		if (!isAssignedTeacher(context, assignment.classId())) {
			throw GradingErrors.forbidden();
		}
		AssignmentPublication existing = this.publications.findById(assignmentId).orElse(null);
		if (existing != null) {
			return new PublicationView(existing.getAssignmentId(), existing.getPublishedBySub(),
					existing.getPublishedAt());
		}
		AssignmentPublication publication = new AssignmentPublication();
		publication.setAssignmentId(assignmentId);
		publication.setPublishedBySub(context.sub());
		publication.setPublishedAt(OffsetDateTime.now());
		this.publications.save(publication);
		for (AttemptData.AttemptRow attempt : this.attemptData.attemptsOfAssignment(assignmentId)) {
			if ("GRADED".equals(attempt.status())) {
				this.attemptData.updateAttemptStatus(attempt.id(), "PUBLISHED");
			}
		}
		return new PublicationView(publication.getAssignmentId(), publication.getPublishedBySub(),
				publication.getPublishedAt());
	}

	@Transactional(readOnly = true)
	public List<SubmissionRowView> submissions(UUID assignmentId) {
		TenantContext context = TenantContexts.current();
		AttemptData.AssignmentRow assignment = this.attemptData.findAssignment(assignmentId)
			.orElseThrow(GradingErrors::notFound);
		if (!isAssignedTeacher(context, assignment.classId()) && !isTenantAdmin(context)) {
			throw GradingErrors.forbidden();
		}
		List<SnapshotItem> items = sortedItems(assignment.examVersionId());
		BigDecimal maxScore = totalMaxScore(items);
		Set<String> activeStudents = new HashSet<>(this.attemptData.activeStudentSubsOfClass(assignment.classId()));
		Map<String, SubmissionRowView> rowsByStudent = new HashMap<>();
		for (AttemptData.AttemptRow attempt : this.attemptData.attemptsOfAssignment(assignmentId)) {
			List<AttemptItemGrade> grades = this.itemGrades.findByAttemptId(attempt.id());
			boolean published = "PUBLISHED".equals(attempt.status());
			boolean complete = "GRADED".equals(attempt.status()) || published;
			BigDecimal totalScore = "IN_PROGRESS".equals(attempt.status()) ? null
					: grades.stream().map(AttemptItemGrade::getScore).reduce(BigDecimal.ZERO, BigDecimal::add);
			rowsByStudent.put(attempt.studentSub(),
					new SubmissionRowView(attempt.studentSub(), attempt.status(), attempt.startedAt(),
							attempt.submittedAt(), attempt.submitMode(),
							!activeStudents.contains(attempt.studentSub()),
							totalScore != null ? num(totalScore) : null, num(maxScore), !complete, published));
		}
		for (String studentSub : activeStudents) {
			rowsByStudent.putIfAbsent(studentSub, new SubmissionRowView(studentSub, "NOT_STARTED", null, null,
					null, false, null, num(maxScore), true, false));
		}
		List<SubmissionRowView> rows = new ArrayList<>(rowsByStudent.values());
		rows.sort(Comparator.comparing(SubmissionRowView::studentSub));
		return rows;
	}

	@Transactional(readOnly = true)
	public Object attemptView(UUID attemptId) {
		TenantContext context = TenantContexts.current();
		AttemptData.AttemptRow attempt = this.attemptData.findAttempt(attemptId)
			.orElseThrow(GradingErrors::notFound);
		AttemptData.AssignmentRow assignment = this.attemptData.findAssignment(attempt.assignmentId())
			.orElseThrow(GradingErrors::notFound);
		List<SnapshotItem> items = sortedItems(assignment.examVersionId());
		if (context != null && context.sub().equals(attempt.studentSub())) {
			return studentView(attempt, items);
		}
		if (isAssignedTeacher(context, assignment.classId())) {
			return teacherView(attempt, items);
		}
		if (context != null && (context.organizationRoles().contains("tenant-teacher")
				|| context.organizationRoles().contains("tenant-admin"))) {
			throw GradingErrors.forbidden();
		}
		throw GradingErrors.notFound();
	}

	private void validateGradeItems(List<SnapshotItem> items, List<GradeItemRequest> requested) {
		Map<UUID, SnapshotItem> itemsById = new HashMap<>();
		items.forEach(item -> itemsById.put(item.examItemId(), item));
		Set<UUID> seen = new HashSet<>();
		for (GradeItemRequest gradeItem : requested) {
			if (gradeItem.examItemId() == null || gradeItem.score() == null) {
				throw GradingErrors.validation("Mỗi mục chấm phải có mục đề và điểm");
			}
			if (!seen.add(gradeItem.examItemId())) {
				throw GradingErrors.validation("Một mục bị chấm hai lần trong cùng một lần gọi");
			}
			SnapshotItem item = itemsById.get(gradeItem.examItemId());
			if (item == null) {
				throw GradingErrors.validation("Mục chấm không thuộc snapshot của đề đã giao");
			}
			if (!SnapshotItem.TYPE_ESSAY_TEXT.equals(item.questionType())) {
				throw GradingErrors.validation("Mục trắc nghiệm đã có điểm tự động cố định tại thời điểm nộp");
			}
			if (gradeItem.score().signum() < 0 || gradeItem.score().compareTo(item.maxScore()) > 0) {
				throw GradingErrors.validation("Điểm của mục phải nằm trong khoảng từ 0 đến điểm của mục");
			}
		}
	}

	private StudentAttemptView studentView(AttemptData.AttemptRow attempt, List<SnapshotItem> items) {
		Map<UUID, String> answers = this.attemptData.answersOf(attempt.id());
		List<StudentItemView> itemViews = items.stream()
			.map(item -> new StudentItemView(item.examItemId(), item.questionType(), item.order(), item.stem(),
					choicesOf(item), num(item.maxScore()), this.answerJson.toView(answers.get(item.examItemId()))))
			.toList();
		StudentResultView result = null;
		if ("PUBLISHED".equals(attempt.status())) {
			Map<UUID, AttemptItemGrade> gradesByItem = gradesByItem(attempt.id());
			List<StudentResultItemView> resultItems = items.stream()
				.filter(item -> gradesByItem.containsKey(item.examItemId()))
				.map(item -> {
					AttemptItemGrade grade = gradesByItem.get(item.examItemId());
					return new StudentResultItemView(item.examItemId(), num(grade.getScore()),
							num(grade.getMaxScore()), grade.getComment());
				})
				.toList();
			BigDecimal totalScore = gradesByItem.values()
				.stream()
				.map(AttemptItemGrade::getScore)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
			String overallComment = this.summaries.findById(attempt.id())
				.map(AttemptGradeSummary::getOverallComment)
				.orElse(null);
			result = new StudentResultView(resultItems, overallComment, num(totalScore),
					num(totalMaxScore(items)));
		}
		return new StudentAttemptView(attempt.id(), attempt.assignmentId(), attempt.status(), attempt.startedAt(),
				attempt.submittedAt(), attempt.submitMode(), itemViews, result);
	}

	private TeacherAttemptView teacherView(AttemptData.AttemptRow attempt, List<SnapshotItem> items) {
		Map<UUID, String> answers = this.attemptData.answersOf(attempt.id());
		Map<UUID, AttemptItemGrade> gradesByItem = gradesByItem(attempt.id());
		List<TeacherItemView> itemViews = items.stream()
			.map(item -> {
				AttemptItemGrade grade = gradesByItem.get(item.examItemId());
				GradeView gradeView = grade == null ? null
						: new GradeView(num(grade.getScore()), num(grade.getMaxScore()), grade.getSource(),
								grade.getComment());
				return new TeacherItemView(item.examItemId(), item.questionType(), item.order(), item.stem(),
						choicesOf(item), item.correctChoiceId(), item.rubric(), num(item.maxScore()),
						this.answerJson.toView(answers.get(item.examItemId())), gradeView);
			})
			.toList();
		BigDecimal totalScore = gradesByItem.values()
			.stream()
			.map(AttemptItemGrade::getScore)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		boolean provisional = !"GRADED".equals(attempt.status()) && !"PUBLISHED".equals(attempt.status());
		String overallComment = this.summaries.findById(attempt.id())
			.map(AttemptGradeSummary::getOverallComment)
			.orElse(null);
		return new TeacherAttemptView(attempt.id(), attempt.assignmentId(), attempt.studentSub(), attempt.status(),
				attempt.startedAt(), attempt.submittedAt(), attempt.submitMode(), itemViews, num(totalScore),
				num(totalMaxScore(items)), provisional, overallComment);
	}

	private GradeResultView resultView(UUID attemptId, AttemptData.AttemptRow attempt, List<SnapshotItem> items) {
		BigDecimal totalScore = this.itemGrades.findByAttemptId(attemptId)
			.stream()
			.map(AttemptItemGrade::getScore)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		boolean provisional = !"GRADED".equals(attempt.status()) && !"PUBLISHED".equals(attempt.status());
		return new GradeResultView(attemptId, attempt.status(), num(totalScore), num(totalMaxScore(items)),
				provisional);
	}

	private Map<UUID, AttemptItemGrade> gradesByItem(UUID attemptId) {
		Map<UUID, AttemptItemGrade> gradesByItem = new HashMap<>();
		this.itemGrades.findByAttemptId(attemptId)
			.forEach(grade -> gradesByItem.put(grade.getExamItemId(), grade));
		return gradesByItem;
	}

	private List<SnapshotItem> sortedItems(UUID examVersionId) {
		List<SnapshotItem> items = new ArrayList<>(this.snapshotSource.itemsOf(examVersionId));
		items.sort(Comparator.comparingInt(SnapshotItem::order));
		return items;
	}

	private boolean isAssignedTeacher(TenantContext context, UUID classId) {
		if (context == null || context.guest() || context.organizationId() == null) {
			return false;
		}
		return this.attemptData.teacherSubOfClass(classId).filter(context.sub()::equals).isPresent();
	}

	private boolean isTenantAdmin(TenantContext context) {
		return context != null && !context.guest() && context.organizationId() != null
				&& context.organizationRoles().contains("tenant-admin");
	}

	private static List<ChoiceView> choicesOf(SnapshotItem item) {
		if (item.choices() == null) {
			return List.of();
		}
		return item.choices().stream().map(choice -> new ChoiceView(choice.id(), choice.text())).toList();
	}

	private static BigDecimal totalMaxScore(List<SnapshotItem> items) {
		return items.stream().map(SnapshotItem::maxScore).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private static BigDecimal num(BigDecimal value) {
		return value != null ? value.stripTrailingZeros() : null;
	}

	public record GradeItemRequest(UUID examItemId, BigDecimal score, String comment) {
	}

	public record GradeRequest(List<GradeItemRequest> items, String comment) {
	}

	public record GradeResultView(UUID attemptId, String status, BigDecimal totalScore, BigDecimal maxScore,
			boolean provisional) {
	}

	public record PublicationView(UUID assignmentId, String publishedBySub, OffsetDateTime publishedAt) {
	}

	public record SubmissionRowView(String studentSub, String status, OffsetDateTime startedAt,
			OffsetDateTime submittedAt, String submitMode, boolean removed, BigDecimal totalScore,
			BigDecimal maxScore, boolean provisional, boolean published) {
	}

	public record ChoiceView(String id, String text) {
	}

	public record StudentItemView(UUID examItemId, String questionType, int order, String stem,
			List<ChoiceView> choices, BigDecimal maxScore, Map<String, Object> answer) {
	}

	public record StudentResultItemView(UUID examItemId, BigDecimal score, BigDecimal maxScore, String comment) {
	}

	public record StudentResultView(List<StudentResultItemView> items, String overallComment, BigDecimal totalScore,
			BigDecimal maxScore) {
	}

	public record StudentAttemptView(UUID attemptId, UUID assignmentId, String status, OffsetDateTime startedAt,
			OffsetDateTime submittedAt, String submitMode, List<StudentItemView> items, StudentResultView result) {
	}

	public record GradeView(BigDecimal score, BigDecimal maxScore, String source, String comment) {
	}

	public record TeacherItemView(UUID examItemId, String questionType, int order, String stem,
			List<ChoiceView> choices, String correctChoiceId, String rubric, BigDecimal maxScore,
			Map<String, Object> answer, GradeView grade) {
	}

	public record TeacherAttemptView(UUID attemptId, UUID assignmentId, String studentSub, String status,
			OffsetDateTime startedAt, OffsetDateTime submittedAt, String submitMode, List<TeacherItemView> items,
			BigDecimal totalScore, BigDecimal maxScore, boolean provisional, String overallComment) {
	}

}
