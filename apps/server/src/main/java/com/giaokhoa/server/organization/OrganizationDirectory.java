package com.giaokhoa.server.organization;

import java.util.Optional;
import java.util.UUID;

/**
 * Hai dữ kiện của Keycloak mà token không mang theo và khối này buộc phải đọc tại thời điểm xử lý: định danh
 * Organization suy ra từ alias của trang, và vai trò giáo viên hiện hành của một thành viên tại một Organization.
 * Mọi tình huống không đọc được đều trả về rỗng hoặc {@link TeacherRoleResult#UNVERIFIABLE} để tầng nghiệp vụ từ
 * chối an toàn (503), không bao giờ suy đoán thay cho dữ kiện chưa kiểm chứng.
 */
public interface OrganizationDirectory {

	/**
	 * Tìm định danh của Organization theo alias. Rỗng khi không tìm thấy hoặc không đọc được Keycloak.
	 */
	Optional<UUID> findOrganizationIdByAlias(String alias);

	/**
	 * Kiểm tra người dùng có đang giữ vai trò giáo viên tại Organization hay không, theo cơ chế gán vai trò qua
	 * nhóm của Organization trong Keycloak.
	 */
	TeacherRoleResult checkTeacherRole(UUID organizationId, String memberSub);

	enum TeacherRoleResult {

		TEACHER, NOT_TEACHER, UNVERIFIABLE

	}

}
