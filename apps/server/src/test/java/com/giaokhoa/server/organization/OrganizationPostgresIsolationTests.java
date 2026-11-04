package com.giaokhoa.server.organization;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.giaokhoa.server.tenancy.MembershipChecker;

// Cách ly ở tầng cơ sở dữ liệu của ba bảng khối tổ chức dạy học là cơ chế riêng của PostgreSQL (Row Level
// Security) nên bộ test H2 không thực thi được; bộ này chạy trên PostgreSQL test online riêng theo đúng khuôn
// của PostgresRlsTests: một database test riêng được tạo ngay trước lượt và bị xoá ngay sau lượt, Flyway chạy
// bằng tài khoản quản trị lấy từ biến môi trường LMS_TEST_PG_URL (JDBC URL không kèm tên database, ví dụ
// jdbc:postgresql://127.0.0.1:15434), LMS_TEST_PG_USER và LMS_TEST_PG_PASSWORD, còn datasource của ứng dụng
// chạy bằng role runtime lms_server_app. Chưa đặt biến thì toàn bộ lớp bị bỏ qua, và kết quả bỏ qua đó không
// được tính là đạt.
@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri="
		+ OrganizationPostgresIsolationTests.ISSUER)
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_URL", matches = ".+")
class OrganizationPostgresIsolationTests {

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
		registry.add("spring.datasource.url", OrganizationPostgresIsolationTests::testDatabaseUrl);
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

	@MockitoBean
	private MembershipChecker membershipChecker;

	@MockitoBean
	private OrganizationDirectory directory;

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
	@DisplayName("Tiêu chí 11 (phần migration): database trống chạy sạch toàn bộ migration gồm dãy V20–V29 của khối tổ chức dạy học, không có migration nào lỗi")
	void blockMigrationsApplyCleanly() {
		List<String> versions = adminJdbc().queryForList(
				"select version from flyway_schema_history where success order by installed_rank", String.class);
		assertTrue(versions.containsAll(List.of("20", "21")), "thiếu migration của khối: " + versions);
		Integer failed = adminJdbc().queryForObject(
				"select count(*) from flyway_schema_history where not success", Integer.class);
		assertEquals(0, failed);
	}

	@Test
	@DisplayName("Tiêu chí 10: qua kết nối của role runtime — chưa đặt biến phân vùng thì cả ba bảng course, teaching_class, class_member trả 0 hàng; đặt biến là trung tâm B thì chỉ thấy hàng của B; ghi hàng mang phân vùng A trong giao dịch có biến là B bị chính sách WITH CHECK từ chối")
	void criterion10RlsIsolationOnThreeTables() throws Exception {
		UUID courseA = UUID.randomUUID();
		UUID courseB = UUID.randomUUID();
		UUID classA = UUID.randomUUID();
		UUID classB = UUID.randomUUID();
		adminJdbc().update("insert into course (id, partition_key, name, created_by_sub) values (?, ?, ?, ?)",
				courseA, ORG_1_ID, "c10-course-a", "c10-seed");
		adminJdbc().update("insert into course (id, partition_key, name, created_by_sub) values (?, ?, ?, ?)",
				courseB, ORG_2_ID, "c10-course-b", "c10-seed");
		adminJdbc().update(
				"insert into teaching_class (id, partition_key, course_id, name, created_by_sub) values (?, ?, ?, ?, ?)",
				classA, ORG_1_ID, courseA, "c10-class-a", "c10-seed");
		adminJdbc().update(
				"insert into teaching_class (id, partition_key, course_id, name, created_by_sub) values (?, ?, ?, ?, ?)",
				classB, ORG_2_ID, courseB, "c10-class-b", "c10-seed");
		adminJdbc().update(
				"insert into class_member (id, partition_key, class_id, student_sub, enrolled_by_sub) values (?, ?, ?, ?, ?)",
				UUID.randomUUID(), ORG_1_ID, classA, "c10-student-a", "c10-seed");
		adminJdbc().update(
				"insert into class_member (id, partition_key, class_id, student_sub, enrolled_by_sub) values (?, ?, ?, ?, ?)",
				UUID.randomUUID(), ORG_2_ID, classB, "c10-student-b", "c10-seed");
		try (Connection connection = appConnection()) {
			connection.setAutoCommit(false);
			assertEquals(List.of(), selectStrings(connection, "select name from course where name like 'c10-%'"));
			assertEquals(List.of(),
					selectStrings(connection, "select name from teaching_class where name like 'c10-%'"));
			assertEquals(List.of(), selectStrings(connection,
					"select student_sub from class_member where student_sub like 'c10-%'"));
			connection.rollback();
			setConfig(connection, "app.partition_key", ORG_2_ID);
			assertEquals(List.of("c10-course-b"),
					selectStrings(connection, "select name from course where name like 'c10-%'"));
			assertEquals(List.of("c10-class-b"),
					selectStrings(connection, "select name from teaching_class where name like 'c10-%'"));
			assertEquals(List.of("c10-student-b"), selectStrings(connection,
					"select student_sub from class_member where student_sub like 'c10-%'"));
			assertThrows(SQLException.class, () -> {
				try (PreparedStatement insert = connection.prepareStatement(
						"insert into course (id, partition_key, name, created_by_sub) values (?, ?, ?, ?)")) {
					insert.setObject(1, UUID.randomUUID());
					insert.setString(2, ORG_1_ID);
					insert.setString(3, "c10-course-le");
					insert.setString(4, "c10-seed");
					insert.executeUpdate();
				}
			});
			connection.rollback();
			setConfig(connection, "app.partition_key", ORG_2_ID);
			assertThrows(SQLException.class, () -> {
				try (PreparedStatement insert = connection.prepareStatement(
						"insert into class_member (id, partition_key, class_id, student_sub, enrolled_by_sub) values (?, ?, ?, ?, ?)")) {
					insert.setObject(1, UUID.randomUUID());
					insert.setString(2, ORG_1_ID);
					insert.setObject(3, classA);
					insert.setString(4, "c10-student-le");
					insert.setString(5, "c10-seed");
					insert.executeUpdate();
				}
			});
			connection.rollback();
		}
	}

	@Test
	@DisplayName("Ứng dụng ghi qua role runtime vượt qua được WITH CHECK của RLS trên bảng mới: lớp tạo qua API mang đúng khoá phân vùng của trung tâm trong database")
	void appWritesClassThroughRuntimeRole() throws Exception {
		String token = mint("hai-ngu-canh", Map.of("organization",
				Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-admin")))));
		this.mockMvc
			.perform(post("/api/classes").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", "trung-tam-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Lop C10 Qua App\"}"))
			.andExpect(status().isCreated())
			.andExpect(content().string(containsString("\"name\":\"Lop C10 Qua App\"")));
		String stored = adminJdbc().queryForObject(
				"select partition_key from teaching_class where name = 'Lop C10 Qua App'", String.class);
		assertEquals(ORG_1_ID, stored);
	}

	private JdbcTemplate adminJdbc() {
		return new JdbcTemplate(new DriverManagerDataSource(testDatabaseUrl(), ADMIN_USER, ADMIN_PASSWORD));
	}

	private Connection appConnection() throws SQLException {
		return DriverManager.getConnection(testDatabaseUrl(), "lms_server_app", APP_PASSWORD);
	}

	private List<String> selectStrings(Connection connection, String sql) throws SQLException {
		List<String> values = new ArrayList<>();
		try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
			while (rs.next()) {
				values.add(rs.getString(1));
			}
		}
		return values;
	}

	private void setConfig(Connection connection, String name, String value) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select set_config(?, ?, true)")) {
			statement.setString(1, name);
			statement.setString(2, value);
			statement.execute();
		}
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
