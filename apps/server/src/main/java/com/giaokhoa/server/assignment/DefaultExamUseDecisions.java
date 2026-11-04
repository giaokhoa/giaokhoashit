package com.giaokhoa.server.assignment;

import java.util.UUID;

import com.giaokhoa.server.access.AccessAction;
import com.giaokhoa.server.access.AccessDecisionService;
import com.giaokhoa.server.tenancy.TenantContext;

import org.springframework.stereotype.Component;

/**
 * Bản thật của điểm nối quyết quyền giao bài: mọi quyết định đều uỷ thác sang {@link AccessDecisionService}
 * của lõi quyền với hành động lõi {@code use} trên đúng phiên bản đề, cùng khuôn với cổng quyết quyền của lát
 * đề. Mã lý do của lõi được ánh xạ về ba kết cục của điểm nối này; riêng {@code PENDING_REVIEW} giữ nguyên là
 * một kết cục riêng để tầng nghiệp vụ trả đúng điều kiện nghiệp vụ "bản nhận đang chờ duyệt" thay vì lẫn với
 * từ chối quyền thường.
 */
@Component
public class DefaultExamUseDecisions implements ExamUseDecisions {

	private final AccessDecisionService decisions;

	public DefaultExamUseDecisions(AccessDecisionService decisions) {
		this.decisions = decisions;
	}

	@Override
	public Verdict decideUse(TenantContext context, UUID resourceId, UUID versionId) {
		AccessDecisionService.Decision decision = this.decisions.decide(context, resourceId, versionId,
				AccessAction.USE);
		if (decision.allowed()) {
			return Verdict.ALLOWED;
		}
		return AccessDecisionService.REASON_PENDING_REVIEW.equals(decision.reasonCode()) ? Verdict.PENDING_REVIEW
				: Verdict.DENIED;
	}

}
