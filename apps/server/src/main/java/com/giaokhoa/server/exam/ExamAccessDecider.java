package com.giaokhoa.server.exam;

import com.giaokhoa.server.tenancy.TenantContext;

/**
 * Điểm quyết định quyền duy nhất của lát soạn đề, theo đúng hợp đồng của dịch vụ quyết định ở lõi quyền: đầu vào
 * là ngữ cảnh đã xác minh cùng tài nguyên/phiên bản đích và hành động; mặc định là từ chối và mọi kết quả mang
 * mã lý do. Khi lõi quyền merge, dịch vụ quyết định của lõi thay thế cài đặt của giao diện này mà không đổi
 * hành vi tại các endpoint.
 */
public interface ExamAccessDecider {

	/**
	 * Quyết định một hành động trên một phiên bản đề. {@code version} được phép vắng khi quyết định ở mức tài
	 * nguyên (ví dụ tạo phiên bản nháp mới), khi đó phần thu hẹp theo trạng thái của một phiên bản cụ thể không
	 * áp dụng.
	 */
	Decision decide(TenantContext context, Exam exam, ExamVersion version, ExamAction action);

	/** Quyết định một hành động trên một phiên bản câu thành phần của đề. */
	Decision decideOnQuestion(TenantContext context, QuestionVersionSource.QuestionVersionInfo question,
			ExamAction action);

	/**
	 * Kết quả quyết định: {@code allowed} kèm {@code reasonCode} (một trong các mã {@code ALLOWED},
	 * {@code NO_MATCHING_SOURCE}, {@code VERSION_LOCKED}, {@code VERSION_NOT_DRAFT}) và {@code source} là nhãn
	 * của nguồn quyền đã cấp khi được phép, vắng khi bị từ chối.
	 */
	record Decision(boolean allowed, String reasonCode, String source) {
	}

}
