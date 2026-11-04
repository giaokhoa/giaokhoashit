package com.giaokhoa.server.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.giaokhoa.server.organization.OrganizationDirectory.TeacherRoleResult;

// Kiểm chứng hình đáp ứng trên đường HTTP thật của các lời gọi Admin REST API trong KeycloakOrganizationDirectory:
// theo tài liệu Keycloak Admin REST API 26.8.1, endpoint .../groups/{group-id}/role-mappings/realm trả về một mảng
// List[RoleRepresentation], còn kiểu object có trường realmMappings là MappingsRepresentation của endpoint gộp
// .../role-mappings. Bộ test ở OrganizationEndpointTests stub tại seam OrganizationDirectory nên không thể thấy
// sai lệch hình đáp ứng này; lớp này stub ở tầng HTTP bằng MockRestServiceServer để ghim đúng hình dạng docs quy
// định và đúng hành vi fail-closed khi đáp ứng sai hình dạng đó.
class KeycloakOrganizationDirectoryHttpTests {

	private static final String ISSUER = "http://keycloak.test/realms/giaokhoa";

	private static final String ADMIN_BASE = "http://keycloak.test/admin/realms/giaokhoa";

	private static final UUID ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final String TEACHER_GROUP_ID = "nhom-giao-vien";

	private static final String MEMBER_SUB = "gv-trung-tam-1";

	private MockRestServiceServer server;

	private KeycloakOrganizationDirectory directory;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		this.server = MockRestServiceServer.bindTo(builder).build();
		this.directory = new KeycloakOrganizationDirectory(ISSUER, "test-secret", builder);
	}

	@Test
	@DisplayName("Đáp ứng role-mappings là mảng chứa vai trò tenant-teacher và người dùng thuộc đúng nhóm đó: kết quả là giáo viên")
	void arrayWithTeacherRoleYieldsTeacher() {
		expectToken();
		expectOrganizationGroups();
		expectRoleMappings("""
				[{"id":"id-vai-giao-vien","name":"tenant-teacher","description":"Giao vien cua trung tam",\
				"composite":false,"clientRole":false,"containerId":"id-realm"}]""");
		expectMemberGroups("""
				[{"id":"nhom-giao-vien","name":"giao-vien","subGroups":[]}]""");
		assertEquals(TeacherRoleResult.TEACHER, this.directory.checkTeacherRole(ORG_ID, MEMBER_SUB));
		this.server.verify();
	}

	@Test
	@DisplayName("Đáp ứng role-mappings là mảng chỉ chứa vai trò khác tenant-teacher: kết quả là không phải giáo viên")
	void arrayWithoutTeacherRoleYieldsNotTeacher() {
		expectToken();
		expectOrganizationGroups();
		expectRoleMappings("""
				[{"id":"id-vai-thanh-vien","name":"tenant-member","composite":false,"clientRole":false,\
				"containerId":"id-realm"}]""");
		assertEquals(TeacherRoleResult.NOT_TEACHER, this.directory.checkTeacherRole(ORG_ID, MEMBER_SUB));
		this.server.verify();
	}

	@Test
	@DisplayName("Đáp ứng role-mappings là mảng rỗng: kết quả là không phải giáo viên")
	void emptyArrayYieldsNotTeacher() {
		expectToken();
		expectOrganizationGroups();
		expectRoleMappings("[]");
		assertEquals(TeacherRoleResult.NOT_TEACHER, this.directory.checkTeacherRole(ORG_ID, MEMBER_SUB));
		this.server.verify();
	}

	@Test
	@DisplayName("Đáp ứng role-mappings sai hình dạng — object của MappingsRepresentation thay vì mảng: kết quả là không xác minh được, lỗi chuyển đổi không lọt ra ngoài thành lỗi 500")
	void objectShapedBodyYieldsUnverifiable() {
		expectToken();
		expectOrganizationGroups();
		expectRoleMappings("""
				{"realmMappings":[{"id":"id-vai-giao-vien","name":"tenant-teacher"}],"clientMappings":{}}""");
		assertEquals(TeacherRoleResult.UNVERIFIABLE, this.directory.checkTeacherRole(ORG_ID, MEMBER_SUB));
		this.server.verify();
	}

	private void expectToken() {
		this.server
			.expect(requestTo("http://keycloak.test/realms/giaokhoa/protocol/openid-connect/token"))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("{\"access_token\":\"token-thu\",\"token_type\":\"Bearer\",\"expires_in\":300}",
					MediaType.APPLICATION_JSON));
	}

	private void expectOrganizationGroups() {
		this.server
			.expect(requestTo(ADMIN_BASE + "/organizations/" + ORG_ID
					+ "/groups?briefRepresentation=false&populateHierarchy=true"))
			.andRespond(withSuccess("[{\"id\":\"" + TEACHER_GROUP_ID + "\",\"name\":\"giao-vien\",\"subGroups\":[]}]",
					MediaType.APPLICATION_JSON));
	}

	private void expectRoleMappings(String body) {
		this.server
			.expect(requestTo(ADMIN_BASE + "/organizations/" + ORG_ID + "/groups/" + TEACHER_GROUP_ID
					+ "/role-mappings/realm"))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

	private void expectMemberGroups(String body) {
		this.server
			.expect(requestTo(ADMIN_BASE + "/organizations/" + ORG_ID + "/members/" + MEMBER_SUB + "/groups"))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

}
