package com.giaokhoa.server.assignment;

import java.util.UUID;

import com.giaokhoa.server.tenancy.TenantContext;

/**
 * Điểm nối quyết quyền "giao cho lớp" trên một phiên bản đề, qua dịch vụ quyết quyền của lõi quyền: hành động
 * này ánh xạ vào hành động lõi `use` trên đúng phiên bản đó. Ba kết cục tách rõ để tầng nghiệp vụ ánh xạ lỗi:
 * được phép; bị từ chối; và bị chặn vì bản nhận đang chờ duyệt (mã lý do `PENDING_REVIEW` của dịch vụ quyết
 * quyền — về mặt đáp ứng cũng là 403 nhưng là một điều kiện nghiệp vụ riêng, không phải thiếu quyền thường).
 */
public interface ExamUseDecisions {

	Verdict decideUse(TenantContext context, UUID resourceId, UUID versionId);

	enum Verdict {

		ALLOWED, DENIED, PENDING_REVIEW

	}

}
