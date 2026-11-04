package com.giaokhoa.server.access;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.giaokhoa.server.tenancy.TenantContext;

import org.springframework.stereotype.Service;

/**
 * Dịch vụ quyết định quyền tập trung của lõi: mọi thao tác đọc, ghi, chia sẻ, nộp và truy cập tài nguyên đều qua
 * đây, không endpoint hay feat nào tự suy diễn quyền. Quyết định chỉ dựa trên ngữ cảnh đã xác minh
 * ({@link TenantContext}) và dữ kiện đã lưu tại API; các trường tự khai trong thân yêu cầu không bao giờ là đầu
 * vào. Thuật toán: xác định các phân vùng ứng viên của người gọi; tìm tài nguyên trong các phân vùng đó, nếu không
 * có thì tìm grant trong các phân vùng phía nhận mà người gọi đại diện rồi đọc tài nguyên nguồn ngay trong phân
 * vùng của nó; khớp một nguồn quyền; sau khi khớp vẫn thu hẹp theo trạng thái phiên bản, giới hạn sử dụng kế
 * thừa, điều kiện nghiệp vụ của bản và hiệu lực của grant cấp phép; không khớp gì thì từ chối mặc định.
 */
@Service
public class AccessDecisionService {

	public static final String REASON_ALLOWED = "ALLOWED";

	public static final String REASON_NOT_FOUND = "RESOURCE_NOT_FOUND";

	public static final String REASON_VERSION_MISMATCH = "VERSION_MISMATCH";

	public static final String REASON_NO_SOURCE = "NO_MATCHING_SOURCE";

	public static final String REASON_VERSION_LOCKED = "VERSION_LOCKED";

	public static final String REASON_VERSION_NOT_DRAFT = "VERSION_NOT_DRAFT";

	public static final String REASON_USAGE_LIMIT = "USAGE_LIMIT_EXCEEDED";

	public static final String REASON_LICENSE_INACTIVE = "LICENSE_GRANT_INACTIVE";

	public static final String REASON_PENDING_REVIEW = "PENDING_REVIEW";

	public static final String SOURCE_NONE = "NONE";

	public static final String SOURCE_OWNER = "OWNER";

	public static final String SOURCE_PLATFORM_ADMIN = "PLATFORM_ADMIN";

	public static final String SOURCE_TENANT_ADMIN = "TENANT_ADMIN";

	public static final String SOURCE_ASSIGNMENT = "ASSIGNMENT";

	public static final String SOURCE_ORG_GRANT = "ORG_GRANT";

	public static final String SOURCE_USER_GRANT = "USER_GRANT";

	public static final String SOURCE_LICENSED = "LICENSED";

	private static final Set<AccessAction> OWNER_ACTIONS = Set.of(AccessAction.VIEW_CONTENT,
			AccessAction.VIEW_ANSWER, AccessAction.USE, AccessAction.COPY, AccessAction.SHARE, AccessAction.SUBMIT,
			AccessAction.EDIT_DRAFT, AccessAction.PUBLISH);

	private static final Set<AccessAction> TENANT_ADMIN_ACTIONS = Set.of(AccessAction.MANAGE_COLLECTION,
			AccessAction.MANAGE_ASSIGNMENT, AccessAction.CREATE_IN_COLLECTION, AccessAction.VIEW_CONTENT,
			AccessAction.EDIT_DRAFT, AccessAction.REVIEW, AccessAction.PUBLISH, AccessAction.SHARE);

	/** Các hành động đọc/dùng nội dung — chỉ những hành động này mới bị grant cấp phép của bản sao điều tiết. */
	private static final Set<AccessAction> CONTENT_ACTIONS = Set.of(AccessAction.VIEW_CONTENT,
			AccessAction.VIEW_ANSWER, AccessAction.USE, AccessAction.COPY, AccessAction.SHARE, AccessAction.SUBMIT,
			AccessAction.EDIT_DRAFT, AccessAction.REVIEW, AccessAction.PUBLISH);

	private final AccessResourceRepository resources;

	private final ResourceVersionRepository versions;

	private final ResourceGrantRepository grants;

	private final CollectionAssignmentRepository assignments;

	private final PartitionSessions sessions;

	public AccessDecisionService(AccessResourceRepository resources, ResourceVersionRepository versions,
			ResourceGrantRepository grants, CollectionAssignmentRepository assignments, PartitionSessions sessions) {
		this.resources = resources;
		this.versions = versions;
		this.grants = grants;
		this.assignments = assignments;
		this.sessions = sessions;
	}

	/** Kết quả quyết định: cho phép hay không, mã lý do và nguồn quyền đã khớp (nếu có). */
	public record Decision(boolean allowed, String reasonCode, String source) {
	}

	/**
	 * Tài nguyên đã định vị được cùng phân vùng chứa nó. Khi tìm qua grant, danh sách grant của tài nguyên mà
	 * người gọi đại diện được mang kèm để bước khớp nguồn không phải tải lại.
	 */
	public record Located(AccessResource resource, String partitionKey, boolean viaGrant,
			List<ResourceGrant> grants) {
	}

	public boolean isCenterMember(TenantContext context) {
		return context.organizationId() != null && !context.guest();
	}

	public boolean isTenantAdmin(TenantContext context) {
		return isCenterMember(context) && context.organizationRoles().contains("tenant-admin");
	}

	public Located locate(TenantContext context, UUID resourceId) {
		for (String partition : candidatePartitions(context)) {
			AccessResource resource = this.sessions.inPartition(partition, context.sub(),
					() -> this.resources.findById(resourceId).orElse(null));
			if (resource != null) {
				return new Located(resource, partition, false, List.of());
			}
		}
		List<ResourceGrant> callerGrants = new ArrayList<>();
		for (String partition : granteePartitions(context)) {
			callerGrants.addAll(this.sessions.inPartition(partition, context.sub(),
					() -> this.grants.findByResourceId(resourceId)));
		}
		if (callerGrants.isEmpty()) {
			return null;
		}
		String sourcePartition = callerGrants.get(0).getSourcePartitionKey();
		AccessResource resource = this.sessions.inPartition(sourcePartition, context.sub(),
				() -> this.resources.findById(resourceId).orElse(null));
		return (resource != null) ? new Located(resource, sourcePartition, true, callerGrants) : null;
	}

	public Decision decide(TenantContext context, UUID resourceId, UUID versionId, AccessAction action) {
		if (action == AccessAction.READ_MATH_SOURCE || action == AccessAction.EDIT_MATH_SOURCE) {
			return decideMathSource(context, resourceId, versionId, action, false);
		}
		Located located = locate(context, resourceId);
		if (located == null) {
			return deny(REASON_NOT_FOUND);
		}
		AccessResource resource = located.resource();
		ResourceVersion version = null;
		if (versionId != null) {
			version = this.sessions.inPartition(located.partitionKey(), context.sub(),
					() -> this.versions.findByIdAndResourceId(versionId, resourceId).orElse(null));
			if (version == null) {
				return deny(REASON_VERSION_MISMATCH);
			}
		}
		OffsetDateTime now = OffsetDateTime.now();
		String source = null;

		if (!located.viaGrant()) {
			if (AccessResource.WORKSPACE_PERSONAL.equals(resource.getWorkspaceKind())
					&& context.sub().equals(resource.getOwnerUserSub()) && OWNER_ACTIONS.contains(action)) {
				source = SOURCE_OWNER;
			}
			else if (AccessResource.WORKSPACE_SYSTEM.equals(resource.getWorkspaceKind())
					&& context.platformAdmin()) {
				source = SOURCE_PLATFORM_ADMIN;
			}
			if (source == null && AccessResource.OWNER_ORGANIZATION.equals(resource.getOwnerType())
					&& isCenterMember(context) && context.organizationId().equals(resource.getOwnerOrganizationId())) {
				if (AccessResource.CUSTODY_OWNED.equals(resource.getCustodyKind())) {
					if (isTenantAdmin(context) && TENANT_ADMIN_ACTIONS.contains(action)) {
						source = SOURCE_TENANT_ADMIN;
					}
					else if (assignmentCovers(context, resource, action)) {
						source = SOURCE_ASSIGNMENT;
					}
				}
				else if (isTenantAdmin(context) && (action == AccessAction.MANAGE_ASSIGNMENT
						|| action == AccessAction.MANAGE_COLLECTION)) {
					source = SOURCE_TENANT_ADMIN;
				}
				else if (licensedSourceAllows(context, resource, action, now)) {
					source = SOURCE_LICENSED;
				}
			}
		}

		if (source == null) {
			List<ResourceGrant> callerGrants = located.viaGrant() ? located.grants()
					: loadCallerGrants(context, resourceId);
			for (ResourceGrant grant : callerGrants) {
				if (!grant.getResourceId().equals(resourceId) || !grant.getAction().equals(action.code())) {
					continue;
				}
				if (versionId == null || !grant.getResourceVersionId().equals(versionId)) {
					continue;
				}
				if (!grant.isEffectiveAt(now)) {
					continue;
				}
				if (ResourceGrant.GRANTEE_USER.equals(grant.getGranteeType())) {
					if (context.sub().equals(grant.getGranteeUserSub())) {
						source = SOURCE_USER_GRANT;
						break;
					}
				}
				else if (isCenterMember(context) && context.organizationId().equals(grant.getGranteeOrganizationId())
						&& grant.getReceivingCollectionId() != null && assignmentGrantConditionCoversOn(context,
								grant.getReceivingCollectionId(), resource.getResourceType(), action)) {
					source = SOURCE_ORG_GRANT;
					break;
				}
			}
		}

		if (source == null) {
			return deny(REASON_NO_SOURCE);
		}
		return narrow(context, resource, version, action, source, now);
	}

	/**
	 * Quyết định hai hành động chuyên biệt trên nguồn LaTeX của một khối công thức. Nguồn quyền chỉ đến từ chủ sở
	 * hữu hiện hành, quản trị nền tảng trên tài nguyên hệ thống, hoặc phân công biên tập (vai editor) của trung
	 * tâm sở hữu — không bao giờ từ grant chia sẻ, không từ vai quản trị trung tâm đứng một mình và không từ tư
	 * cách người tạo hay người tải tệp lên. Khi khối nằm trong phần đáp án hay lời giải ({@code answerArea}), quyết
	 * định còn phải kết hợp điều kiện {@code view_answer} trên đúng tài nguyên và phiên bản đó.
	 */
	public Decision decideMathSource(TenantContext context, UUID resourceId, UUID versionId, AccessAction action,
			boolean answerArea) {
		if (action != AccessAction.READ_MATH_SOURCE && action != AccessAction.EDIT_MATH_SOURCE) {
			throw new IllegalArgumentException("decideMathSource chỉ quyết hai hành động nguồn LaTeX: " + action);
		}
		Located located = locate(context, resourceId);
		if (located == null) {
			return deny(REASON_NOT_FOUND);
		}
		if (located.viaGrant()) {
			return deny(REASON_NO_SOURCE);
		}
		AccessResource resource = located.resource();
		ResourceVersion version = null;
		if (versionId != null) {
			version = this.sessions.inPartition(located.partitionKey(), context.sub(),
					() -> this.versions.findByIdAndResourceId(versionId, resourceId).orElse(null));
			if (version == null) {
				return deny(REASON_VERSION_MISMATCH);
			}
		}
		String source = null;
		if (AccessResource.WORKSPACE_PERSONAL.equals(resource.getWorkspaceKind())
				&& context.sub().equals(resource.getOwnerUserSub())) {
			source = SOURCE_OWNER;
		}
		else if (AccessResource.WORKSPACE_SYSTEM.equals(resource.getWorkspaceKind()) && context.platformAdmin()) {
			source = SOURCE_PLATFORM_ADMIN;
		}
		else if (AccessResource.OWNER_ORGANIZATION.equals(resource.getOwnerType())
				&& AccessResource.CUSTODY_OWNED.equals(resource.getCustodyKind()) && isCenterMember(context)
				&& context.organizationId().equals(resource.getOwnerOrganizationId())
				&& editorAssignmentCovers(context, resource)) {
			source = SOURCE_ASSIGNMENT;
		}
		if (source == null) {
			return deny(REASON_NO_SOURCE);
		}
		if (answerArea && !decide(context, resourceId, versionId, AccessAction.VIEW_ANSWER).allowed()) {
			return deny(REASON_NO_SOURCE);
		}
		if (action == AccessAction.EDIT_MATH_SOURCE && version != null
				&& (ResourceVersion.STATUS_PUBLISHED.equals(version.getStatus()) || version.getLockedAt() != null)) {
			return new Decision(false, REASON_VERSION_LOCKED, source);
		}
		UsageLimits limits = UsageLimits.parse(resource.getInheritedUsageLimits());
		if (limits.restrictsActions() && !limits.actionCodes().contains(action.code())) {
			return new Decision(false, REASON_USAGE_LIMIT, source);
		}
		return new Decision(true, REASON_ALLOWED, source);
	}

	private boolean editorAssignmentCovers(TenantContext context, AccessResource resource) {
		UUID collectionKey = (resource.getCollectionId() != null) ? resource.getCollectionId() : resource.getId();
		List<CollectionAssignment> rows = this.sessions.inPartition(context.organizationId(), context.sub(),
				() -> this.assignments.findByCollectionIdAndResourceTypeAndAssigneeSubAndRevokedAtIsNull(collectionKey,
						resource.getResourceType(), context.sub()));
		for (CollectionAssignment row : rows) {
			AssignmentRole role = AssignmentRole.fromCode(row.getAssignmentRole()).orElse(null);
			if (role == AssignmentRole.BANK_EDITOR || role == AssignmentRole.RESOURCE_EDITOR) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Quyết định tạo tài nguyên thuộc một bộ sưu tập: quyền được tính trên cặp (bộ sưu tập, loại tài nguyên sắp
	 * tạo), không suy ra từ việc được xem bộ sưu tập. Chủ cá nhân chỉ tạo được trong bộ sưu tập của mình và vẫn cần
	 * quyền soạn nền tảng {@code personal-author} — đầu vào bắt buộc lấy từ token đã xác minh.
	 */
	public Decision decideCreateInCollection(TenantContext context, boolean personalAuthor, UUID collectionId,
			String resourceType) {
		Located located = locate(context, collectionId);
		if (located == null) {
			return deny(REASON_NOT_FOUND);
		}
		AccessResource collection = located.resource();
		if (AccessResource.WORKSPACE_PERSONAL.equals(collection.getWorkspaceKind())) {
			return (context.sub().equals(collection.getOwnerUserSub()) && personalAuthor)
					? new Decision(true, REASON_ALLOWED, SOURCE_OWNER) : deny(REASON_NO_SOURCE);
		}
		if (AccessResource.WORKSPACE_SYSTEM.equals(collection.getWorkspaceKind())) {
			return context.platformAdmin() ? new Decision(true, REASON_ALLOWED, SOURCE_PLATFORM_ADMIN)
					: deny(REASON_NO_SOURCE);
		}
		if (isCenterMember(context) && context.organizationId().equals(collection.getOwnerOrganizationId())) {
			if (isTenantAdmin(context)) {
				return new Decision(true, REASON_ALLOWED, SOURCE_TENANT_ADMIN);
			}
			if (assignmentCoversOn(context, collection.getId(), resourceType, AccessAction.CREATE_IN_COLLECTION)) {
				return new Decision(true, REASON_ALLOWED, SOURCE_ASSIGNMENT);
			}
		}
		return deny(REASON_NO_SOURCE);
	}

	/**
	 * Nguồn được xem nội dung của bản đang chờ duyệt: quản trị viên của trung tâm sở hữu, vai quản lý kho trên cặp
	 * (bộ sưu tập, loại) hoặc phân công có ánh xạ {@code review} trên cùng cặp đó.
	 */
	public boolean hasReviewSource(TenantContext context, AccessResource resource) {
		if (!isCenterMember(context) || !context.organizationId().equals(resource.getOwnerOrganizationId())) {
			return false;
		}
		if (isTenantAdmin(context)) {
			return true;
		}
		UUID collectionKey = (resource.getCollectionId() != null) ? resource.getCollectionId() : resource.getId();
		List<CollectionAssignment> rows = this.sessions.inPartition(context.organizationId(), context.sub(),
				() -> this.assignments.findByCollectionIdAndResourceTypeAndAssigneeSubAndRevokedAtIsNull(collectionKey,
						resource.getResourceType(), context.sub()));
		for (CollectionAssignment row : rows) {
			AssignmentRole role = AssignmentRole.fromCode(row.getAssignmentRole()).orElse(null);
			if (role == AssignmentRole.BANK_MANAGER || (role != null && role.covers(AccessAction.REVIEW))) {
				return true;
			}
		}
		return false;
	}

	private Decision narrow(TenantContext context, AccessResource resource, ResourceVersion version,
			AccessAction action, String source, OffsetDateTime now) {
		if (version != null) {
			if (action == AccessAction.EDIT_DRAFT && (ResourceVersion.STATUS_PUBLISHED.equals(version.getStatus())
					|| version.getLockedAt() != null)) {
				return new Decision(false, REASON_VERSION_LOCKED, source);
			}
			if (action == AccessAction.PUBLISH && !ResourceVersion.STATUS_DRAFT.equals(version.getStatus())) {
				return new Decision(false, REASON_VERSION_NOT_DRAFT, source);
			}
		}
		if (AccessResource.CUSTODY_LICENSED.equals(resource.getCustodyKind()) && CONTENT_ACTIONS.contains(action)
				&& !licenseAnchorEffective(context, resource, now)) {
			return new Decision(false, REASON_LICENSE_INACTIVE, source);
		}
		UsageLimits limits = UsageLimits.parse(resource.getInheritedUsageLimits());
		if (limits.restrictsActions() && !limits.actionCodes().contains(action.code())) {
			return new Decision(false, REASON_USAGE_LIMIT, source);
		}
		if (limits.restrictsOrganizations() && isCenterMember(context)
				&& !limits.organizationIds().contains(context.organizationId())) {
			return new Decision(false, REASON_USAGE_LIMIT, source);
		}
		String condition = resource.getBusinessCondition();
		if (AccessResource.CONDITION_PENDING_REVIEW.equals(condition)
				|| AccessResource.CONDITION_REJECTED.equals(condition)) {
			if (action == AccessAction.USE) {
				return new Decision(false, REASON_PENDING_REVIEW, source);
			}
			if (action == AccessAction.VIEW_CONTENT && !hasReviewSource(context, resource)) {
				return new Decision(false, REASON_PENDING_REVIEW, source);
			}
		}
		return new Decision(true, REASON_ALLOWED, source);
	}

	/**
	 * Bản sao được cấp phép chỉ dùng được trong khuôn khổ các grant còn hiệu lực mà trung tâm nhận được trên đúng
	 * phiên bản nguồn của bản sao: grant neo giữ quyền sở hữu phải còn hiệu lực, hành động phải có một grant tương
	 * ứng, và người gọi phải có phân công bao phủ hành động trên bộ sưu tập chứa bản sao.
	 */
	private boolean licensedSourceAllows(TenantContext context, AccessResource resource, AccessAction action,
			OffsetDateTime now) {
		if (!CONTENT_ACTIONS.contains(action) || resource.getCollectionId() == null
				|| resource.getSourceResourceId() == null) {
			return false;
		}
		List<ResourceGrant> siblings = this.sessions.inPartition(resource.getPartitionKey(), context.sub(),
				() -> this.grants.findByResourceId(resource.getSourceResourceId()));
		boolean actionGranted = false;
		for (ResourceGrant grant : siblings) {
			if (!context.organizationId().equals(grant.getGranteeOrganizationId())
					|| !resource.getSourceVersionId().equals(grant.getResourceVersionId())
					|| !grant.isEffectiveAt(now)) {
				continue;
			}
			if (grant.getAction().equals(action.code())) {
				actionGranted = true;
			}
		}
		return actionGranted && licenseAnchorEffective(context, resource, now) && assignmentGrantConditionCoversOn(
				context, resource.getCollectionId(), resource.getResourceType(), action);
	}

	private boolean licenseAnchorEffective(TenantContext context, AccessResource resource, OffsetDateTime now) {
		if (resource.getLicenseGrantId() == null) {
			return false;
		}
		ResourceGrant anchor = this.sessions.inPartition(resource.getPartitionKey(), context.sub(),
				() -> this.grants.findById(resource.getLicenseGrantId()).orElse(null));
		return anchor != null && anchor.isEffectiveAt(now)
				&& context.organizationId().equals(anchor.getGranteeOrganizationId());
	}

	private boolean assignmentCovers(TenantContext context, AccessResource resource, AccessAction action) {
		UUID collectionKey = (resource.getCollectionId() != null) ? resource.getCollectionId() : resource.getId();
		return assignmentCoversOn(context, collectionKey, resource.getResourceType(), action);
	}

	public boolean assignmentCoversOn(TenantContext context, UUID collectionId, String resourceType,
			AccessAction action) {
		if (!isCenterMember(context)) {
			return false;
		}
		List<CollectionAssignment> rows = this.sessions.inPartition(context.organizationId(), context.sub(),
				() -> this.assignments.findByCollectionIdAndResourceTypeAndAssigneeSubAndRevokedAtIsNull(collectionId,
						resourceType, context.sub()));
		for (CollectionAssignment row : rows) {
			if (AssignmentRole.fromCode(row.getAssignmentRole()).map(role -> role.covers(action)).orElse(false)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Điều kiện phân công ở hai đường giao nhau — grant cho Organization (bước 6 của thuật toán ở mục 8 của đặc
	 * tả) và điều kiện của bản được cấp phép: hành động do grant xác lập, phân công chỉ là điều kiện bao phủ
	 * theo {@link AssignmentRole#coversAsGrantCondition(AccessAction)}, không phải nguồn quyền độc lập.
	 */
	private boolean assignmentGrantConditionCoversOn(TenantContext context, UUID collectionId, String resourceType,
			AccessAction action) {
		if (!isCenterMember(context)) {
			return false;
		}
		List<CollectionAssignment> rows = this.sessions.inPartition(context.organizationId(), context.sub(),
				() -> this.assignments.findByCollectionIdAndResourceTypeAndAssigneeSubAndRevokedAtIsNull(collectionId,
						resourceType, context.sub()));
		for (CollectionAssignment row : rows) {
			if (AssignmentRole.fromCode(row.getAssignmentRole()).map(role -> role.coversAsGrantCondition(action))
					.orElse(false)) {
				return true;
			}
		}
		return false;
	}

	private List<ResourceGrant> loadCallerGrants(TenantContext context, UUID resourceId) {
		List<ResourceGrant> result = new ArrayList<>();
		for (String partition : granteePartitions(context)) {
			result.addAll(this.sessions.inPartition(partition, context.sub(),
					() -> this.grants.findByResourceId(resourceId)));
		}
		return result;
	}

	private List<String> candidatePartitions(TenantContext context) {
		LinkedHashSet<String> partitions = new LinkedHashSet<>();
		partitions.add("user:" + context.sub());
		if (isCenterMember(context)) {
			partitions.add(context.organizationId());
		}
		if (context.platformAdmin()) {
			partitions.add("system");
		}
		return List.copyOf(partitions);
	}

	private List<String> granteePartitions(TenantContext context) {
		LinkedHashSet<String> partitions = new LinkedHashSet<>();
		partitions.add("user:" + context.sub());
		if (isCenterMember(context)) {
			partitions.add(context.organizationId());
		}
		return List.copyOf(partitions);
	}

	private static Decision deny(String reasonCode) {
		return new Decision(false, reasonCode, SOURCE_NONE);
	}

}
