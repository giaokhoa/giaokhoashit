package com.giaokhoa.server.exam;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Một đề. Đề thuộc đúng một workspace và một chủ sở hữu; {@code created_by_sub} chỉ là truy vết người tạo, không
 * sinh quyền — quyền trên đề do điểm quyết định suy ra từ workspace và chủ sở hữu. Các phiên bản của đề nằm ở bảng
 * {@code exam_version}, nội dung mục ở {@code exam_item}.
 */
@Entity
@Table(name = "exam")
public class Exam {

	@Id
	private UUID id;

	// Hibernate tự đóng dấu bằng tenant id của session khi hàng được persist lần đầu; ứng dụng không tự đặt.
	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "workspace_kind", nullable = false)
	private String workspaceKind;

	@Column(name = "owner_type", nullable = false)
	private String ownerType;

	@Column(name = "owner_user_sub")
	private String ownerUserSub;

	@Column(name = "owner_organization_id")
	private String ownerOrganizationId;

	@Column(name = "collection_id")
	private UUID collectionId;

	@Column(name = "custody_kind", nullable = false)
	private String custodyKind;

	@Column(name = "title", nullable = false)
	private String title;

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

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public String getWorkspaceKind() {
		return this.workspaceKind;
	}

	public void setWorkspaceKind(String workspaceKind) {
		this.workspaceKind = workspaceKind;
	}

	public String getOwnerType() {
		return this.ownerType;
	}

	public void setOwnerType(String ownerType) {
		this.ownerType = ownerType;
	}

	public String getOwnerUserSub() {
		return this.ownerUserSub;
	}

	public void setOwnerUserSub(String ownerUserSub) {
		this.ownerUserSub = ownerUserSub;
	}

	public String getOwnerOrganizationId() {
		return this.ownerOrganizationId;
	}

	public void setOwnerOrganizationId(String ownerOrganizationId) {
		this.ownerOrganizationId = ownerOrganizationId;
	}

	public UUID getCollectionId() {
		return this.collectionId;
	}

	public void setCollectionId(UUID collectionId) {
		this.collectionId = collectionId;
	}

	public String getCustodyKind() {
		return this.custodyKind;
	}

	public void setCustodyKind(String custodyKind) {
		this.custodyKind = custodyKind;
	}

	public String getTitle() {
		return this.title;
	}

	public void setTitle(String title) {
		this.title = title;
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
