package com.giaokhoa.server.exam;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Kiểm chứng lớp PostgreSQL của lát soạn đề trên cùng khuôn với PostgresRlsTests của khung tenancy: ứng dụng
// chạy trên PostgreSQL 18 online qua một database test riêng cho lượt chạy (tạo trước, xoá sau), Flyway bằng
// tài khoản quản trị, datasource của ứng dụng bằng role runtime lms_server_app. Cổng đọc phiên bản câu là cài
// đặt thật như ở bộ test H2: test gieo hàng câu thật vào các bảng nội dung của feat câu hỏi bằng tài khoản quản
// trị, nên luồng chạy qua đúng điểm nối tích hợp dưới RLS thật của cả bảng câu lẫn bảng lõi.
@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ExamPostgresRlsTests.ISSUER)
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_URL", matches = ".+")
class ExamPostgresRlsTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	private static final String ORG_2_ID = "22222222-2222-2222-2222-222222222222";

	private static final String APP_PASSWORD = "app-secret";

	private static final String ADMIN_BASE_URL = System.getenv("LMS_TEST_PG_URL");

	private static final String ADMIN_USER = System.getenv("LMS_TEST_PG_USER");

	private static final String ADMIN_PASSWORD = System.getenv("LMS_TEST_PG_PASSWORD");

	private static final String TEST_DATABASE = "lms_server_test_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 12);

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		createTestDatabase();
		registry.add("spring.datasource.url", ExamPostgresRlsTests::testDatabaseUrl);
		registry.add("spring.datasource.username", () -> "lms_server_app");
		registry.add("spring.datasource.password", () -> APP_PASSWORD);
		registry.add("spring.flyway.user", () -> ADMIN_USER);
		registry.add("spring.flyway.password", () -> ADMIN_PASSWORD);
		registry.add("spring.flyway.placeholders.lms-server-app-password", () -> APP_PASSWORD);
	}

	@AfterAll
	static void dropTestDatabase() {
		// Xoá database test là một phần của hợp đồng lượt chạy: lỗi dọn dẹp chỉ ghi ra log, không che kết quả test.
		try (Connection connection = DriverManager.getConnection(ADMIN_BASE_URL + "/postgres", ADMIN_USER,
				ADMIN_PASSWORD); Statement statement = connection.createStatement()) {
			statement.execute("select pg_terminate_backend(pid) from pg_stat_activity where datname = '"
					+ TEST_DATABASE + "'");
			statement.execute("drop database if exists " + TEST_DATABASE);
		}
		catch (SQLException ex) {
			System.err.println("Không xoá được database test " + TEST_DATABASE + ": " + ex.getMessage());
		}
	}

	private static String testDatabaseUrl() {
		return ADMIN_BASE_URL + "/" + TEST_DATABASE;
	}

	private static void createTestDatabase() {
		try (Connection connection = DriverManager.getConnection(ADMIN_BASE_URL + "/postgres", ADMIN_USER,
				ADMIN_PASSWORD); Statement statement = connection.createStatement()) {
			statement.execute("create database " + TEST_DATABASE);
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Không tạo được database test " + TEST_DATABASE, ex);
		}
	}

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private MembershipChecker membershipChecker;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context)
				.apply(springSecurity())
				.build();
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
			NimbusJwtDecoder decoder = NimbusJwtDecoder
					.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic())
					.build();
			decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
			return decoder;
		}

	}

	@Test
	@DisplayName("Migration của đề áp sạch trên database trống: V40 tạo bốn bảng và V41 bật RLS, đúng thứ tự trong dãy migration")
	void examMigrationsApplyOnEmptyDatabase() {
		List<String> versions = adminJdbc().queryForList(
				"select version from flyway_schema_history where success order by installed_rank", String.class);
		assertTrue(versions.containsAll(List.of("40", "41")));
		assertTrue(versions.indexOf("40") < versions.indexOf("41"));
	}

	@Test
	@DisplayName("RLS chặn ở mức database trên cả bốn bảng của đề qua kết nối role runtime: chưa đặt biến ngữ cảnh thì không thấy hàng nào, đặt khoá của một phân vùng thì chỉ thấy hàng của phân vùng đó ở cả bốn bảng, ghi hàng mang khoá khác bị từ chối")
	void examTablesAreRowLevelIsolated() throws Exception {
		UUID examOne = UUID.randomUUID();
		UUID versionOne = UUID.randomUUID();
		UUID itemOne = UUID.randomUUID();
		UUID questionVersion = UUID.randomUUID();
		adminJdbc().update(
				"insert into exam (id, partition_key, workspace_kind, owner_type, owner_organization_id,"
						+ " custody_kind, title, created_by_sub, created_at, updated_at) values"
						+ " (?, ?, 'center', 'organization', ?, 'owned', 'rls-de-tt1', 'rls-owner', now(), now())",
				examOne, ORG_1_ID, ORG_1_ID);
		adminJdbc().update(
				"insert into exam (id, partition_key, workspace_kind, owner_type, owner_organization_id,"
						+ " custody_kind, title, created_by_sub, created_at, updated_at) values"
						+ " (?, ?, 'center', 'organization', ?, 'owned', 'rls-de-tt2', 'rls-owner', now(), now())",
				UUID.randomUUID(), ORG_2_ID, ORG_2_ID);
		adminJdbc().update(
				"insert into exam_version (id, exam_id, partition_key, version_number, status, lock_version,"
						+ " created_by_sub, created_at, updated_at) values"
						+ " (?, ?, ?, 1, 'published', 0, 'rls-owner', now(), now())",
				versionOne, examOne, ORG_1_ID);
		adminJdbc().update(
				"insert into exam_item (id, exam_version_id, exam_id, partition_key, question_version_id,"
						+ " position, score, created_at, updated_at) values (?, ?, ?, ?, ?, 1, 5, now(), now())",
				itemOne, versionOne, examOne, ORG_1_ID, questionVersion);
		adminJdbc().update(
				"insert into exam_item_snapshot (id, exam_item_id, exam_version_id, partition_key,"
						+ " question_version_id, question_type, position, score, content, created_at, updated_at)"
						+ " values (?, ?, ?, ?, ?, 'single_choice', 1, 5, '{}', now(), now())",
				UUID.randomUUID(), itemOne, versionOne, ORG_1_ID, questionVersion);
		try (Connection connection = appConnection()) {
			connection.setAutoCommit(false);
			assertEquals(0, count(connection, "select count(*) from exam where title like 'rls-de-%'"));
			assertEquals(0, count(connection, "select count(*) from exam_version where id = '" + versionOne + "'"));
			assertEquals(0, count(connection, "select count(*) from exam_item where id = '" + itemOne + "'"));
			assertEquals(0, count(connection, "select count(*) from exam_item_snapshot where exam_version_id = '"
					+ versionOne + "'"));
			connection.rollback();
			setConfig(connection, "app.partition_key", ORG_1_ID);
			assertEquals(1, count(connection, "select count(*) from exam where title like 'rls-de-%'"));
			assertEquals(1, count(connection, "select count(*) from exam_version where id = '" + versionOne + "'"));
			assertEquals(1, count(connection, "select count(*) from exam_item where id = '" + itemOne + "'"));
			assertEquals(1, count(connection, "select count(*) from exam_item_snapshot where exam_version_id = '"
					+ versionOne + "'"));
			assertThrows(SQLException.class, () -> {
				try (PreparedStatement insert = connection.prepareStatement(
						"insert into exam (id, partition_key, workspace_kind, owner_type, owner_organization_id,"
								+ " custody_kind, title, created_by_sub, created_at, updated_at) values"
								+ " (?, ?, 'center', 'organization', ?, 'owned', 'rls-de-le', 'rls-owner', now(),"
								+ " now())")) {
					insert.setObject(1, UUID.randomUUID());
					insert.setString(2, ORG_2_ID);
					insert.setString(3, ORG_2_ID);
					insert.executeUpdate();
				}
			});
			connection.rollback();
		}
	}

	@Test
	@DisplayName("Luồng soạn đề chạy trọn trên PostgreSQL bằng role runtime: soạn từ câu thật trong bảng nội dung, phát hành đóng băng snapshot trong database, ghi đúng hàng lõi và hàng phụ thuộc, đọc lại giữ nguyên nội dung cùng content_hash dù nội dung câu nguồn đã đổi")
	void examFlowFreezesSnapshotOnRealPostgres() throws Exception {
		UUID bankId = UUID.randomUUID();
		UUID questionId = UUID.randomUUID();
		UUID questionVersion = UUID.randomUUID();
		adminJdbc().update(
				"insert into question_bank (id, owner_sub, name, created_at, updated_at) "
						+ "values (?, 'gv-pg', 'Ngân hàng trên PostgreSQL', now(), now())",
				bankId);
		adminJdbc().update(
				"insert into question (id, bank_id, owner_sub, created_at, updated_at) "
						+ "values (?, ?, 'gv-pg', now(), now())",
				questionId, bankId);
		adminJdbc().update(
				"insert into question_version (id, question_id, owner_sub, version_number, status, question_type, "
						+ "content_contract_version, stem, choices, correct_choice_id, solution, content_hash, "
						+ "revision, created_at, updated_at) values (?, ?, 'gv-pg', 1, 'draft', 'single_choice', 1, "
						+ "'2 + 2 bằng mấy?', '[{\"id\":\"a\",\"text\":\"3\"},{\"id\":\"b\",\"text\":\"4\"}]', "
						+ "'b', '2 + 2 = 4.', "
						+ "'0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 0, now(), now())",
				questionVersion, questionId);
		String teacher = mint("gv-pg", Map.of("realm_access", Map.of("roles", List.of("personal-author"))));
		MvcResult created = this.mockMvc.perform(post("/api/exams").header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Đề trên PostgreSQL\"}"))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode createdJson = readJson(created);
		String examId = createdJson.get("id").asText();
		String versionId = createdJson.get("versionId").asText();
		String putBody = "{\"lockVersion\":" + createdJson.get("lockVersion").asLong() + ",\"items\":["
				+ "{\"questionVersionId\":\"" + questionVersion + "\",\"score\":5}]}";
		this.mockMvc.perform(put("/api/exams/" + examId + "/versions/" + versionId)
				.header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON).content(putBody))
				.andExpect(status().isOk());
		MvcResult published = this.mockMvc
				.perform(post("/api/exams/" + examId + "/versions/" + versionId + "/publish")
						.header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"published\"")))
				.andReturn();
		String contentHash = readJson(published).get("contentHash").asText();
		String storedHash = adminJdbc().queryForObject(
				"select content_hash from exam_version where id = ?", String.class, UUID.fromString(versionId));
		assertEquals(contentHash, storedHash);
		String frozen = adminJdbc().queryForObject(
				"select content from exam_item_snapshot where exam_version_id = ?", String.class,
				UUID.fromString(versionId));
		assertTrue(frozen.contains("\"correctChoiceId\":\"b\""));
		assertTrue(frozen.contains("2 + 2 bằng mấy?"));
		Integer mirrorCount = adminJdbc().queryForObject(
				"select count(*) from resource_version where id = ? and resource_id = ? and status = 'published' "
						+ "and content_hash = ? and partition_key = 'user:gv-pg'",
				Integer.class, UUID.fromString(versionId), UUID.fromString(examId), contentHash);
		assertEquals(1, mirrorCount);
		Integer dependencyCount = adminJdbc().queryForObject(
				"select count(*) from resource_dependency where resource_version_id = ? and resource_id = ? "
						+ "and depends_on_version_id = ? and depends_on_resource_id = ? "
						+ "and partition_key = 'user:gv-pg'",
				Integer.class, UUID.fromString(versionId), UUID.fromString(examId), questionVersion, questionId);
		assertEquals(1, dependencyCount);
		adminJdbc().update("update question_version set stem = 'ĐÃ SỬA TẠI NGUỒN' where id = ?", questionVersion);
		MvcResult after = this.mockMvc.perform(get("/api/exams/" + examId)
				.header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode afterJson = readJson(after);
		assertEquals(contentHash, afterJson.get("contentHash").asText());
		assertEquals("2 + 2 bằng mấy?",
				afterJson.get("items").get(0).get("content").get("stem").asText());
	}

	private int count(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
			rs.next();
			return rs.getInt(1);
		}
	}

	private JsonNode readJson(MvcResult result) throws Exception {
		return this.objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private JdbcTemplate adminJdbc() {
		return new JdbcTemplate(new DriverManagerDataSource(testDatabaseUrl(), ADMIN_USER, ADMIN_PASSWORD));
	}

	private Connection appConnection() throws SQLException {
		return DriverManager.getConnection(testDatabaseUrl(), "lms_server_app", APP_PASSWORD);
	}

	private void setConfig(Connection connection, String name, String value) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select set_config(?, ?, true)")) {
			statement.setString(1, name);
			statement.setString(2, value);
			statement.execute();
		}
	}

	private String mint(String subject, Map<String, Object> extraClaims) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.subject(subject)
				.issueTime(Date.from(now.minusSeconds(10)))
				.expirationTime(Date.from(now.plusSeconds(300)));
		extraClaims.forEach(builder::claim);
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
		jwt.sign(new RSASSASigner((RSAPrivateKey) TestJwtConfiguration.KEY_PAIR.getPrivate()));
		return jwt.serialize();
	}

}
