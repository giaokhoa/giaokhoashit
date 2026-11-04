package com.giaokhoa.server.assignment;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Câu trả lời đã lưu của một mục đề trong một lượt làm, giữ nguyên dạng JSON theo loại câu của snapshot
 * (`single_choice`: {"choiceId": "..."}; `essay_text`: {"text": "..."}). Ràng buộc duy nhất trên (attempt_id,
 * exam_item_id) khiến lưu lại một mục là ghi đè đúng hàng của mục đó.
 */
@Entity
@Table(name = "attempt_answer")
public class AttemptAnswer {

	@Id
	private UUID id;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "attempt_id", nullable = false)
	private UUID attemptId;

	@Column(name = "exam_item_id", nullable = false)
	private UUID examItemId;

	@Column(name = "answer", nullable = false)
	private String answer;

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

	public String getAnswer() {
		return this.answer;
	}

	public void setAnswer(String answer) {
		this.answer = answer;
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
