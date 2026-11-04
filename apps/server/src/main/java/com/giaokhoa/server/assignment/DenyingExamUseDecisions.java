package com.giaokhoa.server.assignment;

import java.util.UUID;

import com.giaokhoa.server.tenancy.TenantContext;

import org.springframework.stereotype.Component;

/**
 * Bản mặc định của điểm nối quyết quyền khi lõi quyền chưa hợp nhất: từ chối mọi quyết định (fail-closed),
 * tuyệt đối không tự suy quyền giao từ quyền sở hữu hay bất kỳ dữ kiện nào phía client. Khi lõi quyền hợp
 * nhất, điểm nối này được thay bằng lời gọi `AccessDecisionService.decide(context, resourceId, versionId,
 * AccessAction.USE)` và mã lý do của nó.
 */
@Component
public class DenyingExamUseDecisions implements ExamUseDecisions {

	@Override
	public Verdict decideUse(TenantContext context, UUID resourceId, UUID versionId) {
		return Verdict.DENIED;
	}

}
