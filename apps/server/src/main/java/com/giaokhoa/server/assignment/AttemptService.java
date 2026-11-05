package com.giaokhoa.server.assignment;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.giaokhoa.server.attachment.AttachmentStore;
import com.giaokhoa.server.grading.AttemptGradeSummary;
import com.giaokhoa.server.grading.AttemptItemGrade;
import com.giaokhoa.server.grading.AttemptItemGradeRepository;
import com.giaokhoa.server.grading.AttemptGradeSummaryRepository;
import com.giaokhoa.server.grading.ExamSnapshotSource;
import com.giaokhoa.server.grading.GradingService;
import com.giaokhoa.server.grading.SnapshotItem;
import com.giaokhoa.server.grading.SubmissionGrading;
import com.giaokhoa.server.tenancy.TenantContext;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Vòng đời của một lượt làm: bắt đầu/mở lại là một thao tác duy nhất đứng trên ràng buộc duy nhất của
 * (lần giao, học viên); lưu câu trả lời từng phần có kiểm dạng theo snapshot; nộp là chuyển trạng thái một
 * chiều nên gọi lại chỉ trả kết quả đã có. Hết hạn không có job nền — mọi điểm chạm (đọc, lưu, nộp, bảng trạng
 * thái) đều chốt lười lượt còn đang làm trước khi xử lý tiếp, để không bề mặt nào trình bày một lượt đã quá
 * hạn như còn đang làm.
 */
@Service
public class AttemptService {

	private static final Set<String> ANSWER_FILE_POSITIONS = Set.of("answer", "solution");

	private static final Set<String> ANSWER_IMAGE_SECTIONS = Set.of("solution", "rubric");

	private final ExamAssignmentRepository assignments;

	private final ExamAttemptRepository attempts;

	private final AttemptAnswerRepository answers;

	private final TeachingChecks teaching;

	private final ExamVersionSnapshots snapshots;

	private final AssignmentPartitions partitions;

	private final ObjectMapper objectMapper;

	private final SubmissionGrading submissionGrading;

	private final GradingService grading;

	private final AttemptItemGradeRepository itemGrades;

	private final AttemptGradeSummaryRepository gradeSummaries;

	private final ExamSnapshotSource gradingSnapshots;

	private final AttachmentStore attachmentStore;

	public AttemptService(ExamAssignmentRepository assignments, ExamAttemptRepository attempts,
			AttemptAnswerRepository answers, TeachingChecks teaching, ExamVersionSnapshots snapshots,
			AssignmentPartitions partitions, ObjectMapper objectMapper, SubmissionGrading submissionGrading,
			GradingService grading, AttemptItemGradeRepository itemGrades,
			AttemptGradeSummaryRepository gradeSummaries, ExamSnapshotSource gradingSnapshots,
			AttachmentStore attachmentStore) {
		this.assignments = assignments;
		this.attempts = attempts;
		this.answers = answers;
		this.teaching = teaching;
		this.snapshots = snapshots;
		this.partitions = partitions;
		this.objectMapper = objectMapper;
		this.submissionGrading = submissionGrading;
		this.grading = grading;
		this.itemGrades = itemGrades;
		this.gradeSummaries = gradeSummaries;
		this.gradingSnapshots = gradingSnapshots;
		this.attachmentStore = attachmentStore;
	}

	// Phần kết quả chỉ tồn tại khi lượt làm đã công bố: trước mốc đó trường này luôn null và đáp ứng không mang
	// bất kỳ trường điểm nào, đúng quy tắc học viên chỉ thấy điểm của chính mình sau công bố.
	public record AttemptView(UUID attemptId, UUID assignmentId, String status, OffsetDateTime startedAt,
			OffsetDateTime submittedAt, String submitMode, OffsetDateTime opensAt, OffsetDateTime dueAt,
			List<ItemView> items, List<AnswerView> answers, GradingService.StudentResultView result) {
	}

	public record ItemView(UUID examItemId, String type, int position, java.math.BigDecimal points, String prompt,
			List<ChoiceView> choices, List<AttachmentView> attachments, List<FormulaImageView> formulaImages) {
	}

	public record ChoiceView(String choiceId, String text) {
	}

	// Tệp và ảnh công thức của đề bài, lấy từ chính snapshot đã đóng băng: chỉ mang tham chiếu blob và số đo
	// hiển thị; byte được đọc qua đường tệp của snapshot với cùng điều kiện quyền của lượt làm.
	public record AttachmentView(UUID blobId, String position, String blockId, String displayName,
			String contentType, Integer width, Integer height) {
	}

	public record FormulaImageView(String blockId, String section, int position, String fileRef, UUID imageBlobId,
			Integer width, Integer height, Integer baselineOffset, String description) {
	}

	public record SnapshotFile(byte[] bytes, String contentType, String displayName) {
	}

	public record AnswerView(UUID examItemId, JsonNode answer) {
	}

	public record SubmitView(UUID attemptId, String status, OffsetDateTime submittedAt, String submitMode) {
	}

	// Một dòng của bảng trạng thái nộp: phần trạng thái thuộc lát giao/làm bài, phần điểm thuộc lát chấm —
	// điểm tạm chừng nào còn mục chưa chấm (provisional), điểm của một lượt đang làm là null vì chưa có gì
	// để cộng, và cờ công bố suy từ chính trạng thái của lượt.
	public record SubmissionRow(String studentSub, String status, OffsetDateTime startedAt,
			OffsetDateTime submittedAt, String submitMode, boolean removed, java.math.BigDecimal totalScore,
			java.math.BigDecimal maxScore, boolean provisional, boolean published) {
	}

	public record AnswerInput(UUID examItemId, JsonNode answer) {
	}

	public AttemptView startOrResume(TenantContext context, UUID assignmentId) {
		String partition = this.partitions.requirePagePartition(context);
		AttemptView existing = this.partitions.inPartition(partition, context.sub(),
				() -> loadForStart(context, partition, assignmentId));
		if (existing != null) {
			return existing;
		}
		try {
			return this.partitions.inPartition(partition, context.sub(),
					() -> createAttempt(context, partition, assignmentId));
		}
		catch (DataIntegrityViolationException duplicate) {
			// Hai lần gọi đồng thời cùng tạo lượt: giao dịch thua cuộc bị ràng buộc duy nhất chặn và đã rollback,
			// nên phải đọc lại bằng giao dịch mới — session của giao dịch hỏng không dùng lại được.
			AttemptView winner = this.partitions.inPartition(partition, context.sub(),
					() -> loadForStart(context, partition, assignmentId));
			if (winner == null) {
				throw duplicate;
			}
			return winner;
		}
	}

	// Đường đọc hợp nhất của một lượt làm: học viên chủ lượt nhận hình chiếu của lát giao/làm bài, kèm phần
	// kết quả của lát chấm khi lượt đã công bố; giáo viên phụ trách lớp đích nhận hình chiếu chấm đầy đủ của
	// lát chấm. Nhánh đọc vẫn quyết theo phép đọc của lát giao/làm bài và chốt hạn lười diễn ra trước khi
	// phân nhánh như mọi điểm chạm khác.
	public Object get(TenantContext context, UUID attemptId) {
		String partition = readPartition(context);
		ReadOutcome outcome = this.partitions.inPartition(partition, context.sub(), () -> {
			ExamAttempt attempt = this.attempts.findById(attemptId)
					.orElseThrow(() -> AssignmentException.notFound("Lượt làm không tồn tại."));
			ExamAssignment assignment = assignmentOf(attempt);
			finalizeIfPastDue(attempt, assignment);
			if (context.sub().equals(attempt.getStudentSub())) {
				return new ReadOutcome(viewWithResult(attempt, assignment), false);
			}
			if (!context.guest() && this.teaching.isAssignedToTeach(context.sub(), partition,
					assignment.getClassId())) {
				return new ReadOutcome(null, true);
			}
			requireOwner(context, partition, attempt, assignment);
			throw new IllegalStateException("Người gọi không thuộc nhánh đọc nào của lượt làm.");
		});
		// Hình chiếu chấm do lát chấm dựng bằng giao dịch riêng trên ngữ cảnh thật của request, vì ngữ cảnh
		// dữ kiện đã ghim trong phạm vi phân vùng ở trên không mang vai của người gọi.
		return outcome.teacherView() ? this.grading.attemptView(attemptId) : outcome.view();
	}

	// Byte của một tệp hay ảnh thuộc chính snapshot của lượt làm: blob chỉ được phục vụ khi định danh của nó
	// nằm trong bản kê đã đóng băng của phiên bản đề của lượt này (phần trình bày, không gồm phần đáp án),
	// và người gọi là chủ lượt làm hoặc giáo viên được phân công dạy lớp đích. Đường này không đọc liên kết
	// sống của phiên bản câu, nên tệp của đề đã giao không đổi khi câu nguồn thay đổi sau đó.
	public SnapshotFile snapshotFile(TenantContext context, UUID attemptId, UUID blobId) {
		String partition = readPartition(context);
		return this.partitions.inPartition(partition, context.sub(), () -> {
			ExamAttempt attempt = this.attempts.findById(attemptId)
					.orElseThrow(() -> AssignmentException.notFound("Lượt làm không tồn tại."));
			ExamAssignment assignment = assignmentOf(attempt);
			boolean owner = context.sub().equals(attempt.getStudentSub());
			boolean teacher = !context.guest() && this.teaching.isAssignedToTeach(context.sub(), partition,
					assignment.getClassId());
			if (!owner && !teacher) {
				throw AssignmentException.notFound("Lượt làm không tồn tại.");
			}
			ExamVersionSnapshots.Snapshot snapshot = snapshotOf(assignment);
			String displayName = null;
			String contentType = null;
			String blobPartition = null;
			for (ExamVersionSnapshots.Item item : snapshot.items()) {
				for (ExamVersionSnapshots.Attachment attachment : item.attachments()) {
					if (blobId.equals(attachment.blobId())
							&& !ANSWER_FILE_POSITIONS.contains(attachment.position())) {
						displayName = attachment.displayName();
						contentType = attachment.contentType();
						blobPartition = attachment.blobPartitionKey();
					}
				}
				for (ExamVersionSnapshots.FormulaImage image : item.formulaImages()) {
					if (blobId.equals(image.imageBlobId()) && !ANSWER_IMAGE_SECTIONS.contains(image.section())) {
						displayName = image.description();
						contentType = "image/png";
						blobPartition = image.blobPartitionKey();
					}
				}
			}
			if (contentType == null) {
				throw AssignmentException.notFound("Tệp không thuộc snapshot của lượt làm này.");
			}
			byte[] bytes = readSnapshotBlob(blobPartition, partition, context.sub(), blobId)
					.orElseThrow(() -> AssignmentException.notFound("Tệp không thuộc snapshot của lượt làm này."));
			return new SnapshotFile(bytes, contentType, displayName != null ? displayName : "tep-ban-chup");
		});
	}

	// Byte của blob trong snapshot đọc ở phân vùng nhà đã đóng băng cùng snapshot (phân vùng của chủ
	// phiên bản câu), vì blob không nằm trong phân vùng của lượt làm; snapshot cũ không ghi khoá thì đọc
	// ngay trong phân vùng hiện hành như trước. Blob chưa sẵn sàng (ví dụ đã bị cách ly) thì coi như
	// không có tệp.
	private java.util.Optional<byte[]> readSnapshotBlob(String blobPartition, String readPartition,
			String actorSub, UUID blobId) {
		java.util.function.Supplier<java.util.Optional<byte[]>> read = () -> this.attachmentStore
				.findBlob(blobId)
				.filter((candidate) -> AttachmentStore.BLOB_READY.equals(candidate.status()))
				.flatMap((blob) -> this.attachmentStore.readBlob(blobId));
		if (blobPartition != null && !blobPartition.equals(readPartition)) {
			return this.partitions.inPartition(blobPartition, actorSub, read::get);
		}
		return read.get();
	}

	private record ReadOutcome(AttemptView view, boolean teacherView) {
	}

	public AttemptView saveAnswers(TenantContext context, UUID attemptId, List<AnswerInput> inputs) {
		String partition = readPartition(context);
		return this.partitions.inPartition(partition, context.sub(), () -> {
			ExamAttempt attempt = this.attempts.findById(attemptId)
					.orElseThrow(() -> AssignmentException.notFound("Lượt làm không tồn tại."));
			ExamAssignment assignment = assignmentOf(attempt);
			requireOwner(context, partition, attempt, assignment);
			if (finalizeIfPastDue(attempt, assignment)) {
				// Câu trả lời đến sau hạn không được ghi: lượt đã chốt ở thao tác chạm này, và chính thao tác
				// lưu bị từ chối.
				throw AssignmentException.stateConflict("Đã quá hạn nộp; lượt làm đã được chốt.");
			}
			if (!ExamAttempt.IN_PROGRESS.equals(attempt.getStatus())) {
				throw AssignmentException.stateConflict("Lượt làm không còn ở trạng thái đang làm.");
			}
			if (!this.teaching.isEnrolled(context.sub(), partition, assignment.getClassId())) {
				throw AssignmentException.forbidden("Ghi danh vào lớp đã bị gỡ nên không lưu tiếp được.");
			}
			ExamVersionSnapshots.Snapshot snapshot = snapshotOf(assignment);
			Map<UUID, ExamVersionSnapshots.Item> itemsById = snapshot.items().stream()
					.collect(Collectors.toMap(ExamVersionSnapshots.Item::itemId, Function.identity()));
			// Kiểm toàn bộ dữ kiện vào trước khi ghi bất kỳ mục nào: một mục sai là cả lần gọi bị từ chối và
			// không mục nào được ghi.
			for (AnswerInput input : inputs) {
				ExamVersionSnapshots.Item item = input.examItemId() != null ? itemsById.get(input.examItemId())
						: null;
				if (item == null) {
					throw AssignmentException.validation("Mục đề không thuộc bài đã giao.");
				}
				validateAnswer(item, input.answer());
			}
			OffsetDateTime now = now();
			for (AnswerInput input : inputs) {
				String answerJson = writeJson(input.answer());
				this.answers.findByAttemptIdAndExamItemId(attempt.getId(), input.examItemId())
						.ifPresentOrElse(saved -> {
							saved.setAnswer(answerJson);
							saved.setUpdatedAt(now);
							this.answers.saveAndFlush(saved);
						}, () -> {
							AttemptAnswer answer = new AttemptAnswer();
							answer.setId(UUID.randomUUID());
							answer.setAttemptId(attempt.getId());
							answer.setExamItemId(input.examItemId());
							answer.setAnswer(answerJson);
							answer.setCreatedAt(now);
							answer.setUpdatedAt(now);
							this.answers.saveAndFlush(answer);
						});
			}
			attempt.setUpdatedAt(now);
			this.attempts.saveAndFlush(attempt);
			return view(attempt, assignment);
		});
	}

	public SubmitView submit(TenantContext context, UUID attemptId) {
		String partition = readPartition(context);
		return this.partitions.inPartition(partition, context.sub(), () -> {
			ExamAttempt attempt = this.attempts.findById(attemptId)
					.orElseThrow(() -> AssignmentException.notFound("Lượt làm không tồn tại."));
			ExamAssignment assignment = assignmentOf(attempt);
			requireOwner(context, partition, attempt, assignment);
			if (ExamAttempt.IN_PROGRESS.equals(attempt.getStatus())) {
				if (!finalizeIfPastDue(attempt, assignment)) {
					if (!this.teaching.isEnrolled(context.sub(), partition, assignment.getClassId())) {
						throw AssignmentException.forbidden("Ghi danh vào lớp đã bị gỡ nên không nộp tay được.");
					}
					attempt.setStatus(ExamAttempt.SUBMITTED);
					attempt.setSubmittedAt(now());
					attempt.setSubmitMode(ExamAttempt.SUBMIT_MANUAL);
					attempt.setUpdatedAt(now());
					this.attempts.saveAndFlush(attempt);
					// Chấm tự động trắc nghiệm của lát chấm chạy trong cùng giao dịch nộp tại đây, trên đáp án
					// của snapshot và câu trả lời đã chốt của lượt này, ngay sau chuyển trạng thái sang đã nộp.
					this.submissionGrading.gradeOnSubmit(attempt.getId());
				}
			}
			// Lượt đã nộp (tay, tự chốt, hay đã chấm/công bố) thì nộp lại chỉ trả nguyên kết quả đã có: không
			// tạo bài nộp thứ hai, không đổi submitted_at, không chấm lại.
			return new SubmitView(attempt.getId(), attempt.getStatus(), attempt.getSubmittedAt(),
					attempt.getSubmitMode());
		});
	}

	public List<SubmissionRow> submissions(TenantContext context, UUID assignmentId) {
		String partition = readPartition(context);
		return this.partitions.inPartition(partition, context.sub(), () -> {
			ExamAssignment assignment = this.assignments.findById(assignmentId)
					.orElseThrow(() -> AssignmentException.notFound("Lần giao không tồn tại."));
			boolean admin = !context.guest() && context.organizationRoles().contains("tenant-admin");
			boolean teacher = !context.guest()
					&& this.teaching.isAssignedToTeach(context.sub(), partition, assignment.getClassId());
			if (!admin && !teacher) {
				throw AssignmentException.forbidden(
						"Chỉ giáo viên phụ trách lớp hoặc quản trị trung tâm mới xem được bảng trạng thái nộp.");
			}
			List<ExamAttempt> classAttempts = this.attempts.findByAssignmentId(assignmentId);
			classAttempts.forEach(attempt -> finalizeIfPastDue(attempt, assignment));
			Map<String, ExamAttempt> attemptsByStudent = classAttempts.stream()
					.collect(Collectors.toMap(ExamAttempt::getStudentSub, Function.identity()));
			java.math.BigDecimal maxScore = totalMaxScore(assignment);
			List<SubmissionRow> rows = new ArrayList<>();
			java.util.Set<String> activeStudents = new java.util.HashSet<>(
					this.teaching.activeRoster(partition, assignment.getClassId()));
			for (String studentSub : activeStudents) {
				ExamAttempt attempt = attemptsByStudent.get(studentSub);
				rows.add(attempt != null ? row(attempt, false, maxScore)
						: new SubmissionRow(studentSub, "NOT_STARTED", null, null, null, false, null,
								num(maxScore), true, false));
			}
			// Học viên đã bị gỡ ghi danh nhưng đã có lượt làm vẫn thuộc diện chấm/công bố, nên vẫn xuất hiện
			// trong bảng với dấu đã gỡ.
			for (ExamAttempt attempt : classAttempts) {
				if (!activeStudents.contains(attempt.getStudentSub())) {
					rows.add(row(attempt, true, maxScore));
				}
			}
			rows.sort(Comparator.comparing(SubmissionRow::studentSub));
			return rows;
		});
	}

	// Phần điểm của một dòng bảng trạng thái, tính từ dữ kiện điểm hiện hành của lát chấm theo đúng quy tắc
	// của lát đó: lượt đang làm chưa có tổng điểm, tổng điểm là tạm cho tới khi mọi mục đều đã có điểm.
	private SubmissionRow row(ExamAttempt attempt, boolean removed, java.math.BigDecimal maxScore) {
		boolean published = ExamAttempt.PUBLISHED.equals(attempt.getStatus());
		boolean complete = published || ExamAttempt.GRADED.equals(attempt.getStatus());
		java.math.BigDecimal totalScore = ExamAttempt.IN_PROGRESS.equals(attempt.getStatus()) ? null
				: num(this.itemGrades.findByAttemptId(attempt.getId()).stream()
						.map(AttemptItemGrade::getScore)
						.reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
		return new SubmissionRow(attempt.getStudentSub(), attempt.getStatus(), attempt.getStartedAt(),
				attempt.getSubmittedAt(), attempt.getSubmitMode(), removed, totalScore, num(maxScore),
				!complete, published);
	}

	private java.math.BigDecimal totalMaxScore(ExamAssignment assignment) {
		return this.gradingSnapshots.itemsOf(assignment.getExamVersionId()).stream()
				.map(SnapshotItem::maxScore)
				.reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
	}

	private AttemptView loadForStart(TenantContext context, String partition, UUID assignmentId) {
		ExamAssignment assignment = this.assignments.findById(assignmentId)
				.orElseThrow(() -> AssignmentException.notFound("Lần giao không tồn tại."));
		requireEnrolled(context, partition, assignment);
		return this.attempts.findByAssignmentIdAndStudentSub(assignmentId, context.sub())
				.map(attempt -> {
					finalizeIfPastDue(attempt, assignment);
					return view(attempt, assignment);
				})
				.orElse(null);
	}

	private AttemptView createAttempt(TenantContext context, String partition, UUID assignmentId) {
		ExamAssignment assignment = this.assignments.findById(assignmentId)
				.orElseThrow(() -> AssignmentException.notFound("Lần giao không tồn tại."));
		requireEnrolled(context, partition, assignment);
		OffsetDateTime now = now();
		if (now.isBefore(assignment.getOpensAt()) || now.isAfter(assignment.getDueAt())) {
			throw AssignmentException.stateConflict("Lượt làm chỉ bắt đầu được trong cửa sổ mở bài của lần giao.");
		}
		ExamAttempt attempt = new ExamAttempt();
		attempt.setId(UUID.randomUUID());
		attempt.setAssignmentId(assignmentId);
		attempt.setStudentSub(context.sub());
		attempt.setStatus(ExamAttempt.IN_PROGRESS);
		attempt.setStartedAt(now);
		attempt.setCreatedAt(now);
		attempt.setUpdatedAt(now);
		this.attempts.saveAndFlush(attempt);
		return view(attempt, assignment);
	}

	private void requireEnrolled(TenantContext context, String partition, ExamAssignment assignment) {
		if (!this.teaching.isEnrolled(context.sub(), partition, assignment.getClassId())) {
			throw AssignmentException.forbidden("Người gọi không có ghi danh đang hiệu lực vào lớp đích.");
		}
	}

	// Người không phải chủ lượt làm: bạn học cùng lớp thì coi như lượt không tồn tại (404, không kèm dữ kiện),
	// các vai còn lại bị từ chối quyền (403) — khớp quy ước phân biệt của đặc tả cho từng nhóm người gọi.
	private void requireOwner(TenantContext context, String partition, ExamAttempt attempt,
			ExamAssignment assignment) {
		if (context.sub().equals(attempt.getStudentSub())) {
			return;
		}
		if (this.teaching.isEnrolled(context.sub(), partition, assignment.getClassId())) {
			throw AssignmentException.notFound("Lượt làm không tồn tại.");
		}
		throw AssignmentException.forbidden("Lượt làm thuộc về học viên khác.");
	}

	// Chốt lười: quá hạn mà lượt còn đang làm thì chuyển đã nộp với submit_mode auto_deadline và mốc nộp là
	// chính hạn nộp (thời điểm lượt được coi là đã nộp), giữ nguyên câu trả lời đã lưu gần nhất. Trả true khi
	// có chốt thật ở lần chạm này.
	private boolean finalizeIfPastDue(ExamAttempt attempt, ExamAssignment assignment) {
		if (!ExamAttempt.IN_PROGRESS.equals(attempt.getStatus())
				|| !OffsetDateTime.now().isAfter(assignment.getDueAt())) {
			return false;
		}
		attempt.setStatus(ExamAttempt.SUBMITTED);
		attempt.setSubmittedAt(assignment.getDueAt());
		attempt.setSubmitMode(ExamAttempt.SUBMIT_AUTO_DEADLINE);
		attempt.setUpdatedAt(now());
		this.attempts.saveAndFlush(attempt);
		// Chốt hạn là một đường nộp như nộp tay: chấm tự động chạy ngay trong cùng giao dịch chốt này.
		this.submissionGrading.gradeOnSubmit(attempt.getId());
		return true;
	}

	private ExamAssignment assignmentOf(ExamAttempt attempt) {
		return this.assignments.findById(attempt.getAssignmentId())
				.orElseThrow(() -> AssignmentException.notFound("Lần giao không tồn tại."));
	}

	private ExamVersionSnapshots.Snapshot snapshotOf(ExamAssignment assignment) {
		return this.snapshots.findByVersionId(assignment.getExamVersionId())
				.orElseThrow(() -> new AssignmentException(HttpStatus.INTERNAL_SERVER_ERROR, "SNAPSHOT_UNAVAILABLE",
						"Không đọc được snapshot của phiên bản đề đã giao."));
	}

	// Hình chiếu cho học viên do server tạo từ snapshot: chỉ định danh mục, loại câu, thân câu, các lựa chọn và
	// điểm — đáp án đúng và tiêu chí chấm của snapshot không bao giờ được chép sang DTO này, ở bất kỳ trường
	// nào. Tệp và ảnh công thức đi theo cùng quy tắc: tệp ở vị trí đáp án và ảnh thuộc phần lời giải hay tiêu
	// chí chấm bị lược khỏi hình chiếu này dù snapshot có mang chúng.
	private AttemptView view(ExamAttempt attempt, ExamAssignment assignment) {
		List<ItemView> items = this.snapshots.findByVersionId(assignment.getExamVersionId())
				.map(snapshot -> snapshot.items().stream()
						.sorted(Comparator.comparingInt(ExamVersionSnapshots.Item::position))
						.map(item -> new ItemView(item.itemId(), item.type(), item.position(), item.points(),
								item.prompt(),
								item.choices().stream()
										.map(choice -> new ChoiceView(choice.choiceId(), choice.text()))
										.toList(),
								item.attachments().stream()
										.filter(attachment -> !ANSWER_FILE_POSITIONS.contains(attachment.position()))
										.map(attachment -> new AttachmentView(attachment.blobId(), attachment.position(),
												attachment.blockId(), attachment.displayName(), attachment.contentType(),
												attachment.width(), attachment.height()))
										.toList(),
								item.formulaImages().stream()
										.filter(image -> !ANSWER_IMAGE_SECTIONS.contains(image.section()))
										.map(image -> new FormulaImageView(image.blockId(), image.section(),
												image.position(), image.fileRef(), image.imageBlobId(), image.width(),
												image.height(), image.baselineOffset(), image.description()))
										.toList()))
						.toList())
				.orElse(List.of());
		List<AnswerView> savedAnswers = this.answers.findByAttemptId(attempt.getId()).stream()
				.map(answer -> new AnswerView(answer.getExamItemId(), readJson(answer.getAnswer())))
				.toList();
		return new AttemptView(attempt.getId(), assignment.getId(), attempt.getStatus(), attempt.getStartedAt(),
				attempt.getSubmittedAt(), attempt.getSubmitMode(), assignment.getOpensAt(), assignment.getDueAt(),
				items, savedAnswers, null);
	}

	// Hình chiếu của học viên kèm phần kết quả khi lượt đã công bố: điểm và nhận xét theo từng mục theo đúng
	// thứ tự của snapshot, nhận xét chung và hai con số tổng — tất cả lấy từ dữ kiện điểm của lát chấm; đáp án
	// đúng và tiêu chí chấm không bao giờ đi qua đường này.
	private AttemptView viewWithResult(ExamAttempt attempt, ExamAssignment assignment) {
		AttemptView base = view(attempt, assignment);
		if (!ExamAttempt.PUBLISHED.equals(attempt.getStatus())) {
			return base;
		}
		Map<UUID, AttemptItemGrade> gradesByItem = this.itemGrades.findByAttemptId(attempt.getId()).stream()
				.collect(Collectors.toMap(AttemptItemGrade::getExamItemId, Function.identity()));
		List<SnapshotItem> items = this.gradingSnapshots.itemsOf(assignment.getExamVersionId()).stream()
				.sorted(Comparator.comparingInt(SnapshotItem::order))
				.toList();
		List<GradingService.StudentResultItemView> resultItems = items.stream()
				.filter(item -> gradesByItem.containsKey(item.examItemId()))
				.map(item -> {
					AttemptItemGrade grade = gradesByItem.get(item.examItemId());
					return new GradingService.StudentResultItemView(item.examItemId(), num(grade.getScore()),
							num(grade.getMaxScore()), grade.getComment());
				})
				.toList();
		java.math.BigDecimal totalScore = gradesByItem.values().stream().map(AttemptItemGrade::getScore)
				.reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
		java.math.BigDecimal maxScore = items.stream().map(SnapshotItem::maxScore)
				.reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
		String overallComment = this.gradeSummaries.findById(attempt.getId())
				.map(AttemptGradeSummary::getOverallComment)
				.orElse(null);
		return new AttemptView(base.attemptId(), base.assignmentId(), base.status(), base.startedAt(),
				base.submittedAt(), base.submitMode(), base.opensAt(), base.dueAt(), base.items(),
				base.answers(), new GradingService.StudentResultView(resultItems, overallComment,
						num(totalScore), num(maxScore)));
	}

	private static java.math.BigDecimal num(java.math.BigDecimal value) {
		return value != null ? value.stripTrailingZeros() : null;
	}

	private void validateAnswer(ExamVersionSnapshots.Item item, JsonNode answer) {
		if (answer == null || !answer.isObject()) {
			throw AssignmentException.validation("Câu trả lời phải là một đối tượng JSON theo loại câu.");
		}
		switch (item.type()) {
			case "single_choice" -> {
				JsonNode choiceId = answer.get("choiceId");
				if (choiceId == null || !choiceId.isTextual() || item.choices().stream()
						.noneMatch(choice -> choice.choiceId().equals(choiceId.asText()))) {
					throw AssignmentException.validation("Lựa chọn không nằm trong các lựa chọn của mục.");
				}
			}
			case "essay_text" -> {
				JsonNode text = answer.get("text");
				if (text == null || !text.isTextual()) {
					throw AssignmentException.validation("Câu trả lời tự luận phải là văn bản.");
				}
				// Hợp đồng nội dung của ngân hàng câu hỏi chưa chốt độ dài tối đa cho tự luận, nên tại đây chỉ
				// kiểm dạng văn bản; giới hạn độ dài được áp tại tầng này khi hợp đồng đó chốt con số.
			}
			default -> throw AssignmentException.validation("Loại câu không thuộc phạm vi của bản này.");
		}
	}

	// Mốc thời gian đóng dấu lên hàng được cắt về micro giây: đó là độ chính xác của timestamptz trong
	// PostgreSQL, nên giá trị trả ngay trong request tạo và giá trị đọc lại từ database ở các request sau
	// khớp nhau tuyệt đối (nộp chống trùng so bằng chính submitted_at này).
	private static OffsetDateTime now() {
		return OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
	}

	private String readPartition(TenantContext context) {
		if (context == null || context.organizationAlias() == null) {
			throw AssignmentException.notFound("Không tìm thấy dữ kiện trong ngữ cảnh trang hiện tại.");
		}
		return this.partitions.requirePagePartition(context);
	}

	private String writeJson(JsonNode node) {
		try {
			return this.objectMapper.writeValueAsString(node);
		}
		catch (JacksonException ex) {
			throw AssignmentException.validation("Câu trả lời không phải JSON hợp lệ.");
		}
	}

	private JsonNode readJson(String json) {
		try {
			return this.objectMapper.readTree(json);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Câu trả lời đã lưu không phải JSON hợp lệ", ex);
		}
	}

}
