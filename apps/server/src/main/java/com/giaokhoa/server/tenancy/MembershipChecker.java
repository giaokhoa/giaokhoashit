package com.giaokhoa.server.tenancy;

/**
 * Kiểm membership hiện hành của một người dùng trong một Organization tại Keycloak. Resolver chỉ hỏi thành phần này
 * khi sắp mở chế độ trung tâm: claim trong token chỉ phản ánh membership tại thời điểm phát hành token, nên một token
 * chưa hết hạn không đủ căn cứ mở phân vùng của trung tâm sau khi membership đã bị gỡ.
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
