package com.giaokhoa.server.files;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Một phiên tải lên: khoá người gọi, đích gắn (tài nguyên và phiên bản) và giới hạn của lượt tải ngay từ bước
 * xin phiên; đích đã khoá thì bước hoàn tất không đổi sang đích khác được. Phiên chưa hoàn tất quá thời hạn cấu
 * hình là phiên dở và bị dọn.
 */
@Entity
@Table(name = "upload_session")
public class UploadSession {

	public static final String STATUS_OPEN = "OPEN";

	public static final String STATUS_READY = "READY";

	public static final String STATUS_COMPLETED = "COMPLETED";

	public static final String STATUS_EXPIRED = "EXPIRED";

	@Id
	private UUID id;

	@Column(name = "uploader_sub", nullable = false)
	private String uploaderSub;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "target_resource_id", nullable = false)
	private UUID targetResourceId;

	@Column(name = "target_version_id", nullable = false)
	private UUID targetVersionId;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "staging_key")
	private String stagingKey;

	@Column(name = "blob_id")
	private UUID blobId;

	@Column(name = "link_id")
	private UUID linkId;

	@Column(name = "expires_at", nullable = false)
	private OffsetDateTime expiresAt;

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

	public String getUploaderSub() {
		return this.uploaderSub;
	}

	public void setUploaderSub(String uploaderSub) {
		this.uploaderSub = uploaderSub;
	}

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public UUID getTargetResourceId() {
		return this.targetResourceId;
	}

	public void setTargetResourceId(UUID targetResourceId) {
		this.targetResourceId = targetResourceId;
	}

	public UUID getTargetVersionId() {
		return this.targetVersionId;
	}

	public void setTargetVersionId(UUID targetVersionId) {
		this.targetVersionId = targetVersionId;
	}

	public String getDisplayName() {
		return this.displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getStatus() {
		return this.status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getStagingKey() {
		return this.stagingKey;
	}

	public void setStagingKey(String stagingKey) {
		this.stagingKey = stagingKey;
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

	public OffsetDateTime getExpiresAt() {
		return this.expiresAt;
	}

	public void setExpiresAt(OffsetDateTime expiresAt) {
		this.expiresAt = expiresAt;
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
