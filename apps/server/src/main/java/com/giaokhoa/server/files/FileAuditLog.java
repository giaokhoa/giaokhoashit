package com.giaokhoa.server.files;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Một dòng dấu vết kiểm toán của nền tệp: người thực hiện, hành động và các định danh liên quan. Không ghi
 * token, khoá lưu trữ hay nội dung tệp vào dấu vết.
 */
@Entity
@Table(name = "file_audit_log")
public class FileAuditLog {

	public static final String ACTION_UPLOAD_FINALIZED = "upload_finalized";

	public static final String ACTION_ATTACH = "attach";

	public static final String ACTION_READ = "read";

	public static final String ACTION_QUARANTINE = "quarantine";

	@Id
	private UUID id;

	@Column(name = "actor_sub", nullable = false)
	private String actorSub;

	@Column(name = "action", nullable = false)
	private String action;

	@Column(name = "resource_id")
	private UUID resourceId;

	@Column(name = "resource_version_id")
	private UUID resourceVersionId;

	@Column(name = "blob_id")
	private UUID blobId;

	@Column(name = "link_id")
	private UUID linkId;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public String getActorSub() {
		return this.actorSub;
	}

	public void setActorSub(String actorSub) {
		this.actorSub = actorSub;
	}

	public String getAction() {
		return this.action;
	}

	public void setAction(String action) {
		this.action = action;
	}

	public UUID getResourceId() {
		return this.resourceId;
	}

	public void setResourceId(UUID resourceId) {
		this.resourceId = resourceId;
	}

	public UUID getResourceVersionId() {
		return this.resourceVersionId;
	}

	public void setResourceVersionId(UUID resourceVersionId) {
		this.resourceVersionId = resourceVersionId;
	}

	public UUID getBlobId() {
		return this.blobId;
	}

	public void setBlobId(UUID blobId) {
		this.blobId = blobId;
	}

	public UUID getLinkId() {
		return this.linkId;
	}

	public void setLinkId(UUID linkId) {
		this.linkId = linkId;
	}

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public OffsetDateTime getCreatedAt() {
		return this.createdAt;
	}

	public void setCreatedAt(OffsetDateTime createdAt) {
		this.createdAt = createdAt;
	}

}
