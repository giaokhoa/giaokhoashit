package com.giaokhoa.server.access;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import com.giaokhoa.server.access.AccessDecisionService.Decision;
import com.giaokhoa.server.access.AccessDecisionService.Located;
import com.giaokhoa.server.tenancy.TenantContext;

import org.springframework.stereotype.Service;

/**
 * Tạo, tiếp nhận và thu hồi grant. Tạo grant là thao tác hai pha trên hai phân vùng: khoá phiên bản nguồn trước
 * (khoá là trạng thái an toàn tự giữ nguyên khi pha sau lỗi và thao tác được thực thi lại nguyên vẹn), rồi mới
 * chèn grant vào phân vùng của phía nhận. Grant cho Organization luôn hướng về chính Organization trong ngữ cảnh
 * đã xác minh của người gọi — thân yêu cầu không nêu tên trung tâm nhận. Grant dài hạn cho Organization không bị
 * phía cấp thu hồi đơn phương qua bề mặt này.
 */
@Service
public class AccessGrantService {

	public record CreateGrantRequest(UUID resourceId, UUID resourceVersionId, String action, String granteeType,
			String granteeUserSub, String mode, OffsetDateTime startsAt, OffsetDateTime expiresAt,
			String revocationTerms) {
	}

	public record ReceptionRequest(UUID receivingCollectionId) {
	}

	/**
	 * Gợi ý định vị phía nhận khi thu hồi: grant nằm ở phân vùng của phía nhận nên thao tác thu hồi cần biết phân
	 * vùng đó để tìm đúng hàng. Đây là dữ kiện đích của thao tác, không phải ngữ cảnh người gọi — quyền thu hồi vẫn
	 * chỉ quyết bởi {@code grantor_sub} đã lưu trên grant.
	 */
	public record RevokeRequest(String granteeUserSub, String granteeOrganizationId) {
	}

	public record GrantView(UUID grantId, UUID resourceId, UUID resourceVersionId, String action, String granteeType,
			String granteeOrganizationId, String granteeUserSub, String mode, UUID receivingCollectionId,
			OffsetDateTime startsAt, OffsetDateTime expiresAt, OffsetDateTime revokedAt) {
	}

	private final ResourceVersionRepository versions;

	private final ResourceGrantRepository grants;

	private final AccessResourceRepository resources;

	private final AccessDecisionService decisions;

	private final PartitionSessions sessions;

	public AccessGrantService(ResourceVersionRepository versions, ResourceGrantRepository grants,
			AccessResourceRepository resources, AccessDecisionService decisions, PartitionSessions sessions) {
		this.versions = versions;
		this.grants = grants;
		this.resources = resources;
		this.decisions = decisions;
		this.sessions = sessions;
	}

	static GrantView view(ResourceGrant grant) {
		return new GrantView(grant.getId(), grant.getResourceId(), grant.getResourceVersionId(), grant.getAction(),
				grant.getGranteeType(), grant.getGranteeOrganizationId(), grant.getGranteeUserSub(), grant.getMode(),
				grant.getReceivingCollectionId(), grant.getStartsAt(), grant.getExpiresAt(), grant.getRevokedAt());
	}

	public GrantView createGrant(TenantContext context, CreateGrantRequest request) {
		if (request.resourceId() == null || request.resourceVersionId() == null) {
			throw AccessException.validation("resourceId và resourceVersionId là bắt buộc");
		}
		AccessAction action = AccessAction.fromCode(request.action())
				.orElseThrow(() -> AccessException.validation("action không hợp lệ"));
		if (action == AccessAction.READ_MATH_SOURCE || action == AccessAction.EDIT_MATH_SOURCE) {
			throw AccessException.validation("Hành động trên nguồn LaTeX không được cấp qua grant chia sẻ");
		}
		boolean toUser = ResourceGrant.GRANTEE_USER.equals(request.granteeType());
		boolean toOrganization = ResourceGrant.GRANTEE_ORGANIZATION.equals(request.granteeType());
		if (!toUser && !toOrganization) {
			throw AccessException.validation("granteeType không hợp lệ");
		}
		if (toUser && (request.granteeUserSub() == null || request.mode() != null)) {
			throw AccessException.validation("Grant cho người dùng cần granteeUserSub và không có mode");
		}
		if (toOrganization) {
			if (!ResourceGrant.MODE_TEMPORARY.equals(request.mode())
					&& !ResourceGrant.MODE_LONG_TERM.equals(request.mode())) {
				throw AccessException.validation("Grant cho Organization cần mode temporary hoặc long_term");
			}
			if (ResourceGrant.MODE_TEMPORARY.equals(request.mode()) && request.expiresAt() == null) {
				throw AccessException.validation("Grant tạm thời cho Organization bắt buộc có expiresAt");
			}
		}
		Decision decision = this.decisions.decide(context, request.resourceId(), request.resourceVersionId(),
				AccessAction.SHARE);
		if (!decision.allowed()) {
			throw AccessCheckService.mapDenial(decision);
		}
		Located located = this.decisions.locate(context, request.resourceId());
		AccessResource resource = located.resource();
		UsageLimits limits = UsageLimits.parse(resource.getInheritedUsageLimits());
		boolean shareFromGrant = AccessDecisionService.SOURCE_ORG_GRANT.equals(decision.source())
				|| AccessDecisionService.SOURCE_USER_GRANT.equals(decision.source());
		if (shareFromGrant && !limits.reshareActionCodes().contains(action.code())) {
			throw AccessException
					.forbidden("Quyền chia sẻ chỉ đến từ grant và giới hạn của nguồn không cho cấp lại hành động này");
		}
		if (limits.restrictsActions() && !limits.actionCodes().contains(action.code())) {
			throw AccessException.forbidden("Giới hạn sử dụng của nguồn không gồm hành động được cấp");
		}
		String granteePartition;
		String granteeOrganizationId = null;
		if (toUser) {
			granteePartition = "user:" + request.granteeUserSub();
		}
		else {
			if (!this.decisions.isCenterMember(context)) {
				throw AccessException.forbidden("Người gọi không phải thành viên của trung tâm nhận grant");
			}
			granteeOrganizationId = context.organizationId();
			if (limits.restrictsOrganizations() && !limits.organizationIds().contains(granteeOrganizationId)) {
				throw AccessException.forbidden("Giới hạn sử dụng của nguồn không cho chia sẻ tới trung tâm này");
			}
			granteePartition = granteeOrganizationId;
		}
		OffsetDateTime now = OffsetDateTime.now();
		this.sessions.inPartition(located.partitionKey(), context.sub(), () -> {
			ResourceVersion version = this.versions
					.findByIdAndResourceId(request.resourceVersionId(), request.resourceId())
					.orElseThrow(() -> AccessException.notFound("Không tìm thấy phiên bản nguồn"));
			if (version.getLockedAt() == null) {
				version.setLockedAt(now);
				version.setUpdatedAt(now);
				this.versions.save(version);
			}
		});
		ResourceGrant grant = new ResourceGrant();
		grant.setId(UUID.randomUUID());
		grant.setResourceId(request.resourceId());
		grant.setResourceVersionId(request.resourceVersionId());
		grant.setGranteeType(request.granteeType());
		grant.setGranteeUserSub(toUser ? request.granteeUserSub() : null);
		grant.setGranteeOrganizationId(granteeOrganizationId);
		grant.setAction(action.code());
		grant.setMode(toOrganization ? request.mode() : null);
		grant.setStartsAt((request.startsAt() != null) ? request.startsAt() : now);
		grant.setExpiresAt(request.expiresAt());
		grant.setRevocationTerms(request.revocationTerms());
		grant.setGrantorSub(context.sub());
		grant.setSourcePartitionKey(located.partitionKey());
		grant.setCreatedAt(now);
		grant.setUpdatedAt(now);
		this.sessions.inPartition(granteePartition, context.sub(), () -> this.grants.save(grant));
		return view(grant);
	}

	public GrantView reception(TenantContext context, UUID grantId, ReceptionRequest request) {
		if (!this.decisions.isCenterMember(context)) {
			throw AccessException.forbidden("Người gọi không phải thành viên của trung tâm nhận grant");
		}
		if (request.receivingCollectionId() == null) {
			throw AccessException.validation("receivingCollectionId là bắt buộc");
		}
		return this.sessions.inPartition(context.organizationId(), context.sub(), () -> {
			ResourceGrant grant = this.grants.findById(grantId)
					.orElseThrow(() -> AccessException.notFound("Không tìm thấy grant"));
			if (!this.decisions.isTenantAdmin(context)) {
				throw AccessException.forbidden("Chỉ tenant-admin của trung tâm nhận mới tiếp nhận được grant");
			}
			this.resources.findById(request.receivingCollectionId())
					.orElseThrow(() -> AccessException.notFound("Không tìm thấy bộ sưu tập tiếp nhận"));
			grant.setReceivingCollectionId(request.receivingCollectionId());
			grant.setUpdatedAt(OffsetDateTime.now());
			return view(this.grants.save(grant));
		});
	}

	public GrantView revokeGrant(TenantContext context, Set<String> tokenOrganizationIds, UUID grantId,
			RevokeRequest request) {
		LinkedHashSet<String> candidates = new LinkedHashSet<>();
		if (request != null && request.granteeUserSub() != null) {
			candidates.add("user:" + request.granteeUserSub());
		}
		if (request != null && request.granteeOrganizationId() != null) {
			candidates.add(request.granteeOrganizationId());
		}
		candidates.add("user:" + context.sub());
		candidates.addAll(tokenOrganizationIds);
		if (this.decisions.isCenterMember(context)) {
			candidates.add(context.organizationId());
		}
		for (String partition : candidates) {
			ResourceGrant grant = this.sessions.inPartition(partition, context.sub(),
					() -> this.grants.findById(grantId).orElse(null));
			if (grant == null) {
				continue;
			}
			if (!context.sub().equals(grant.getGrantorSub())) {
				throw AccessException.forbidden("Chỉ phía đã cấp mới thu hồi được grant này");
			}
			if (ResourceGrant.GRANTEE_ORGANIZATION.equals(grant.getGranteeType())
					&& ResourceGrant.MODE_LONG_TERM.equals(grant.getMode())) {
				throw AccessException
						.forbidden("Grant dài hạn cho Organization không bị phía cấp thu hồi đơn phương");
			}
			return this.sessions.inPartition(partition, context.sub(), () -> {
				ResourceGrant row = this.grants.findById(grantId)
						.orElseThrow(() -> AccessException.notFound("Không tìm thấy grant"));
				OffsetDateTime now = OffsetDateTime.now();
				row.setRevokedAt(now);
				row.setUpdatedAt(now);
				return view(this.grants.save(row));
			});
		}
		throw AccessException.notFound("Không tìm thấy grant");
	}

}
