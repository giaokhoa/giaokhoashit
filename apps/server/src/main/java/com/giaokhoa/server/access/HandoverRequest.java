package com.giaokhoa.server.access;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Yêu cầu nộp do một trung tâm tạo cho một giáo viên. Yêu cầu này không cấp cho trung tâm bất kỳ quyền đọc nào
 * trên kho cá nhân của giáo viên; người của trung tâm chỉ thấy yêu cầu và các biên nhận thuộc trung tâm mình.
 */
@Entity
@Table(name = "handover_request")
public class HandoverRequest {

	public static final String STATUS_OPEN = "open";

	public static final String STATUS_CLOSED = "closed";

	@Id
	private UUID id;

	@Column(name = "organization_id", nullable = false)
	private String organizationId;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "requested_teacher_sub", nullable = false)
	private String requestedTeacherSub;

	@Column(name = "resource_types")
	private String resourceTypes;

	@Column(name = "destination_collection_id")
	private UUID destinationCollectionId;

	@Column(name = "reuse_rights")
	private String reuseRights;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "created_by_sub", nullable = false)
	private String createdBySub;

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

	public String getOrganizationId() {
		return this.organizationId;
	}

	public void setOrganizationId(String organizationId) {
		this.organizationId = organizationId;
	}

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public void setPartitionKey(String partitionKey) {
		this.partitionKey = partitionKey;
	}

	public String getRequestedTeacherSub() {
		return this.requestedTeacherSub;
	}

	public void setRequestedTeacherSub(String requestedTeacherSub) {
		this.requestedTeacherSub = requestedTeacherSub;
	}

	public String getResourceTypes() {
		return this.resourceTypes;
	}

	public void setResourceTypes(String resourceTypes) {
		this.resourceTypes = resourceTypes;
	}

	public UUID getDestinationCollectionId() {
		return this.destinationCollectionId;
	}

	public void setDestinationCollectionId(UUID destinationCollectionId) {
		this.destinationCollectionId = destinationCollectionId;
	}

	public String getReuseRights() {
		return this.reuseRights;
	}

	public void setReuseRights(String reuseRights) {
		this.reuseRights = reuseRights;
	}

	public String getStatus() {
		return this.status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getCreatedBySub() {
		return this.createdBySub;
	}

	public void setCreatedBySub(String createdBySub) {
		this.createdBySub = createdBySub;
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
