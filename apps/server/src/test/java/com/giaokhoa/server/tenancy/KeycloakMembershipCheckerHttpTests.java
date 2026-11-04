package com.giaokhoa.server.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.giaokhoa.server.tenancy.MembershipChecker.Result;

// Kiểm chứng hình đáp ứng trên đường HTTP thật của các lời gọi Admin REST API trong KeycloakMembershipChecker:
// các view được deserialize từ thân đáp ứng, nên một đáp ứng sai hình dạng văng HttpMessageNotReadableException —
// lỗi chuyển đổi thuộc nhánh HttpMessageConversionException, tách hẳn khỏi RestClientException trong Spring 7.
// Các bộ test ở mức endpoint stub tại seam MembershipChecker hoặc để secret trống nên không tạo được đáp ứng
// sai hình dạng; lớp này stub ở tầng HTTP bằng MockRestServiceServer để ghim hành vi fail-closed: đáp ứng sai
// hình dạng quy về UNVERIFIABLE — kết quả mà lát tổ chức dịch thành 503 MEMBERSHIP_UNVERIFIABLE tại các quyết
// định ghi dùng bản kiểm này — thay vì lọt ra ngoài thành 500.
class KeycloakMembershipCheckerHttpTests {

	private static final String ISSUER = "http://keycloak.test/realms/giaokhoa";

	private static final String ADMIN_BASE = "http://keycloak.test/admin/realms/giaokhoa";

	private static final String ORG_ID = "11111111-1111-1111-1111-111111111111";

	private static final String MEMBER_ID = "gv-trung-tam-1";

	private MockRestServiceServer server;

	private KeycloakMembershipChecker checker;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		this.server = MockRestServiceServer.bindTo(builder).build();
		this.checker = new KeycloakMembershipChecker(ISSUER, "test-secret", builder);
	}

	@Test
	@DisplayName("Đáp ứng token và Organization đúng hình dạng, Organization đang bật và endpoint thành viên trả 200: kết quả là thành viên")
	void wellFormedResponsesYieldMember() {
		expectToken("{\"access_token\":\"token-thu\",\"token_type\":\"Bearer\",\"expires_in\":300}");
		expectOrganization("{\"id\":\"" + ORG_ID + "\",\"name\":\"Trung tam 1\",\"alias\":\"trung-tam-1\",\"enabled\":true}");
		expectMemberEndpoint();
		assertEquals(Result.MEMBER, this.checker.check(ORG_ID, MEMBER_ID));
		this.server.verify();
	}

	@Test
	@DisplayName("Đáp ứng Organization sai hình dạng — mảng thay vì object: kết quả là không xác minh được, lỗi chuyển đổi không lọt ra ngoài thành lỗi 500")
	void arrayShapedOrganizationBodyYieldsUnverifiable() {
		expectToken("{\"access_token\":\"token-thu\",\"token_type\":\"Bearer\",\"expires_in\":300}");
		expectOrganization("[{\"id\":\"" + ORG_ID + "\",\"enabled\":true}]");
		assertEquals(Result.UNVERIFIABLE, this.checker.check(ORG_ID, MEMBER_ID));
		this.server.verify();
	}

	@Test
	@DisplayName("Đáp ứng token sai hình dạng — mảng thay vì object: kết quả là không xác minh được, lỗi chuyển đổi không lọt ra ngoài thành lỗi 500")
	void arrayShapedTokenBodyYieldsUnverifiable() {
		expectToken("[{\"access_token\":\"token-thu\"}]");
		assertEquals(Result.UNVERIFIABLE, this.checker.check(ORG_ID, MEMBER_ID));
		this.server.verify();
	}

	private void expectToken(String body) {
		this.server.expect(requestTo("http://keycloak.test/realms/giaokhoa/protocol/openid-connect/token"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

	private void expectOrganization(String body) {
		this.server.expect(requestTo(ADMIN_BASE + "/organizations/" + ORG_ID))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

	private void expectMemberEndpoint() {
		this.server.expect(requestTo(ADMIN_BASE + "/organizations/" + ORG_ID + "/members/" + MEMBER_ID))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("", MediaType.APPLICATION_JSON));
	}

}
