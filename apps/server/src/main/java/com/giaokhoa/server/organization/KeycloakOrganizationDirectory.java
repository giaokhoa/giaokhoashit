package com.giaokhoa.server.organization;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Bản đọc qua Admin REST API của Keycloak bằng cùng service account chỉ đọc của client {@code lms-api} mà bản
 * kiểm membership hiện hành dùng (hai client role {@code view-organizations} và {@code view-users} của client
 * {@code realm-management}). Không cache: vai trò giáo viên và định danh Organization phải là dữ kiện hiện hành tại
 * đúng thời điểm đặt giáo viên hoặc mở đọc cho khách.
 */
@Component
public class KeycloakOrganizationDirectory implements OrganizationDirectory {

	private static final String TEACHER_ROLE = "tenant-teacher";

	private final RestClient keycloak;

	private final String realm;

	private final String clientSecret;

	@Autowired
	public KeycloakOrganizationDirectory(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
			@Value("${app.keycloak.membership.client-secret:}") String clientSecret) {
		// Chặn thời gian chờ ở cả hai chiều như đường kiểm membership của khung tenancy: một lần đọc treo không
		// được treo theo request đang chờ.
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
		requestFactory.setReadTimeout(Duration.ofSeconds(5));
		this(issuerUri, clientSecret, RestClient.builder().requestFactory(requestFactory));
	}

	// Constructor nhận builder để test ghim được máy chủ HTTP giả lập (MockRestServiceServer) vào đúng RestClient
	// mà bản đọc này dùng, thay vì stub tại seam OrganizationDirectory.
	KeycloakOrganizationDirectory(String issuerUri, String clientSecret, RestClient.Builder restClientBuilder) {
		// Issuer có dạng <địa chỉ Keycloak>/realms/<realm>; token endpoint và Admin REST API suy ra từ cùng gốc này.
		int realmMarker = issuerUri.indexOf("/realms/");
		String serverBase = issuerUri.substring(0, realmMarker);
		this.realm = issuerUri.substring(realmMarker + "/realms/".length());
		this.keycloak = restClientBuilder.baseUrl(serverBase).build();
		this.clientSecret = clientSecret;
	}

	@Override
	public Optional<UUID> findOrganizationIdByAlias(String alias) {
		// Endpoint liệt kê Organization của Admin REST API chỉ tìm theo tên, domain hoặc thuộc tính tuỳ chỉnh, không
		// tìm theo alias, nên phải đọc danh sách rồi đối chiếu trường alias của từng Organization ở phía server này.
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
			return Optional.empty();
		}
	}

	@Override
	public TeacherRoleResult checkTeacherRole(UUID organizationId, String memberSub) {
		if (this.clientSecret.isBlank()) {
			return TeacherRoleResult.UNVERIFIABLE;
		}
		try {
			String accessToken = serviceAccountToken();
			if (accessToken == null) {
				return TeacherRoleResult.UNVERIFIABLE;
			}
			List<GroupView> groups = getOrganizationGroups(accessToken, organizationId);
			if (groups == null) {
				return TeacherRoleResult.UNVERIFIABLE;
			}
			Set<String> teacherGroupIds = new HashSet<>();
			for (String groupId : flattenGroupIds(groups)) {
				List<RoleView> mappings = getGroupRoleMappings(accessToken, organizationId, groupId);
				if (mappings == null) {
					return TeacherRoleResult.UNVERIFIABLE;
				}
				// Chỉ ánh xạ trực tiếp mới được tính: ánh xạ composite sẽ kéo theo vai trò do nhóm của Organization
				// khác đóng góp, trong khi vai trò giáo viên phải là của đúng Organization đang xét.
				if (mappings.stream().anyMatch(role -> TEACHER_ROLE.equals(role.name()))) {
					teacherGroupIds.add(groupId);
				}
			}
			if (teacherGroupIds.isEmpty()) {
				return TeacherRoleResult.NOT_TEACHER;
			}
			Set<String> memberGroupIds = getMemberGroupIds(accessToken, organizationId, memberSub);
			if (memberGroupIds == null) {
				return TeacherRoleResult.UNVERIFIABLE;
			}
			return memberGroupIds.stream().anyMatch(teacherGroupIds::contains) ? TeacherRoleResult.TEACHER
					: TeacherRoleResult.NOT_TEACHER;
		}
		catch (RestClientException | HttpMessageConversionException ex) {
			// HttpMessageConversionException không phải RestClientException trong Spring 7 (hai nhánh độc lập cùng
			// kế thừa NestedRuntimeException): đáp ứng Keycloak sai hình dạng văng lỗi này, không bắt riêng thì nó
			// lọt ra ngoài thành 500 thay vì 503 fail-closed như thiết kế.
			return TeacherRoleResult.UNVERIFIABLE;
		}
	}

	private List<GroupView> getOrganizationGroups(String accessToken, UUID organizationId) {
		// populateHierarchy=true để nhận cả nhóm con lồng trong từng nhóm cấp cao nhất: vai trò giáo viên có thể
		// được gán ở bất kỳ mức nhóm nào của Organization.
		return this.keycloak.get()
			.uri(uriBuilder -> uriBuilder
				.path("/admin/realms/{realm}/organizations/{organizationId}/groups")
				.queryParam("briefRepresentation", false)
				.queryParam("populateHierarchy", true)
				.build(this.realm, organizationId))
			.headers(headers -> headers.setBearerAuth(accessToken))
			.retrieve()
			.body(new ParameterizedTypeReference<List<GroupView>>() {
			});
	}

	private List<RoleView> getGroupRoleMappings(String accessToken, UUID organizationId, String groupId) {
		// Theo tài liệu Admin REST API, endpoint .../role-mappings/realm trả về List[RoleRepresentation] — một
		// mảng JSON; kiểu object có trường realmMappings là MappingsRepresentation của endpoint gộp
		// .../role-mappings, không phải của endpoint này.
		return this.keycloak.get()
			.uri("/admin/realms/{realm}/organizations/{organizationId}/groups/{groupId}/role-mappings/realm",
					this.realm, organizationId, groupId)
			.headers(headers -> headers.setBearerAuth(accessToken))
			.retrieve()
			.body(new ParameterizedTypeReference<List<RoleView>>() {
			});
	}

	private Set<String> getMemberGroupIds(String accessToken, UUID organizationId, String memberSub) {
		try {
			List<GroupView> groups = this.keycloak.get()
				.uri("/admin/realms/{realm}/organizations/{organizationId}/members/{memberId}/groups", this.realm,
						organizationId, memberSub)
				.headers(headers -> headers.setBearerAuth(accessToken))
				.retrieve()
				.body(new ParameterizedTypeReference<List<GroupView>>() {
				});
			Set<String> ids = new HashSet<>();
			if (groups != null) {
				groups.forEach(group -> ids.add(group.id()));
			}
			return ids;
		}
		catch (HttpClientErrorException.NotFound ex) {
			// Theo tài liệu Admin REST API, endpoint này trả NOT_FOUND khi người dùng hiện không phải thành viên của
			// Organization: đó là kết quả "không thuộc nhóm nào của Organization", không phải lỗi không xác minh được.
			return Set.of();
		}
	}

	private Set<String> flattenGroupIds(List<GroupView> groups) {
		Set<String> ids = new HashSet<>();
		List<GroupView> pending = new ArrayList<>(groups);
		while (!pending.isEmpty()) {
			GroupView group = pending.remove(pending.size() - 1);
			ids.add(group.id());
			if (group.subGroups() != null) {
				pending.addAll(group.subGroups());
			}
		}
		return ids;
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

	private record GroupView(String id, List<GroupView> subGroups) {
	}

	private record RoleView(String name) {
	}

	private record TokenView(@JsonProperty("access_token") String accessToken) {
	}

}
