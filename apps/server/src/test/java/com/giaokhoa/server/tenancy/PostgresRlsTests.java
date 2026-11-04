package com.giaokhoa.server.tenancy;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

// Lớp RLS và việc tách role runtime là cơ chế riêng của PostgreSQL nên bộ test H2 không thực thi được; bộ này
// chạy ứng dụng trên PostgreSQL 18 online của stack qua một database test riêng: database được tạo ngay trước
// lượt test và bị xoá ngay sau lượt dù test đạt hay hỏng, không đụng vào bất kỳ database sẵn có nào. Thông tin
// kết nối quản trị lấy từ biến môi trường LMS_TEST_PG_URL (JDBC URL không kèm tên database, ví dụ
// jdbc:postgresql://dev-pg.fuckr.net:5433), LMS_TEST_PG_USER và LMS_TEST_PG_PASSWORD; chưa đặt biến thì toàn bộ
// lớp bị bỏ qua, và kết quả bỏ qua đó không được tính là đạt. Topology nối đúng mô hình chạy thật: Flyway chạy
// bằng tài khoản quản trị được cấp, datasource của ứng dụng chạy bằng role runtime lms_server_app mà migration
// V3 tạo.
@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + PostgresRlsTests.ISSUER)
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_URL", matches = ".+")
class PostgresRlsTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	private static final String ORG_2_ID = "22222222-2222-2222-2222-222222222222";

	private static final String APP_PASSWORD = "app-secret";

	private static final Pattern ID_PATTERN = Pattern.compile("\"id\":\"([^\"]+)\"");

	private static final String ADMIN_BASE_URL = System.getenv("LMS_TEST_PG_URL");

	private static final String ADMIN_USER = System.getenv("LMS_TEST_PG_USER");

	private static final String ADMIN_PASSWORD = System.getenv("LMS_TEST_PG_PASSWORD");

	private static final String TEST_DATABASE = "lms_server_test_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 12);

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		createTestDatabase();
		registry.add("spring.datasource.url", PostgresRlsTests::testDatabaseUrl);
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
	private JdbcTemplate jdbcTemplate;

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
	@DisplayName("Toàn bộ migration áp sạch trên database trống, gồm cả migration RLS chỉ có ở PostgreSQL")
	void migrationsApplyCleanlyOnEmptyDatabase() {
		List<String> versions = adminJdbc().queryForList(
				"select version from flyway_schema_history where success order by installed_rank", String.class);
		assertEquals(List.of("1", "2", "3"), versions);
	}

	@Test
	@DisplayName("Role runtime không phải superuser, không có BYPASSRLS, không sở hữu bảng nào, và datasource của ứng dụng đang chạy bằng chính role đó")
	void runtimeRoleCannotBypassRls() {
		Boolean superuser = adminJdbc().queryForObject(
				"select rolsuper from pg_roles where rolname = 'lms_server_app'", Boolean.class);
		Boolean bypassRls = adminJdbc().queryForObject(
				"select rolbypassrls from pg_roles where rolname = 'lms_server_app'", Boolean.class);
		Integer ownedTables = adminJdbc().queryForObject(
				"select count(*) from pg_tables where tableowner = 'lms_server_app'", Integer.class);
		assertEquals(Boolean.FALSE, superuser);
		assertEquals(Boolean.FALSE, bypassRls);
		assertEquals(0, ownedTables);
		assertEquals("lms_server_app", this.jdbcTemplate.queryForObject("select current_user", String.class));
	}

	@Test
	@DisplayName("RLS chặn ở mức database qua kết nối của role runtime: chưa đặt biến ngữ cảnh thì không thấy hàng nào, đặt khoá của một trung tâm thì chỉ thấy hàng của trung tâm đó và ghi hàng mang khoá khác bị từ chối, app_user chỉ hiện đúng hồ sơ của sub đã đặt")
	void rlsLimitsRowsByTransactionContext() throws Exception {
		adminJdbc().update("insert into tenant_record (id, partition_key, owner_sub, content) values (?, ?, ?, ?)",
				UUID.randomUUID(), ORG_1_ID, "rls-owner", "rls-record-tt1");
		adminJdbc().update("insert into tenant_record (id, partition_key, owner_sub, content) values (?, ?, ?, ?)",
				UUID.randomUUID(), ORG_2_ID, "rls-owner", "rls-record-tt2");
		adminJdbc().update("insert into app_user (id, keycloak_sub) values (?, ?)", UUID.randomUUID(), "rls-user-a");
		adminJdbc().update("insert into app_user (id, keycloak_sub) values (?, ?)", UUID.randomUUID(), "rls-user-b");
		try (Connection connection = appConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement();
					ResultSet rs = statement.executeQuery(
							"select count(*) from tenant_record where content like 'rls-record-%'")) {
				assertTrue(rs.next());
				assertEquals(0, rs.getInt(1));
			}
			connection.rollback();
			setConfig(connection, "app.partition_key", ORG_2_ID);
			List<String> visible = new ArrayList<>();
			try (Statement statement = connection.createStatement();
					ResultSet rs = statement.executeQuery(
							"select content from tenant_record where content like 'rls-record-%'")) {
				while (rs.next()) {
					visible.add(rs.getString(1));
				}
			}
			assertEquals(List.of("rls-record-tt2"), visible);
			assertThrows(SQLException.class, () -> {
				try (PreparedStatement insert = connection.prepareStatement(
						"insert into tenant_record (id, partition_key, owner_sub, content) values (?, ?, ?, ?)")) {
					insert.setObject(1, UUID.randomUUID());
					insert.setString(2, ORG_1_ID);
					insert.setString(3, "rls-owner");
					insert.setString(4, "rls-record-le");
					insert.executeUpdate();
				}
			});
			connection.rollback();
			setConfig(connection, "app.current_sub", "rls-user-a");
			List<String> profiles = new ArrayList<>();
			try (Statement statement = connection.createStatement();
					ResultSet rs = statement.executeQuery(
							"select keycloak_sub from app_user where keycloak_sub like 'rls-user-%'")) {
				while (rs.next()) {
					profiles.add(rs.getString(1));
				}
			}
			assertEquals(List.of("rls-user-a"), profiles);
			connection.rollback();
		}
	}

	@Test
	@DisplayName("Luồng hồ sơ chạy trọn trên PostgreSQL bằng role runtime: lần gọi đầu tự tạo hồ sơ (gồm giao dịch REQUIRES_NEW) và lần gọi sau trả đúng hồ sơ đó, chứng tỏ biến ngữ cảnh RLS được đặt lại ở mọi giao dịch")
	void profileFlowKeepsRlsContextOnRealPostgres() throws Exception {
		String token = mint("rls-profile-user", Map.of());
		MvcResult first = this.mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"keycloakSub\":\"rls-profile-user\"")))
				.andReturn();
		MvcResult second = this.mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		assertEquals(extractId(first), extractId(second));
	}

	@Test
	@DisplayName("Ghi record ở chế độ trung tâm qua ứng dụng vượt qua được WITH CHECK của RLS và hàng mang đúng khoá của trung tâm trong database")
	void centerRecordWritePassesRlsWithCheck() throws Exception {
		String token = mint("gv-rls", Map.of("organization",
				Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-teacher")))));
		this.mockMvc.perform(post("/api/tenancy-check/records").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", "trung-tam-1")
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"rls-ghi-tu-app\"}"))
				.andExpect(status().isOk());
		String stored = adminJdbc().queryForObject(
				"select partition_key from tenant_record where content = 'rls-ghi-tu-app'", String.class);
		assertEquals(ORG_1_ID, stored);
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

	private String extractId(MvcResult result) throws Exception {
		Matcher matcher = ID_PATTERN.matcher(result.getResponse().getContentAsString());
		if (!matcher.find()) {
			throw new AssertionError("response has no id: " + result.getResponse().getContentAsString());
		}
		return matcher.group(1);
	}

	private Map<String, Object> orgEntry(String id, List<String> roles) {
		return Map.of("id", id, "realm_access", Map.of("roles", roles));
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
