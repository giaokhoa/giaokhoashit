package com.giaokhoa.server;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Endpoint danh tính của người gọi: ánh xạ thẳng các claim danh tính trong JWT đã được cổng
// Resource Server kiểm chứng (chữ ký, issuer, thời hạn) sang đáp ứng JSON. Token là nguồn sự
// thật duy nhất ở đây — endpoint không truy vấn cơ sở dữ liệu và không gọi ngược Keycloak.
@RestController
@RequestMapping("/api")
public class MeController {

	@GetMapping("/me")
	MeResponse me(@AuthenticationPrincipal Jwt jwt) {
		return new MeResponse(
				jwt.getSubject(),
				jwt.getClaimAsString("preferred_username"),
				jwt.getClaimAsString("name"),
				jwt.getClaimAsString("email"),
				realmRoles(jwt),
				organizationAliases(jwt));
	}

	// Keycloak đặt vai trò realm trong claim lồng realm_access.roles, không có claim phẳng.
	private List<String> realmRoles(Jwt jwt) {
		Map<String, Object> realmAccess = jwt.getClaim("realm_access");
		if (realmAccess == null) {
			return List.of();
		}
		Object roles = realmAccess.get("roles");
		if (roles instanceof List<?> list) {
			return list.stream().filter(String.class::isInstance).map(String.class::cast).sorted().toList();
		}
		return List.of();
	}

	// Claim organization có hai hình dạng tuỳ cấu hình mapper của client scope organization:
	// mảng alias ở cấu hình mặc định, hoặc map alias -> thông tin organization khi mapper bật
	// thêm id/attributes. Cả hai đều quy về danh sách alias của các organization người dùng
	// là thành viên.
	private List<String> organizationAliases(Jwt jwt) {
		Object claim = jwt.getClaim("organization");
		if (claim instanceof List<?> list) {
			return list.stream().filter(String.class::isInstance).map(String.class::cast).sorted().toList();
		}
		if (claim instanceof Map<?, ?> map) {
			return map.keySet().stream().filter(String.class::isInstance).map(String.class::cast).sorted().toList();
		}
		return List.of();
	}

	record MeResponse(String sub, String preferredUsername, String name, String email,
			List<String> realmRoles, List<String> organizations) {
	}

}
