package com.giaokhoa.server.access;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Một phiên bản xác định của một {@link AccessResource}. Tính bất biến của phiên bản được quyết bởi dấu khoá
 * {@code lockedAt} đã ghi trên chính phiên bản — đặt khi phát hành hoặc khi tạo grant trên phiên bản — không suy
 * ra từ việc còn grant nào đang hiệu lực, và không bao giờ mở lại.
 */
@Entity
@Table(name = "resource_version")
public class ResourceVersion {

	public static final String STATUS_DRAFT = "draft";

	public static final String STATUS_PUBLISHED = "published";

	@Id
	private UUID id;

	@Column(name = "resource_id", nullable = false)
	private UUID resourceId;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "version_number", nullable = false)
	private int versionNumber;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "content_hash")
	private String contentHash;

	@Column(name = "created_by_sub", nullable = false)
	private String createdBySub;

	@Column(name = "locked_at")
	private OffsetDateTime lockedAt;

	@Column(name = "published_at")
	private OffsetDateTime publishedAt;

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

	public UUID getResourceId() {
		return this.resourceId;
	}

	public void setResourceId(UUID resourceId) {
		this.resourceId = resourceId;
	}

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public void setPartitionKey(String partitionKey) {
		this.partitionKey = partitionKey;
	}

	public int getVersionNumber() {
		return this.versionNumber;
	}

	public void setVersionNumber(int versionNumber) {
		this.versionNumber = versionNumber;
	}

	public String getStatus() {
		return this.status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getContentHash() {
		return this.contentHash;
	}

	public void setContentHash(String contentHash) {
		this.contentHash = contentHash;
	}

	public String getCreatedBySub() {
		return this.createdBySub;
	}

	public void setCreatedBySub(String createdBySub) {
		this.createdBySub = createdBySub;
	}

	public OffsetDateTime getLockedAt() {
		return this.lockedAt;
	}

	public void setLockedAt(OffsetDateTime lockedAt) {
		this.lockedAt = lockedAt;
	}

	public OffsetDateTime getPublishedAt() {
		return this.publishedAt;
	}

	public void setPublishedAt(OffsetDateTime publishedAt) {
		this.publishedAt = publishedAt;
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
