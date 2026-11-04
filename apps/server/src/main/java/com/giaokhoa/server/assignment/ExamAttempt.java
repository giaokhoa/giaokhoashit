package com.giaokhoa.server.assignment;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Một lượt làm của một học viên cho một lần giao. Ràng buộc duy nhất trên (assignment_id, student_sub) ở tầng
 * database giữ bất biến "một học viên — một lượt cho mỗi lần giao"; trạng thái `NOT_STARTED` không bao giờ là
 * một hàng ở đây, nó được suy ra từ lần giao cộng danh sách ghi danh khi chưa có hàng.
 */
@Entity
@Table(name = "exam_attempt")
public class ExamAttempt {

	public static final String IN_PROGRESS = "IN_PROGRESS";

	public static final String SUBMITTED = "SUBMITTED";

	public static final String GRADED = "GRADED";

	public static final String PUBLISHED = "PUBLISHED";

	public static final String SUBMIT_MANUAL = "manual";

	public static final String SUBMIT_AUTO_DEADLINE = "auto_deadline";

	@Id
	private UUID id;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "assignment_id", nullable = false)
	private UUID assignmentId;

	@Column(name = "student_sub", nullable = false)
	private String studentSub;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "started_at")
	private OffsetDateTime startedAt;

	@Column(name = "submitted_at")
	private OffsetDateTime submittedAt;

	@Column(name = "submit_mode")
	private String submitMode;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public UUID getAssignmentId() {
		return this.assignmentId;
	}

	public void setAssignmentId(UUID assignmentId) {
		this.assignmentId = assignmentId;
	}

	public String getStudentSub() {
		return this.studentSub;
	}

	public void setStudentSub(String studentSub) {
		this.studentSub = studentSub;
	}

	public String getStatus() {
		return this.status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public OffsetDateTime getStartedAt() {
		return this.startedAt;
	}

	public void setStartedAt(OffsetDateTime startedAt) {
		this.startedAt = startedAt;
	}

	public OffsetDateTime getSubmittedAt() {
		return this.submittedAt;
	}

	public void setSubmittedAt(OffsetDateTime submittedAt) {
		this.submittedAt = submittedAt;
	}

	public String getSubmitMode() {
		return this.submitMode;
	}

	public void setSubmitMode(String submitMode) {
		this.submitMode = submitMode;
	}

	public OffsetDateTime getCreatedAt() {
		return this.createdAt;
	}

	public void setCreatedAt(OffsetDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public OffsetDateTime getUpdatedAt() {
		return this.updatedAt;
	}

	public void setUpdatedAt(OffsetDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

}
