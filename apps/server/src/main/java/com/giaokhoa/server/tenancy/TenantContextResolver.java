package com.giaokhoa.server.tenancy;

import java.util.List;
import java.util.Map;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Suy ra {@link TenantContext} từ JWT đã kiểm chứng và alias trung tâm của trang. Bốn chế độ: trung tâm (thành viên
 * của Organization của trang), khách (có ngữ cảnh trang nhưng không phải thành viên), cá nhân (trang nền tảng) và hệ
 * thống (trang nền tảng với realm role {@code platform-admin}). Tư cách thành viên quyết hoàn toàn theo claim
 * {@code organization} của token đã kiểm chứng, đúng flow chuẩn của docs Keycloak: claim chỉ chứa các Organization
 * mà người dùng là thành viên tường minh, nên việc gỡ membership có hiệu lực từ token được cấp sau, không phải
 * ngay với token đang còn hạn.
 */
public class TenantContextResolver {

	private static final String PLATFORM_ADMIN_ROLE = "platform-admin";

	private static final String SYSTEM_PARTITION_KEY = "system";

	public TenantContext resolve(Jwt jwt, String pageOrganizationAlias) {
		String sub = jwt.getSubject();
		boolean platformAdmin = realmRoles(jwt).contains(PLATFORM_ADMIN_ROLE);
		if (pageOrganizationAlias != null) {
			Map<String, Object> organization = organizationEntry(jwt, pageOrganizationAlias);
			if (organization == null) {
				return new TenantContext(sub, pageOrganizationAlias, null, List.of(), true, platformAdmin, null);
			}
			String organizationId = organization.get("id") instanceof String id ? id : null;
			if (organizationId == null) {
				// Claim mang alias nhưng thiếu id (mapper Organization Membership chưa bật "Add organization id")
				// thì không có khoá phân vùng của trung tâm để mở: theo chế độ khách thay vì tin một claim thiếu
				// căn cứ định danh.
				return new TenantContext(sub, pageOrganizationAlias, null, List.of(), true, platformAdmin, null);
			}
			return new TenantContext(sub, pageOrganizationAlias, organizationId, organizationRoles(organization),
					false, platformAdmin, organizationId);
		}
		if (platformAdmin) {
			return new TenantContext(sub, null, null, List.of(), false, true, SYSTEM_PARTITION_KEY);
		}
		return new TenantContext(sub, null, null, List.of(), false, false, "user:" + sub);
	}

	// Claim organization có hai hình dạng tuỳ cấu hình mapper của client scope organization: map alias -> thông tin
	// organization (hình dạng đầy đủ mang id và vai), hoặc mảng alias ở cấu hình mặc định. Dạng mảng chỉ chứng minh
	// được tư cách thành viên, không có id hay vai để dựng ngữ cảnh trung tâm.
	private Map<String, Object> organizationEntry(Jwt jwt, String alias) {
		Object claim = jwt.getClaim("organization");
		if (claim instanceof Map<?, ?> map) {
			if (map.get(alias) instanceof Map<?, ?> entry) {
				@SuppressWarnings("unchecked")
				Map<String, Object> typed = (Map<String, Object>) entry;
				return typed;
			}
			return null;
		}
		if (claim instanceof List<?> list && list.contains(alias)) {
			return Map.of();
		}
		return null;
	}

	// Vai theo Organization chỉ đọc tại realm_access lồng trong đúng mục của Organization đó: claim realm_access ở
	// cấp cao nhất của token đã gộp vai từ mọi group của người dùng nên đọc ở đó sẽ làm vai của trung tâm này áp
	// sang trung tâm khác. Mục thiếu realm_access (mapper chưa bật) nghĩa là không có vai nào.
	private List<String> organizationRoles(Map<String, Object> organization) {
		if (organization.get("realm_access") instanceof Map<?, ?> realmAccess) {
			return stringList(realmAccess.get("roles"));
		}
		return List.of();
	}

	private List<String> realmRoles(Jwt jwt) {
		if (jwt.getClaim("realm_access") instanceof Map<?, ?> realmAccess) {
			return stringList(realmAccess.get("roles"));
		}
		return List.of();
	}

	private List<String> stringList(Object value) {
		if (value instanceof List<?> list) {
			return list.stream().filter(String.class::isInstance).map(String.class::cast).sorted().toList();
		}
		return List.of();
	}

}
