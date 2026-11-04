package com.giaokhoa.server.assignment;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Đọc định danh Organization theo alias qua Admin REST API của Keycloak bằng cùng service account chỉ đọc của
 * client `lms-api` mà khung tenancy dùng để kiểm membership, không cache: phân vùng của trang khách phải là dữ
 * kiện hiện hành tại đúng thời điểm mở đọc. Endpoint liệt kê Organization không tìm theo alias, nên đọc danh
 * sách rồi đối chiếu trường alias của từng Organization tại đây — cùng cách khối tổ chức dạy học dùng cho đọc
 * của khách.
 */
@Component
public class KeycloakOrganizationIds implements OrganizationIds {

	private final RestClient keycloak;

	private final String realm;

	private final String clientSecret;

	@Autowired
	public KeycloakOrganizationIds(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
			@Value("${app.keycloak.membership.client-secret:}") String clientSecret) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
		requestFactory.setReadTimeout(Duration.ofSeconds(5));
		this(issuerUri, clientSecret, RestClient.builder().requestFactory(requestFactory));
	}

	// Constructor nhận builder để test ghim được máy chủ HTTP giả lập (MockRestServiceServer) vào đúng RestClient
	// mà bản đọc này dùng, thay vì stub tại seam OrganizationIds.
	KeycloakOrganizationIds(String issuerUri, String clientSecret, RestClient.Builder restClientBuilder) {
		// Issuer có dạng <địa chỉ Keycloak>/realms/<realm>; token endpoint và Admin REST API suy ra từ cùng gốc này.
		int realmMarker = issuerUri.indexOf("/realms/");
		String serverBase = issuerUri.substring(0, realmMarker);
		this.realm = issuerUri.substring(realmMarker + "/realms/".length());
		this.keycloak = restClientBuilder.baseUrl(serverBase).build();
		this.clientSecret = clientSecret;
	}

	@Override
	public Optional<UUID> findOrganizationIdByAlias(String alias) {
		if (this.clientSecret.isBlank() || alias == null || alias.isBlank()) {
			return Optional.empty();
		}
		try {
			String accessToken = serviceAccountToken();
			if (accessToken == null) {
				return Optional.empty();
			}
			List<OrganizationView> organizations = this.keycloak.get()
					.uri(uriBuilder -> uriBuilder.path("/admin/realms/{realm}/organizations")
							.queryParam("briefRepresentation", false)
							.queryParam("first", 0)
							.queryParam("max", 100)
							.build(this.realm))
					.headers(headers -> headers.setBearerAuth(accessToken))
					.retrieve()
					.body(new ParameterizedTypeReference<List<OrganizationView>>() {
					});
			if (organizations == null) {
				return Optional.empty();
			}
			return organizations.stream()
					.filter(organization -> alias.equals(organization.alias()))
					.map(organization -> UUID.fromString(organization.id()))
					.findFirst();
		}
		catch (RestClientException | HttpMessageConversionException | IllegalArgumentException ex) {
			// HttpMessageConversionException không phải RestClientException trong Spring 7 (hai nhánh độc lập cùng
			// kế thừa NestedRuntimeException): đáp ứng Keycloak sai hình dạng văng lỗi này, không bắt riêng thì nó
			// lọt ra ngoài thành 500 thay vì kết quả rỗng fail-closed như hợp đồng của điểm nối.
			return Optional.empty();
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

	private record OrganizationView(String id, String alias) {
	}

	private record TokenView(@JsonProperty("access_token") String accessToken) {
	}

}
