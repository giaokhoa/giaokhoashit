package com.giaokhoa.server.exam;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Một mục của một phiên bản đề: trỏ tới đúng một phiên bản câu cụ thể ({@code question_version_id}), không trỏ
 * "phiên bản mới nhất" của câu, kèm thứ tự trong đề và điểm của mục. Hàng này là quan hệ phụ thuộc đề–câu ở lát
 * tối thiểu; khi phát hành, nội dung của mục được đóng băng sang {@code exam_item_snapshot}.
 */
@Entity
@Table(name = "exam_item")
public class ExamItem {

	@Id
	private UUID id;

	@Column(name = "exam_version_id", nullable = false)
	private UUID examVersionId;

	@Column(name = "exam_id", nullable = false)
	private UUID examId;

	// Hibernate tự đóng dấu bằng tenant id của session khi hàng được persist lần đầu; ứng dụng không tự đặt.
	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "question_version_id", nullable = false)
	private UUID questionVersionId;

	@Column(name = "position", nullable = false)
	private int position;

	@Column(name = "score", nullable = false)
	private BigDecimal score;

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

	public UUID getExamVersionId() {
		return this.examVersionId;
	}

	public void setExamVersionId(UUID examVersionId) {
		this.examVersionId = examVersionId;
	}

	public UUID getExamId() {
		return this.examId;
	}

	public void setExamId(UUID examId) {
		this.examId = examId;
	}

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public void setPartitionKey(String partitionKey) {
		this.partitionKey = partitionKey;
	}

	public UUID getQuestionVersionId() {
		return this.questionVersionId;
	}

	public void setQuestionVersionId(UUID questionVersionId) {
		this.questionVersionId = questionVersionId;
	}

	public int getPosition() {
		return this.position;
	}

	public void setPosition(int position) {
		this.position = position;
	}

	public BigDecimal getScore() {
		return this.score;
	}

	public void setScore(BigDecimal score) {
		this.score = score;
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
