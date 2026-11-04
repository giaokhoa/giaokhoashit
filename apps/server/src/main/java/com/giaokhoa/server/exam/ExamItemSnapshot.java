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
 * Nội dung đóng băng của một mục đề, chỉ tạo tại thời điểm phát hành: toàn bộ nội dung của phiên bản câu (thân
 * câu, đáp án, lời giải/tiêu chí chấm) dưới dạng văn bản JSON trong {@code content}, kèm điểm và thứ tự của mục.
 * Nội dung lưu dạng text thay vì jsonb vì jsonb không giữ thứ tự khoá và khoảng trắng của đầu vào nên sẽ phá
 * tính nguyên vẹn từng ký tự mà {@code content_hash} của phiên bản đề bảo chứng. Sau khi ghi, hàng này không
 * bao giờ được sửa: sửa hay xoá câu nguồn không đổi nội dung của đề đã phát hành.
 */
@Entity
@Table(name = "exam_item_snapshot")
public class ExamItemSnapshot {

	@Id
	private UUID id;

	@Column(name = "exam_item_id", nullable = false)
	private UUID examItemId;

	@Column(name = "exam_version_id", nullable = false)
	private UUID examVersionId;

	// Hibernate tự đóng dấu bằng tenant id của session khi hàng được persist lần đầu; ứng dụng không tự đặt.
	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "question_version_id", nullable = false)
	private UUID questionVersionId;

	@Column(name = "question_type", nullable = false)
	private String questionType;

	@Column(name = "position", nullable = false)
	private int position;

	@Column(name = "score", nullable = false)
	private BigDecimal score;

	@Column(name = "content", nullable = false, columnDefinition = "text")
	private String content;

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

	public UUID getExamItemId() {
		return this.examItemId;
	}

	public void setExamItemId(UUID examItemId) {
		this.examItemId = examItemId;
	}

	public UUID getExamVersionId() {
		return this.examVersionId;
	}

	public void setExamVersionId(UUID examVersionId) {
		this.examVersionId = examVersionId;
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

	public String getQuestionType() {
		return this.questionType;
	}

	public void setQuestionType(String questionType) {
		this.questionType = questionType;
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

	public String getContent() {
		return this.content;
	}

	public void setContent(String content) {
		this.content = content;
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
