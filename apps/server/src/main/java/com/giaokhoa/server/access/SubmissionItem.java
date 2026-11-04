package com.giaokhoa.server.access;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/** Một mục trong biên nhận nộp: ánh xạ một phiên bản nguồn của giáo viên tới bản thuộc trung tâm đã tạo cho nó. */
@Entity
@Table(name = "submission_item")
public class SubmissionItem {

	@Id
	private UUID id;

	@Column(name = "submission_id", nullable = false)
	private UUID submissionId;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "source_resource_id", nullable = false)
	private UUID sourceResourceId;

	@Column(name = "source_version_id", nullable = false)
	private UUID sourceVersionId;

	@Column(name = "received_resource_id", nullable = false)
	private UUID receivedResourceId;

	@Column(name = "received_version_id", nullable = false)
	private UUID receivedVersionId;

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

	public UUID getSubmissionId() {
		return this.submissionId;
	}

	public void setSubmissionId(UUID submissionId) {
		this.submissionId = submissionId;
	}

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public UUID getSourceResourceId() {
		return this.sourceResourceId;
	}

	public void setSourceResourceId(UUID sourceResourceId) {
		this.sourceResourceId = sourceResourceId;
	}

	public UUID getSourceVersionId() {
		return this.sourceVersionId;
	}

	public void setSourceVersionId(UUID sourceVersionId) {
		this.sourceVersionId = sourceVersionId;
	}

	public UUID getReceivedResourceId() {
		return this.receivedResourceId;
	}

	public void setReceivedResourceId(UUID receivedResourceId) {
		this.receivedResourceId = receivedResourceId;
	}

	public UUID getReceivedVersionId() {
		return this.receivedVersionId;
	}

	public void setReceivedVersionId(UUID receivedVersionId) {
		this.receivedVersionId = receivedVersionId;
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
