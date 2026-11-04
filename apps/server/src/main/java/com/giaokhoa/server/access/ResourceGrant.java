package com.giaokhoa.server.access;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Một grant tường minh theo đúng tài nguyên, đúng phiên bản, đúng chủ thể nhận và đúng một hành động. Hàng grant
 * nằm ở phân vùng của phía nhận; {@code sourcePartitionKey} do server sao chép từ tài nguyên nguồn khi tạo grant
 * để dịch vụ quyết định mở đúng phiên của phân vùng nguồn, client không được tự cung cấp giá trị này.
 */
@Entity
@Table(name = "resource_grant")
public class ResourceGrant {

	public static final String GRANTEE_USER = "user";

	public static final String GRANTEE_ORGANIZATION = "organization";

	public static final String MODE_TEMPORARY = "temporary";

	public static final String MODE_LONG_TERM = "long_term";

	@Id
	private UUID id;

	@Column(name = "resource_id", nullable = false)
	private UUID resourceId;

	@Column(name = "resource_version_id", nullable = false)
	private UUID resourceVersionId;

	@Column(name = "grantee_type", nullable = false)
	private String granteeType;

	@Column(name = "grantee_user_sub")
	private String granteeUserSub;

	@Column(name = "grantee_organization_id")
	private String granteeOrganizationId;

	@Column(name = "receiving_collection_id")
	private UUID receivingCollectionId;

	@Column(name = "action", nullable = false)
	private String action;

	@Column(name = "mode")
	private String mode;

	@Column(name = "starts_at", nullable = false)
	private OffsetDateTime startsAt;

	@Column(name = "expires_at")
	private OffsetDateTime expiresAt;

	@Column(name = "revocation_terms")
	private String revocationTerms;

	@Column(name = "grantor_sub", nullable = false)
	private String grantorSub;

	@Column(name = "source_partition_key", nullable = false)
	private String sourcePartitionKey;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "revoked_at")
	private OffsetDateTime revokedAt;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	/** Grant còn hiệu lực tại một thời điểm khi chưa bị thu hồi và thời điểm đó nằm trong cửa sổ hiệu lực. */
	public boolean isEffectiveAt(OffsetDateTime instant) {
		return this.revokedAt == null && !instant.isBefore(this.startsAt)
				&& (this.expiresAt == null || instant.isBefore(this.expiresAt));
	}

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

	public String getGranteeType() {
		return this.granteeType;
	}

	public void setGranteeType(String granteeType) {
		this.granteeType = granteeType;
	}

	public String getGranteeUserSub() {
		return this.granteeUserSub;
	}

	public void setGranteeUserSub(String granteeUserSub) {
		this.granteeUserSub = granteeUserSub;
	}

	public String getGranteeOrganizationId() {
		return this.granteeOrganizationId;
	}

	public void setGranteeOrganizationId(String granteeOrganizationId) {
		this.granteeOrganizationId = granteeOrganizationId;
	}

	public UUID getReceivingCollectionId() {
		return this.receivingCollectionId;
	}

	public void setReceivingCollectionId(UUID receivingCollectionId) {
		this.receivingCollectionId = receivingCollectionId;
	}

	public String getAction() {
		return this.action;
	}

	public void setAction(String action) {
		this.action = action;
	}

	public String getMode() {
		return this.mode;
	}

	public void setMode(String mode) {
		this.mode = mode;
	}

	public OffsetDateTime getStartsAt() {
		return this.startsAt;
	}

	public void setStartsAt(OffsetDateTime startsAt) {
		this.startsAt = startsAt;
	}

	public OffsetDateTime getExpiresAt() {
		return this.expiresAt;
	}

	public void setExpiresAt(OffsetDateTime expiresAt) {
		this.expiresAt = expiresAt;
	}

	public String getRevocationTerms() {
		return this.revocationTerms;
	}

	public void setRevocationTerms(String revocationTerms) {
		this.revocationTerms = revocationTerms;
	}

	public String getGrantorSub() {
		return this.grantorSub;
	}

	public void setGrantorSub(String grantorSub) {
		this.grantorSub = grantorSub;
	}

	public String getSourcePartitionKey() {
		return this.sourcePartitionKey;
	}

	public void setSourcePartitionKey(String sourcePartitionKey) {
		this.sourcePartitionKey = sourcePartitionKey;
	}

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public void setPartitionKey(String partitionKey) {
		this.partitionKey = partitionKey;
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
