package com.giaokhoa.server.assignment;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.giaokhoa.server.tenancy.TenantContext;

import org.springframework.stereotype.Service;

/**
 * Thao tác trên lần giao: tạo với hai cổng quyền độc lập (hành động giao trên đúng phiên bản đề qua dịch vụ
 * quyết quyền, và phép kiểm phân công dạy lớp đích), sửa hạn nộp, rút lần giao chưa có lượt làm, và danh sách
 * lần giao theo tầm nhìn của từng vai.
 */
@Service
public class AssignmentService {

	private final ExamAssignmentRepository assignments;

	private final ExamAttemptRepository attempts;

	private final TeachingChecks teaching;

	private final ExamVersionSnapshots snapshots;

	private final ExamUseDecisions useDecisions;

	private final AssignmentPartitions partitions;

	public AssignmentService(ExamAssignmentRepository assignments, ExamAttemptRepository attempts,
			TeachingChecks teaching, ExamVersionSnapshots snapshots, ExamUseDecisions useDecisions,
			AssignmentPartitions partitions) {
		this.assignments = assignments;
		this.attempts = attempts;
		this.teaching = teaching;
		this.snapshots = snapshots;
		this.useDecisions = useDecisions;
		this.partitions = partitions;
	}

	public record AssignmentView(UUID id, UUID classId, UUID examResourceId, UUID examVersionId,
			String assignedBySub, OffsetDateTime opensAt, OffsetDateTime dueAt) {
	}

	public AssignmentView create(TenantContext context, UUID examVersionId, UUID classId, OffsetDateTime opensAt,
			OffsetDateTime dueAt) {
		if (examVersionId == null || classId == null || opensAt == null || dueAt == null) {
			throw AssignmentException.validation("Thiếu dữ kiện bắt buộc của lần giao.");
		}
		if (!opensAt.isBefore(dueAt)) {
			throw AssignmentException.validation("Mốc mở bài phải trước hạn nộp.");
		}
		String partition = this.partitions.requirePagePartition(context);
		ExamVersionSnapshots.Snapshot snapshot = this.snapshots.findByVersionId(examVersionId)
				.orElseThrow(() -> AssignmentException.notFound("Phiên bản đề không tồn tại."));
		if (!"published".equals(snapshot.status())) {
			throw AssignmentException.stateConflict("Chỉ giao được phiên bản đề đã phát hành.");
		}
		this.partitions.inPartition(partition, context.sub(), () -> {
			// Lớp thuộc trung tâm khác không tồn tại trong phân vùng của trang: trả 404 như không tồn tại, theo
			// cùng quy ước chống lộ của khung tenancy.
			this.teaching.findClass(partition, classId)
					.orElseThrow(() -> AssignmentException.notFound("Lớp không tồn tại."));
			if (context.guest() || !this.teaching.isAssignedToTeach(context.sub(), partition, classId)) {
				throw AssignmentException.forbidden("Người gọi không được phân công dạy lớp đích.");
			}
			return null;
		});
		ExamUseDecisions.Verdict verdict = this.useDecisions.decideUse(context, snapshot.resourceId(),
				snapshot.versionId());
		if (verdict == ExamUseDecisions.Verdict.PENDING_REVIEW) {
			throw AssignmentException.forbidden("Bản đề đang chờ duyệt nên chưa được dùng để giao bài.");
		}
		if (verdict != ExamUseDecisions.Verdict.ALLOWED) {
			throw AssignmentException.forbidden("Người gọi không có hành động giao trên phiên bản đề này.");
		}
		return this.partitions.inPartition(partition, context.sub(), () -> {
			ExamAssignment assignment = new ExamAssignment();
			assignment.setId(UUID.randomUUID());
			assignment.setClassId(classId);
			assignment.setExamResourceId(snapshot.resourceId());
			assignment.setExamVersionId(snapshot.versionId());
			assignment.setAssignedBySub(context.sub());
			assignment.setOpensAt(opensAt);
			assignment.setDueAt(dueAt);
			OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
			assignment.setCreatedAt(now);
			assignment.setUpdatedAt(now);
			return view(this.assignments.saveAndFlush(assignment));
		});
	}

	public AssignmentView updateDueAt(TenantContext context, UUID assignmentId, OffsetDateTime dueAt) {
		if (dueAt == null) {
			throw AssignmentException.validation("Thiếu hạn nộp mới.");
		}
		String partition = this.partitions.requirePagePartition(context);
		return this.partitions.inPartition(partition, context.sub(), () -> {
			ExamAssignment assignment = this.assignments.findById(assignmentId)
					.orElseThrow(() -> AssignmentException.notFound("Lần giao không tồn tại."));
			requireAssigner(context, partition, assignment);
			// Dời hạn về quá khứ là cách đóng lần giao: các lượt đang làm được chốt ở lần chạm tiếp theo, không
			// cần thao tác đóng riêng.
			assignment.setDueAt(dueAt);
			assignment.setUpdatedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
			return view(this.assignments.saveAndFlush(assignment));
		});
	}

	public void delete(TenantContext context, UUID assignmentId) {
		String partition = this.partitions.requirePagePartition(context);
		this.partitions.inPartition(partition, context.sub(), () -> {
			ExamAssignment assignment = this.assignments.findById(assignmentId)
					.orElseThrow(() -> AssignmentException.notFound("Lần giao không tồn tại."));
			requireAssigner(context, partition, assignment);
			if (this.attempts.existsByAssignmentId(assignmentId)) {
				throw AssignmentException.stateConflict(
						"Lần giao đã có lượt làm nên không rút được; hãy đóng bằng cách dời hạn nộp về quá khứ.");
			}
			this.assignments.delete(assignment);
			return null;
		});
	}

	public List<AssignmentView> list(TenantContext context) {
		if (context == null || context.organizationAlias() == null) {
			return List.of();
		}
		String partition = this.partitions.requirePagePartition(context);
		return this.partitions.inPartition(partition, context.sub(), () -> {
			if (!context.guest() && context.organizationRoles().contains("tenant-admin")) {
				return this.assignments.findAll().stream()
						.sorted(java.util.Comparator.comparing(ExamAssignment::getOpensAt))
						.map(AssignmentService::view)
						.toList();
			}
			Map<UUID, ExamAssignment> visible = new LinkedHashMap<>();
			java.util.Set<UUID> taughtClassIds = context.guest() ? java.util.Set.of()
					: new java.util.HashSet<>(this.teaching.classIdsTaughtBy(partition, context.sub()));
			if (!taughtClassIds.isEmpty()) {
				// Giáo viên thấy mọi lần giao của các lớp mình phụ trách, bất kể cửa sổ mở bài.
				this.assignments.findByClassIdIn(taughtClassIds)
						.forEach(assignment -> visible.put(assignment.getId(), assignment));
			}
			List<UUID> enrolledClassIds = this.teaching.classIdsEnrolledBy(partition, context.sub()).stream()
					.filter(classId -> !taughtClassIds.contains(classId))
					.toList();
			if (!enrolledClassIds.isEmpty()) {
				// Học viên chỉ thấy lần giao của lớp mình ghi danh kể từ mốc mở bài.
				this.assignments
						.findByClassIdInAndOpensAtLessThanEqual(enrolledClassIds, OffsetDateTime.now())
						.forEach(assignment -> visible.put(assignment.getId(), assignment));
			}
			return visible.values().stream()
					.sorted(java.util.Comparator.comparing(ExamAssignment::getOpensAt))
					.map(AssignmentService::view)
					.toList();
		});
	}

	// Sửa hạn và rút lần giao chỉ thuộc về giáo viên đã tạo lần giao đó và còn đang phụ trách lớp đích: thay
	// giáo viên phụ trách là tước luôn hai thao tác này của người cũ, theo cùng phép kiểm dạy đọc dữ kiện hiện
	// hành.
	private void requireAssigner(TenantContext context, String partition, ExamAssignment assignment) {
		if (!context.sub().equals(assignment.getAssignedBySub()) || context.guest()
				|| !this.teaching.isAssignedToTeach(context.sub(), partition, assignment.getClassId())) {
			throw AssignmentException.forbidden("Chỉ giáo viên đã giao và còn phụ trách lớp mới sửa được lần giao.");
		}
	}

	private static AssignmentView view(ExamAssignment assignment) {
		return new AssignmentView(assignment.getId(), assignment.getClassId(), assignment.getExamResourceId(),
				assignment.getExamVersionId(), assignment.getAssignedBySub(), assignment.getOpensAt(),
				assignment.getDueAt());
	}

}
