package com.giaokhoa.server.access;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Đầu mối quyền của một tài nguyên thuộc bất kỳ loại nào; nội dung chuyên biệt của loại tài nguyên không nằm ở
 * bảng này. "Thuộc trung tâm", "do ai tạo" và "được chia sẻ" là ba thuộc tính khác nhau: chủ sở hữu nằm ở
 * {@code ownerType} cùng trường chủ sở hữu tương ứng, {@code createdBySub} chỉ là truy vết và không tham gia bất
 * kỳ quyết định quyền nào.
 */
@Entity
@Table(name = "resource")
public class AccessResource {

	public static final String WORKSPACE_PERSONAL = "personal";

	public static final String WORKSPACE_CENTER = "center";

	public static final String WORKSPACE_SYSTEM = "system";

	public static final String OWNER_USER = "user";

	public static final String OWNER_ORGANIZATION = "organization";

	public static final String OWNER_PLATFORM = "platform";

	public static final String CUSTODY_OWNED = "owned";

	public static final String CUSTODY_LICENSED = "licensed";

	/**
	 * Điều kiện nghiệp vụ của bản đã nhận qua nộp: chờ duyệt. Khi còn ở trạng thái này, hành động {@code use} bị
	 * chặn với mọi người và {@code view_content} chỉ còn cho người có nguồn quyền duyệt.
	 */
	public static final String CONDITION_PENDING_REVIEW = "PENDING_REVIEW";

	public static final String CONDITION_ACCEPTED = "ACCEPTED";

	public static final String CONDITION_REJECTED = "REJECTED";

	@Id
	private UUID id;

	@Column(name = "resource_type", nullable = false)
	private String resourceType;

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

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "custody_kind", nullable = false)
	private String custodyKind;

	@Column(name = "license_grant_id")
	private UUID licenseGrantId;

	@Column(name = "inherited_usage_limits")
	private String inheritedUsageLimits;

	@Column(name = "source_resource_id")
	private UUID sourceResourceId;

	@Column(name = "source_version_id")
	private UUID sourceVersionId;

	@Column(name = "business_condition")
	private String businessCondition;

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

	public String getResourceType() {
		return this.resourceType;
	}

	public void setResourceType(String resourceType) {
		this.resourceType = resourceType;
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

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public void setPartitionKey(String partitionKey) {
		this.partitionKey = partitionKey;
	}

	public String getCustodyKind() {
		return this.custodyKind;
	}

	public void setCustodyKind(String custodyKind) {
		this.custodyKind = custodyKind;
	}

	public UUID getLicenseGrantId() {
		return this.licenseGrantId;
	}

	public void setLicenseGrantId(UUID licenseGrantId) {
		this.licenseGrantId = licenseGrantId;
	}

	public String getInheritedUsageLimits() {
		return this.inheritedUsageLimits;
	}

	public void setInheritedUsageLimits(String inheritedUsageLimits) {
		this.inheritedUsageLimits = inheritedUsageLimits;
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

	public String getBusinessCondition() {
		return this.businessCondition;
	}

	public void setBusinessCondition(String businessCondition) {
		this.businessCondition = businessCondition;
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
