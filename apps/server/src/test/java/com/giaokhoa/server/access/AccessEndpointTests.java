package com.giaokhoa.server.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.giaokhoa.server.tenancy.MembershipChecker;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// Nghiệm thu lõi quyền trên đường thật của ứng dụng: chuỗi lọc bảo mật và khung tenancy của F3, tầng dữ kiện thật
// (Flyway + Hibernate/JPA với discriminator) trên H2 ở chế độ PostgreSQL, dịch vụ quyết định và các thao tác ghi
// chạy trọn qua HTTP. Chỉ bước tải JWKS và bước kiểm membership hiện hành được thay bằng bản có kiểm soát; lớp
// RLS của PostgreSQL được kiểm chứng riêng trong AccessPostgresRlsTests.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + AccessEndpointTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:access;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration" })
class AccessEndpointTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	private static final String ORG_2_ID = "22222222-2222-2222-2222-222222222222";

	private static final String ALIAS_1 = "trung-tam-1";

	private static final String ALIAS_2 = "trung-tam-2";

	private static final Pattern RESOURCE_ID = Pattern.compile("\"resourceId\":\"([^\"]+)\"");

	private static final Pattern VERSION_ID = Pattern.compile("\"versionId\":\"([^\"]+)\"");

	private static final Pattern GRANT_ID = Pattern.compile("\"grantId\":\"([^\"]+)\"");

	private static final Pattern ASSIGNMENT_ID = Pattern.compile("\"assignmentId\":\"([^\"]+)\"");

	private static final Pattern REQUEST_ID = Pattern.compile("\"requestId\":\"([^\"]+)\"");

	private static final Pattern SUBMISSION_ID = Pattern.compile("\"submissionId\":\"([^\"]+)\"");

	private static final Pattern RECEIVED_RESOURCE_ID = Pattern.compile("\"receivedResourceId\":\"([^\"]+)\"");

	private static final Pattern REASON_CODE = Pattern.compile("\"reasonCode\":\"([^\"]+)\"");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private MembershipChecker membershipChecker;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		lenient().when(this.membershipChecker.check(anyString(), anyString()))
				.thenReturn(MembershipChecker.Result.MEMBER);
	}

	@TestConfiguration
	static class TestJwtConfiguration {

		static final KeyPair KEY_PAIR = generateKeyPair();

		private static KeyPair generateKeyPair() {
			try {
				KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
				generator.initialize(2048);
				return generator.generateKeyPair();
			}
			catch (NoSuchAlgorithmException ex) {
				throw new IllegalStateException(ex);
			}
		}

		@Bean
		JwtDecoder jwtDecoder() {
			NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic()).build();
			decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
			return decoder;
		}

	}

	@Test
	@DisplayName("Chủ cá nhân có đủ quyền trên tài nguyên của mình, bản đã xuất bản bị khoá sửa, và tài khoản không có personal-author không tạo được tài nguyên cá nhân")
	void personalOwnerFlow() throws Exception {
		String teacher = authorToken("gv-chu");
		MvcResult created = post(teacher, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"personal\",\"contentHash\":\"h1\"}");
		assertEquals(200, created.getResponse().getStatus(), body(created));
		UUID resourceId = extract(RESOURCE_ID, created);
		UUID v1 = extract(VERSION_ID, created);
		assertTrue(decide(teacher, null, resourceId, v1, "view_content"));
		assertTrue(decide(teacher, null, resourceId, v1, "view_answer"));
		assertTrue(decide(teacher, null, resourceId, v1, "edit_draft"));
		assertTrue(decide(teacher, null, resourceId, v1, "share"));
		MvcResult published = post(teacher, null,
				"/api/access-check/resources/" + resourceId + "/versions/" + v1 + "/publish", "{}");
		assertEquals(200, published.getResponse().getStatus(), body(published));
		assertEquals("VERSION_LOCKED", decideReason(teacher, null, resourceId, v1, "edit_draft"));
		MvcResult v2 = post(teacher, null, "/api/access-check/resources/" + resourceId + "/versions",
				"{\"contentHash\":\"h2\"}");
		assertEquals(200, v2.getResponse().getStatus(), body(v2));
		MvcResult denied = post(plainToken("hv-khong-quyen-soan"), null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"personal\",\"contentHash\":\"h1\"}");
		assertEquals(403, denied.getResponse().getStatus(), body(denied));
	}

	@Test
	@DisplayName("Kho cá nhân cách ly tuyệt đối: giáo viên khác, tenant-admin của một trung tâm và khách của trung tâm đều không quyết được quyền nào trên tài nguyên cá nhân")
	void personalIsolation() throws Exception {
		String owner = authorToken("gv-bi-cach-ly");
		MvcResult created = post(owner, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"personal\",\"contentHash\":\"h1\"}");
		UUID resourceId = extract(RESOURCE_ID, created);
		UUID v1 = extract(VERSION_ID, created);
		String other = authorToken("gv-nguoi-la");
		assertFalse(decide(other, null, resourceId, v1, "view_content"));
		assertEquals(404, get(other, null, "/api/access-check/resources/" + resourceId).getResponse().getStatus());
		String admin = adminToken("admin-cach-ly");
		assertFalse(decide(admin, ALIAS_1, resourceId, v1, "view_content"));
		assertEquals(404, get(admin, ALIAS_1, "/api/access-check/resources/" + resourceId).getResponse().getStatus());
		lenient().when(this.membershipChecker.check(ORG_1_ID, "khach-cach-ly"))
				.thenReturn(MembershipChecker.Result.NOT_MEMBER);
		String guest = memberToken("khach-cach-ly", ALIAS_1, ORG_1_ID, List.of("tenant-member"));
		assertFalse(decide(guest, ALIAS_1, resourceId, v1, "view_content"));
	}

	@Test
	@DisplayName("Trường tự khai trong thân yêu cầu về người gọi, trung tâm và vai bị bỏ qua: quyết định chỉ theo token đã xác minh")
	void forgedBodyFieldsIgnored() throws Exception {
		String owner = authorToken("gv-that");
		MvcResult created = post(owner, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"personal\",\"contentHash\":\"h1\"}");
		UUID resourceId = extract(RESOURCE_ID, created);
		UUID v1 = extract(VERSION_ID, created);
		String forged = "{\"resourceId\":\"" + resourceId + "\",\"resourceVersionId\":\"" + v1
				+ "\",\"action\":\"view_content\",\"sub\":\"gv-that\",\"organizationId\":\"" + ORG_1_ID
				+ "\",\"roles\":[\"tenant-admin\"],\"ownerUserSub\":\"gv-mao\"}";
		MvcResult asStranger = post(authorToken("gv-mao"), null, "/api/access-check/decisions", forged);
		assertEquals(200, asStranger.getResponse().getStatus(), body(asStranger));
		assertTrue(body(asStranger).contains("\"allowed\":false"), body(asStranger));
		MvcResult asOwner = post(owner, null, "/api/access-check/decisions", forged);
		assertTrue(body(asOwner).contains("\"allowed\":true"), body(asOwner));
	}

	@Test
	@DisplayName("Thành viên chưa có phân công trên một bộ sưu tập của trung tâm không xem và không tạo được gì trong đó; có phân công soạn thì tạo và sửa được trong đúng bộ đó")
	void memberWithoutAssignment() throws Exception {
		String admin = adminToken("admin-phan-cong");
		UUID collectionId = createCollection(admin, ALIAS_1);
		String member = memberToken("hv-chua-phan-cong", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		assertFalse(decide(member, ALIAS_1, collectionId, null, "view_content"));
		MvcResult denied = post(member, ALIAS_1, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"center\",\"collectionId\":\"" + collectionId + "\"}");
		assertEquals(403, denied.getResponse().getStatus(), body(denied));
		assign(admin, ALIAS_1, collectionId, "question", "hv-chua-phan-cong", "bank-editor");
		MvcResult created = post(member, ALIAS_1, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"center\",\"collectionId\":\"" + collectionId + "\"}");
		assertEquals(200, created.getResponse().getStatus(), body(created));
		UUID questionId = extract(RESOURCE_ID, created);
		UUID questionV1 = extract(VERSION_ID, created);
		assertTrue(decide(member, ALIAS_1, questionId, questionV1, "edit_draft"));
		assertTrue(decide(member, ALIAS_1, questionId, questionV1, "view_content"));
	}

	@Test
	@DisplayName("created_by chỉ là thông tin nguồn gốc: bản ghi do chính một thành viên tạo ra trong quá khứ không cho thành viên đó bất kỳ quyền nào khi không có nguồn quyền khác")
	void createdByGrantsNothing() throws Exception {
		UUID resourceId = insertResource(ORG_1_ID, "center", "organization", null, ORG_1_ID, null, "owned", null,
				null, null, "hv-tao-boi");
		UUID v1 = insertVersion(resourceId, ORG_1_ID, 1, "hv-tao-boi");
		String member = memberToken("hv-tao-boi", ALIAS_1, ORG_1_ID, List.of("tenant-member"));
		assertFalse(decide(member, ALIAS_1, resourceId, v1, "view_content"));
		assertFalse(decide(member, ALIAS_1, resourceId, v1, "use"));
	}

	@Test
	@DisplayName("Phân công chỉ có hiệu lực trên đúng cặp bộ sưu tập và loại tài nguyên đã gán: soạn được ở kho này thì vẫn bị từ chối ở kho khác của cùng trung tâm")
	void assignmentScopingAcrossCollections() throws Exception {
		String admin = adminToken("admin-pham-vi");
		UUID c1 = createCollection(admin, ALIAS_1);
		UUID c2 = createCollection(admin, ALIAS_1);
		assign(admin, ALIAS_1, c1, "question", "gv-bien-tap", "bank-editor");
		String editor = memberToken("gv-bien-tap", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		MvcResult inC1 = post(editor, ALIAS_1, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"center\",\"collectionId\":\"" + c1 + "\"}");
		assertEquals(200, inC1.getResponse().getStatus(), body(inC1));
		MvcResult inC2 = post(editor, ALIAS_1, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"center\",\"collectionId\":\"" + c2 + "\"}");
		assertEquals(403, inC2.getResponse().getStatus(), body(inC2));
		MvcResult adminResource = post(admin, ALIAS_1, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"center\",\"collectionId\":\"" + c2 + "\"}");
		UUID c2Resource = extract(RESOURCE_ID, adminResource);
		UUID c2V1 = extract(VERSION_ID, adminResource);
		assertFalse(decide(editor, ALIAS_1, c2Resource, c2V1, "view_content"));
	}

	@Test
	@DisplayName("Người duyệt và người xuất bản tách bạch: duyệt không xuất bản được, xuất bản không duyệt được, cả hai đều không có view_answer ở lõi, và phân công bị thu hồi thì quyền mất ngay")
	void reviewerPublisherSeparation() throws Exception {
		String admin = adminToken("admin-duyet");
		UUID collectionId = createCollection(admin, ALIAS_1);
		MvcResult created = post(admin, ALIAS_1, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"center\",\"collectionId\":\"" + collectionId + "\"}");
		UUID resourceId = extract(RESOURCE_ID, created);
		UUID v1 = extract(VERSION_ID, created);
		UUID reviewerAssignment = assign(admin, ALIAS_1, collectionId, "question", "gv-duyet", "resource-reviewer");
		assign(admin, ALIAS_1, collectionId, "question", "gv-xuat-ban", "resource-publisher");
		String reviewer = memberToken("gv-duyet", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		String publisher = memberToken("gv-xuat-ban", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		assertTrue(decide(reviewer, ALIAS_1, resourceId, v1, "review"));
		assertFalse(decide(reviewer, ALIAS_1, resourceId, v1, "publish"));
		assertFalse(decide(reviewer, ALIAS_1, resourceId, v1, "view_answer"));
		assertTrue(decide(publisher, ALIAS_1, resourceId, v1, "publish"));
		assertFalse(decide(publisher, ALIAS_1, resourceId, v1, "review"));
		assertFalse(decide(publisher, ALIAS_1, resourceId, v1, "view_answer"));
		MvcResult revoked = post(admin, ALIAS_1, "/api/access-check/assignments/" + reviewerAssignment + "/revoke",
				"{}");
		assertEquals(200, revoked.getResponse().getStatus(), body(revoked));
		assertFalse(decide(reviewer, ALIAS_1, resourceId, v1, "review"));
	}

	@Test
	@DisplayName("Quyền của tenant-admin dừng ở biên trung tâm của mình: admin của trung tâm khác và platform-admin đều không xem được tài nguyên của trung tâm, và tenant-admin không có view_answer ở lõi")
	void tenantAdminScope() throws Exception {
		String admin = adminToken("admin-a-scope");
		UUID collectionId = createCollection(admin, ALIAS_1);
		MvcResult created = post(admin, ALIAS_1, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"center\",\"collectionId\":\"" + collectionId + "\"}");
		UUID resourceId = extract(RESOURCE_ID, created);
		UUID v1 = extract(VERSION_ID, created);
		String adminB = adminBToken("admin-b-scope");
		assertFalse(decide(adminB, ALIAS_2, resourceId, v1, "view_content"));
		assertEquals(404, get(adminB, ALIAS_2, "/api/access-check/resources/" + resourceId).getResponse().getStatus());
		String platform = platformToken("platform-scope");
		assertFalse(decide(platform, null, resourceId, v1, "view_content"));
		assertFalse(decide(admin, ALIAS_1, resourceId, v1, "view_answer"));
		assertTrue(decide(admin, ALIAS_1, resourceId, null, "manage_collection"));
		assertTrue(decide(admin, ALIAS_1, resourceId, null, "manage_assignment"));
	}

	@Test
	@DisplayName("Grant cho một trung tâm chỉ mở đúng hành động trên đúng phiên bản sau khi trung tâm tiếp nhận vào một bộ sưu tập và thành viên có phân công bao phủ; trước tiếp nhận không ai dùng được")
	void organizationGrantFlow() throws Exception {
		String teacherPersonal = authorToken("gv-cap-grant");
		MvcResult created = post(teacherPersonal, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"personal\",\"contentHash\":\"h1\"}");
		UUID resourceId = extract(RESOURCE_ID, created);
		UUID v1 = extract(VERSION_ID, created);
		MvcResult v2Result = post(teacherPersonal, null, "/api/access-check/resources/" + resourceId + "/versions",
				"{\"contentHash\":\"h2\"}");
		UUID v2 = extract(VERSION_ID, v2Result);
		String admin = adminToken("admin-nhan-grant");
		UUID collectionId = createCollection(admin, ALIAS_1);
		String teacherAtCenter = authorMemberToken("gv-cap-grant", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		MvcResult grant = post(teacherAtCenter, ALIAS_1, "/api/access-check/grants",
				"{\"resourceId\":\"" + resourceId + "\",\"resourceVersionId\":\"" + v1
						+ "\",\"action\":\"view_content\",\"granteeType\":\"organization\",\"mode\":\"long_term\"}");
		assertEquals(200, grant.getResponse().getStatus(), body(grant));
		UUID grantId = extract(GRANT_ID, grant);
		MvcResult useGrant = post(teacherAtCenter, ALIAS_1, "/api/access-check/grants",
				"{\"resourceId\":\"" + resourceId + "\",\"resourceVersionId\":\"" + v1
						+ "\",\"action\":\"use\",\"granteeType\":\"organization\",\"mode\":\"long_term\"}");
		assertEquals(200, useGrant.getResponse().getStatus(), body(useGrant));
		UUID useGrantId = extract(GRANT_ID, useGrant);
		assertEquals("VERSION_LOCKED", decideReason(teacherPersonal, null, resourceId, v1, "edit_draft"));
		String member = memberToken("hv-nhan-grant", ALIAS_1, ORG_1_ID, List.of("tenant-member"));
		assertFalse(decide(member, ALIAS_1, resourceId, v1, "view_content"));
		MvcResult reception = post(admin, ALIAS_1, "/api/access-check/grants/" + grantId + "/reception",
				"{\"receivingCollectionId\":\"" + collectionId + "\"}");
		assertEquals(200, reception.getResponse().getStatus(), body(reception));
		receive(admin, ALIAS_1, useGrantId, collectionId);
		assertFalse(decide(member, ALIAS_1, resourceId, v1, "view_content"));
		assign(admin, ALIAS_1, collectionId, "question", "hv-nhan-grant", "bank-manager");
		assertTrue(decide(member, ALIAS_1, resourceId, v1, "view_content"));
		assertTrue(decide(member, ALIAS_1, resourceId, v1, "use"));
		assertFalse(decide(member, ALIAS_1, resourceId, v2, "view_content"));
		assertFalse(decide(member, ALIAS_1, resourceId, v1, "copy"));
		assertFalse(decide(member, ALIAS_1, resourceId, v1, "share"));
		assertFalse(decide(member, ALIAS_1, resourceId, v1, "edit_draft"));
		MvcResult view = get(member, ALIAS_1, "/api/access-check/resources/" + resourceId);
		assertEquals(200, view.getResponse().getStatus(), body(view));
		assertTrue(body(view).contains(v1.toString()), body(view));
		assertFalse(body(view).contains(v2.toString()), body(view));
		String memberB = memberToken("hv-trung-tam-b", ALIAS_2, ORG_2_ID, List.of("tenant-member"));
		assertFalse(decide(memberB, ALIAS_2, resourceId, v1, "view_content"));
		String owner = this.jdbcTemplate.queryForObject("select owner_user_sub from resource where id = ?",
				String.class, resourceId);
		assertEquals("gv-cap-grant", owner);
		String partition = this.jdbcTemplate.queryForObject("select partition_key from resource where id = ?",
				String.class, resourceId);
		assertEquals("user:gv-cap-grant", partition);
	}

	@Test
	@DisplayName("Grant tạm thời bị phía cấp thu hồi được và hết hạn thì mất hiệu lực; grant dài hạn cho trung tâm không bị phía cấp thu hồi đơn phương; thu hồi không mở khoá phiên bản nguồn")
	void temporaryAndLongTermGrants() throws Exception {
		String teacherPersonal = authorToken("gv-thoi-han");
		MvcResult created = post(teacherPersonal, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"personal\",\"contentHash\":\"h1\"}");
		UUID resourceId = extract(RESOURCE_ID, created);
		UUID v1 = extract(VERSION_ID, created);
		String admin = adminToken("admin-thoi-han");
		UUID collectionId = createCollection(admin, ALIAS_1);
		assign(admin, ALIAS_1, collectionId, "question", "hv-thoi-han", "bank-manager");
		String member = memberToken("hv-thoi-han", ALIAS_1, ORG_1_ID, List.of("tenant-member"));
		String teacherAtCenter = authorMemberToken("gv-thoi-han", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		UUID temporary = grantToOrganization(teacherAtCenter, resourceId, v1,
				"\"mode\":\"temporary\",\"expiresAt\":\"2999-01-01T00:00:00Z\"");
		receive(admin, ALIAS_1, temporary, collectionId);
		assertTrue(decide(member, ALIAS_1, resourceId, v1, "view_content"));
		MvcResult revoke = post(teacherAtCenter, ALIAS_1, "/api/access-check/grants/" + temporary + "/revoke",
				"{\"granteeOrganizationId\":\"" + ORG_1_ID + "\"}");
		assertEquals(200, revoke.getResponse().getStatus(), body(revoke));
		assertFalse(decide(member, ALIAS_1, resourceId, v1, "view_content"));
		assertEquals("VERSION_LOCKED", decideReason(teacherPersonal, null, resourceId, v1, "edit_draft"));
		UUID longTerm = grantToOrganization(teacherAtCenter, resourceId, v1, "\"mode\":\"long_term\"");
		receive(admin, ALIAS_1, longTerm, collectionId);
		assertTrue(decide(member, ALIAS_1, resourceId, v1, "view_content"));
		MvcResult revokeLongTerm = post(teacherAtCenter, ALIAS_1,
				"/api/access-check/grants/" + longTerm + "/revoke", "{\"granteeOrganizationId\":\"" + ORG_1_ID + "\"}");
		assertEquals(403, revokeLongTerm.getResponse().getStatus(), body(revokeLongTerm));
		MvcResult second = post(teacherPersonal, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"personal\",\"contentHash\":\"h9\"}");
		UUID resource2 = extract(RESOURCE_ID, second);
		UUID resource2V1 = extract(VERSION_ID, second);
		UUID expired = grantToOrganization(teacherAtCenter, resource2, resource2V1,
				"\"mode\":\"temporary\",\"startsAt\":\"2020-01-01T00:00:00Z\",\"expiresAt\":\"2020-01-02T00:00:00Z\"");
		receive(admin, ALIAS_1, expired, collectionId);
		assertFalse(decide(member, ALIAS_1, resource2, resource2V1, "view_content"));
	}

	@Test
	@DisplayName("Khoá phiên bản là bất biến theo dữ kiện đã ghi: xuất bản thì khoá, grant thì khoá, và không thao tác thu hồi nào mở lại được bản đã khoá")
	void versionLocking() throws Exception {
		String teacher = authorToken("gv-khoa-ban");
		MvcResult created = post(teacher, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"personal\",\"contentHash\":\"h1\"}");
		UUID resourceId = extract(RESOURCE_ID, created);
		UUID v1 = extract(VERSION_ID, created);
		MvcResult v2Result = post(teacher, null, "/api/access-check/resources/" + resourceId + "/versions",
				"{\"contentHash\":\"h2\"}");
		UUID v2 = extract(VERSION_ID, v2Result);
		assertTrue(decide(teacher, null, resourceId, v2, "edit_draft"));
		MvcResult grant = post(teacher, null, "/api/access-check/grants",
				"{\"resourceId\":\"" + resourceId + "\",\"resourceVersionId\":\"" + v2
						+ "\",\"action\":\"use\",\"granteeType\":\"user\",\"granteeUserSub\":\"hv-dung-ban\"}");
		assertEquals(200, grant.getResponse().getStatus(), body(grant));
		assertEquals("VERSION_LOCKED", decideReason(teacher, null, resourceId, v2, "edit_draft"));
		assertTrue(decide(plainToken("hv-dung-ban"), null, resourceId, v2, "use"));
		assertFalse(decide(plainToken("hv-dung-ban"), null, resourceId, v1, "use"));
		MvcResult published = post(teacher, null,
				"/api/access-check/resources/" + resourceId + "/versions/" + v1 + "/publish", "{}");
		assertEquals(200, published.getResponse().getStatus(), body(published));
		assertEquals("VERSION_LOCKED", decideReason(teacher, null, resourceId, v1, "edit_draft"));
	}

	@Test
	@DisplayName("Giới hạn sử dụng của nguồn ràng buộc cả chủ sở hữu và người nhận: ngoài danh sách Organization thì không chia sẻ tới và không dùng được, và quyền có được chỉ nhờ grant không tự sinh quyền cấp lại")
	void inheritedLimitsAndRegrant() throws Exception {
		UUID limited = insertResource("user:gv-gioi-han", "personal", "user", "gv-gioi-han", null, null, "owned",
				null, "org:" + ORG_1_ID, null, "gv-gioi-han");
		UUID limitedV1 = insertVersion(limited, "user:gv-gioi-han", 1, "gv-gioi-han");
		String teacherAtB = authorMemberToken("gv-gioi-han", ALIAS_2, ORG_2_ID, List.of("tenant-teacher"));
		MvcResult toB = post(teacherAtB, ALIAS_2, "/api/access-check/grants",
				"{\"resourceId\":\"" + limited + "\",\"resourceVersionId\":\"" + limitedV1
						+ "\",\"action\":\"view_content\",\"granteeType\":\"organization\",\"mode\":\"long_term\"}");
		assertEquals(403, toB.getResponse().getStatus(), body(toB));
		String teacherAtA = authorMemberToken("gv-gioi-han", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		MvcResult toA = post(teacherAtA, ALIAS_1, "/api/access-check/grants",
				"{\"resourceId\":\"" + limited + "\",\"resourceVersionId\":\"" + limitedV1
						+ "\",\"action\":\"view_content\",\"granteeType\":\"organization\",\"mode\":\"long_term\"}");
		assertEquals(200, toA.getResponse().getStatus(), body(toA));
		String adminB = adminBToken("admin-b-gioi-han");
		UUID collectionB = createCollection(adminB, ALIAS_2);
		assign(adminB, ALIAS_2, collectionB, "question", "hv-b-gioi-han", "bank-manager");
		UUID copyB = insertResource(ORG_2_ID, "center", "organization", null, ORG_2_ID, collectionB, "owned", null,
				"org:" + ORG_1_ID, null, "admin-b-gioi-han");
		UUID copyBV1 = insertVersion(copyB, ORG_2_ID, 1, "admin-b-gioi-han");
		String memberB = memberToken("hv-b-gioi-han", ALIAS_2, ORG_2_ID, List.of("tenant-member"));
		// Grant use cho chính thành viên là nguồn hợp lệ để quyết định đi tới bước thu hẹp theo giới hạn của nguồn;
		// phân công một mình không cấp use theo bảng ở mục 7 của đặc tả.
		this.jdbcTemplate.update(
				"insert into resource_grant (id, resource_id, resource_version_id, grantee_type, grantee_user_sub, action, starts_at, grantor_sub, source_partition_key, partition_key) values (?, ?, ?, ?, ?, ?, current_timestamp, ?, ?, ?)",
				UUID.randomUUID(), copyB, copyBV1, "user", "hv-b-gioi-han", "use", "admin-b-gioi-han", ORG_2_ID,
				"user:hv-b-gioi-han");
		assertEquals("USAGE_LIMIT_EXCEEDED", decideReason(memberB, ALIAS_2, copyB, copyBV1, "use"));
		String teacher = authorToken("gv-gioi-han");
		UUID plain = insertResource("user:gv-gioi-han", "personal", "user", "gv-gioi-han", null, null, "owned", null,
				null, null, "gv-gioi-han");
		UUID plainV1 = insertVersion(plain, "user:gv-gioi-han", 1, "gv-gioi-han");
		grantShareToUser(teacher, plain, plainV1, "gv-cap-lai");
		String regranter = authorToken("gv-cap-lai");
		assertTrue(decide(regranter, null, plain, plainV1, "share"));
		MvcResult regrant = post(regranter, null, "/api/access-check/grants",
				"{\"resourceId\":\"" + plain + "\",\"resourceVersionId\":\"" + plainV1
						+ "\",\"action\":\"view_content\",\"granteeType\":\"user\",\"granteeUserSub\":\"hv-nguoi-ba\"}");
		assertEquals(403, regrant.getResponse().getStatus(), body(regrant));
		UUID reshareable = insertResource("user:gv-gioi-han", "personal", "user", "gv-gioi-han", null, null, "owned",
				null, "reshare:view_content", null, "gv-gioi-han");
		UUID reshareableV1 = insertVersion(reshareable, "user:gv-gioi-han", 1, "gv-gioi-han");
		grantShareToUser(teacher, reshareable, reshareableV1, "gv-cap-lai");
		MvcResult allowed = post(regranter, null, "/api/access-check/grants",
				"{\"resourceId\":\"" + reshareable + "\",\"resourceVersionId\":\"" + reshareableV1
						+ "\",\"action\":\"view_content\",\"granteeType\":\"user\",\"granteeUserSub\":\"hv-nguoi-ba\"}");
		assertEquals(200, allowed.getResponse().getStatus(), body(allowed));
		assertTrue(decide(plainToken("hv-nguoi-ba"), null, reshareable, reshareableV1, "view_content"));
	}

	@Test
	@DisplayName("Token cũ sau khi tư cách thành viên bị gỡ không còn mở được tài nguyên của trung tâm, nhưng kho cá nhân của chính người đó vẫn nguyên vẹn")
	void membershipRemovalOldToken() throws Exception {
		String admin = adminToken("admin-go-tu-cach");
		UUID collectionId = createCollection(admin, ALIAS_1);
		MvcResult created = post(admin, ALIAS_1, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"center\",\"collectionId\":\"" + collectionId + "\"}");
		UUID resourceId = extract(RESOURCE_ID, created);
		UUID v1 = extract(VERSION_ID, created);
		assign(admin, ALIAS_1, collectionId, "question", "hv-bi-go", "bank-manager");
		String member = memberToken("hv-bi-go", ALIAS_1, ORG_1_ID, List.of("tenant-member"));
		assertTrue(decide(member, ALIAS_1, resourceId, v1, "view_content"));
		String personal = authorToken("hv-bi-go");
		MvcResult ownResource = post(personal, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"personal\",\"contentHash\":\"h1\"}");
		UUID ownId = extract(RESOURCE_ID, ownResource);
		UUID ownV1 = extract(VERSION_ID, ownResource);
		lenient().when(this.membershipChecker.check(ORG_1_ID, "hv-bi-go"))
				.thenReturn(MembershipChecker.Result.NOT_MEMBER);
		assertFalse(decide(member, ALIAS_1, resourceId, v1, "view_content"));
		assertTrue(decide(personal, null, ownId, ownV1, "view_content"));
	}

	@Test
	@DisplayName("Bản đang chờ duyệt chặn use với mọi người và chỉ mở view_content cho nguồn duyệt; gỡ điều kiện nghiệp vụ thì quyền theo phân công trở lại đầy đủ")
	void pendingReviewNarrowing() throws Exception {
		String admin = adminToken("admin-cho-duyet");
		UUID collectionId = createCollection(admin, ALIAS_1);
		UUID copy = insertResource(ORG_1_ID, "center", "organization", null, ORG_1_ID, collectionId, "owned", null,
				null, "PENDING_REVIEW", "gv-nop-ban");
		UUID copyV1 = insertVersion(copy, ORG_1_ID, 1, "gv-nop-ban");
		assign(admin, ALIAS_1, collectionId, "question", "hv-sua-ban", "bank-editor");
		assign(admin, ALIAS_1, collectionId, "question", "hv-quan-ly-ban", "bank-manager");
		MvcResult useGrant = post(admin, ALIAS_1, "/api/access-check/grants",
				"{\"resourceId\":\"" + copy + "\",\"resourceVersionId\":\"" + copyV1
						+ "\",\"action\":\"use\",\"granteeType\":\"user\",\"granteeUserSub\":\"hv-sua-ban\"}");
		assertEquals(200, useGrant.getResponse().getStatus(), body(useGrant));
		String editor = memberToken("hv-sua-ban", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		String manager = memberToken("hv-quan-ly-ban", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		assertFalse(decide(editor, ALIAS_1, copy, copyV1, "view_content"));
		assertFalse(decide(editor, ALIAS_1, copy, copyV1, "use"));
		assertTrue(decide(manager, ALIAS_1, copy, copyV1, "view_content"));
		assertFalse(decide(manager, ALIAS_1, copy, copyV1, "use"));
		this.jdbcTemplate.update("update resource set business_condition = null where id = ?", copy);
		assertTrue(decide(editor, ALIAS_1, copy, copyV1, "view_content"));
		assertTrue(decide(editor, ALIAS_1, copy, copyV1, "use"));
	}

	@Test
	@DisplayName("Bàn giao chính thức: yêu cầu nộp không cấp quyền đọc cho trung tâm, chỉ giáo viên được nêu tên mới nộp được, một mục không đạt huỷ toàn bộ lần nộp, và bản đã nhận phải qua duyệt mới dùng được")
	void handoverFlow() throws Exception {
		String admin = adminToken("admin-ban-giao");
		UUID destination = createCollection(admin, ALIAS_1);
		String teacher = authorMemberToken("gv-nop-bai", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		MvcResult own = post(teacher, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"personal\",\"contentHash\":\"h1\"}");
		UUID resourceId = extract(RESOURCE_ID, own);
		UUID v1 = extract(VERSION_ID, own);
		MvcResult request = post(admin, ALIAS_1, "/api/access-check/handover-requests",
				"{\"requestedTeacherSub\":\"gv-nop-bai\",\"resourceTypes\":\"question\",\"destinationCollectionId\":\""
						+ destination + "\"}");
		assertEquals(200, request.getResponse().getStatus(), body(request));
		UUID requestId = extract(REQUEST_ID, request);
		assertFalse(decide(admin, ALIAS_1, resourceId, v1, "view_content"));
		String stranger = authorMemberToken("gv-nguoi-ngoai", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		MvcResult byStranger = submit(stranger, requestId, resourceId, v1);
		assertEquals(403, byStranger.getResponse().getStatus(), body(byStranger));
		MvcResult submitted = submit(teacher, requestId, resourceId, v1);
		assertEquals(200, submitted.getResponse().getStatus(), body(submitted));
		UUID submissionId = extract(SUBMISSION_ID, submitted);
		UUID receivedId = extract(RECEIVED_RESOURCE_ID, submitted);
		assign(admin, ALIAS_1, destination, "question", "hv-dung-ban-nhan", "bank-manager");
		String manager = memberToken("hv-dung-ban-nhan", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		UUID receivedV1 = this.jdbcTemplate.queryForObject(
				"select received_version_id from submission_item where submission_id = ?", UUID.class, submissionId);
		MvcResult useGrant = post(admin, ALIAS_1, "/api/access-check/grants",
				"{\"resourceId\":\"" + receivedId + "\",\"resourceVersionId\":\"" + receivedV1
						+ "\",\"action\":\"use\",\"granteeType\":\"user\",\"granteeUserSub\":\"hv-dung-ban-nhan\"}");
		assertEquals(200, useGrant.getResponse().getStatus(), body(useGrant));
		assertFalse(decide(manager, ALIAS_1, receivedId, receivedV1, "use"));
		MvcResult duplicate = submit(teacher, requestId, resourceId, v1);
		assertEquals(submissionId, extract(SUBMISSION_ID, duplicate));
		MvcResult reviewed = post(admin, ALIAS_1, "/api/access-check/submissions/" + submissionId + "/review",
				"{\"decision\":\"accept\"}");
		assertEquals(200, reviewed.getResponse().getStatus(), body(reviewed));
		assertTrue(decide(manager, ALIAS_1, receivedId, receivedV1, "use"));
		assertTrue(decide(teacher, null, resourceId, v1, "view_content"));
		String owner = this.jdbcTemplate.queryForObject("select owner_user_sub from resource where id = ?",
				String.class, resourceId);
		assertEquals("gv-nop-bai", owner);
		MvcResult second = post(teacher, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"personal\",\"contentHash\":\"h2\"}");
		UUID secondId = extract(RESOURCE_ID, second);
		UUID secondV1 = extract(VERSION_ID, second);
		String otherTeacher = authorToken("gv-so-huu-khac");
		MvcResult foreign = post(otherTeacher, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"personal\",\"contentHash\":\"h3\"}");
		UUID foreignId = extract(RESOURCE_ID, foreign);
		UUID foreignV1 = extract(VERSION_ID, foreign);
		MvcResult mixed = post(teacher, null, "/api/access-check/handover-requests/" + requestId + "/submissions",
				"{\"items\":[{\"sourceResourceId\":\"" + secondId + "\",\"sourceVersionId\":\"" + secondV1
						+ "\"},{\"sourceResourceId\":\"" + foreignId + "\",\"sourceVersionId\":\"" + foreignV1
						+ "\"}]}");
		assertEquals(404, mixed.getResponse().getStatus(), body(mixed));
		Integer submissions = this.jdbcTemplate.queryForObject(
				"select count(*) from submission where handover_request_id = ?", Integer.class, requestId);
		assertEquals(1, submissions);
	}

	@Test
	@DisplayName("Không gian hệ thống chỉ thuộc platform-admin: tạo, xuất bản và xem đều được, còn giáo viên và thành viên trung tâm bị từ chối")
	void systemWorkspace() throws Exception {
		String platform = platformToken("platform-he-thong");
		MvcResult created = post(platform, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"system\",\"contentHash\":\"h1\"}");
		assertEquals(200, created.getResponse().getStatus(), body(created));
		UUID resourceId = extract(RESOURCE_ID, created);
		UUID v1 = extract(VERSION_ID, created);
		assertTrue(decide(platform, null, resourceId, v1, "view_content"));
		MvcResult published = post(platform, null,
				"/api/access-check/resources/" + resourceId + "/versions/" + v1 + "/publish", "{}");
		assertEquals(200, published.getResponse().getStatus(), body(published));
		assertFalse(decide(authorToken("gv-he-thong"), null, resourceId, v1, "view_content"));
		String member = memberToken("hv-he-thong", ALIAS_1, ORG_1_ID, List.of("tenant-member"));
		assertFalse(decide(member, ALIAS_1, resourceId, v1, "view_content"));
	}

	@Test
	@DisplayName("Khách tại trang trung tâm không có quyền nào của trung tâm đó nhưng vẫn mở được ngữ cảnh của mình và dùng kho cá nhân bình thường")
	void guestAtCenterPage() throws Exception {
		String admin = adminToken("admin-khach");
		UUID collectionId = createCollection(admin, ALIAS_1);
		MvcResult created = post(admin, ALIAS_1, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"center\",\"collectionId\":\"" + collectionId + "\"}");
		UUID resourceId = extract(RESOURCE_ID, created);
		UUID v1 = extract(VERSION_ID, created);
		lenient().when(this.membershipChecker.check(ORG_1_ID, "khach-trang"))
				.thenReturn(MembershipChecker.Result.NOT_MEMBER);
		String guest = authorMemberToken("khach-trang", ALIAS_1, ORG_1_ID, List.of("tenant-member"));
		MvcResult contextView = get(guest, ALIAS_1, "/api/tenancy-check/context");
		assertEquals(200, contextView.getResponse().getStatus(), body(contextView));
		assertTrue(body(contextView).contains("\"guest\":true"), body(contextView));
		assertFalse(decide(guest, ALIAS_1, resourceId, v1, "view_content"));
		MvcResult own = post(guest, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"personal\",\"contentHash\":\"h1\"}");
		assertEquals(200, own.getResponse().getStatus(), body(own));
		UUID ownId = extract(RESOURCE_ID, own);
		UUID ownV1 = extract(VERSION_ID, own);
		assertTrue(decide(guest, null, ownId, ownV1, "view_content"));
	}

	@Test
	@DisplayName("Ràng buộc ở mức database: phiên bản không gắn được sang phân vùng khác, grant không trỏ được sang phiên bản của tài nguyên khác, và bản licensed không tồn tại được nếu thiếu grant cấp phép")
	void schemaConstraints() {
		UUID resourceA = insertResource(ORG_1_ID, "center", "organization", null, ORG_1_ID, null, "owned", null, null,
				null, "admin-schema");
		assertThrows(DataIntegrityViolationException.class,
				() -> this.jdbcTemplate.update(
						"insert into resource_version (id, resource_id, partition_key, version_number, status, created_by_sub) values (?, ?, ?, ?, ?, ?)",
						UUID.randomUUID(), resourceA, ORG_2_ID, 1, "draft", "admin-schema"));
		UUID first = insertResource(ORG_1_ID, "center", "organization", null, ORG_1_ID, null, "owned", null, null,
				null, "admin-schema");
		UUID second = insertResource(ORG_1_ID, "center", "organization", null, ORG_1_ID, null, "owned", null, null,
				null, "admin-schema");
		UUID secondV1 = insertVersion(second, ORG_1_ID, 1, "admin-schema");
		assertThrows(DataIntegrityViolationException.class,
				() -> this.jdbcTemplate.update(
						"insert into resource_grant (id, resource_id, resource_version_id, grantee_type, grantee_user_sub, action, starts_at, grantor_sub, source_partition_key, partition_key) values (?, ?, ?, ?, ?, ?, current_timestamp, ?, ?, ?)",
						UUID.randomUUID(), first, secondV1, "user", "hv-schema", "use", "admin-schema", "user:gv-schema",
						"user:hv-schema"));
		assertThrows(DataIntegrityViolationException.class, () -> insertResource(ORG_1_ID, "center", "organization",
				null, ORG_1_ID, null, "licensed", null, null, null, "admin-schema"));
	}

	private UUID createCollection(String admin, String alias) throws Exception {
		MvcResult result = post(admin, alias, "/api/access-check/resources",
				"{\"resourceType\":\"question-bank\",\"workspace\":\"center\"}");
		assertEquals(200, result.getResponse().getStatus(), body(result));
		assertTrue(body(result).contains("\"versions\":[]"), body(result));
		return extract(RESOURCE_ID, result);
	}

	private UUID assign(String admin, String alias, UUID collectionId, String resourceType, String assignee,
			String role) throws Exception {
		MvcResult result = post(admin, alias, "/api/access-check/assignments",
				"{\"collectionId\":\"" + collectionId + "\",\"resourceType\":\"" + resourceType
						+ "\",\"assigneeSub\":\"" + assignee + "\",\"assignmentRole\":\"" + role + "\"}");
		assertEquals(200, result.getResponse().getStatus(), body(result));
		return extract(ASSIGNMENT_ID, result);
	}

	private UUID grantToOrganization(String grantorAtCenter, UUID resourceId, UUID versionId, String modeJson)
			throws Exception {
		MvcResult result = post(grantorAtCenter, ALIAS_1, "/api/access-check/grants",
				"{\"resourceId\":\"" + resourceId + "\",\"resourceVersionId\":\"" + versionId
						+ "\",\"action\":\"view_content\",\"granteeType\":\"organization\"," + modeJson + "}");
		assertEquals(200, result.getResponse().getStatus(), body(result));
		return extract(GRANT_ID, result);
	}

	private void receive(String admin, String alias, UUID grantId, UUID collectionId) throws Exception {
		MvcResult result = post(admin, alias, "/api/access-check/grants/" + grantId + "/reception",
				"{\"receivingCollectionId\":\"" + collectionId + "\"}");
		assertEquals(200, result.getResponse().getStatus(), body(result));
	}

	private void grantShareToUser(String teacher, UUID resourceId, UUID versionId, String grantee) throws Exception {
		MvcResult result = post(teacher, null, "/api/access-check/grants",
				"{\"resourceId\":\"" + resourceId + "\",\"resourceVersionId\":\"" + versionId
						+ "\",\"action\":\"share\",\"granteeType\":\"user\",\"granteeUserSub\":\"" + grantee + "\"}");
		assertEquals(200, result.getResponse().getStatus(), body(result));
	}

	private MvcResult submit(String teacher, UUID requestId, UUID resourceId, UUID versionId) throws Exception {
		return post(teacher, null, "/api/access-check/handover-requests/" + requestId + "/submissions",
				"{\"items\":[{\"sourceResourceId\":\"" + resourceId + "\",\"sourceVersionId\":\"" + versionId
						+ "\"}]}");
	}

	private boolean decide(String token, String alias, UUID resourceId, UUID versionId, String action)
			throws Exception {
		MvcResult result = decideResult(token, alias, resourceId, versionId, action);
		return body(result).contains("\"allowed\":true");
	}

	private String decideReason(String token, String alias, UUID resourceId, UUID versionId, String action)
			throws Exception {
		MvcResult result = decideResult(token, alias, resourceId, versionId, action);
		Matcher matcher = REASON_CODE.matcher(body(result));
		if (!matcher.find()) {
			throw new AssertionError("response has no reasonCode: " + body(result));
		}
		return matcher.group(1);
	}

	private MvcResult decideResult(String token, String alias, UUID resourceId, UUID versionId, String action)
			throws Exception {
		String json = "{\"resourceId\":\"" + resourceId + "\""
				+ ((versionId != null) ? ",\"resourceVersionId\":\"" + versionId + "\"" : "")
				+ ",\"action\":\"" + action + "\"}";
		MvcResult result = post(token, alias, "/api/access-check/decisions", json);
		assertEquals(200, result.getResponse().getStatus(), body(result));
		return result;
	}

	private UUID insertResource(String partitionKey, String workspace, String ownerType, String ownerUserSub,
			String ownerOrganizationId, UUID collectionId, String custody, UUID licenseGrantId, String limits,
			String businessCondition, String createdBy) {
		UUID id = UUID.randomUUID();
		this.jdbcTemplate.update(
				"insert into resource (id, resource_type, workspace_kind, owner_type, owner_user_sub, owner_organization_id, collection_id, partition_key, custody_kind, license_grant_id, inherited_usage_limits, business_condition, created_by_sub) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				id, "question", workspace, ownerType, ownerUserSub, ownerOrganizationId, collectionId, partitionKey,
				custody, licenseGrantId, limits, businessCondition, createdBy);
		return id;
	}

	private UUID insertVersion(UUID resourceId, String partitionKey, int number, String createdBy) {
		UUID id = UUID.randomUUID();
		this.jdbcTemplate.update(
				"insert into resource_version (id, resource_id, partition_key, version_number, status, content_hash, created_by_sub) values (?, ?, ?, ?, ?, ?, ?)",
				id, resourceId, partitionKey, number, "draft", "hash", createdBy);
		return id;
	}

	private MvcResult post(String token, String alias, String path, String json) throws Exception {
		MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post(path)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(json);
		if (alias != null) {
			builder.header("X-LMS-Organization-Alias", alias);
		}
		return this.mockMvc.perform(builder).andReturn();
	}

	private MvcResult get(String token, String alias, String path) throws Exception {
		MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.get(path)
				.header("Authorization", "Bearer " + token);
		if (alias != null) {
			builder.header("X-LMS-Organization-Alias", alias);
		}
		return this.mockMvc.perform(builder).andReturn();
	}

	private String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString();
	}

	private UUID extract(Pattern pattern, MvcResult result) throws Exception {
		Matcher matcher = pattern.matcher(body(result));
		if (!matcher.find()) {
			throw new AssertionError("response missing field: " + body(result));
		}
		return UUID.fromString(matcher.group(1));
	}

	private String authorToken(String sub) throws Exception {
		return mint(sub, Map.of("realm_access", Map.of("roles", List.of("personal-author"))));
	}

	private String plainToken(String sub) throws Exception {
		return mint(sub, Map.of());
	}

	private String platformToken(String sub) throws Exception {
		return mint(sub, Map.of("realm_access", Map.of("roles", List.of("platform-admin"))));
	}

	private String memberToken(String sub, String alias, String orgId, List<String> orgRoles) throws Exception {
		return mint(sub, Map.of("organization", Map.of(alias, orgEntry(orgId, orgRoles))));
	}

	private String authorMemberToken(String sub, String alias, String orgId, List<String> orgRoles) throws Exception {
		return mint(sub, Map.of("realm_access", Map.of("roles", List.of("personal-author")), "organization",
				Map.of(alias, orgEntry(orgId, orgRoles))));
	}

	private String adminToken(String sub) throws Exception {
		return memberToken(sub, ALIAS_1, ORG_1_ID, List.of("tenant-admin"));
	}

	private String adminBToken(String sub) throws Exception {
		return memberToken(sub, ALIAS_2, ORG_2_ID, List.of("tenant-admin"));
	}

	private Map<String, Object> orgEntry(String id, List<String> roles) {
		return Map.of("id", id, "realm_access", Map.of("roles", roles));
	}

	private String mint(String subject, Map<String, Object> extraClaims) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject)
				.issueTime(Date.from(now.minusSeconds(10))).expirationTime(Date.from(now.plusSeconds(300)));
		extraClaims.forEach(builder::claim);
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
		jwt.sign(new RSASSASigner((RSAPrivateKey) TestJwtConfiguration.KEY_PAIR.getPrivate()));
		return jwt.serialize();
	}

}
