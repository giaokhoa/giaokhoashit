package com.giaokhoa.server.access;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Ghi việc một phiên bản của một tài nguyên dùng tới một phiên bản xác định của một tài nguyên khác. Quan hệ này
 * chỉ phục vụ truy vết và làm căn cứ để các feat loại tài nguyên áp giới hạn sử dụng kế thừa khi đóng gói, chia
 * sẻ hoặc bàn giao; tự nó không cấp bất kỳ quyền nào trên tài nguyên được dùng tới.
 */
@Entity
@Table(name = "resource_dependency")
public class ResourceDependency {

	@Id
	private UUID id;

	@Column(name = "resource_id", nullable = false)
	private UUID resourceId;

	@Column(name = "resource_version_id", nullable = false)
	private UUID resourceVersionId;

	@Column(name = "depends_on_resource_id", nullable = false)
	private UUID dependsOnResourceId;

	@Column(name = "depends_on_version_id", nullable = false)
	private UUID dependsOnVersionId;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

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

	public UUID getResourceVersionId() {
		return this.resourceVersionId;
	}

	public void setResourceVersionId(UUID resourceVersionId) {
		this.resourceVersionId = resourceVersionId;
	}

	public UUID getDependsOnResourceId() {
		return this.dependsOnResourceId;
	}

	public void setDependsOnResourceId(UUID dependsOnResourceId) {
		this.dependsOnResourceId = dependsOnResourceId;
	}

	public UUID getDependsOnVersionId() {
		return this.dependsOnVersionId;
	}

	public void setDependsOnVersionId(UUID dependsOnVersionId) {
		this.dependsOnVersionId = dependsOnVersionId;
	}

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public void setPartitionKey(String partitionKey) {
		this.partitionKey = partitionKey;
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
