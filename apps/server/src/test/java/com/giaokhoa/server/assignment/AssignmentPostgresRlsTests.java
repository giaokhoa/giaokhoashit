package com.giaokhoa.server.assignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

// Kiểm thử lớp bảo vệ theo hàng (RLS) của ba bảng giao/làm bài trên PostgreSQL thật: cùng khuôn với
// PostgresRlsTests của khung tenancy (một database riêng cho lượt chạy, tạo và xoá trong chính lượt, kết nối
// bằng đúng vai trò ứng dụng lms_server_app và mật khẩu app-secret). Điều kiện chạy: đủ ba biến môi trường
// LMS_TEST_PG_URL, LMS_TEST_PG_USER, LMS_TEST_PG_PASSWORD trỏ tới PostgreSQL test chuyên dụng.
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_PASSWORD", matches = ".+")
class AssignmentPostgresRlsTests {

	private static final String PARTITION_A = "11111111-1111-1111-1111-111111111111";

	private static final String PARTITION_B = "22222222-2222-2222-2222-222222222222";

	private static String databaseName;

	private static String databaseUrl;

	@BeforeAll
	static void createDatabaseAndMigrate() throws SQLException {
		String baseUrl = System.getenv("LMS_TEST_PG_URL");
		String user = System.getenv("LMS_TEST_PG_USER");
		String password = System.getenv("LMS_TEST_PG_PASSWORD");
		databaseName = "lms_server_test_" + UUID.randomUUID().toString().replace("-", "");
		databaseUrl = baseUrl + "/" + databaseName;
		try (Connection admin = DriverManager.getConnection(baseUrl + "/postgres", user, password);
				Statement statement = admin.createStatement()) {
			statement.execute("create database " + databaseName);
		}
		Flyway.configure()
				.dataSource(databaseUrl, user, password)
				.locations("classpath:db/migration", "classpath:db/vendors/postgresql")
				.placeholders(java.util.Map.of("lms-server-app-password", "app-secret"))
				.load()
				.migrate();
		seedPartitionA();
	}

	@AfterAll
	static void dropDatabase() throws SQLException {
		if (databaseName == null) {
			return;
		}
		String baseUrl = System.getenv("LMS_TEST_PG_URL");
		try (Connection admin = DriverManager.getConnection(baseUrl + "/postgres",
				System.getenv("LMS_TEST_PG_USER"), System.getenv("LMS_TEST_PG_PASSWORD"));
				Statement statement = admin.createStatement()) {
			statement.execute("drop database if exists " + databaseName + " with (force)");
		}
	}

	private static void seedPartitionA() throws SQLException {
		try (Connection connection = appConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				statement.execute("select set_config('app.partition_key', '" + PARTITION_A + "', true)");
				statement.execute("select set_config('app.current_sub', 'gv-trung-tam-1', true)");
				statement.execute("insert into teaching_class (id, partition_key, name, teacher_sub, "
						+ "created_by_sub) values ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1', '" + PARTITION_A
						+ "', 'Lớp kiểm chứng RLS', 'gv-trung-tam-1', 'gv-trung-tam-1')");
				statement.execute("insert into exam_assignment (id, partition_key, exam_version_id, "
						+ "exam_resource_id, class_id, opens_at, due_at, assigned_by_sub) values "
						+ "('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', '" + PARTITION_A + "', "
						+ "'dddddddd-dddd-dddd-dddd-ddddddddddd1', 'cccccccc-cccc-cccc-cccc-ccccccccccc1', "
						+ "'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1', now(), now() + interval '1 day', "
						+ "'gv-trung-tam-1')");
				statement.execute("insert into exam_attempt (id, partition_key, assignment_id, student_sub, "
						+ "status, started_at) values ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1', '"
						+ PARTITION_A + "', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 'hs-trung-tam-1', "
						+ "'IN_PROGRESS', now())");
				statement.execute("insert into attempt_answer (id, partition_key, attempt_id, exam_item_id, "
						+ "answer) values ('ffffffff-ffff-ffff-ffff-fffffffffff1', '" + PARTITION_A + "', "
						+ "'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1', "
						+ "'99999999-9999-9999-9999-999999999991', '{\"choiceId\":\"c1\"}')");
			}
			connection.commit();
		}
	}

	private static Connection appConnection() throws SQLException {
		return DriverManager.getConnection(databaseUrl, "lms_server_app", "app-secret");
	}

	private static int countInPartition(String table, String partitionKey) throws SQLException {
		try (Connection connection = appConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				if (partitionKey != null) {
					statement.execute("select set_config('app.partition_key', '" + partitionKey + "', true)");
				}
				try (ResultSet result = statement.executeQuery("select count(*) from " + table)) {
					result.next();
					return result.getInt(1);
				}
			}
			finally {
				connection.rollback();
			}
		}
	}

	@Test
	@DisplayName("Migration của lát giao bài lên bản 50 và 51 nằm trong dãy đã áp dụng trên database trống")
	void migrationsIncludeAssignmentVersions() throws SQLException {
		List<String> versions = new ArrayList<>();
		try (Connection connection = DriverManager.getConnection(databaseUrl, System.getenv("LMS_TEST_PG_USER"),
				System.getenv("LMS_TEST_PG_PASSWORD"));
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(
						"select version from flyway_schema_history where success order by installed_rank")) {
			while (result.next()) {
				versions.add(result.getString(1));
			}
		}
		assertEquals(List.of("1", "2", "3", "10", "11", "20", "21", "30", "31", "40", "41", "50", "51", "60", "61", "80", "90", "91", "92", "93", "94", "96", "96.1", "97"),
				versions);
	}

	@Test
	@DisplayName("RLS cô lập cả ba bảng giao bài, lượt làm và câu trả lời: phân vùng khác và phiên không đặt phân vùng đều không thấy hàng; ghi chéo phân vùng bị từ chối")
	void rlsIsolatesAssignmentTables() throws SQLException {
		for (String table : List.of("exam_assignment", "exam_attempt", "attempt_answer")) {
			assertEquals(1, countInPartition(table, PARTITION_A), table);
			assertEquals(0, countInPartition(table, PARTITION_B), table);
			assertEquals(0, countInPartition(table, null), table);
		}
		try (Connection connection = appConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				statement.execute("select set_config('app.partition_key', '" + PARTITION_B + "', true)");
				assertThrows(SQLException.class, () -> statement.execute(
						"insert into exam_attempt (id, partition_key, assignment_id, student_sub, status, "
								+ "started_at) values ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee9', '"
								+ PARTITION_A + "', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 'hs-khac', "
								+ "'IN_PROGRESS', now())"));
			}
			finally {
				connection.rollback();
			}
		}
		assertTrue(true);
	}

}
