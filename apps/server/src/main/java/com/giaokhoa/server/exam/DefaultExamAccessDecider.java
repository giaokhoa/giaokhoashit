package com.giaokhoa.server.exam;

import org.springframework.stereotype.Component;

import com.giaokhoa.server.tenancy.TenantContext;

/**
 * Cài đặt quyết định của lát tối thiểu, chỉ gồm các nguồn quyền áp được khi chưa có bảng grant và phân công của
 * lõi quyền: chủ sở hữu cá nhân, {@code tenant-admin} trên đề thuộc trung tâm của chính mình,
 * {@code platform-admin} trên đề hệ thống, và câu catalog đã công bố cho hành động dùng-trong-đề. Mọi đường
 * quyền khác (grant, phân công biên tập, thành viên được phân công) là điểm nối của lõi quyền và ở lát này đều
 * là từ chối.
 */
@Component
public class DefaultExamAccessDecider implements ExamAccessDecider {

	private static final String TENANT_ADMIN_ROLE = "tenant-admin";

	@Override
	public Decision decide(TenantContext context, Exam exam, ExamVersion version, ExamAction action) {
		return switch (exam.getWorkspaceKind()) {
			case "personal" -> personalSource(context, exam, version, action);
			case "center" -> centerSource(context, exam, version, action);
			case "system" -> systemSource(context, exam, version, action);
			default -> deny("NO_MATCHING_SOURCE");
		};
	}

	@Override
	public Decision decideOnQuestion(TenantContext context, QuestionVersionSource.QuestionVersionInfo question,
			ExamAction action) {
		if (question.ownerUserSub() != null && question.ownerUserSub().equals(context.sub())) {
			return allow("owner");
		}
		// Catalog công bố cho mọi người gọi đã xác thực quyền dùng-trong-đề và xem nội dung của câu, nhưng không
		// cho xem đáp án: đáp án của câu catalog chỉ tới tay người xem qua snapshot sau khi đề đã phát hành.
		if (question.catalogPublished() && (action == ExamAction.USE || action == ExamAction.VIEW_CONTENT)) {
			return allow("catalog");
		}
		return deny("NO_MATCHING_SOURCE");
	}

	private Decision personalSource(TenantContext context, Exam exam, ExamVersion version, ExamAction action) {
		if (!exam.getOwnerUserSub().equals(context.sub())) {
			return deny("NO_MATCHING_SOURCE");
		}
		return narrowByVersionState(version, action, "owner");
	}

	private Decision centerSource(TenantContext context, Exam exam, ExamVersion version, ExamAction action) {
		boolean adminOfOwningCenter = context.organizationId() != null
				&& context.organizationId().equals(exam.getOwnerOrganizationId())
				&& context.organizationRoles().contains(TENANT_ADMIN_ROLE);
		if (!adminOfOwningCenter) {
			return deny("NO_MATCHING_SOURCE");
		}
		// Ở mức lõi, tenant-admin quản trị được đề của trung tâm nhưng không có view_answer: đáp án là mặt dữ
		// kiện của người soạn và học liệu đã giao, không suy ra từ vai quản trị trung tâm.
		if (action == ExamAction.VIEW_ANSWER) {
			return deny("NO_MATCHING_SOURCE");
		}
		return narrowByVersionState(version, action, "tenant-admin");
	}

	private Decision systemSource(TenantContext context, Exam exam, ExamVersion version, ExamAction action) {
		if (!context.platformAdmin()) {
			return deny("NO_MATCHING_SOURCE");
		}
		return narrowByVersionState(version, action, "platform-admin");
	}

	// Thu hẹp theo trạng thái phiên bản đích áp đồng nhất cho mọi nguồn quyền: bản đã khoá thì edit_draft bị
	// chặn ngay tại quyết định, và publish chỉ được công nhận trên bản đang là draft.
	private Decision narrowByVersionState(ExamVersion version, ExamAction action, String source) {
		if (version != null) {
			if (action == ExamAction.EDIT_DRAFT && version.getLockedAt() != null) {
				return deny("VERSION_LOCKED");
			}
			if (action == ExamAction.PUBLISH && !"draft".equals(version.getStatus())) {
				return deny("VERSION_NOT_DRAFT");
			}
		}
		return allow(source);
	}

	private static Decision allow(String source) {
		return new Decision(true, "ALLOWED", source);
	}

	private static Decision deny(String reasonCode) {
		return new Decision(false, reasonCode, null);
	}

}
