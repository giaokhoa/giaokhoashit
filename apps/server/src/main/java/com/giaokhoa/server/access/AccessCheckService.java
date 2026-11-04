package com.giaokhoa.server.access;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.giaokhoa.server.access.AccessDecisionService.Decision;
import com.giaokhoa.server.access.AccessDecisionService.Located;
import com.giaokhoa.server.tenancy.TenantContext;

import org.springframework.stereotype.Service;

/**
 * Bề mặt kiểm chứng của lõi quyền: tạo tài nguyên và phiên bản mẫu, đọc tài nguyên theo đúng phần người gọi được
 * xem, quản lý phân công và truy vấn quyết định. Mọi thao tác ghi đều chạy lại quyết định đầy đủ tại thời điểm ghi
 * qua {@link AccessDecisionService}; bề mặt này chỉ phục vụ phát triển và kiểm thử, không phải bề mặt sản phẩm.
 */
@Service
public class AccessCheckService {

	public record CreateResourceRequest(String resourceType, String workspace, UUID collectionId,
			String contentHash) {
	}

	public record CreateVersionRequest(String contentHash) {
	}

	public record CreateAssignmentRequest(UUID collectionId, String resourceType, String assigneeSub,
			String assignmentRole) {
	}

	public record DecisionRequest(UUID resourceId, UUID resourceVersionId, String action) {
	}

	public record VersionView(UUID versionId, int versionNumber, String status, OffsetDateTime lockedAt,
			String contentHash) {
	}

	public record ResourceView(UUID resourceId, String resourceType, String workspace, String ownerType,
			String ownerOrganizationId, UUID collectionId, String custody, String businessCondition,
			List<VersionView> versions) {
	}

	public record AssignmentView(UUID assignmentId, String organizationId, UUID collectionId, String resourceType,
			String assigneeSub, String assignmentRole, boolean revoked) {
	}

	public record DecisionView(boolean allowed, String reasonCode) {
	}

	private final AccessResourceRepository resources;

	private final ResourceVersionRepository versions;

	private final CollectionAssignmentRepository assignments;

	private final AccessDecisionService decisions;

	private final PartitionSessions sessions;

	public AccessCheckService(AccessResourceRepository resources, ResourceVersionRepository versions,
			CollectionAssignmentRepository assignments, AccessDecisionService decisions, PartitionSessions sessions) {
		this.resources = resources;
		this.versions = versions;
		this.assignments = assignments;
		this.decisions = decisions;
		this.sessions = sessions;
	}

	static AccessException mapDenial(Decision decision) {
		if (AccessDecisionService.REASON_NOT_FOUND.equals(decision.reasonCode())) {
			return AccessException.notFound("Không tìm thấy tài nguyên");
		}
		return AccessException.forbidden("Quyết định quyền từ chối thao tác: " + decision.reasonCode());
	}

	static VersionView view(ResourceVersion version) {
		return new VersionView(version.getId(), version.getVersionNumber(), version.getStatus(),
				version.getLockedAt(), version.getContentHash());
	}

	static ResourceView view(AccessResource resource, List<VersionView> versions) {
		return new ResourceView(resource.getId(), resource.getResourceType(), resource.getWorkspaceKind(),
				resource.getOwnerType(), resource.getOwnerOrganizationId(), resource.getCollectionId(),
				resource.getCustodyKind(), resource.getBusinessCondition(), versions);
	}

	public ResourceView createResource(TenantContext context, boolean personalAuthor,
			CreateResourceRequest request) {
		if (request.resourceType() == null || request.resourceType().isBlank()) {
			throw AccessException.validation("resourceType là bắt buộc");
		}
		OffsetDateTime now = OffsetDateTime.now();
		AccessResource resource = new AccessResource();
		resource.setId(UUID.randomUUID());
		resource.setResourceType(request.resourceType());
		resource.setCustodyKind(AccessResource.CUSTODY_OWNED);
		resource.setCreatedBySub(context.sub());
		resource.setCreatedAt(now);
		resource.setUpdatedAt(now);
		boolean withVersion;
		String partition;
		String workspace = (request.workspace() != null) ? request.workspace() : "";
		switch (workspace) {
			case AccessResource.WORKSPACE_PERSONAL -> {
				if (!personalAuthor) {
					throw AccessException.forbidden("Tài khoản không có quyền soạn nền tảng personal-author");
				}
				if (request.collectionId() != null) {
					AccessResource collection = this.sessions.inPartition("user:" + context.sub(), context.sub(),
							() -> this.resources.findById(request.collectionId()).orElse(null));
					if (collection == null || !AccessResource.WORKSPACE_PERSONAL.equals(collection.getWorkspaceKind())
							|| !context.sub().equals(collection.getOwnerUserSub())) {
						throw AccessException.notFound("Không tìm thấy bộ sưu tập cá nhân");
					}
				}
				resource.setWorkspaceKind(AccessResource.WORKSPACE_PERSONAL);
				resource.setOwnerType(AccessResource.OWNER_USER);
				resource.setOwnerUserSub(context.sub());
				partition = "user:" + context.sub();
				resource.setCollectionId(request.collectionId());
				withVersion = true;
			}
			case AccessResource.WORKSPACE_CENTER -> {
				if (!this.decisions.isCenterMember(context)) {
					throw AccessException.forbidden("Người gọi không phải thành viên của trung tâm trong ngữ cảnh");
				}
				resource.setWorkspaceKind(AccessResource.WORKSPACE_CENTER);
				resource.setOwnerType(AccessResource.OWNER_ORGANIZATION);
				resource.setOwnerOrganizationId(context.organizationId());
				partition = context.organizationId();
				if (request.collectionId() == null) {
					if (!this.decisions.isTenantAdmin(context)) {
						throw AccessException
								.forbidden("Chỉ tenant-admin của trung tâm mới tạo được bộ sưu tập của trung tâm");
					}
					withVersion = false;
				}
				else {
					Decision decision = this.decisions.decideCreateInCollection(context, personalAuthor,
							request.collectionId(), request.resourceType());
					if (!decision.allowed()) {
						throw mapDenial(decision);
					}
					resource.setCollectionId(request.collectionId());
					withVersion = true;
				}
			}
			case AccessResource.WORKSPACE_SYSTEM -> {
				if (!context.platformAdmin()) {
					throw AccessException.forbidden("Chỉ platform-admin mới tạo được tài nguyên hệ thống");
				}
				resource.setWorkspaceKind(AccessResource.WORKSPACE_SYSTEM);
				resource.setOwnerType(AccessResource.OWNER_PLATFORM);
				partition = "system";
				withVersion = true;
			}
			default -> throw AccessException.validation("workspace không hợp lệ");
		}
		ResourceVersion version1 = withVersion ? newVersion(resource, 1, request.contentHash(), context.sub(), now)
				: null;
		this.sessions.inPartition(partition, context.sub(), () -> {
			this.resources.save(resource);
			if (version1 != null) {
				this.versions.save(version1);
			}
		});
		return view(resource, (version1 != null) ? List.of(view(version1)) : List.of());
	}

	public ResourceView getResource(TenantContext context, UUID resourceId) {
		Located located = this.decisions.locate(context, resourceId);
		if (located == null) {
			throw AccessException.notFound("Không tìm thấy tài nguyên");
		}
		List<ResourceVersion> all = this.sessions.inPartition(located.partitionKey(), context.sub(),
				() -> this.versions.findByResourceIdOrderByVersionNumberAsc(resourceId));
		List<VersionView> visible = new ArrayList<>();
		for (ResourceVersion version : all) {
			if (this.decisions.decide(context, resourceId, version.getId(), AccessAction.VIEW_CONTENT).allowed()) {
				visible.add(view(version));
			}
		}
		boolean resourceLevel = this.decisions.decide(context, resourceId, null, AccessAction.VIEW_CONTENT).allowed();
		if (visible.isEmpty() && !resourceLevel) {
			throw AccessException.forbidden("Người gọi không được xem phần nào của tài nguyên này");
		}
		return view(located.resource(), visible);
	}

	public VersionView createVersion(TenantContext context, UUID resourceId, CreateVersionRequest request) {
		Decision decision = this.decisions.decide(context, resourceId, null, AccessAction.EDIT_DRAFT);
		if (!decision.allowed()) {
			throw mapDenial(decision);
		}
		Located located = this.decisions.locate(context, resourceId);
		return this.sessions.inPartition(located.partitionKey(), context.sub(), () -> {
			int next = this.versions.findByResourceIdOrderByVersionNumberAsc(resourceId).stream()
					.mapToInt(ResourceVersion::getVersionNumber).max().orElse(0) + 1;
			ResourceVersion version = newVersion(located.resource(), next, request.contentHash(), context.sub(),
					OffsetDateTime.now());
			return view(this.versions.save(version));
		});
	}

	public VersionView publishVersion(TenantContext context, UUID resourceId, UUID versionId) {
		Decision decision = this.decisions.decide(context, resourceId, versionId, AccessAction.PUBLISH);
		if (!decision.allowed()) {
			throw mapDenial(decision);
		}
		Located located = this.decisions.locate(context, resourceId);
		return this.sessions.inPartition(located.partitionKey(), context.sub(), () -> {
			ResourceVersion version = this.versions.findByIdAndResourceId(versionId, resourceId)
					.orElseThrow(() -> AccessException.notFound("Không tìm thấy phiên bản"));
			OffsetDateTime now = OffsetDateTime.now();
			version.setStatus(ResourceVersion.STATUS_PUBLISHED);
			version.setPublishedAt(now);
			if (version.getLockedAt() == null) {
				version.setLockedAt(now);
			}
			version.setUpdatedAt(now);
			return view(this.versions.save(version));
		});
	}

	public AssignmentView createAssignment(TenantContext context, CreateAssignmentRequest request) {
		if (!this.decisions.isTenantAdmin(context)) {
			throw AccessException.forbidden("Chỉ tenant-admin của trung tâm mới tạo được phân công");
		}
		if (request.collectionId() == null || request.resourceType() == null || request.assigneeSub() == null) {
			throw AccessException.validation("collectionId, resourceType và assigneeSub là bắt buộc");
		}
		AssignmentRole.fromCode(request.assignmentRole())
				.orElseThrow(() -> AccessException.validation("assignmentRole không hợp lệ"));
		return this.sessions.inPartition(context.organizationId(), context.sub(), () -> {
			AccessResource collection = this.resources.findById(request.collectionId())
					.orElseThrow(() -> AccessException.notFound("Không tìm thấy bộ sưu tập"));
			if (!context.organizationId().equals(collection.getOwnerOrganizationId())) {
				throw AccessException.notFound("Không tìm thấy bộ sưu tập");
			}
			OffsetDateTime now = OffsetDateTime.now();
			CollectionAssignment row = new CollectionAssignment();
			row.setId(UUID.randomUUID());
			row.setOrganizationId(context.organizationId());
			row.setCollectionId(request.collectionId());
			row.setResourceType(request.resourceType());
			row.setAssigneeSub(request.assigneeSub());
			row.setAssignmentRole(request.assignmentRole());
			row.setAssignedBySub(context.sub());
			row.setCreatedAt(now);
			row.setUpdatedAt(now);
			return view(this.assignments.save(row));
		});
	}

	public AssignmentView revokeAssignment(TenantContext context, UUID assignmentId) {
		if (!this.decisions.isTenantAdmin(context)) {
			throw AccessException.forbidden("Chỉ tenant-admin của trung tâm mới thu hồi được phân công");
		}
		return this.sessions.inPartition(context.organizationId(), context.sub(), () -> {
			CollectionAssignment row = this.assignments.findById(assignmentId)
					.orElseThrow(() -> AccessException.notFound("Không tìm thấy phân công"));
			OffsetDateTime now = OffsetDateTime.now();
			row.setRevokedAt(now);
			row.setUpdatedAt(now);
			return view(this.assignments.save(row));
		});
	}

	public DecisionView decide(TenantContext context, DecisionRequest request) {
		if (request.resourceId() == null) {
			throw AccessException.validation("resourceId là bắt buộc");
		}
		AccessAction action = AccessAction.fromCode(request.action())
				.orElseThrow(() -> AccessException.validation("action không hợp lệ"));
		Decision decision = this.decisions.decide(context, request.resourceId(), request.resourceVersionId(), action);
		return new DecisionView(decision.allowed(), decision.reasonCode());
	}

	private static AssignmentView view(CollectionAssignment row) {
		return new AssignmentView(row.getId(), row.getOrganizationId(), row.getCollectionId(), row.getResourceType(),
				row.getAssigneeSub(), row.getAssignmentRole(), row.getRevokedAt() != null);
	}

	static ResourceVersion newVersion(AccessResource resource, int number, String contentHash, String creatorSub,
			OffsetDateTime now) {
		ResourceVersion version = new ResourceVersion();
		version.setId(UUID.randomUUID());
		version.setResourceId(resource.getId());
		version.setVersionNumber(number);
		version.setStatus(ResourceVersion.STATUS_DRAFT);
		version.setContentHash(contentHash);
		version.setCreatedBySub(creatorSub);
		version.setCreatedAt(now);
		version.setUpdatedAt(now);
		return version;
	}

}
