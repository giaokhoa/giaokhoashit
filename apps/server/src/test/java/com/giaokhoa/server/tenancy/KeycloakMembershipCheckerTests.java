package com.giaokhoa.server.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Kiểm chứng riêng thành phần kiểm membership ở biên fail-closed không cần mạng: thiếu secret của service account
// là một trường hợp "không xác minh được" đã ghi trong đặc tả, và thành phần phải trả kết quả đó trước mọi lời gọi
// tới Keycloak.
class KeycloakMembershipCheckerTests {

	@Test
	@DisplayName("Thiếu secret của service account thì kết quả kiểm membership là không xác minh được")
	void blankSecretIsUnverifiable() {
		KeycloakMembershipChecker checker = new KeycloakMembershipChecker(
				"http://localhost:8180/realms/giaokhoa", "");
		assertEquals(MembershipChecker.Result.UNVERIFIABLE, checker.check("org-id", "member-id"));
	}

}
