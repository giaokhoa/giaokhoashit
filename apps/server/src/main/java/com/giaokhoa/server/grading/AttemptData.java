package com.giaokhoa.server.grading;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

/**
 * Điểm nối dữ kiện sang các bảng của khối tổ chức dạy học và khối giao/làm bài ({@code class_member},
 * {@code exam_assignment}, {@code exam_attempt}, {@code attempt_answer}): khối này chỉ đọc và chuyển trạng
 * thái lượt làm qua SQL thuần trong đúng phân vùng của ngữ cảnh trang, không định nghĩa lại entity của các
 * bảng đó. Mọi truy vấn đều kèm khoá phân vùng của ngữ cảnh hiện hành — trên PostgreSQL, Row Level Security
 * là lớp chặn chính; điều kiện tường minh này giữ cùng một ranh giới khi chạy trên database không có RLS.
 * Ngữ cảnh khách không có khoá phân vùng nên không đọc được gì qua điểm nối này; phép kiểm phân công dạy
 * không nằm ở đây mà dùng chung {@code TeachingAccessChecks} của khối tổ chức dạy học.
 */
@Component
public class AttemptData {

	public record AttemptRow(UUID id, UUID assignmentId, String studentSub, String status,
			OffsetDateTime startedAt, OffsetDateTime submittedAt, String submitMode) {
	}

	public record AssignmentRow(UUID id, UUID classId, UUID examVersionId) {
	}

	private final JdbcTemplate jdbc;

	public AttemptData(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<AttemptRow> findAttempt(UUID attemptId) {
		String partitionKey = partitionKey();
		if (partitionKey == null) {
			return Optional.empty();
		}
		return this.jdbc.query("""
				select id, assignment_id, student_sub, status, started_at, submitted_at, submit_mode
				from exam_attempt where id = ? and partition_key = ?
				""", (rs, row) -> new AttemptRow(rs.getObject("id", UUID.class),
						rs.getObject("assignment_id", UUID.class), rs.getString("student_sub"),
						rs.getString("status"), rs.getObject("started_at", OffsetDateTime.class),
						rs.getObject("submitted_at", OffsetDateTime.class), rs.getString("submit_mode")),
				attemptId, partitionKey).stream().findFirst();
	}

	public Optional<AssignmentRow> findAssignment(UUID assignmentId) {
		String partitionKey = partitionKey();
		if (partitionKey == null) {
			return Optional.empty();
		}
		return this.jdbc.query("""
				select id, class_id, exam_version_id from exam_assignment where id = ? and partition_key = ?
				""", (rs, row) -> new AssignmentRow(rs.getObject("id", UUID.class),
						rs.getObject("class_id", UUID.class), rs.getObject("exam_version_id", UUID.class)),
				assignmentId, partitionKey).stream().findFirst();
	}

	public List<AttemptRow> attemptsOfAssignment(UUID assignmentId) {
		String partitionKey = partitionKey();
		if (partitionKey == null) {
			return List.of();
		}
		return this.jdbc.query("""
				select id, assignment_id, student_sub, status, started_at, submitted_at, submit_mode
				from exam_attempt where assignment_id = ? and partition_key = ?
				""", (rs, row) -> new AttemptRow(rs.getObject("id", UUID.class),
						rs.getObject("assignment_id", UUID.class), rs.getString("student_sub"),
						rs.getString("status"), rs.getObject("started_at", OffsetDateTime.class),
						rs.getObject("submitted_at", OffsetDateTime.class), rs.getString("submit_mode")),
				assignmentId, partitionKey);
	}

	public Map<UUID, String> answersOf(UUID attemptId) {
		String partitionKey = partitionKey();
		if (partitionKey == null) {
			return Map.of();
		}
		Map<UUID, String> answers = new HashMap<>();
		this.jdbc.query("""
				select exam_item_id, answer from attempt_answer where attempt_id = ? and partition_key = ?
				""", (rs, row) -> {
			answers.put(rs.getObject("exam_item_id", UUID.class), rs.getString("answer"));
			return null;
		}, attemptId, partitionKey);
		return answers;
	}

	public void updateAttemptStatus(UUID attemptId, String status) {
		String partitionKey = partitionKey();
		if (partitionKey == null) {
			return;
		}
		this.jdbc.update("""
				update exam_attempt set status = ?, updated_at = current_timestamp
				where id = ? and partition_key = ?
				""", status, attemptId, partitionKey);
	}

	private String partitionKey() {
		TenantContext context = TenantContexts.current();
		return context != null ? context.partitionKey() : null;
	}

}
