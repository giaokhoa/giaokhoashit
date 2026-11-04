package com.giaokhoa.server.tenancy;

/**
 * Kiểm membership hiện hành của một người dùng trong một Organization tại Keycloak, dùng tại các quyết định ghi
 * thực hiện một lần trong lát tổ chức dạy học (đặt giáo viên, ghi danh): các quyết định đó phải dựa trên dữ kiện
 * hiện hành tại đúng thời điểm thực hiện, không dựa trên claim của một token được cấp trước đó. Ngữ cảnh request
 * không qua thành phần này: ngữ cảnh quyết theo claim {@code organization} của token đã kiểm chứng.
 */
public interface MembershipChecker {

	enum Result {

		/** Keycloak xác nhận người dùng còn là thành viên và Organization còn đang hoạt động. */
		MEMBER,

		/** Keycloak xác nhận người dùng không còn là thành viên, hoặc Organization đã tắt hay không tồn tại. */
		NOT_MEMBER,

		/** Không xác minh được (Keycloak không tới được hoặc service account bị từ chối quyền đọc): fail-closed. */
		UNVERIFIABLE

	}

	Result check(String organizationId, String memberId);

}
