package com.giaokhoa.server.question;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "bank_entry")
public class BankEntry {

	@Id
	private UUID id;

	@Column(name = "bank_id", nullable = false)
	private UUID bankId;

	@Column(name = "question_id", nullable = false)
	private UUID questionId;

	// Một mục ngân hàng trỏ tới đúng một phiên bản cụ thể, không trỏ tới "phiên bản mới nhất": gỡ mục chỉ xoá
	// hàng này, câu hỏi và phiên bản được trỏ tới không đổi.
	@Column(name = "question_version_id", nullable = false)
	private UUID questionVersionId;

	@Column(name = "owner_sub", nullable = false)
	private String ownerSub;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public UUID getBankId() {
		return this.bankId;
	}

	public void setBankId(UUID bankId) {
		this.bankId = bankId;
	}

	public UUID getQuestionId() {
		return this.questionId;
	}

	public void setQuestionId(UUID questionId) {
		this.questionId = questionId;
	}

	public UUID getQuestionVersionId() {
		return this.questionVersionId;
	}

	public void setQuestionVersionId(UUID questionVersionId) {
		this.questionVersionId = questionVersionId;
	}

	public String getOwnerSub() {
		return this.ownerSub;
	}

	public void setOwnerSub(String ownerSub) {
		this.ownerSub = ownerSub;
	}

	public OffsetDateTime getCreatedAt() {
		return this.createdAt;
	}

	public void setCreatedAt(OffsetDateTime createdAt) {
		this.createdAt = createdAt;
	}

}
