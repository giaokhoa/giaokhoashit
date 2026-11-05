package com.giaokhoa.server.access;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.giaokhoa.server.access.AccessCheckService.AssignmentView;
import com.giaokhoa.server.access.AccessCheckService.DecisionView;
import com.giaokhoa.server.access.AccessCheckService.ResourceView;
import com.giaokhoa.server.access.AccessCheckService.VersionView;
import com.giaokhoa.server.access.AccessGrantService.GrantView;
import com.giaokhoa.server.access.HandoverService.HandoverRequestView;
import com.giaokhoa.server.access.HandoverService.SubmissionView;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bề mặt kiểm chứng của lõi quyền, chỉ bật ngoài production: tạo dữ kiện mẫu và thực thi các thao tác của lõi để
 * nghiệm thu quyết định quyền trên đường runtime thật. Các trường tự khai trong thân yêu cầu về người gọi, vai,
 * chủ sở hữu hay phân vùng đều bị bỏ qua — danh tính chỉ đến từ token đã xác minh và {@link TenantContext}.
 */
@RestController
@RequestMapping("/api/access-check")
@Profile("!prod")
public class AccessCheckController {

	private final AccessCheckService checkService;

	private final AccessGrantService grantService;

	private final HandoverService handoverService;

	public AccessCheckController(AccessCheckService checkService, AccessGrantService grantService,
			HandoverService handoverService) {
		this.checkService = checkService;
		this.grantService = grantService;
		this.handoverService = handoverService;
	}

	@PostMapping("/resources")
	ResourceView createResource(@AuthenticationPrincipal Jwt jwt,
			@RequestBody AccessCheckService.CreateResourceRequest request) {
		return this.checkService.createResource(context(), personalAuthor(jwt), request);
	}

	@GetMapping("/resources/{resourceId}")
	ResourceView getResource(@PathVariable UUID resourceId) {
		return this.checkService.getResource(context(), resourceId);
	}

	@PostMapping("/resources/{resourceId}/versions")
	VersionView createVersion(@PathVariable UUID resourceId,
			@RequestBody AccessCheckService.CreateVersionRequest request) {
		return this.checkService.createVersion(context(), resourceId, request);
	}

	@PostMapping("/resources/{resourceId}/versions/{versionId}/publish")
	VersionView publishVersion(@PathVariable UUID resourceId, @PathVariable UUID versionId) {
		return this.checkService.publishVersion(context(), resourceId, versionId);
	}

	@PostMapping("/assignments")
	AssignmentView createAssignment(@RequestBody AccessCheckService.CreateAssignmentRequest request) {
		return this.checkService.createAssignment(context(), request);
	}

	@PostMapping("/assignments/{assignmentId}/revoke")
	AssignmentView revokeAssignment(@PathVariable UUID assignmentId) {
		return this.checkService.revokeAssignment(context(), assignmentId);
	}

	@PostMapping("/grants")
	GrantView createGrant(@RequestBody AccessGrantService.CreateGrantRequest request) {
		return this.grantService.createGrant(context(), request);
	}

	@PostMapping("/grants/{grantId}/reception")
	GrantView reception(@PathVariable UUID grantId, @RequestBody AccessGrantService.ReceptionRequest request) {
		return this.grantService.reception(context(), grantId, request);
	}

	@PostMapping("/grants/{grantId}/revoke")
	GrantView revokeGrant(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID grantId,
			@RequestBody(required = false) AccessGrantService.RevokeRequest request) {
		return this.grantService.revokeGrant(context(), tokenOrganizationIds(jwt), grantId, request);
	}

	@PostMapping("/handover-requests")
	HandoverRequestView createHandoverRequest(@RequestBody HandoverService.CreateHandoverRequest request) {
		return this.handoverService.createRequest(context(), request);
	}

	@PostMapping("/handover-requests/{requestId}/submissions")
	SubmissionView submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID requestId,
			@RequestBody HandoverService.SubmitRequest request) {
		return this.handoverService.submit(context(), tokenOrganizationIds(jwt), requestId, request);
	}

	@PostMapping("/submissions/{submissionId}/review")
	SubmissionView review(@PathVariable UUID submissionId, @RequestBody HandoverService.ReviewRequest request) {
		return this.handoverService.review(context(), submissionId, request);
	}

	@GetMapping(value = "/submissions/{submissionId}/items/{itemId}/content",
			produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
	org.springframework.http.ResponseEntity<String> receiptContent(@PathVariable UUID submissionId,
			@PathVariable UUID itemId) {
		return org.springframework.http.ResponseEntity.ok()
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.body(this.handoverService.receiptContent(context(), submissionId, itemId));
	}

	@PostMapping("/decisions")
	DecisionView decide(@RequestBody AccessCheckService.DecisionRequest request) {
		return this.checkService.decide(context(), request);
	}

	private TenantContext context() {
		TenantContext context = TenantContexts.current();
		if (context == null) {
			throw new AccessException(401, "UNAUTHENTICATED", "Chưa xác thực");
		}
		return context;
	}

	/**
	 * Quyền soạn nền tảng là một realm role ở cấp cao nhất của token đã xác minh. Nó không được ghi vào
	 * {@link TenantContext} của khung tenancy nên tầng này đọc trực tiếp tại đây và truyền tường minh vào dịch vụ —
	 * vẫn là dữ kiện từ token, không bao giờ từ thân yêu cầu.
	 */
	private boolean personalAuthor(Jwt jwt) {
		if (jwt.getClaim("realm_access") instanceof Map<?, ?> realmAccess
				&& realmAccess.get("roles") instanceof List<?> roles) {
			return roles.contains("personal-author");
		}
		return false;
	}

	/** Các Organization mà người gọi là thành viên theo claim của token — chỉ dùng để định vị yêu cầu nộp. */
	private Set<String> tokenOrganizationIds(Jwt jwt) {
		Set<String> ids = new LinkedHashSet<>();
		if (jwt.getClaim("organization") instanceof Map<?, ?> organizations) {
			for (Object entry : organizations.values()) {
				if (entry instanceof Map<?, ?> organization && organization.get("id") instanceof String id) {
					ids.add(id);
				}
			}
		}
		return ids;
	}

}
