package com.giaokhoa.server.organization;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.giaokhoa.server.tenancy.MembershipChecker;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

// Kiểm thử khối tổ chức dạy học trên cùng khuôn với TenancyEndpointTests: chuỗi lọc bảo mật thật của khung
// tenancy, tầng dữ kiện thật (Flyway chỉ phần migration chung + Hibernate/JPA với discriminator) trên H2 ở chế
// độ PostgreSQL, token JWT ký thật bằng khoá RSA tĩnh. Hai biên đọc Keycloak được thay bằng bản giả lập có kiểm
// soát: MembershipChecker của khung tenancy (mặc định xác nhận còn membership) và OrganizationDirectory của khối
// này (alias suy ra đúng hai trung tâm thử, vai trò giáo viên chỉ đúng với các giáo viên đã định danh). Lớp RLS
// của PostgreSQL không chạy trên H2 nên được kiểm chứng riêng trên PostgreSQL thật.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + OrganizationEndpointTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:organization;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration" })
class OrganizationEndpointTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	private static final String ORG_2_ID = "22222222-2222-2222-2222-222222222222";

	private static final String ALIAS_1 = "trung-tam-1";

	private static final String ALIAS_2 = "trung-tam-2";

	private static final Pattern ID_PATTERN = Pattern.compile("\"id\":\"([^\"]+)\"");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TeachingAccessChecks accessChecks;

	@Autowired
	private TeachingClassRepository classes;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private MembershipChecker membershipChecker;

	@MockitoBean
	private OrganizationDirectory directory;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		this.jdbcTemplate.update("delete from class_member");
		this.jdbcTemplate.update("delete from teaching_class");
		this.jdbcTemplate.update("delete from course");
		lenient().when(this.membershipChecker.check(anyString(), anyString()))
			.thenReturn(MembershipChecker.Result.MEMBER);
		lenient().when(this.directory.findOrganizationIdByAlias(ALIAS_1))
			.thenReturn(Optional.of(UUID.fromString(ORG_1_ID)));
		lenient().when(this.directory.findOrganizationIdByAlias(ALIAS_2))
			.thenReturn(Optional.of(UUID.fromString(ORG_2_ID)));
		lenient().when(this.directory.checkTeacherRole(any(UUID.class), anyString()))
			.thenReturn(OrganizationDirectory.TeacherRoleResult.NOT_TEACHER);
		lenient().when(this.directory.checkTeacherRole(UUID.fromString(ORG_1_ID), "gv-trung-tam-1"))
			.thenReturn(OrganizationDirectory.TeacherRoleResult.TEACHER);
		lenient().when(this.directory.checkTeacherRole(UUID.fromString(ORG_1_ID), "gv2-trung-tam-1"))
			.thenReturn(OrganizationDirectory.TeacherRoleResult.TEACHER);
		lenient().when(this.directory.checkTeacherRole(UUID.fromString(ORG_2_ID), "gv-trung-tam-2"))
			.thenReturn(OrganizationDirectory.TeacherRoleResult.TEACHER);
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
	@DisplayName("Tiêu chí 1: quản trị viên tạo khoá học, lớp thuộc khoá và lớp độc lập, cả ba hàng mang khoá phân vùng của trung tâm trong database; giáo viên tạo lớp bị 403; cùng một người ở ngữ cảnh trung tâm còn lại không thấy và không tác động được lớp của trung tâm này, lớp tạo ở ngữ cảnh còn lại mang khoá của trung tâm còn lại")
	void criterion1CreateAndPartition() throws Exception {
		String adminA = adminAToken();
		String courseId = createCourseOk(adminA, ALIAS_1, "Khoa C1");
		String classInCourse = createClassOk(adminA, ALIAS_1, "Lop C1 Trong Khoa", courseId);
		MvcResult standalone = this.mockMvc
			.perform(post("/api/classes").header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Lop C1 Doc Lap\"}"))
			.andExpect(status().isCreated())
			.andExpect(content().string(containsString("\"courseId\":null")))
			.andExpect(content().string(containsString("\"teacherSub\":null")))
			.andReturn();
		String standaloneId = extractId(standalone);
		assertEquals(ORG_1_ID, partitionOf("course", courseId));
		assertEquals(ORG_1_ID, partitionOf("teaching_class", classInCourse));
		assertEquals(ORG_1_ID, partitionOf("teaching_class", standaloneId));
		this.mockMvc
			.perform(post("/api/classes").header("Authorization", "Bearer " + teacherA1Token())
				.header("X-LMS-Organization-Alias", ALIAS_1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Lop C1 Cua Giao Vien\"}"))
			.andExpect(status().isForbidden())
			.andExpect(content().string(containsString("\"code\":\"RESOURCE_FORBIDDEN\"")));
		this.mockMvc
			.perform(get("/api/classes").header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_2))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("Lop C1 Trong Khoa"))))
			.andExpect(content().string(not(containsString("Lop C1 Doc Lap"))));
		this.mockMvc
			.perform(get("/api/classes/" + classInCourse).header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_2))
			.andExpect(status().isForbidden());
		String classB = createClassOk(adminBToken(), ALIAS_2, "Lop C1 Cua B", null);
		assertEquals(ORG_2_ID, partitionOf("teaching_class", classB));
		this.mockMvc
			.perform(get("/api/classes").header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Lop C1 Trong Khoa")))
			.andExpect(content().string(not(containsString("Lop C1 Cua B"))));
	}

	@Test
	@DisplayName("Tiêu chí 2: đặt giáo viên chỉ thành công khi người được đặt vừa là thành viên hiện hành của trung tâm vừa giữ vai trò giáo viên tại chính trung tâm đó; thiếu một trong hai điều kiện bị 422, giáo viên tự gọi endpoint đặt giáo viên bị 403")
	void criterion2AssignTeacherRules() throws Exception {
		String adminA = adminAToken();
		String classId = createClassOk(adminA, ALIAS_1, "Lop C2", null);
		this.mockMvc
			.perform(put("/api/classes/" + classId + "/teacher").header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"teacherSub\":\"gv-trung-tam-1\"}"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("\"teacherSub\":\"gv-trung-tam-1\"")));
		this.mockMvc
			.perform(put("/api/classes/" + classId + "/teacher").header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"teacherSub\":\"hs-trung-tam-1\"}"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(content().string(containsString("\"code\":\"VALIDATION_FAILED\"")));
		when(this.membershipChecker.check(ORG_1_ID, "gv-trung-tam-2"))
			.thenReturn(MembershipChecker.Result.NOT_MEMBER);
		this.mockMvc
			.perform(put("/api/classes/" + classId + "/teacher").header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"teacherSub\":\"gv-trung-tam-2\"}"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(content().string(containsString("\"code\":\"VALIDATION_FAILED\"")));
		this.mockMvc
			.perform(put("/api/classes/" + classId + "/teacher")
				.header("Authorization", "Bearer " + teacherA1Token())
				.header("X-LMS-Organization-Alias", ALIAS_1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"teacherSub\":\"gv2-trung-tam-1\"}"))
			.andExpect(status().isForbidden())
			.andExpect(content().string(containsString("\"code\":\"RESOURCE_FORBIDDEN\"")));
	}

	@Test
	@DisplayName("Tiêu chí 3: danh sách lớp theo đúng tầm nhìn từng vai — quản trị viên thấy mọi lớp của trung tâm, giáo viên chỉ thấy lớp mình phụ trách, học viên chưa ghi danh và người ngoài thấy rỗng; roster chỉ quản trị viên và giáo viên phụ trách đọc được, học viên bị từ chối và đáp ứng không kèm dữ kiện nào của lớp")
	void criterion3VisionByRole() throws Exception {
		String adminA = adminAToken();
		String classL1 = createClassOk(adminA, ALIAS_1, "Lop C3 L1", null);
		createClassOk(adminA, ALIAS_1, "Lop C3 L2", null);
		assignTeacherOk(adminA, ALIAS_1, classL1, "gv-trung-tam-1");
		this.mockMvc
			.perform(get("/api/classes").header("Authorization", "Bearer " + studentAToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string("[]"));
		enrollOk(adminA, ALIAS_1, classL1, "hs-trung-tam-1");
		this.mockMvc
			.perform(get("/api/classes").header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Lop C3 L1")))
			.andExpect(content().string(containsString("Lop C3 L2")));
		this.mockMvc
			.perform(get("/api/classes").header("Authorization", "Bearer " + teacherA1Token())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Lop C3 L1")))
			.andExpect(content().string(not(containsString("Lop C3 L2"))));
		this.mockMvc
			.perform(get("/api/classes").header("Authorization", "Bearer " + freeToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string("[]"));
		this.mockMvc
			.perform(get("/api/classes/" + classL1 + "/members").header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("hs-trung-tam-1")));
		this.mockMvc
			.perform(get("/api/classes/" + classL1 + "/members")
				.header("Authorization", "Bearer " + teacherA1Token())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("hs-trung-tam-1")));
		this.mockMvc
			.perform(get("/api/classes/" + classL1 + "/members")
				.header("Authorization", "Bearer " + studentAToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isForbidden())
			.andExpect(content().string(containsString("\"code\":\"RESOURCE_FORBIDDEN\"")))
			.andExpect(content().string(not(containsString("hs-trung-tam-1"))))
			.andExpect(content().string(not(containsString("Lop C3 L1"))));
	}

	@Test
	@DisplayName("Tiêu chí 4: học viên là thành viên được ghi danh thì thấy đúng lớp đã ghi danh, xem được chi tiết cơ bản của lớp nhưng vẫn không xem được roster; ghi danh trùng cùng một học viên vào cùng một lớp bị 409")
	void criterion4EnrollMemberStudent() throws Exception {
		String adminA = adminAToken();
		String classL1 = createClassOk(adminA, ALIAS_1, "Lop C4 L1", null);
		createClassOk(adminA, ALIAS_1, "Lop C4 L2", null);
		enrollOk(adminA, ALIAS_1, classL1, "hs-trung-tam-1");
		this.mockMvc
			.perform(get("/api/classes").header("Authorization", "Bearer " + studentAToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Lop C4 L1")))
			.andExpect(content().string(not(containsString("Lop C4 L2"))));
		this.mockMvc
			.perform(get("/api/classes/" + classL1).header("Authorization", "Bearer " + studentAToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Lop C4 L1")));
		this.mockMvc
			.perform(get("/api/classes/" + classL1 + "/members")
				.header("Authorization", "Bearer " + studentAToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isForbidden());
		this.mockMvc
			.perform(post("/api/classes/" + classL1 + "/members").header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentSub\":\"hs-trung-tam-1\"}"))
			.andExpect(status().isConflict())
			.andExpect(content().string(containsString("\"code\":\"STATE_CONFLICT\"")));
	}

	@Test
	@DisplayName("Tiêu chí 5: ghi danh người chưa phải thành viên không tạo tư cách thành viên — ngữ cảnh của họ tại trang trung tâm vẫn là khách với vai trò rỗng, họ chỉ thấy đúng lớp đã ghi danh, xem được chi tiết lớp đó và bị từ chối như khách ở mọi đọc khác của trung tâm")
	void criterion5EnrollGuestKeepsGuestStanding() throws Exception {
		when(this.membershipChecker.check(ORG_1_ID, "hs-trung-tam-2"))
			.thenReturn(MembershipChecker.Result.NOT_MEMBER);
		String adminA = adminAToken();
		String classL1 = createClassOk(adminA, ALIAS_1, "Lop C5 L1", null);
		String classL2 = createClassOk(adminA, ALIAS_1, "Lop C5 L2", null);
		enrollOk(adminA, ALIAS_1, classL1, "hs-trung-tam-2");
		this.mockMvc
			.perform(get("/api/tenancy-check/context").header("Authorization", "Bearer " + studentBToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("\"guest\":true")))
			.andExpect(content().string(containsString("\"organizationRoles\":[]")));
		this.mockMvc
			.perform(get("/api/classes").header("Authorization", "Bearer " + studentBToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Lop C5 L1")))
			.andExpect(content().string(not(containsString("Lop C5 L2"))));
		this.mockMvc
			.perform(get("/api/classes/" + classL1).header("Authorization", "Bearer " + studentBToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Lop C5 L1")));
		this.mockMvc
			.perform(get("/api/classes/" + classL2).header("Authorization", "Bearer " + studentBToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isForbidden());
		this.mockMvc
			.perform(get("/api/classes/" + classL1 + "/members")
				.header("Authorization", "Bearer " + studentBToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isForbidden());
		this.mockMvc
			.perform(get("/api/courses").header("Authorization", "Bearer " + studentBToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string("[]"));
	}

	@Test
	@DisplayName("Tiêu chí 6: huỷ ghi danh làm học viên mất lớp khỏi danh sách ngay từ request kế tiếp và thao tác trên lớp bị từ chối, nhưng dòng ghi danh còn nguyên với removed_at đã đặt; ghi danh lại làm sống lại đúng dòng đó, không sinh dòng thứ hai cho cùng một cặp lớp–học viên")
	void criterion6RemoveAndReenrollRevivesSameRow() throws Exception {
		String adminA = adminAToken();
		String classL1 = createClassOk(adminA, ALIAS_1, "Lop C6 L1", null);
		enrollOk(adminA, ALIAS_1, classL1, "hs-trung-tam-1");
		UUID rowId = this.jdbcTemplate.queryForObject(
				"select id from class_member where class_id = ? and student_sub = 'hs-trung-tam-1'", UUID.class,
				UUID.fromString(classL1));
		this.mockMvc
			.perform(delete("/api/classes/" + classL1 + "/members/hs-trung-tam-1")
				.header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isNoContent());
		this.mockMvc
			.perform(get("/api/classes").header("Authorization", "Bearer " + studentAToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string("[]"));
		this.mockMvc
			.perform(get("/api/classes/" + classL1).header("Authorization", "Bearer " + studentAToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isForbidden());
		assertEquals(1, countMembers(classL1, "removed_at is not null"));
		this.mockMvc
			.perform(delete("/api/classes/" + classL1 + "/members/hs-trung-tam-1")
				.header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isNotFound())
			.andExpect(content().string(containsString("\"code\":\"RESOURCE_NOT_FOUND\"")));
		enrollOk(adminA, ALIAS_1, classL1, "hs-trung-tam-1");
		assertEquals(1, countMembers(classL1, "removed_at is null"));
		assertEquals(rowId, this.jdbcTemplate.queryForObject(
				"select id from class_member where class_id = ? and student_sub = 'hs-trung-tam-1'", UUID.class,
				UUID.fromString(classL1)));
		this.mockMvc
			.perform(get("/api/classes").header("Authorization", "Bearer " + studentAToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Lop C6 L1")));
	}

	@Test
	@DisplayName("Tiêu chí 7: thay giáo viên phụ trách có hiệu lực ngay — danh sách của giáo viên cũ mất lớp, của giáo viên mới có lớp, và phép kiểm được phân công dạy lớp chỉ còn đúng với giáo viên mới")
	void criterion7ReplaceTeacherTakesEffectImmediately() throws Exception {
		String adminA = adminAToken();
		String classL1 = createClassOk(adminA, ALIAS_1, "Lop C7 L1", null);
		assignTeacherOk(adminA, ALIAS_1, classL1, "gv-trung-tam-1");
		TeachingClass loaded = loadClass(classL1);
		TenantContexts.set(new TenantContext("gv-trung-tam-1", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"), false,
				false, ORG_1_ID));
		try {
			assertTrue(this.accessChecks.isAssignedTeacher(loaded));
		}
		finally {
			TenantContexts.clear();
		}
		assignTeacherOk(adminA, ALIAS_1, classL1, "gv2-trung-tam-1");
		TeachingClass reloaded = loadClass(classL1);
		this.mockMvc
			.perform(get("/api/classes").header("Authorization", "Bearer " + teacherA1Token())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string("[]"));
		this.mockMvc
			.perform(get("/api/classes").header("Authorization", "Bearer " + teacherA2Token())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Lop C7 L1")));
		TenantContexts.set(new TenantContext("gv-trung-tam-1", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"), false,
				false, ORG_1_ID));
		try {
			assertFalse(this.accessChecks.isAssignedTeacher(reloaded));
		}
		finally {
			TenantContexts.clear();
		}
		TenantContexts.set(new TenantContext("gv2-trung-tam-1", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"), false,
				false, ORG_1_ID));
		try {
			assertTrue(this.accessChecks.isAssignedTeacher(reloaded));
		}
		finally {
			TenantContexts.clear();
		}
	}

	@Test
	@DisplayName("Tiêu chí 8: giáo viên bị gỡ tư cách thành viên khỏi trung tâm thì mất quyền dạy ngay dù teacher_sub vẫn ghi tên họ — danh sách lớp rỗng, roster bị từ chối, phép kiểm phân công dạy trả sai; ghi danh của học viên trong lớp không đổi")
	void criterion8TeacherLosesMembershipLosesTeachingRights() throws Exception {
		String adminA = adminAToken();
		String classL1 = createClassOk(adminA, ALIAS_1, "Lop C8 L1", null);
		assignTeacherOk(adminA, ALIAS_1, classL1, "gv-trung-tam-1");
		enrollOk(adminA, ALIAS_1, classL1, "hs-trung-tam-1");
		TeachingClass loaded = loadClass(classL1);
		when(this.membershipChecker.check(ORG_1_ID, "gv-trung-tam-1"))
			.thenReturn(MembershipChecker.Result.NOT_MEMBER);
		this.mockMvc
			.perform(get("/api/tenancy-check/context").header("Authorization", "Bearer " + teacherA1Token())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("\"guest\":true")));
		this.mockMvc
			.perform(get("/api/classes").header("Authorization", "Bearer " + teacherA1Token())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string("[]"));
		this.mockMvc
			.perform(get("/api/classes/" + classL1 + "/members")
				.header("Authorization", "Bearer " + teacherA1Token())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isForbidden());
		TenantContexts.set(new TenantContext("gv-trung-tam-1", ALIAS_1, null, List.of(), true, false, null));
		try {
			assertFalse(this.accessChecks.isAssignedTeacher(loaded));
		}
		finally {
			TenantContexts.clear();
		}
		this.mockMvc
			.perform(get("/api/classes/" + classL1 + "/members").header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("hs-trung-tam-1")));
	}

	@Test
	@DisplayName("Tiêu chí 9: học viên bị gỡ tư cách thành viên nhưng ghi danh còn hiệu lực vẫn thấy đúng lớp đã ghi danh và khoá chứa lớp tại trang trung tâm với tư cách khách, xem được chi tiết lớp đó, không thấy thêm gì khác của trung tâm")
	void criterion9StudentLosesMembershipKeepsEnrolledClassesAsGuest() throws Exception {
		String adminA = adminAToken();
		String courseId = createCourseOk(adminA, ALIAS_1, "Khoa C9");
		String classL1 = createClassOk(adminA, ALIAS_1, "Lop C9 L1", courseId);
		String classL2 = createClassOk(adminA, ALIAS_1, "Lop C9 L2", null);
		enrollOk(adminA, ALIAS_1, classL1, "hs-trung-tam-1");
		when(this.membershipChecker.check(ORG_1_ID, "hs-trung-tam-1"))
			.thenReturn(MembershipChecker.Result.NOT_MEMBER);
		this.mockMvc
			.perform(get("/api/tenancy-check/context").header("Authorization", "Bearer " + studentAToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("\"guest\":true")))
			.andExpect(content().string(containsString("\"organizationRoles\":[]")));
		this.mockMvc
			.perform(get("/api/classes").header("Authorization", "Bearer " + studentAToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Lop C9 L1")))
			.andExpect(content().string(not(containsString("Lop C9 L2"))));
		this.mockMvc
			.perform(get("/api/courses").header("Authorization", "Bearer " + studentAToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Khoa C9")));
		this.mockMvc
			.perform(get("/api/classes/" + classL1).header("Authorization", "Bearer " + studentAToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Lop C9 L1")));
		this.mockMvc
			.perform(get("/api/classes/" + classL2).header("Authorization", "Bearer " + studentAToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isForbidden());
		this.mockMvc
			.perform(get("/api/classes/" + classL1 + "/members")
				.header("Authorization", "Bearer " + studentAToken())
				.header("X-LMS-Organization-Alias", ALIAS_1))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("Nhánh lỗi của hợp đồng: tạo lớp với courseId thuộc trung tâm khác hoặc không tồn tại bị 422; ghi danh và đặt giáo viên khi không xác minh được tư cách thành viên tại Keycloak bị 503 và không để lại bất kỳ thay đổi nào")
	void contractErrorBranches() throws Exception {
		String adminA = adminAToken();
		String courseB = createCourseOk(adminBToken(), ALIAS_2, "Khoa C10 Cua B");
		this.mockMvc
			.perform(post("/api/classes").header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Lop C10\",\"courseId\":\"" + courseB + "\"}"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(content().string(containsString("\"code\":\"VALIDATION_FAILED\"")));
		this.mockMvc
			.perform(post("/api/classes").header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Lop C10\",\"courseId\":\"" + UUID.randomUUID() + "\"}"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(content().string(containsString("\"code\":\"VALIDATION_FAILED\"")));
		String classId = createClassOk(adminA, ALIAS_1, "Lop C10 Chinh", null);
		when(this.membershipChecker.check(ORG_1_ID, "hs-trung-tam-1"))
			.thenReturn(MembershipChecker.Result.UNVERIFIABLE);
		this.mockMvc
			.perform(post("/api/classes/" + classId + "/members").header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentSub\":\"hs-trung-tam-1\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(content().string(containsString("\"code\":\"MEMBERSHIP_UNVERIFIABLE\"")));
		assertEquals(0, countMembers(classId, "removed_at is null"));
		when(this.membershipChecker.check(ORG_1_ID, "gv-trung-tam-1"))
			.thenReturn(MembershipChecker.Result.UNVERIFIABLE);
		this.mockMvc
			.perform(put("/api/classes/" + classId + "/teacher").header("Authorization", "Bearer " + adminA)
				.header("X-LMS-Organization-Alias", ALIAS_1)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"teacherSub\":\"gv-trung-tam-1\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(content().string(containsString("\"code\":\"MEMBERSHIP_UNVERIFIABLE\"")));
		String teacherSub = this.jdbcTemplate.queryForObject(
				"select teacher_sub from teaching_class where id = ?", String.class, UUID.fromString(classId));
		assertNull(teacherSub);
	}

	private String partitionOf(String table, String id) {
		return this.jdbcTemplate.queryForObject("select partition_key from " + table + " where id = ?", String.class,
				UUID.fromString(id));
	}

	private int countMembers(String classId, String removedCondition) {
		Integer count = this.jdbcTemplate.queryForObject(
				"select count(*) from class_member where class_id = ? and student_sub = 'hs-trung-tam-1' and "
						+ removedCondition,
				Integer.class, UUID.fromString(classId));
		return count != null ? count : -1;
	}

	private TeachingClass loadClass(String classId) {
		TenantContexts.set(new TenantContext("hai-ngu-canh", ALIAS_1, ORG_1_ID, List.of("tenant-admin"), false,
				false, ORG_1_ID));
		try {
			return new TransactionTemplate(this.transactionManager)
				.execute(status -> this.classes.findById(UUID.fromString(classId)).orElseThrow());
		}
		finally {
			TenantContexts.clear();
		}
	}

	private String createCourseOk(String token, String alias, String name) throws Exception {
		MvcResult result = this.mockMvc
			.perform(post("/api/courses").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", alias)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\"}"))
			.andExpect(status().isCreated())
			.andReturn();
		return extractId(result);
	}

	private String createClassOk(String token, String alias, String name, String courseId) throws Exception {
		String body = courseId != null
				? "{\"name\":\"" + name + "\",\"courseId\":\"" + courseId + "\"}"
				: "{\"name\":\"" + name + "\"}";
		MvcResult result = this.mockMvc
			.perform(post("/api/classes").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", alias)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andReturn();
		return extractId(result);
	}

	private void assignTeacherOk(String token, String alias, String classId, String teacherSub) throws Exception {
		this.mockMvc
			.perform(put("/api/classes/" + classId + "/teacher").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", alias)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"teacherSub\":\"" + teacherSub + "\"}"))
			.andExpect(status().isOk());
	}

	private void enrollOk(String token, String alias, String classId, String studentSub) throws Exception {
		this.mockMvc
			.perform(post("/api/classes/" + classId + "/members").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", alias)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentSub\":\"" + studentSub + "\"}"))
			.andExpect(status().isCreated());
	}

	private String extractId(MvcResult result) throws Exception {
		Matcher matcher = ID_PATTERN.matcher(result.getResponse().getContentAsString());
		if (!matcher.find()) {
			throw new AssertionError("response has no id: " + result.getResponse().getContentAsString());
		}
		return matcher.group(1);
	}

	private String adminAToken() throws Exception {
		return mint("hai-ngu-canh", Map.of("organization", Map.of(ALIAS_1, orgEntry(ORG_1_ID, List.of("tenant-admin")),
				ALIAS_2, orgEntry(ORG_2_ID, List.of("tenant-member")))));
	}

	private String adminBToken() throws Exception {
		return mint("admin-trung-tam-2",
				Map.of("organization", Map.of(ALIAS_2, orgEntry(ORG_2_ID, List.of("tenant-admin")))));
	}

	private String teacherA1Token() throws Exception {
		return mint("gv-trung-tam-1",
				Map.of("organization", Map.of(ALIAS_1, orgEntry(ORG_1_ID, List.of("tenant-teacher")))));
	}

	private String teacherA2Token() throws Exception {
		return mint("gv2-trung-tam-1",
				Map.of("organization", Map.of(ALIAS_1, orgEntry(ORG_1_ID, List.of("tenant-teacher")))));
	}

	private String studentAToken() throws Exception {
		return mint("hs-trung-tam-1",
				Map.of("organization", Map.of(ALIAS_1, orgEntry(ORG_1_ID, List.of("tenant-member")))));
	}

	private String studentBToken() throws Exception {
		return mint("hs-trung-tam-2",
				Map.of("organization", Map.of(ALIAS_2, orgEntry(ORG_2_ID, List.of("tenant-member")))));
	}

	private String freeToken() throws Exception {
		return mint("gv-tu-do", Map.of());
	}

	private Map<String, Object> orgEntry(String id, List<String> roles) {
		return Map.of("id", id, "realm_access", Map.of("roles", roles));
	}

	private String mint(String subject, Map<String, Object> extraClaims) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder().issuer(ISSUER)
			.subject(subject)
			.issueTime(Date.from(now.minusSeconds(10)))
			.expirationTime(Date.from(now.plusSeconds(300)));
		extraClaims.forEach(builder::claim);
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
		jwt.sign(new RSASSASigner((RSAPrivateKey) TestJwtConfiguration.KEY_PAIR.getPrivate()));
		return jwt.serialize();
	}

}
