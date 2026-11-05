package com.giaokhoa.server.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.giaokhoa.server.ServerApplication;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

// NT-17 (phần sao lưu/phục hồi) và B12: bộ sao lưu nhất quán của nền tệp là database cùng thư mục kho blob.
// Lượt kiểm chứng này dựng một database nguồn và một thư mục kho riêng, sao lưu cả hai (bản sao đầy đủ phía
// máy chủ PostgreSQL bằng CREATE DATABASE ... TEMPLATE — chương Backup and Restore của tài liệu PostgreSQL —
// và sao chép cây thư mục kho), phá huỷ bản nguồn, phục hồi vào database và thư mục mới, rồi mở lại tệp của
// một phiên bản đã chốt bằng đúng dịch vụ đọc của ứng dụng trên môi trường đã phục hồi. Mọi database đều là
// database riêng của lượt trên PostgreSQL online chuyên cho test và bị xoá sau lượt; không đụng stack DEV.
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_URL", matches = ".+")
class FileRestorePostgresTests {

	private static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String APP_PASSWORD = "app-secret";

	private static final String ADMIN_BASE_URL = System.getenv("LMS_TEST_PG_URL");

	private static final String ADMIN_USER = System.getenv("LMS_TEST_PG_USER");

	private static final String ADMIN_PASSWORD = System.getenv("LMS_TEST_PG_PASSWORD");

	@Configuration
	static class RestoreJwtConfiguration {

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
	@DisplayName("NT-17 (phần sao lưu/phục hồi) và B12: sao lưu database cùng thư mục kho blob rồi phục hồi vào một môi trường riêng mở lại được trọn vẹn tệp của phiên bản đã chốt — byte đọc lại khớp hệt byte gốc và khớp checksum đã ghi trong cơ sở dữ liệu")
	void backupAndRestoreReopensFinalizedContent() throws Exception {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		String sourceDb = "lms_restore_src_" + suffix;
		String backupDb = "lms_restore_bkp_" + suffix;
		String restoredDb = "lms_restore_rst_" + suffix;
		Path sourceRoot = Files.createTempDirectory("lms-files-source-");
		Path backupRoot = Files.createTempDirectory("lms-files-backup-");
		Path restoredRoot = Files.createTempDirectory("lms-files-restored-");
		String owner = "gv-phuc-hoi";
		String partition = "user:" + owner;
		UUID blobId = UUID.randomUUID();
		UUID linkId = UUID.randomUUID();
		byte[] bytes = "noi-dung-tep-cua-phien-ban-da-chot".getBytes(StandardCharsets.UTF_8);
		try {
			createDatabase(sourceDb);
			migrate(sourceDb);
			seedFinalizedFile(sourceDb, partition, owner, blobId, linkId, bytes);
			Path blobFile = sourceRoot.resolve("blobs").resolve("blobs").resolve(blobId.toString());
			Files.createDirectories(blobFile.getParent());
			Files.write(blobFile, bytes);

			// Sao lưu: bản sao đầy đủ của database nguồn và bản sao của toàn bộ cây thư mục kho blob.
			executeOnPostgres("create database " + backupDb + " template " + sourceDb);
			copyTree(sourceRoot, backupRoot);

			// Sự cố: database nguồn và thư mục kho nguồn đều mất.
			dropDatabase(sourceDb);
			deleteTree(sourceRoot);

			// Phục hồi vào môi trường riêng: database mới từ bản sao lưu và thư mục kho mới từ bản sao lưu.
			executeOnPostgres("create database " + restoredDb + " template " + backupDb);
			copyTree(backupRoot, restoredRoot);

			try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ServerApplication.class,
					RestoreJwtConfiguration.class).web(WebApplicationType.NONE).run(
							"--spring.datasource.url=" + ADMIN_BASE_URL + "/" + restoredDb,
							"--spring.datasource.username=lms_server_app",
							"--spring.datasource.password=" + APP_PASSWORD,
							"--spring.flyway.user=" + ADMIN_USER,
							"--spring.flyway.password=" + ADMIN_PASSWORD,
							"--spring.flyway.placeholders.lms-server-app-password=" + APP_PASSWORD,
							"--app.files.root=" + restoredRoot.toAbsolutePath(),
							"--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER)) {
				FileService service = context.getBean(FileService.class);
				TenantContexts.set(new TenantContext(owner, null, null, List.of(), false, false, partition));
				try {
					FileService.FileContent content = service.readContent(linkId);
					assertArrayEquals(bytes, content.bytes());
				}
				finally {
					TenantContexts.clear();
				}
				String checksum = jdbc(restoredDb).queryForObject(
						"select checksum_sha256 from file_blob where id = ?", String.class, blobId);
				assertEquals(sha256(bytes), checksum);
			}
		}
		finally {
			dropDatabaseQuietly(sourceDb);
			dropDatabaseQuietly(backupDb);
			dropDatabaseQuietly(restoredDb);
			deleteTreeQuietly(sourceRoot);
			deleteTreeQuietly(backupRoot);
			deleteTreeQuietly(restoredRoot);
		}
	}

	private void seedFinalizedFile(String database, String partition, String owner, UUID blobId, UUID linkId,
			byte[] bytes) throws Exception {
		JdbcTemplate jdbc = jdbc(database);
		UUID resource = UUID.randomUUID();
		UUID version = UUID.randomUUID();
		jdbc.update(
				"insert into resource (id, resource_type, workspace_kind, owner_type, owner_user_sub, partition_key, custody_kind, created_by_sub) values (?, ?, ?, ?, ?, ?, ?, ?)",
				resource, "question", "personal", "user", owner, partition, "owned", owner);
		jdbc.update(
				"insert into resource_version (id, resource_id, partition_key, version_number, status, content_hash, created_by_sub) values (?, ?, ?, ?, ?, ?, ?)",
				version, resource, partition, 1, "draft", "hash", owner);
		jdbc.update(
				"insert into file_blob (id, storage_key, content_type, size_bytes, checksum_sha256, status, uploaded_by_sub, partition_key) values (?, ?, ?, ?, ?, ?, ?, ?)",
				blobId, "blobs/" + blobId, "image/png", (long) bytes.length, sha256(bytes), "READY", owner,
				partition);
		jdbc.update(
				"insert into file_link (id, resource_id, resource_version_id, position, blob_id, display_name, partition_key, created_by_sub) values (?, ?, ?, ?, ?, ?, ?, ?)",
				linkId, resource, version, "content", blobId, "tep-da-chot.png", partition, owner);
		jdbc.update("insert into file_audit_log (id, actor_sub, action, blob_id, link_id, partition_key) values (?, ?, ?, ?, ?, ?)",
				UUID.randomUUID(), owner, "attach", blobId, linkId, partition);
	}

	private void migrate(String database) {
		Flyway.configure().dataSource(ADMIN_BASE_URL + "/" + database, ADMIN_USER, ADMIN_PASSWORD)
				.locations("classpath:db/migration", "classpath:db/vendors/postgresql")
				.placeholders(Map.of("lms-server-app-password", APP_PASSWORD)).load().migrate();
	}

	private JdbcTemplate jdbc(String database) {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(ADMIN_BASE_URL + "/" + database);
		dataSource.setUsername(ADMIN_USER);
		dataSource.setPassword(ADMIN_PASSWORD);
		return new JdbcTemplate(dataSource);
	}

	private void createDatabase(String database) throws SQLException {
		executeOnPostgres("create database " + database);
	}

	private void dropDatabase(String database) throws SQLException {
		try (Connection connection = DriverManager.getConnection(ADMIN_BASE_URL + "/postgres", ADMIN_USER,
				ADMIN_PASSWORD); Statement statement = connection.createStatement()) {
			statement.execute("select pg_terminate_backend(pid) from pg_stat_activity where datname = '"
					+ database + "'");
			statement.execute("drop database if exists " + database);
		}
	}

	private void dropDatabaseQuietly(String database) {
		try {
			dropDatabase(database);
		}
		catch (SQLException ex) {
			System.err.println("Không xoá được database " + database + ": " + ex.getMessage());
		}
	}

	private void executeOnPostgres(String sql) throws SQLException {
		try (Connection connection = DriverManager.getConnection(ADMIN_BASE_URL + "/postgres", ADMIN_USER,
				ADMIN_PASSWORD); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private void copyTree(Path from, Path to) throws IOException {
		try (Stream<Path> walk = Files.walk(from)) {
			for (Path path : walk.toList()) {
				Path target = to.resolve(from.relativize(path));
				if (Files.isDirectory(path)) {
					Files.createDirectories(target);
				}
				else {
					Files.copy(path, target);
				}
			}
		}
	}

	private void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private void deleteTreeQuietly(Path root) {
		try {
			deleteTree(root);
		}
		catch (IOException ex) {
			System.err.println("Không xoá được thư mục " + root + ": " + ex.getMessage());
		}
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

}
