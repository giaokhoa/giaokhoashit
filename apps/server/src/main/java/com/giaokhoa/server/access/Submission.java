package com.giaokhoa.server.access;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Biên nhận của một lần nộp theo một yêu cầu bàn giao. Nội dung biên nhận — yêu cầu, người nộp, thời điểm, phiên
 * bản nguồn và bản đã nhận qua các {@link SubmissionItem} — là bất biến sau khi tạo.
 */
@Entity
@Table(name = "submission")
public class Submission {

	@Id
	private UUID id;

	@Column(name = "handover_request_id", nullable = false)
	private UUID handoverRequestId;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "submitter_sub", nullable = false)
	private String submitterSub;

	@Column(name = "submitted_at", nullable = false)
	private OffsetDateTime submittedAt;

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

	public UUID getHandoverRequestId() {
		return this.handoverRequestId;
	}

	public void setHandoverRequestId(UUID handoverRequestId) {
		this.handoverRequestId = handoverRequestId;
	}

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public String getSubmitterSub() {
		return this.submitterSub;
	}

	public void setSubmitterSub(String submitterSub) {
		this.submitterSub = submitterSub;
	}

	public OffsetDateTime getSubmittedAt() {
		return this.submittedAt;
	}

	public void setSubmittedAt(OffsetDateTime submittedAt) {
		this.submittedAt = submittedAt;
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
