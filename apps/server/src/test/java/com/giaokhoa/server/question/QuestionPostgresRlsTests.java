package com.giaokhoa.server.question;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
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

// Lớp RLS theo chủ sở hữu của các bảng câu hỏi là cơ chế riêng của PostgreSQL nên bộ test H2 không thực thi
// được; bộ này chạy trên cùng cơ chế database test riêng từng lượt như PostgresRlsTests (tạo ngay trước lượt,
// xoá ngay sau lượt, thông tin kết nối quản trị qua LMS_TEST_PG_URL, LMS_TEST_PG_USER, LMS_TEST_PG_PASSWORD;
// chưa đặt biến thì toàn bộ lớp bị bỏ qua và kết quả bỏ qua không được tính là đạt). Datasource của ứng dụng
// chạy bằng role runtime lms_server_app, Flyway chạy bằng tài khoản quản trị.
@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + QuestionPostgresRlsTests.ISSUER)
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_URL", matches = ".+")
class QuestionPostgresRlsTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String APP_PASSWORD = "app-secret";

	private static final String ADMIN_BASE_URL = System.getenv("LMS_TEST_PG_URL");

	private static final String ADMIN_USER = System.getenv("LMS_TEST_PG_USER");

	private static final String ADMIN_PASSWORD = System.getenv("LMS_TEST_PG_PASSWORD");

	private static final String TEST_DATABASE = "lms_server_test_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 12);

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		createTestDatabase();
		registry.add("spring.datasource.url", QuestionPostgresRlsTests::testDatabaseUrl);
		registry.add("spring.datasource.username", () -> "lms_server_app");
		registry.add("spring.datasource.password", () -> APP_PASSWORD);
		registry.add("spring.flyway.user", () -> ADMIN_USER);
		registry.add("spring.flyway.password", () -> ADMIN_PASSWORD);
		registry.add("spring.flyway.placeholders.lms-server-app-password", () -> APP_PASSWORD);
	}

	@AfterAll
	static void dropTestDatabase() {
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
			NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic()).build();
			decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
			return decoder;
		}

	}

	@Test
	@DisplayName("Toàn bộ migration áp sạch trên database trống, gồm V30 và migration RLS V31 chỉ có ở PostgreSQL")
	void migrationsApplyCleanlyOnEmptyDatabase() {
		List<String> versions = adminJdbc().queryForList(
				"select version from flyway_schema_history where success order by installed_rank", String.class);
		assertEquals(List.of("1", "2", "3", "10", "11", "20", "21", "30", "31", "40", "41", "50", "51", "60", "61", "80"),
				versions);
	}

	@Test
	@DisplayName("RLS theo chủ sở hữu chặn ở mức database qua kết nối của role runtime: chưa đặt app.current_sub thì không thấy hàng nào của bốn bảng, đặt sub của một chủ thì chỉ thấy hàng của chủ đó, ghi hàng mang owner_sub khác bị từ chối")
	void rlsLimitsQuestionRowsByOwnerSub() throws Exception {
		UUID bankA = UUID.randomUUID();
		UUID bankB = UUID.randomUUID();
		UUID questionA = UUID.randomUUID();
		UUID versionA = UUID.randomUUID();
		adminJdbc().update("insert into question_bank (id, owner_sub, name) values (?, ?, ?)", bankA, "gv-pg-a",
				"bank-a");
		adminJdbc().update("insert into question_bank (id, owner_sub, name) values (?, ?, ?)", bankB, "gv-pg-b",
				"bank-b");
		adminJdbc().update("insert into question (id, owner_sub, bank_id) values (?, ?, ?)", questionA, "gv-pg-a",
				bankA);
		adminJdbc().update(
				"insert into question_version (id, question_id, owner_sub, version_number, status, question_type,"
						+ " content_contract_version, stem, content_hash) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
				versionA, questionA, "gv-pg-a", 1, "draft", "essay_text", 1, "than-cau", "hash");
		adminJdbc().update(
				"insert into bank_entry (id, bank_id, question_id, question_version_id, owner_sub)"
						+ " values (?, ?, ?, ?, ?)",
				UUID.randomUUID(), bankA, questionA, versionA, "gv-pg-a");
		try (Connection connection = appConnection()) {
			connection.setAutoCommit(false);
			assertEquals(0, countVisible(connection, "question_bank"));
			assertEquals(0, countVisible(connection, "question"));
			assertEquals(0, countVisible(connection, "question_version"));
			assertEquals(0, countVisible(connection, "bank_entry"));
			connection.rollback();
			setConfig(connection, "app.current_sub", "gv-pg-a");
			assertEquals(1, countVisible(connection, "question_bank"));
			assertEquals(1, countVisible(connection, "question"));
			assertEquals(1, countVisible(connection, "question_version"));
			assertEquals(1, countVisible(connection, "bank_entry"));
			assertThrows(SQLException.class, () -> {
				try (PreparedStatement insert = connection.prepareStatement(
						"insert into question_bank (id, owner_sub, name) values (?, ?, ?)")) {
					insert.setObject(1, UUID.randomUUID());
					insert.setString(2, "gv-pg-b");
					insert.setString(3, "bank-gia-mao");
					insert.executeUpdate();
				}
			});
			connection.rollback();
		}
	}

	@Test
	@DisplayName("Luồng ngân hàng câu hỏi chạy trọn trên PostgreSQL bằng role runtime: giáo viên tạo được ngân hàng và câu hỏi của mình (WITH CHECK đạt), hàng lưu mang đúng owner_sub của người tạo, và giáo viên khác không thấy gì của họ")
	void questionFlowRunsOnRealPostgresWithRuntimeRole() throws Exception {
		String teacherA = mint("gv-pg-flow-a",
				Map.of("realm_access", Map.of("roles", List.of("personal-author"))));
		String teacherB = mint("gv-pg-flow-b",
				Map.of("realm_access", Map.of("roles", List.of("personal-author"))));
		MvcResult bank = this.mockMvc
			.perform(post("/api/question-banks").header("Authorization", "Bearer " + teacherA)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"ngan-hang-tren-pg\"}"))
			.andExpect(status().isOk())
			.andReturn();
		String bankId = JsonPath.read(bank.getResponse().getContentAsString(), "$.id");
		MvcResult question = this.mockMvc
			.perform(post("/api/questions").header("Authorization", "Bearer " + teacherA)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"bankId\":\"" + bankId + "\",\"questionType\":\"essay_text\","
						+ "\"stem\":\"than-cau-tren-pg\",\"rubric\":\"tieu-chi-tren-pg\"}"))
			.andExpect(status().isOk())
			.andReturn();
		String questionId = JsonPath.read(question.getResponse().getContentAsString(), "$.id");
		String storedOwner = adminJdbc().queryForObject(
				"select owner_sub from question_bank where id = ?", String.class, UUID.fromString(bankId));
		assertEquals("gv-pg-flow-a", storedOwner);
		this.mockMvc.perform(get("/api/question-banks").header("Authorization", "Bearer " + teacherB))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("ngan-hang-tren-pg"))));
		this.mockMvc
			.perform(get("/api/questions/" + questionId).header("Authorization", "Bearer " + teacherB))
			.andExpect(status().isNotFound());
		this.mockMvc.perform(get("/api/questions/" + questionId).header("Authorization", "Bearer " + teacherA))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("than-cau-tren-pg")));
	}

	private int countVisible(Connection connection, String table) throws SQLException {
		try (Statement statement = connection.createStatement();
				var rs = statement.executeQuery("select count(*) from " + table + " where owner_sub like 'gv-pg-%'")) {
			rs.next();
			return rs.getInt(1);
		}
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
