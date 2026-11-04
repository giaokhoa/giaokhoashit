package com.giaokhoa.server.tenancy;

import java.util.List;

/**
 * Ngữ cảnh tenant của một request đã xác thực: suy ra duy nhất từ JWT đã kiểm chứng và alias trung tâm của trang,
 * không bao giờ từ dữ kiện client gửi trong thân request. Bất biến trong suốt request và trong suốt một Hibernate
 * Session.
 *
 * @param sub định danh người dùng trong token
 * @param organizationAlias alias của Organization của trang, vắng khi là trang nền tảng
 * @param organizationId ID của Organization của trang khi người dùng là thành viên, vắng trong các trường hợp còn
 *            lại
 * @param organizationRoles vai của người dùng trong Organization của trang, đọc tại
 *            {@code organization[<alias>].realm_access.roles} của claim
 * @param guest người dùng có ngữ cảnh trang của một trung tâm nhưng không phải thành viên của trung tâm đó
 * @param platformAdmin người dùng có realm role {@code platform-admin}
 * @param partitionKey khoá phân vùng hiệu lực cho dữ kiện: Organization ID ở chế độ trung tâm, {@code user:<sub>} ở
 *            chế độ cá nhân, {@code system} ở chế độ hệ thống, vắng ở chế độ khách
 */
public record TenantContext(String sub, String organizationAlias, String organizationId,
		List<String> organizationRoles, boolean guest, boolean platformAdmin, String partitionKey) {
}
