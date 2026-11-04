package com.giaokhoa.server.assignment;

import java.util.Optional;
import java.util.UUID;

/**
 * Điểm nối đọc định danh Organization từ alias của trang: token của khách không mang mục Organization của
 * trung tâm họ đang đứng (họ không phải thành viên), nên phân vùng của trang chỉ suy ra được bằng đường đọc
 * Keycloak này. Rỗng khi không tìm thấy hoặc không đọc được — tầng nghiệp vụ khi đó từ chối an toàn (503),
 * không suy đoán phân vùng.
 */
public interface OrganizationIds {

	Optional<UUID> findOrganizationIdByAlias(String alias);

}
