package com.giaokhoa.server.assignment;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.giaokhoa.server.tenancy.MembershipChecker;

// Kiểm thử đường thật của điểm nối snapshot trên PostgreSQL thật: ứng dụng khởi động trọn vẹn trên một
// database riêng của lượt chạy (Flyway áp toàn bộ chuỗi migration như khi vận hành), dữ kiện đề và snapshot
// được gieo bằng SQL trực tiếp vào đúng các bảng của lát đề và lõi quyền, và cả điểm nối đọc snapshot lẫn
// điểm nối quyết quyền giao bài đều là bản thật — chỉ hai điểm nối ra hệ thống ngoài phạm vi feat này mới
// giả lập: kiểm membership hiện hành qua Keycloak và tra định danh Organization theo alias. Điều kiện chạy:
// đủ ba biến môi trường LMS_TEST_PG_URL, LMS_TEST_PG_USER, LMS_TEST_PG_PASSWORD trỏ tới PostgreSQL test
// chuyên dụng.
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_PASSWORD", matches = ".+")
@SpringBootTest(properties = {
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=" + AssignmentSnapshotPostgresTests.ISSUER,
		"spring.flyway.placeholders.lms-server-app-password=app-secret" })
class AssignmentSnapshotPostgresTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	private static final String TEACHER_PARTITION = "user:gv-trung-tam-1";

	private static final UUID CLASS_1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");

	private static final UUID EXAM_RESOURCE = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc1");

	private static final UUID VERSION_PUBLISHED = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddddd1");

	private static final UUID VERSION_DRAFT = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddddd2");

	private static final UUID ITEM_CHOICE = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1");

	private static final UUID ITEM_ESSAY = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2");

	private static final String TEACHER_1 = "gv-trung-tam-1";

	private static final String STUDENT_1 = "hs-trung-tam-1";

	private static String databaseName;

	private static String databaseUrl;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private MembershipChecker membershipChecker;

	@MockitoBean
	private OrganizationIds organizationIds;

	private MockMvc mockMvc;

	@DynamicPropertySource
	static void datasourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> databaseUrl);
		registry.add("spring.datasource.username", () -> System.getenv("LMS_TEST_PG_USER"));
		registry.add("spring.datasource.password", () -> System.getenv("LMS_TEST_PG_PASSWORD"));
	}

	@BeforeAll
	static void createDatabase() throws SQLException {
		String baseUrl = System.getenv("LMS_TEST_PG_URL");
		databaseName = "lms_server_test_" + UUID.randomUUID().toString().replace("-", "");
		databaseUrl = baseUrl + "/" + databaseName;
		try (Connection admin = DriverManager.getConnection(baseUrl + "/postgres", System.getenv("LMS_TEST_PG_USER"),
				System.getenv("LMS_TEST_PG_PASSWORD")); Statement statement = admin.createStatement()) {
			statement.execute("create database " + databaseName);
		}
	}

	@AfterAll
	static void dropDatabase() throws SQLException {
		if (databaseName == null) {
			return;
		}
		String baseUrl = System.getenv("LMS_TEST_PG_URL");
		try (Connection admin = DriverManager.getConnection(baseUrl + "/postgres", System.getenv("LMS_TEST_PG_USER"),
				System.getenv("LMS_TEST_PG_PASSWORD")); Statement statement = admin.createStatement()) {
			statement.execute("drop database if exists " + databaseName + " with (force)");
		}
	}

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		lenient().when(this.membershipChecker.check(anyString(), anyString()))
				.thenReturn(MembershipChecker.Result.MEMBER);
		lenient().when(this.organizationIds.findOrganizationIdByAlias("trung-tam-1"))
				.thenReturn(Optional.of(UUID.fromString(ORG_1_ID)));
		seed();
	}

	@Test
	@DisplayName("Tạo lần giao với phiên bản đề đã xuất bản thành công qua endpoint thật và ghim đúng phiên bản; bản nháp bị 409, phiên bản không tồn tại bị 404")
	void createAssignmentFromRealPublishedSnapshot() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		MvcResult created = this.mockMvc
				.perform(post("/api/assignments").header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1").contentType(MediaType.APPLICATION_JSON)
						.content(assignmentJson(VERSION_PUBLISHED, CLASS_1, OffsetDateTime.now().minusHours(1),
								OffsetDateTime.now().plusHours(2))))
				.andExpect(status().isOk())
				.andReturn();
		UUID assignmentId = UUID.fromString(json(created).get("id").asText());
		UUID storedVersion = this.jdbcTemplate.queryForObject(
				"select exam_version_id from exam_assignment where id = ?", UUID.class, assignmentId);
		assertEquals(VERSION_PUBLISHED, storedVersion);

		this.mockMvc
				.perform(post("/api/assignments").header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1").contentType(MediaType.APPLICATION_JSON)
						.content(assignmentJson(VERSION_DRAFT, CLASS_1, OffsetDateTime.now().minusHours(1),
								OffsetDateTime.now().plusHours(2))))
				.andExpect(status().isConflict())
				.andExpect(content().string(containsString("STATE_CONFLICT")));

		this.mockMvc
				.perform(post("/api/assignments").header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1").contentType(MediaType.APPLICATION_JSON)
						.content(assignmentJson(UUID.randomUUID(), CLASS_1, OffsetDateTime.now().minusHours(1),
								OffsetDateTime.now().plusHours(2))))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("Học viên bắt đầu, lưu và nộp bài qua endpoint thật với nội dung chỉ từ snapshot đã đóng băng: sửa dữ kiện mục sống sau khi giao không đổi nội dung lượt làm và đáp ứng không lộ đáp án hay tiêu chí chấm")
	void studentAttemptReadsOnlyFrozenSnapshot() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		String student1 = studentToken(STUDENT_1);
		UUID assignmentId = createAssignment(teacher1, VERSION_PUBLISHED);

		// Sửa dữ kiện sống của mục đề sau khi đã giao: điểm và thứ tự của hàng mục đổi hẳn, nhưng snapshot đã
		// đóng băng của phiên bản đã giao là nguồn nội dung duy nhất của lượt làm nên không được đổi theo.
		this.jdbcTemplate.update("update exam_item set score = 99, position = 7 where id = ?", ITEM_CHOICE);

		MvcResult started = this.mockMvc
				.perform(post("/api/assignments/" + assignmentId + "/attempts")
						.header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString("correctChoiceId"))))
				.andExpect(content().string(not(containsString("RUBRIC-BI-MAT"))))
				.andReturn();
		JsonNode attempt = json(started);
		UUID attemptId = UUID.fromString(attempt.get("attemptId").asText());
		JsonNode items = attempt.get("items");
		assertEquals(2, items.size());
		assertEquals("Câu hỏi 1: 2 + 2 bằng mấy?", items.get(0).get("prompt").asText());
		assertEquals(5, items.get(0).get("points").asInt());
		assertEquals(1, items.get(0).get("position").asInt());
		assertEquals("Trình bày cách giải.", items.get(1).get("prompt").asText());

		this.mockMvc
				.perform(put("/api/attempts/" + attemptId + "/answers")
						.header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1").contentType(MediaType.APPLICATION_JSON)
						.content("{\"answers\":[{\"examItemId\":\"" + ITEM_CHOICE
								+ "\",\"answer\":{\"choiceId\":\"c2\"}},{\"examItemId\":\"" + ITEM_ESSAY
								+ "\",\"answer\":{\"text\":\"2 + 2 = 4 vì ...\"}}]}"))
				.andExpect(status().isOk());
		Integer savedAnswers = this.jdbcTemplate.queryForObject(
				"select count(*) from attempt_answer where attempt_id = ?", Integer.class, attemptId);
		assertEquals(2, savedAnswers);

		this.mockMvc
				.perform(post("/api/attempts/" + attemptId + "/submit")
						.header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("SUBMITTED")));
	}

	private void seed() {
		this.jdbcTemplate.update("delete from attempt_answer");
		this.jdbcTemplate.update("delete from exam_attempt");
		this.jdbcTemplate.update("delete from exam_assignment");
		this.jdbcTemplate.update("delete from exam_item_snapshot");
		this.jdbcTemplate.update("delete from exam_item");
		this.jdbcTemplate.update("delete from exam_version");
		this.jdbcTemplate.update("delete from exam");
		this.jdbcTemplate.update("delete from resource_version");
		this.jdbcTemplate.update("delete from resource");
		this.jdbcTemplate.update("delete from class_member");
		this.jdbcTemplate.update("delete from teaching_class");

		this.jdbcTemplate.update("""
				insert into teaching_class (id, partition_key, course_id, name, teacher_sub, created_by_sub)
				values (?, ?, null, 'L1', ?, ?)
				""", CLASS_1, ORG_1_ID, TEACHER_1, TEACHER_1);
		this.jdbcTemplate.update("""
				insert into class_member (id, partition_key, class_id, student_sub, enrolled_by_sub, removed_at)
				values (?, ?, ?, ?, ?, null)
				""", UUID.randomUUID(), ORG_1_ID, CLASS_1, STUDENT_1, TEACHER_1);

		this.jdbcTemplate.update("""
				insert into exam (id, partition_key, workspace_kind, owner_type, owner_user_sub,
					owner_organization_id, collection_id, custody_kind, title, created_by_sub, created_at,
					updated_at)
				values (?, ?, 'personal', 'user', ?, null, null, 'owned', 'Đề kiểm tra', ?, now(), now())
				""", EXAM_RESOURCE, TEACHER_PARTITION, TEACHER_1, TEACHER_1);
		this.jdbcTemplate.update("""
				insert into exam_version (id, exam_id, partition_key, version_number, status, content_hash,
					lock_version, created_by_sub, published_at, locked_at, created_at, updated_at)
				values (?, ?, ?, 1, 'published', 'hash-v1', 0, ?, now(), now(), now(), now())
				""", VERSION_PUBLISHED, EXAM_RESOURCE, TEACHER_PARTITION, TEACHER_1);
		this.jdbcTemplate.update("""
				insert into exam_version (id, exam_id, partition_key, version_number, status, content_hash,
					lock_version, created_by_sub, published_at, locked_at, created_at, updated_at)
				values (?, ?, ?, 2, 'draft', null, 0, ?, null, null, now(), now())
				""", VERSION_DRAFT, EXAM_RESOURCE, TEACHER_PARTITION, TEACHER_1);
		this.jdbcTemplate.update("""
				insert into exam_item (id, exam_version_id, exam_id, partition_key, question_version_id,
					position, score, created_at, updated_at)
				values (?, ?, ?, ?, ?, 1, 5, now(), now())
				""", ITEM_CHOICE, VERSION_PUBLISHED, EXAM_RESOURCE, TEACHER_PARTITION, UUID.randomUUID());
		this.jdbcTemplate.update("""
				insert into exam_item (id, exam_version_id, exam_id, partition_key, question_version_id,
					position, score, created_at, updated_at)
				values (?, ?, ?, ?, ?, 2, 5, now(), now())
				""", ITEM_ESSAY, VERSION_PUBLISHED, EXAM_RESOURCE, TEACHER_PARTITION, UUID.randomUUID());
		this.jdbcTemplate.update("""
				insert into exam_item_snapshot (id, exam_item_id, exam_version_id, partition_key,
					question_version_id, question_type, position, score, content, created_at, updated_at)
				values (?, ?, ?, ?, ?, 'single_choice', 1, 5,
					'{"stem":"Câu hỏi 1: 2 + 2 bằng mấy?","choices":[{"id":"c1","text":"3"},{"id":"c2","text":"4"}],"correctChoiceId":"c2"}',
					now(), now())
				""", UUID.randomUUID(), ITEM_CHOICE, VERSION_PUBLISHED, TEACHER_PARTITION, UUID.randomUUID());
		this.jdbcTemplate.update("""
				insert into exam_item_snapshot (id, exam_item_id, exam_version_id, partition_key,
					question_version_id, question_type, position, score, content, created_at, updated_at)
				values (?, ?, ?, ?, ?, 'essay_text', 2, 5,
					'{"stem":"Trình bày cách giải.","rubric":"RUBRIC-BI-MAT"}', now(), now())
				""", UUID.randomUUID(), ITEM_ESSAY, VERSION_PUBLISHED, TEACHER_PARTITION, UUID.randomUUID());

		this.jdbcTemplate.update("""
				insert into resource (id, resource_type, workspace_kind, owner_type, owner_user_sub,
					owner_organization_id, collection_id, partition_key, custody_kind, license_grant_id,
					business_condition, created_by_sub, created_at, updated_at)
				values (?, 'exam', 'personal', 'user', ?, null, null, ?, 'owned', null, null, ?, now(), now())
				""", EXAM_RESOURCE, TEACHER_1, TEACHER_PARTITION, TEACHER_1);
		this.jdbcTemplate.update("""
				insert into resource_version (id, resource_id, partition_key, version_number, status,
					content_hash, created_by_sub, locked_at, published_at, created_at, updated_at)
				values (?, ?, ?, 1, 'published', 'hash-v1', ?, now(), now(), now(), now())
				""", VERSION_PUBLISHED, EXAM_RESOURCE, TEACHER_PARTITION, TEACHER_1);
	}

	private UUID createAssignment(String teacherToken, UUID versionId) throws Exception {
		MvcResult result = this.mockMvc
				.perform(post("/api/assignments").header("Authorization", "Bearer " + teacherToken)
						.header("X-LMS-Organization-Alias", "trung-tam-1").contentType(MediaType.APPLICATION_JSON)
						.content(assignmentJson(versionId, CLASS_1, OffsetDateTime.now().minusHours(1),
								OffsetDateTime.now().plusHours(2))))
				.andExpect(status().isOk())
				.andReturn();
		return UUID.fromString(json(result).get("id").asText());
	}

	private String assignmentJson(UUID versionId, UUID classId, OffsetDateTime opensAt, OffsetDateTime dueAt) {
		return "{\"examVersionId\":\"" + versionId + "\",\"classId\":\"" + classId + "\",\"opensAt\":\"" + opensAt
				+ "\",\"dueAt\":\"" + dueAt + "\"}";
	}

	private JsonNode json(MvcResult result) throws Exception {
		return this.objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private String teacherToken(String sub) throws Exception {
		return mint(sub, Map.of("organization", Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-teacher")))));
	}

	private String studentToken(String sub) throws Exception {
		return mint(sub, Map.of("organization", Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-member")))));
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

}
