package com.giaokhoa.server.assignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

// Kiểm thử lớp bảo vệ theo hàng (RLS) của bảng tệp bài làm trên PostgreSQL thật: cùng khuôn với
// AssignmentPostgresRlsTests (một database riêng cho lượt chạy, tạo và xoá trong chính lượt, kết nối bằng
// đúng vai trò ứng dụng lms_server_app). Điều kiện chạy: đủ ba biến môi trường LMS_TEST_PG_URL,
// LMS_TEST_PG_USER, LMS_TEST_PG_PASSWORD trỏ tới PostgreSQL test chuyên dụng.
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_PASSWORD", matches = ".+")
class SubmissionFilePostgresRlsTests {

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
				statement.execute("insert into file_blob (id, storage_key, content_type, size_bytes, "
						+ "checksum_sha256, status, uploaded_by_sub, partition_key) values "
						+ "('99999999-9999-9999-9999-999999999993', 'rls-test-blob', 'image/png', 1024, "
						+ "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'READY', "
						+ "'hs-trung-tam-1', '" + PARTITION_A + "')");
				statement.execute("insert into submission_file (id, partition_key, attempt_id, "
						+ "upload_session_id, blob_id, display_name, content_type, size_bytes, status, "
						+ "attached_at) values ('99999999-9999-9999-9999-999999999991', '" + PARTITION_A
						+ "', 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1', "
						+ "'99999999-9999-9999-9999-999999999992', '99999999-9999-9999-9999-999999999993', "
						+ "'bai-lam.png', 'image/png', 1024, 'ready', now())");
			}
			connection.commit();
		}
	}

	private static Connection appConnection() throws SQLException {
		return DriverManager.getConnection(databaseUrl, "lms_server_app", "app-secret");
	}

	private static int countInPartition(String partitionKey) throws SQLException {
		try (Connection connection = appConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				if (partitionKey != null) {
					statement.execute("select set_config('app.partition_key', '" + partitionKey + "', true)");
				}
				try (ResultSet result = statement.executeQuery("select count(*) from submission_file")) {
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
	@DisplayName("RLS cô lập bảng tệp bài làm: phân vùng khác và phiên không đặt phân vùng đều không thấy hàng; ghi chéo phân vùng bị từ chối")
	void rlsIsolatesSubmissionFiles() throws SQLException {
		assertEquals(1, countInPartition(PARTITION_A));
		assertEquals(0, countInPartition(PARTITION_B));
		assertEquals(0, countInPartition(null));
		try (Connection connection = appConnection()) {
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				statement.execute("select set_config('app.partition_key', '" + PARTITION_B + "', true)");
				assertThrows(SQLException.class, () -> statement.execute(
						"insert into submission_file (id, partition_key, attempt_id, upload_session_id, "
								+ "display_name, status) values ('99999999-9999-9999-9999-999999999999', '"
								+ PARTITION_A + "', 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1', "
								+ "'99999999-9999-9999-9999-999999999998', 'bai-lam.png', 'processing')"));
			}
			finally {
				connection.rollback();
			}
		}
	}

}
