package com.giaokhoa.server.question;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "question")
public class Question {

	@Id
	private UUID id;

	@Column(name = "owner_sub", nullable = false)
	private String ownerSub;

	@Column(name = "bank_id", nullable = false)
	private UUID bankId;

	// Truy vết nguồn khi câu này là một bản sao: bản sao là tài nguyên mới của người sao chép, không kế thừa
	// quyền của nguồn, nhưng giữ nguyên các giới hạn sử dụng đã ghi trên nguồn (usage_limits).
	@Column(name = "source_question_id")
	private UUID sourceQuestionId;

	@Column(name = "source_version_id")
	private UUID sourceVersionId;

	@Column(name = "usage_limits")
	private String usageLimits;

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

	public String getOwnerSub() {
		return this.ownerSub;
	}

	public void setOwnerSub(String ownerSub) {
		this.ownerSub = ownerSub;
	}

	public UUID getBankId() {
		return this.bankId;
	}

	public void setBankId(UUID bankId) {
		this.bankId = bankId;
	}

	public UUID getSourceQuestionId() {
		return this.sourceQuestionId;
	}

	public void setSourceQuestionId(UUID sourceQuestionId) {
		this.sourceQuestionId = sourceQuestionId;
	}

	public UUID getSourceVersionId() {
		return this.sourceVersionId;
	}

	public void setSourceVersionId(UUID sourceVersionId) {
		this.sourceVersionId = sourceVersionId;
	}

	public String getUsageLimits() {
		return this.usageLimits;
	}

	public void setUsageLimits(String usageLimits) {
		this.usageLimits = usageLimits;
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
