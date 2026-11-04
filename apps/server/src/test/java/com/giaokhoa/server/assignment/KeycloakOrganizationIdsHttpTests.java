package com.giaokhoa.server.assignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// Kiểm chứng hình đáp ứng trên đường HTTP thật của các lời gọi Admin REST API trong KeycloakOrganizationIds:
// các view được deserialize từ thân đáp ứng, nên một đáp ứng sai hình dạng văng HttpMessageNotReadableException —
// lỗi chuyển đổi thuộc nhánh HttpMessageConversionException, tách hẳn khỏi RestClientException trong Spring 7.
// Các bộ test ở mức nghiệp vụ stub tại seam OrganizationIds nên không tạo được đáp ứng sai hình dạng; lớp này
// stub ở tầng HTTP bằng MockRestServiceServer để ghim hành vi fail-closed: đáp ứng sai hình dạng quy về kết quả
// rỗng — kết quả mà tầng nghiệp vụ dịch thành HTTP 503 — thay vì lọt ra ngoài thành 500.
class KeycloakOrganizationIdsHttpTests {

	private static final String ISSUER = "http://keycloak.test/realms/giaokhoa";

	private static final String ORGANIZATIONS_URL = "http://keycloak.test/admin/realms/giaokhoa/organizations"
			+ "?briefRepresentation=false&first=0&max=100";

	private static final String ORG_ID = "22222222-2222-2222-2222-222222222222";

	private static final String ALIAS = "trung-tam-1";

	private MockRestServiceServer server;

	private KeycloakOrganizationIds organizationIds;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		this.server = MockRestServiceServer.bindTo(builder).build();
		this.organizationIds = new KeycloakOrganizationIds(ISSUER, "test-secret", builder);
	}

	@Test
	@DisplayName("Đáp ứng token và danh sách Organization đúng hình dạng: trả về ID của Organization khớp alias")
	void wellFormedResponsesYieldMatchingId() {
		expectToken("{\"access_token\":\"token-thu\",\"token_type\":\"Bearer\",\"expires_in\":300}");
		expectOrganizations(
				"[{\"id\":\"" + ORG_ID + "\",\"name\":\"Trung tam 1\",\"alias\":\"" + ALIAS + "\",\"enabled\":true}]");
		assertEquals(Optional.of(UUID.fromString(ORG_ID)), this.organizationIds.findOrganizationIdByAlias(ALIAS));
		this.server.verify();
	}

	@Test
	@DisplayName("Đáp ứng token sai hình dạng — mảng thay vì object: kết quả rỗng fail-closed, lỗi chuyển đổi không lọt ra ngoài thành lỗi 500")
	void arrayShapedTokenBodyYieldsEmpty() {
		expectToken("[{\"access_token\":\"token-thu\"}]");
		assertEquals(Optional.empty(), this.organizationIds.findOrganizationIdByAlias(ALIAS));
		this.server.verify();
	}

	@Test
	@DisplayName("Đáp ứng danh sách Organization sai hình dạng — object thay vì mảng: kết quả rỗng fail-closed, lỗi chuyển đổi không lọt ra ngoài thành lỗi 500")
	void objectShapedOrganizationsBodyYieldsEmpty() {
		expectToken("{\"access_token\":\"token-thu\",\"token_type\":\"Bearer\",\"expires_in\":300}");
		expectOrganizations("{\"id\":\"" + ORG_ID + "\",\"alias\":\"" + ALIAS + "\",\"enabled\":true}");
		assertEquals(Optional.empty(), this.organizationIds.findOrganizationIdByAlias(ALIAS));
		this.server.verify();
	}

	private void expectToken(String body) {
		this.server.expect(requestTo("http://keycloak.test/realms/giaokhoa/protocol/openid-connect/token"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

	private void expectOrganizations(String body) {
		this.server.expect(requestTo(ORGANIZATIONS_URL))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

}
