package com.giaokhoa.server.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Lớp RLS cho tám bảng lõi là cơ chế riêng của PostgreSQL nên bộ test H2 không thực thi được; bộ này chạy ứng
// dụng trên PostgreSQL online chuyên cho test qua một database riêng của lượt: database được tạo ngay trước lượt
// test và bị xoá ngay sau lượt, không đụng vào bất kỳ database sẵn có nào. Thông tin kết nối quản trị lấy từ biến
// môi trường LMS_TEST_PG_URL, LMS_TEST_PG_USER và LMS_TEST_PG_PASSWORD; chưa đặt biến thì toàn bộ lớp bị bỏ qua,
// và kết quả bỏ qua đó không được tính là đạt. Dữ kiện được xếp bằng tài khoản quản trị, khẳng định được đọc qua
// kết nối của chính role runtime lms_server_app.
@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri="
		+ AccessPostgresRlsTests.ISSUER)
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_URL", matches = ".+")
class AccessPostgresRlsTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	private static final String ORG_2_ID = "22222222-2222-2222-2222-222222222222";

	private static final String APP_PASSWORD = "app-secret";

	private static final String ADMIN_BASE_URL = System.getenv("LMS_TEST_PG_URL");

	private static final String ADMIN_USER = System.getenv("LMS_TEST_PG_USER");

	private static final String ADMIN_PASSWORD = System.getenv("LMS_TEST_PG_PASSWORD");

	private static final String TEST_DATABASE = "lms_server_test_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 12);

	private static final List<String> CORE_TABLES = List.of("resource", "resource_version", "resource_grant",
			"collection_assignment", "resource_dependency", "handover_request", "submission", "submission_item");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		createTestDatabase();
		registry.add("spring.datasource.url", AccessPostgresRlsTests::testDatabaseUrl);
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
	@DisplayName("Migration của lõi quyền áp sạch trên PostgreSQL thật: danh sách phiên bản gồm V10 của lõi và V11 RLS của vendor")
	void migrationsIncludeAccessCore() {
		List<String> versions = adminJdbc().queryForList(
				"select version from flyway_schema_history where success order by installed_rank", String.class);
		assertEquals(List.of("1", "2", "3", "10", "11", "20", "21", "30", "31", "40", "41", "50", "51", "60", "61", "80", "90", "91", "92", "93"),
				versions);
	}

	@Test
	@DisplayName("RLS khoá cả tám bảng lõi theo khoá phân vùng của giao dịch: chưa đặt biến thì không thấy hàng nào, đặt khoá của một trung tâm thì chỉ thấy đúng phần của trung tâm đó, ghi hàng mang khoá khác bị từ chối, và khoá cá nhân chỉ mở đúng phần cá nhân")
	void rlsLimitsAccessCoreTables() throws Exception {
		arrange();
		try (Connection connection = appConnection()) {
			connection.setAutoCommit(false);
			for (String table : CORE_TABLES) {
				assertEquals(0, count(connection, table), table + " phải trống khi chưa đặt khoá phân vùng");
			}
			connection.rollback();
			setConfig(connection, "app.partition_key", ORG_1_ID);
			assertEquals(1, count(connection, "resource"));
			assertEquals(1, count(connection, "resource_version"));
			assertEquals(1, count(connection, "resource_grant"));
			assertEquals(1, count(connection, "collection_assignment"));
			assertEquals(1, count(connection, "resource_dependency"));
			assertEquals(1, count(connection, "handover_request"));
			assertEquals(1, count(connection, "submission"));
			assertEquals(1, count(connection, "submission_item"));
			assertThrows(SQLException.class, () -> {
				try (PreparedStatement insert = connection.prepareStatement(
						"insert into resource (id, resource_type, workspace_kind, owner_type, owner_organization_id, partition_key, custody_kind, created_by_sub) values (?, ?, ?, ?, ?, ?, ?, ?)")) {
					insert.setObject(1, UUID.randomUUID());
					insert.setString(2, "question");
					insert.setString(3, "center");
					insert.setString(4, "organization");
					insert.setString(5, ORG_2_ID);
					insert.setString(6, ORG_2_ID);
					insert.setString(7, "owned");
					insert.setString(8, "rls-access");
					insert.executeUpdate();
				}
			});
			connection.rollback();
			setConfig(connection, "app.partition_key", "user:gv-rls");
			assertEquals(1, count(connection, "resource"));
			assertEquals(1, count(connection, "resource_grant"));
			assertEquals(0, count(connection, "resource_version"));
			assertEquals(0, count(connection, "collection_assignment"));
			assertEquals(0, count(connection, "handover_request"));
			connection.rollback();
		}
	}

	private void arrange() {
		JdbcTemplate admin = adminJdbc();
		UUID orgResource = UUID.randomUUID();
		UUID orgVersion = UUID.randomUUID();
		UUID orgResourceB = UUID.randomUUID();
		UUID personalResource = UUID.randomUUID();
		UUID handover = UUID.randomUUID();
		UUID submission = UUID.randomUUID();
		insertResource(admin, orgResource, ORG_1_ID, "center", "organization", null, ORG_1_ID);
		insertResource(admin, orgResourceB, ORG_2_ID, "center", "organization", null, ORG_2_ID);
		insertResource(admin, personalResource, "user:gv-rls", "personal", "user", "gv-rls", null);
		insertVersion(admin, orgVersion, orgResource, ORG_1_ID);
		UUID versionB = UUID.randomUUID();
		insertVersion(admin, versionB, orgResourceB, ORG_2_ID);
		admin.update(
				"insert into resource_grant (id, resource_id, resource_version_id, grantee_type, grantee_organization_id, action, mode, starts_at, grantor_sub, source_partition_key, partition_key) values (?, ?, ?, ?, ?, ?, ?, current_timestamp, ?, ?, ?)",
				UUID.randomUUID(), orgResource, orgVersion, "organization", ORG_1_ID, "view_content", "long_term",
				"rls-access", "user:gv-rls", ORG_1_ID);
		admin.update(
				"insert into resource_grant (id, resource_id, resource_version_id, grantee_type, grantee_user_sub, action, starts_at, grantor_sub, source_partition_key, partition_key) values (?, ?, ?, ?, ?, ?, current_timestamp, ?, ?, ?)",
				UUID.randomUUID(), orgResource, orgVersion, "user", "gv-rls", "use", "rls-access", ORG_2_ID,
				"user:gv-rls");
		admin.update(
				"insert into collection_assignment (id, organization_id, partition_key, collection_id, resource_type, assignee_sub, assignment_role, assigned_by_sub) values (?, ?, ?, ?, ?, ?, ?, ?)",
				UUID.randomUUID(), ORG_1_ID, ORG_1_ID, orgResource, "question", "hv-rls", "bank-manager", "rls-access");
		admin.update(
				"insert into resource_dependency (id, resource_id, resource_version_id, depends_on_resource_id, depends_on_version_id, partition_key) values (?, ?, ?, ?, ?, ?)",
				UUID.randomUUID(), orgResource, orgVersion, orgResource, orgVersion, ORG_1_ID);
		admin.update(
				"insert into handover_request (id, organization_id, partition_key, requested_teacher_sub, status, created_by_sub) values (?, ?, ?, ?, ?, ?)",
				handover, ORG_1_ID, ORG_1_ID, "gv-rls", "open", "rls-access");
		admin.update(
				"insert into submission (id, handover_request_id, partition_key, submitter_sub, submitted_at) values (?, ?, ?, ?, current_timestamp)",
				submission, handover, ORG_1_ID, "gv-rls");
		admin.update(
				"insert into submission_item (id, submission_id, partition_key, source_resource_id, source_version_id, received_resource_id, received_version_id) values (?, ?, ?, ?, ?, ?, ?)",
				UUID.randomUUID(), submission, ORG_1_ID, personalResource, orgVersion, orgResource, orgVersion);
	}

	private void insertResource(JdbcTemplate admin, UUID id, String partitionKey, String workspace, String ownerType,
			String ownerUserSub, String ownerOrganizationId) {
		admin.update(
				"insert into resource (id, resource_type, workspace_kind, owner_type, owner_user_sub, owner_organization_id, partition_key, custody_kind, created_by_sub) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
				id, "question", workspace, ownerType, ownerUserSub, ownerOrganizationId, partitionKey, "owned",
				"rls-access");
	}

	private void insertVersion(JdbcTemplate admin, UUID id, UUID resourceId, String partitionKey) {
		admin.update(
				"insert into resource_version (id, resource_id, partition_key, version_number, status, created_by_sub) values (?, ?, ?, ?, ?, ?)",
				id, resourceId, partitionKey, 1, "draft", "rls-access");
	}

	private int count(Connection connection, String table) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
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

}
