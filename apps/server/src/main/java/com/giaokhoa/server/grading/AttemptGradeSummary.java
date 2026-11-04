package com.giaokhoa.server.grading;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Tổng hợp chấm của một lượt làm: hàng này chỉ tồn tại từ thời điểm lượt làm đạt trạng thái {@code GRADED}, ghi
 * nhận xét chung gần nhất, người hoàn tất việc chấm và mốc hoàn tất. Lượt làm được chấm trọn bằng chấm tự động
 * (đề chỉ gồm trắc nghiệm) thì {@code gradedBySub} trống vì không có giáo viên nào chấm tay.
 */
@Entity
@Table(name = "attempt_grade_summary")
public class AttemptGradeSummary {

	@Id
	@Column(name = "attempt_id")
	private UUID attemptId;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "overall_comment")
	private String overallComment;

	@Column(name = "graded_by_sub")
	private String gradedBySub;

	@Column(name = "graded_at", nullable = false)
	private OffsetDateTime gradedAt;

	public UUID getAttemptId() {
		return this.attemptId;
	}

	public void setAttemptId(UUID attemptId) {
		this.attemptId = attemptId;
	}

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public void setPartitionKey(String partitionKey) {
		this.partitionKey = partitionKey;
	}

	public String getOverallComment() {
		return this.overallComment;
	}

	public void setOverallComment(String overallComment) {
		this.overallComment = overallComment;
	}

	public String getGradedBySub() {
		return this.gradedBySub;
	}

	public void setGradedBySub(String gradedBySub) {
		this.gradedBySub = gradedBySub;
	}

	public OffsetDateTime getGradedAt() {
		return this.gradedAt;
	}

	public void setGradedAt(OffsetDateTime gradedAt) {
		this.gradedAt = gradedAt;
	}

}
