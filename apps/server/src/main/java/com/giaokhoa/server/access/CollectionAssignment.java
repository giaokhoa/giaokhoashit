package com.giaokhoa.server.access;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Một phân công: một người thực hiện một vai trên các tài nguyên thuộc một ngân hàng/bộ sưu tập và một loại tài
 * nguyên, trong đúng một Organization. Hàng phân công tự nó không biến người được phân công thành thành viên của
 * Organization — quyết định theo phân công còn yêu cầu ngữ cảnh đang ở chế độ thành viên của đúng Organization đó.
 */
@Entity
@Table(name = "collection_assignment")
public class CollectionAssignment {

	@Id
	private UUID id;

	@Column(name = "organization_id", nullable = false)
	private String organizationId;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "collection_id", nullable = false)
	private UUID collectionId;

	@Column(name = "resource_type", nullable = false)
	private String resourceType;

	@Column(name = "assignee_sub", nullable = false)
	private String assigneeSub;

	@Column(name = "assignment_role", nullable = false)
	private String assignmentRole;

	@Column(name = "assigned_by_sub", nullable = false)
	private String assignedBySub;

	@Column(name = "revoked_at")
	private OffsetDateTime revokedAt;

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

	public UUID getCollectionId() {
		return this.collectionId;
	}

	public void setCollectionId(UUID collectionId) {
		this.collectionId = collectionId;
	}

	public String getResourceType() {
		return this.resourceType;
	}

	public void setResourceType(String resourceType) {
		this.resourceType = resourceType;
	}

	public String getAssigneeSub() {
		return this.assigneeSub;
	}

	public void setAssigneeSub(String assigneeSub) {
		this.assigneeSub = assigneeSub;
	}

	public String getAssignmentRole() {
		return this.assignmentRole;
	}

	public void setAssignmentRole(String assignmentRole) {
		this.assignmentRole = assignmentRole;
	}

	public String getAssignedBySub() {
		return this.assignedBySub;
	}

	public void setAssignedBySub(String assignedBySub) {
		this.assignedBySub = assignedBySub;
	}

	public OffsetDateTime getRevokedAt() {
		return this.revokedAt;
	}

	public void setRevokedAt(OffsetDateTime revokedAt) {
		this.revokedAt = revokedAt;
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
