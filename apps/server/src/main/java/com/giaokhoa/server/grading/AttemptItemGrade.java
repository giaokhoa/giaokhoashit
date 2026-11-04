package com.giaokhoa.server.grading;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Điểm hiện hành của một mục trong một lượt làm: mỗi cặp (lượt làm, mục) có tối đa một hàng, chấm lại là cập
 * nhật đúng hàng đó. Điểm trắc nghiệm do chấm tự động ghi tại thời điểm nộp với {@code source} là
 * {@value #SOURCE_AUTO}; điểm tự luận do giáo viên phụ trách lớp chấm tay với {@code source} là
 * {@value #SOURCE_MANUAL}.
 */
@Entity
@Table(name = "attempt_item_grade")
public class AttemptItemGrade {

	public static final String SOURCE_AUTO = "auto";

	public static final String SOURCE_MANUAL = "manual";

	@Id
	private UUID id;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "attempt_id", nullable = false)
	private UUID attemptId;

	@Column(name = "exam_item_id", nullable = false)
	private UUID examItemId;

	@Column(name = "score", nullable = false)
	private BigDecimal score;

	@Column(name = "max_score", nullable = false)
	private BigDecimal maxScore;

	@Column(name = "source", nullable = false)
	private String source;

	@Column(name = "grader_sub")
	private String graderSub;

	@Column(name = "comment")
	private String comment;

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

	public void setPartitionKey(String partitionKey) {
		this.partitionKey = partitionKey;
	}

	public UUID getAttemptId() {
		return this.attemptId;
	}

	public void setAttemptId(UUID attemptId) {
		this.attemptId = attemptId;
	}

	public UUID getExamItemId() {
		return this.examItemId;
	}

	public void setExamItemId(UUID examItemId) {
		this.examItemId = examItemId;
	}

	public BigDecimal getScore() {
		return this.score;
	}

	public void setScore(BigDecimal score) {
		this.score = score;
	}

	public BigDecimal getMaxScore() {
		return this.maxScore;
	}

	public void setMaxScore(BigDecimal maxScore) {
		this.maxScore = maxScore;
	}

	public String getSource() {
		return this.source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public String getGraderSub() {
		return this.graderSub;
	}

	public void setGraderSub(String graderSub) {
		this.graderSub = graderSub;
	}

	public String getComment() {
		return this.comment;
	}

	public void setComment(String comment) {
		this.comment = comment;
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
