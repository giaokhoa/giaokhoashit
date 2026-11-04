package com.giaokhoa.server.files;

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

// Lớp cách ly của bốn bảng tệp và bảng khối nội dung là cơ chế riêng của PostgreSQL nên bộ test H2 không thực
// thi được; bộ này chạy ứng dụng trên PostgreSQL online chuyên cho test qua một database riêng của lượt:
// database được tạo ngay trước lượt test và bị xoá ngay sau lượt. Thông tin kết nối quản trị lấy từ biến môi
// trường LMS_TEST_PG_URL, LMS_TEST_PG_USER và LMS_TEST_PG_PASSWORD; chưa đặt biến thì toàn bộ lớp bị bỏ qua,
// và kết quả bỏ qua đó không được tính là đạt. Dữ kiện được xếp bằng tài khoản quản trị, khẳng định được đọc
// qua kết nối của chính role runtime lms_server_app.
@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + FilePostgresRlsTests.ISSUER)
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_URL", matches = ".+")
class FilePostgresRlsTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String PARTITION_A = "user:gv-tep-a";

	private static final String PARTITION_B = "user:gv-tep-b";

	private static final String APP_PASSWORD = "app-secret";

	private static final String ADMIN_BASE_URL = System.getenv("LMS_TEST_PG_URL");

	private static final String ADMIN_USER = System.getenv("LMS_TEST_PG_USER");

	private static final String ADMIN_PASSWORD = System.getenv("LMS_TEST_PG_PASSWORD");

	private static final String TEST_DATABASE = "lms_server_test_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 12);

	private static final List<String> FILE_TABLES = List.of("file_blob", "upload_session", "file_link",
			"file_audit_log");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		createTestDatabase();
		registry.add("spring.datasource.url", FilePostgresRlsTests::testDatabaseUrl);
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
	@DisplayName("Chain migration trên PostgreSQL thật chạy đủ tới V96.1: khối nội dung V90–V91, nền tệp V92–V93, bản ghi render V94 và tệp bài làm V96–V96.1 nằm trong danh sách phiên bản đã áp thành công")
	void migrationChainEndsWithFileFoundation() {
		List<String> versions = adminJdbc().queryForList(
				"select version from flyway_schema_history where success order by installed_rank", String.class);
		assertEquals(List.of("1", "2", "3", "10", "11", "20", "21", "30", "31", "40", "41", "50", "51", "60", "61",
				"80", "90", "91", "92", "93", "94", "96", "96.1", "97"), versions);
	}

	@Test
	@DisplayName("RLS khoá cả bốn bảng tệp theo khoá phân vùng của giao dịch: chưa đặt biến thì không thấy hàng nào, đặt khoá của một phân vùng thì chỉ thấy đúng phần của phân vùng đó, ghi hàng mang khoá khác bị từ chối")
	void rlsLimitsFileTablesByPartition() throws Exception {
		arrange();
		try (Connection connection = appConnection()) {
			connection.setAutoCommit(false);
			for (String table : FILE_TABLES) {
				assertEquals(0, count(connection, table), table + " phải trống khi chưa đặt khoá phân vùng");
			}
			connection.rollback();
			setConfig(connection, "app.partition_key", PARTITION_A);
			for (String table : FILE_TABLES) {
				assertEquals(1, count(connection, table), table);
			}
			assertThrows(SQLException.class, () -> {
				try (PreparedStatement insert = connection.prepareStatement(
						"insert into file_blob (id, storage_key, content_type, size_bytes, checksum_sha256, status, uploaded_by_sub, partition_key) values (?, ?, ?, ?, ?, ?, ?, ?)")) {
					insert.setObject(1, UUID.randomUUID());
					insert.setString(2, "blobs/khac-phan-vung");
					insert.setString(3, "image/png");
					insert.setLong(4, 3L);
					insert.setString(5, "abc");
					insert.setString(6, "READY");
					insert.setString(7, "gv-tep-b");
					insert.setString(8, PARTITION_B);
					insert.executeUpdate();
				}
			});
			connection.rollback();
			setConfig(connection, "app.partition_key", PARTITION_B);
			for (String table : FILE_TABLES) {
				assertEquals(1, count(connection, table), table);
			}
			connection.rollback();
		}
	}

	@Test
	@DisplayName("RLS của bảng khối nội dung khoá theo chủ sở hữu: chưa đặt biến chủ thì không thấy hàng nào, đặt đúng chủ thì chỉ thấy khối của chủ đó")
	void rlsLimitsQuestionBlocksByOwner() throws Exception {
		JdbcTemplate admin = adminJdbc();
		UUID versionA = insertQuestionWithVersion(admin, "gv-khoi-a");
		UUID versionB = insertQuestionWithVersion(admin, "gv-khoi-b");
		for (UUID versionId : List.of(versionA, versionB)) {
			String owner = versionId.equals(versionA) ? "gv-khoi-a" : "gv-khoi-b";
			admin.update(
					"insert into question_version_block (id, question_version_id, owner_sub, part, position, kind, text_content) values (?, ?, ?, ?, ?, ?, ?)",
					UUID.randomUUID(), versionId, owner, "stem", 0, "text", "than cau");
		}
		try (Connection connection = appConnection()) {
			connection.setAutoCommit(false);
			assertEquals(0, count(connection, "question_version_block"));
			connection.rollback();
			setConfig(connection, "app.current_sub", "gv-khoi-a");
			assertEquals(1, count(connection, "question_version_block"));
			connection.rollback();
		}
	}

	private void arrange() {
		JdbcTemplate admin = adminJdbc();
		UUID resource = UUID.randomUUID();
		UUID version = UUID.randomUUID();
		admin.update(
				"insert into resource (id, resource_type, workspace_kind, owner_type, owner_user_sub, partition_key, custody_kind, created_by_sub) values (?, ?, ?, ?, ?, ?, ?, ?)",
				resource, "question", "personal", "user", "gv-tep-a", PARTITION_A, "owned", "gv-tep-a");
		admin.update(
				"insert into resource_version (id, resource_id, partition_key, version_number, status, content_hash, created_by_sub) values (?, ?, ?, ?, ?, ?, ?)",
				version, resource, PARTITION_A, 1, "draft", "hash", "gv-tep-a");
		for (String partition : List.of(PARTITION_A, PARTITION_B)) {
			String uploader = partition.equals(PARTITION_A) ? "gv-tep-a" : "gv-tep-b";
			UUID blob = UUID.randomUUID();
			admin.update(
					"insert into file_blob (id, storage_key, content_type, size_bytes, checksum_sha256, status, uploaded_by_sub, partition_key) values (?, ?, ?, ?, ?, ?, ?, ?)",
					blob, "blobs/" + blob, "image/png", 3L, "abc", "READY", uploader, partition);
			admin.update(
					"insert into upload_session (id, uploader_sub, partition_key, target_resource_id, target_version_id, display_name, status, expires_at) values (?, ?, ?, ?, ?, ?, ?, current_timestamp + interval '1 day')",
					UUID.randomUUID(), uploader, partition, resource, version, "tep.png", "COMPLETED");
			// Hàng của phân vùng B trỏ về cùng một tài nguyên là dữ kiện xếp tay chỉ để đếm hàng theo chính
			// sách RLS; khoá ngoại tổ hợp của file_link neo theo resource_id nên vẫn hợp lệ về mặt tham chiếu.
			admin.update(
					"insert into file_link (id, resource_id, resource_version_id, position, blob_id, display_name, partition_key, created_by_sub) values (?, ?, ?, ?, ?, ?, ?, ?)",
					UUID.randomUUID(), resource, version, "content", blob, "tep.png", partition, uploader);
			admin.update(
					"insert into file_audit_log (id, actor_sub, action, blob_id, partition_key) values (?, ?, ?, ?, ?)",
					UUID.randomUUID(), uploader, "attach", blob, partition);
		}
	}

	private UUID insertQuestionWithVersion(JdbcTemplate admin, String ownerSub) {
		UUID bank = UUID.randomUUID();
		UUID question = UUID.randomUUID();
		UUID version = UUID.randomUUID();
		admin.update("insert into question_bank (id, owner_sub, name) values (?, ?, ?)", bank, ownerSub,
				"ngan-hang-" + ownerSub);
		admin.update("insert into question (id, owner_sub, bank_id) values (?, ?, ?)", question, ownerSub, bank);
		admin.update(
				"insert into question_version (id, question_id, owner_sub, version_number, status, question_type, content_contract_version, stem, rubric, content_hash) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				version, question, ownerSub, 1, "draft", "essay_text", 2, "than", "rubric", "hash");
		return version;
	}

	private JdbcTemplate adminJdbc() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(testDatabaseUrl());
		dataSource.setUsername(ADMIN_USER);
		dataSource.setPassword(ADMIN_PASSWORD);
		return new JdbcTemplate(dataSource);
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

	private int count(Connection connection, String table) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("select count(*) from " + table)) {
			result.next();
			return result.getInt(1);
		}
	}

}
