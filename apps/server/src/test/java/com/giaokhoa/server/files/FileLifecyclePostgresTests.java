package com.giaokhoa.server.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.beans.factory.annotation.Autowired;
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

// NT-27 ở dạng đồng thời thật cần khoá hàng của PostgreSQL: một giao dịch giữ liên kết chưa commit tới blob
// đang chờ thu gom trong khi bộ thu gom chạy ở luồng khác trên đúng dịch vụ của ứng dụng. Khung chạy giống
// FilePostgresRlsTests: database riêng của lượt trên PostgreSQL online chuyên cho test, tạo trước lượt và xoá
// sau lượt; chưa đặt biến môi trường LMS_TEST_PG_* thì lớp bị bỏ qua và kết quả bỏ qua không tính là đạt.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri="
		+ FileLifecyclePostgresTests.ISSUER, "app.files.root=./target/test-files-lifecycle-pg" })
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_URL", matches = ".+")
class FileLifecyclePostgresTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String APP_PASSWORD = "app-secret";

	private static final String ADMIN_BASE_URL = System.getenv("LMS_TEST_PG_URL");

	private static final String ADMIN_USER = System.getenv("LMS_TEST_PG_USER");

	private static final String ADMIN_PASSWORD = System.getenv("LMS_TEST_PG_PASSWORD");

	private static final String TEST_DATABASE = "lms_server_test_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 12);

	@Autowired
	private FileService fileService;

	@Autowired
	private BlobStorage storage;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		createTestDatabase();
		registry.add("spring.datasource.url", FileLifecyclePostgresTests::testDatabaseUrl);
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
	@DisplayName("NT-27 (đồng thời thật): một liên kết đang được tạo trong giao dịch chưa commit giữ blob khỏi bị thu gom — bộ thu gom chạy song song không xoá được blob, và sau khi liên kết commit thì blob cùng byte của nó còn nguyên")
	void concurrentLinkCreationBlocksCollection() throws Exception {
		String owner = "gv-dong-thoi";
		String partition = "user:" + owner;
		OffsetDateTime now = OffsetDateTime.now();
		JdbcTemplate admin = adminJdbc();
		UUID resource = UUID.randomUUID();
		UUID version = UUID.randomUUID();
		admin.update(
				"insert into resource (id, resource_type, workspace_kind, owner_type, owner_user_sub, partition_key, custody_kind, created_by_sub) values (?, ?, ?, ?, ?, ?, ?, ?)",
				resource, "question", "personal", "user", owner, partition, "owned", owner);
		admin.update(
				"insert into resource_version (id, resource_id, partition_key, version_number, status, content_hash, created_by_sub) values (?, ?, ?, ?, ?, ?, ?)",
				version, resource, partition, 1, "draft", "hash", owner);
		UUID blobId = UUID.randomUUID();
		String storageKey = "blobs/" + blobId;
		// Blob đã ở đúng trạng thái chờ thu gom: hết hạn lưu, đã ghi mốc và mốc đã qua khoảng chờ an toàn.
		admin.update(
				"insert into file_blob (id, storage_key, content_type, size_bytes, checksum_sha256, status, uploaded_by_sub, partition_key, created_at, updated_at, gc_marked_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
				blobId, storageKey, "image/png", 3L, "abc", "READY", owner, partition, now.minusDays(31), now,
				now.minusHours(25));
		byte[] bytes = "byte-cua-blob-dang-cho-thu-gom".getBytes(StandardCharsets.UTF_8);
		this.storage.promote(this.storage.stage(UUID.randomUUID(), bytes), storageKey);

		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (Connection linking = DriverManager.getConnection(testDatabaseUrl(), ADMIN_USER, ADMIN_PASSWORD)) {
			linking.setAutoCommit(false);
			try (PreparedStatement insert = linking.prepareStatement(
					"insert into file_link (id, resource_id, resource_version_id, position, blob_id, display_name, partition_key, created_by_sub) values (?, ?, ?, ?, ?, ?, ?, ?)")) {
				insert.setObject(1, UUID.randomUUID());
				insert.setObject(2, resource);
				insert.setObject(3, version);
				insert.setString(4, "content");
				insert.setObject(5, blobId);
				insert.setString(6, "tep-sao-chep.png");
				insert.setString(7, partition);
				insert.setString(8, owner);
				insert.executeUpdate();
			}
			// Liên kết chưa commit đã giữ khoá chia sẻ trên hàng blob qua khoá ngoại; bộ thu gom chạy ở luồng
			// khác phải chờ ở câu lệnh xoá của nó thay vì xoá được blob ngay.
			Future<Integer> collection = executor
					.submit(() -> this.fileService.collectOrphanedBlobs(partition, OffsetDateTime.now()));
			waitForCollectorToBlock();
			linking.commit();
			assertEquals(0, collection.get(120, TimeUnit.SECONDS).intValue());
		}
		finally {
			executor.shutdownNow();
		}
		Integer blobs = admin.queryForObject("select count(*) from file_blob where id = ?", Integer.class, blobId);
		assertEquals(1, (blobs != null) ? blobs : 0);
		Integer links = admin.queryForObject("select count(*) from file_link where blob_id = ?", Integer.class,
				blobId);
		assertEquals(1, (links != null) ? links : 0);
		assertArrayEquals(bytes, this.storage.read(storageKey));
	}

	// Chờ tới khi có một phiên của database test đang chờ khoá — dấu hiệu bộ thu gom đã chạm câu lệnh xoá bị
	// giao dịch giữ liên kết chặn. Hết thời gian chờ mà không thấy thì vẫn đi tiếp: khẳng định cuối cùng về
	// blob còn nguyên mới là kết luận, phần chờ này chỉ để hai luồng thật sự chồng nhau.
	private void waitForCollectorToBlock() throws Exception {
		JdbcTemplate admin = adminJdbc();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (System.nanoTime() < deadline) {
			Integer waiting = admin.queryForObject(
					"select count(*) from pg_stat_activity where datname = ? and wait_event_type = 'Lock'",
					Integer.class, TEST_DATABASE);
			if (waiting != null && waiting > 0) {
				return;
			}
			Thread.sleep(200);
		}
	}

	private JdbcTemplate adminJdbc() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(testDatabaseUrl());
		dataSource.setUsername(ADMIN_USER);
		dataSource.setPassword(ADMIN_PASSWORD);
		return new JdbcTemplate(dataSource);
	}

}
