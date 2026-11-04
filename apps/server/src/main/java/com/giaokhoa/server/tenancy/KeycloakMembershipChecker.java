package com.giaokhoa.server.tenancy;

import java.net.http.HttpClient;
import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Đọc membership hiện hành trực tiếp từ Admin REST API của Keycloak bằng service account của client {@code lms-api},
 * không cache: mỗi lần resolver sắp mở chế độ trung tâm đều đọc lại, để việc gỡ membership hoặc tắt Organization có
 * hiệu lực ngay cả với token chưa hết hạn. Service account chỉ cần hai client role chỉ đọc {@code view-organizations}
 * và {@code view-users} của client {@code realm-management}.
 */
@Component
public class KeycloakMembershipChecker implements MembershipChecker {

	private final RestClient keycloak;

	private final String realm;

	private final String clientSecret;

	@Autowired
	public KeycloakMembershipChecker(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
			@Value("${app.keycloak.membership.client-secret:}") String clientSecret) {
		// Chặn thời gian chờ ở cả hai chiều: một lần kiểm membership treo không được treo theo request đang chờ mở
		// phân vùng trung tâm.
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
		requestFactory.setReadTimeout(Duration.ofSeconds(5));
		this(issuerUri, clientSecret, RestClient.builder().requestFactory(requestFactory));
	}

	// Constructor nhận builder để test ghim được máy chủ HTTP giả lập (MockRestServiceServer) vào đúng RestClient
	// mà bản kiểm này dùng, thay vì stub tại seam MembershipChecker.
	KeycloakMembershipChecker(String issuerUri, String clientSecret, RestClient.Builder restClientBuilder) {
		// Issuer có dạng <địa chỉ Keycloak>/realms/<realm>; token endpoint và Admin REST API suy ra từ cùng gốc này.
		int realmMarker = issuerUri.indexOf("/realms/");
		String serverBase = issuerUri.substring(0, realmMarker);
		this.realm = issuerUri.substring(realmMarker + "/realms/".length());
		this.keycloak = restClientBuilder.baseUrl(serverBase).build();
		this.clientSecret = clientSecret;
	}

	@Override
	public Result check(String organizationId, String memberId) {
		if (this.clientSecret.isBlank()) {
			return Result.UNVERIFIABLE;
		}
		try {
			String accessToken = serviceAccountToken();
			if (accessToken == null) {
				return Result.UNVERIFIABLE;
			}
			OrganizationView organization = getOrganization(accessToken, organizationId);
			if (organization == null || !organization.enabled()) {
				return Result.NOT_MEMBER;
			}
			return isMember(accessToken, organizationId, memberId) ? Result.MEMBER : Result.NOT_MEMBER;
		}
		catch (RestClientException | HttpMessageConversionException ex) {
			// HttpMessageConversionException không phải RestClientException trong Spring 7 (hai nhánh độc lập cùng
			// kế thừa NestedRuntimeException): đáp ứng Keycloak sai hình dạng văng lỗi này, không bắt riêng thì nó
			// lọt ra ngoài thành 500 thay vì 503 fail-closed như thiết kế.
			return Result.UNVERIFIABLE;
		}
	}

	private String serviceAccountToken() {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "client_credentials");
		form.add("client_id", "lms-api");
		form.add("client_secret", this.clientSecret);
		TokenView token = this.keycloak.post()
				.uri("/realms/{realm}/protocol/openid-connect/token", this.realm)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.body(TokenView.class);
		return token != null ? token.accessToken() : null;
	}

	// Endpoint đọc Organization không liệt kê 404 trong tài liệu, nhưng một Organization không tồn tại thì không thể
	// có thành viên: coi NOT_FOUND là đã xác nhận không còn membership thay vì lỗi không xác minh được.
	private OrganizationView getOrganization(String accessToken, String organizationId) {
		try {
			return this.keycloak.get()
					.uri("/admin/realms/{realm}/organizations/{id}", this.realm, organizationId)
					.headers(headers -> headers.setBearerAuth(accessToken))
					.retrieve()
					.body(OrganizationView.class);
		}
		catch (HttpClientErrorException.NotFound ex) {
			return null;
		}
	}

	// Theo tài liệu Admin REST API, endpoint này trả 200 khi người dùng hiện còn là thành viên của Organization và
	// NOT_FOUND khi không còn, nên trạng thái HTTP là toàn bộ kết quả cần đọc.
	private boolean isMember(String accessToken, String organizationId, String memberId) {
		try {
			this.keycloak.get()
					.uri("/admin/realms/{realm}/organizations/{id}/members/{memberId}", this.realm, organizationId,
							memberId)
					.headers(headers -> headers.setBearerAuth(accessToken))
					.retrieve()
					.toBodilessEntity();
			return true;
		}
		catch (HttpClientErrorException.NotFound ex) {
			return false;
		}
	}

	private record OrganizationView(boolean enabled) {
	}

	private record TokenView(@JsonProperty("access_token") String accessToken) {
	}

}
