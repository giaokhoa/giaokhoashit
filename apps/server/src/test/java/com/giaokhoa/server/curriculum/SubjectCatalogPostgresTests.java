package com.giaokhoa.server.curriculum;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

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

// Danh mục môn học chỉ kiểm chứng được trọn vẹn trên PostgreSQL thật (migration nạp dữ liệu và chuỗi Flyway đầy
// đủ gồm cả các migration riêng của PostgreSQL), nên bộ này theo cùng khuôn các bộ PostgresRlsTests: tạo một
// database test riêng trên test-pg online trước lượt và xoá ngay sau lượt; Flyway chạy bằng tài khoản quản trị,
// datasource của ứng dụng chạy bằng role runtime lms_server_app — truy vấn danh mục qua JdbcTemplate của ứng dụng
// cũng đồng thời chứng minh role runtime đọc được bảng tham chiếu này. Chưa đặt biến môi trường LMS_TEST_PG_URL
// thì toàn bộ lớp bị bỏ qua, và kết quả bỏ qua đó không được tính là đạt.
@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + SubjectCatalogPostgresTests.ISSUER)
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_URL", matches = ".+")
class SubjectCatalogPostgresTests {

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
		registry.add("spring.datasource.url", SubjectCatalogPostgresTests::testDatabaseUrl);
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
	private JdbcTemplate jdbcTemplate;

	private JdbcTemplate adminJdbc() {
		return new JdbcTemplate(new DriverManagerDataSource(testDatabaseUrl(), ADMIN_USER, ADMIN_PASSWORD));
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

	private record ExpectedSubject(String slug, String name, String schoolLevel, int gradeFrom, int gradeTo,
			String category) {
	}

	// Danh sách kỳ vọng viết theo kế hoạch giáo dục của Chương trình giáo dục phổ thông (Thông tư 32/2018/TT-BGDĐT,
	// Lịch sử THPT bắt buộc theo Thông tư 13/2022/TT-BGDĐT): mỗi môn ở mỗi cấp là một mục, thứ tự trong danh sách là
	// thứ tự hiển thị của danh mục. Đối chiếu nguyên danh sách này bắt cả ba loại sai: thiếu môn, gộp môn của nhiều
	// cấp vào một mục, và khoảng lớp sai.
	private static final List<ExpectedSubject> EXPECTED = List.of(
			new ExpectedSubject("tieng-viet-tieu-hoc", "Tiếng Việt", "PRIMARY", 1, 5, "REQUIRED"),
			new ExpectedSubject("toan-tieu-hoc", "Toán", "PRIMARY", 1, 5, "REQUIRED"),
			new ExpectedSubject("dao-duc-tieu-hoc", "Đạo đức", "PRIMARY", 1, 5, "REQUIRED"),
			new ExpectedSubject("ngoai-ngu-1-tieu-hoc", "Ngoại ngữ 1", "PRIMARY", 3, 5, "REQUIRED"),
			new ExpectedSubject("tu-nhien-va-xa-hoi-tieu-hoc", "Tự nhiên và Xã hội", "PRIMARY", 1, 3, "REQUIRED"),
			new ExpectedSubject("lich-su-va-dia-li-tieu-hoc", "Lịch sử và Địa lí", "PRIMARY", 4, 5, "REQUIRED"),
			new ExpectedSubject("khoa-hoc-tieu-hoc", "Khoa học", "PRIMARY", 4, 5, "REQUIRED"),
			new ExpectedSubject("tin-hoc-va-cong-nghe-tieu-hoc", "Tin học và Công nghệ", "PRIMARY", 3, 5, "REQUIRED"),
			new ExpectedSubject("giao-duc-the-chat-tieu-hoc", "Giáo dục thể chất", "PRIMARY", 1, 5, "REQUIRED"),
			new ExpectedSubject("nghe-thuat-tieu-hoc", "Nghệ thuật", "PRIMARY", 1, 5, "REQUIRED"),
			new ExpectedSubject("hoat-dong-trai-nghiem-tieu-hoc", "Hoạt động trải nghiệm", "PRIMARY", 1, 5, "EDUCATIONAL_ACTIVITY"),
			new ExpectedSubject("tieng-dan-toc-thieu-so-tieu-hoc", "Tiếng dân tộc thiểu số", "PRIMARY", 1, 5, "OPTIONAL"),
			new ExpectedSubject("ngoai-ngu-1-tieu-hoc-tu-chon", "Ngoại ngữ 1", "PRIMARY", 1, 2, "OPTIONAL"),
			new ExpectedSubject("ngu-van-thcs", "Ngữ văn", "LOWER_SECONDARY", 6, 9, "REQUIRED"),
			new ExpectedSubject("toan-thcs", "Toán", "LOWER_SECONDARY", 6, 9, "REQUIRED"),
			new ExpectedSubject("ngoai-ngu-1-thcs", "Ngoại ngữ 1", "LOWER_SECONDARY", 6, 9, "REQUIRED"),
			new ExpectedSubject("giao-duc-cong-dan-thcs", "Giáo dục công dân", "LOWER_SECONDARY", 6, 9, "REQUIRED"),
			new ExpectedSubject("lich-su-va-dia-li-thcs", "Lịch sử và Địa lí", "LOWER_SECONDARY", 6, 9, "REQUIRED"),
			new ExpectedSubject("khoa-hoc-tu-nhien-thcs", "Khoa học tự nhiên", "LOWER_SECONDARY", 6, 9, "REQUIRED"),
			new ExpectedSubject("cong-nghe-thcs", "Công nghệ", "LOWER_SECONDARY", 6, 9, "REQUIRED"),
			new ExpectedSubject("tin-hoc-thcs", "Tin học", "LOWER_SECONDARY", 6, 9, "REQUIRED"),
			new ExpectedSubject("giao-duc-the-chat-thcs", "Giáo dục thể chất", "LOWER_SECONDARY", 6, 9, "REQUIRED"),
			new ExpectedSubject("nghe-thuat-thcs", "Nghệ thuật", "LOWER_SECONDARY", 6, 9, "REQUIRED"),
			new ExpectedSubject("hoat-dong-trai-nghiem-huong-nghiep-thcs", "Hoạt động trải nghiệm, hướng nghiệp", "LOWER_SECONDARY", 6, 9, "EDUCATIONAL_ACTIVITY"),
			new ExpectedSubject("noi-dung-giao-duc-cua-dia-phuong-thcs", "Nội dung giáo dục của địa phương", "LOWER_SECONDARY", 6, 9, "REQUIRED"),
			new ExpectedSubject("tieng-dan-toc-thieu-so-thcs", "Tiếng dân tộc thiểu số", "LOWER_SECONDARY", 6, 9, "OPTIONAL"),
			new ExpectedSubject("ngoai-ngu-2-thcs", "Ngoại ngữ 2", "LOWER_SECONDARY", 6, 9, "OPTIONAL"),
			new ExpectedSubject("ngu-van-thpt", "Ngữ văn", "UPPER_SECONDARY", 10, 12, "REQUIRED"),
			new ExpectedSubject("toan-thpt", "Toán", "UPPER_SECONDARY", 10, 12, "REQUIRED"),
			new ExpectedSubject("ngoai-ngu-1-thpt", "Ngoại ngữ 1", "UPPER_SECONDARY", 10, 12, "REQUIRED"),
			new ExpectedSubject("lich-su-thpt", "Lịch sử", "UPPER_SECONDARY", 10, 12, "REQUIRED"),
			new ExpectedSubject("giao-duc-the-chat-thpt", "Giáo dục thể chất", "UPPER_SECONDARY", 10, 12, "REQUIRED"),
			new ExpectedSubject("giao-duc-quoc-phong-va-an-ninh-thpt", "Giáo dục quốc phòng và an ninh", "UPPER_SECONDARY", 10, 12, "REQUIRED"),
			new ExpectedSubject("hoat-dong-trai-nghiem-huong-nghiep-thpt", "Hoạt động trải nghiệm, hướng nghiệp", "UPPER_SECONDARY", 10, 12, "EDUCATIONAL_ACTIVITY"),
			new ExpectedSubject("noi-dung-giao-duc-cua-dia-phuong-thpt", "Nội dung giáo dục của địa phương", "UPPER_SECONDARY", 10, 12, "REQUIRED"),
			new ExpectedSubject("dia-li-thpt", "Địa lí", "UPPER_SECONDARY", 10, 12, "ELECTIVE"),
			new ExpectedSubject("giao-duc-kinh-te-va-phap-luat-thpt", "Giáo dục kinh tế và pháp luật", "UPPER_SECONDARY", 10, 12, "ELECTIVE"),
			new ExpectedSubject("vat-li-thpt", "Vật lí", "UPPER_SECONDARY", 10, 12, "ELECTIVE"),
			new ExpectedSubject("hoa-hoc-thpt", "Hoá học", "UPPER_SECONDARY", 10, 12, "ELECTIVE"),
			new ExpectedSubject("sinh-hoc-thpt", "Sinh học", "UPPER_SECONDARY", 10, 12, "ELECTIVE"),
			new ExpectedSubject("tin-hoc-thpt", "Tin học", "UPPER_SECONDARY", 10, 12, "ELECTIVE"),
			new ExpectedSubject("cong-nghe-thpt", "Công nghệ", "UPPER_SECONDARY", 10, 12, "ELECTIVE"),
			new ExpectedSubject("am-nhac-thpt", "Âm nhạc", "UPPER_SECONDARY", 10, 12, "ELECTIVE"),
			new ExpectedSubject("mi-thuat-thpt", "Mĩ thuật", "UPPER_SECONDARY", 10, 12, "ELECTIVE"),
			new ExpectedSubject("tieng-dan-toc-thieu-so-thpt", "Tiếng dân tộc thiểu số", "UPPER_SECONDARY", 10, 12, "OPTIONAL"),
			new ExpectedSubject("ngoai-ngu-2-thpt", "Ngoại ngữ 2", "UPPER_SECONDARY", 10, 12, "OPTIONAL"));

	@Test
	@DisplayName("Chuỗi migration đầy đủ áp sạch trên database trống và kết thúc bằng migration V80 của miền môn học")
	void migrationChainEndsWithSubjectsMigration() {
		List<String> versions = adminJdbc().queryForList(
				"select version from flyway_schema_history where success order by installed_rank", String.class);
		assertEquals(List.of("1", "2", "3", "10", "11", "20", "21", "30", "31", "40", "41", "50", "51", "60", "61",
				"80"), versions);
	}

	@Test
	@DisplayName("Danh mục đã nạp khớp đúng danh sách kỳ vọng của chương trình GDPT 2018: đủ môn theo từng cấp, mỗi môn tách theo cấp, đúng khoảng lớp và đúng nhóm")
	void catalogMatchesExpectedGdpt2018List() {
		assertEquals(13, countByLevel("PRIMARY"));
		assertEquals(14, countByLevel("LOWER_SECONDARY"));
		assertEquals(19, countByLevel("UPPER_SECONDARY"));
		List<ExpectedSubject> actual = this.jdbcTemplate.query(
				"select slug, name, school_level, grade_from, grade_to, category from subject order by "
						+ "case school_level when 'PRIMARY' then 0 when 'LOWER_SECONDARY' then 1 else 2 end, "
						+ "display_order",
				(rs, rowNum) -> new ExpectedSubject(rs.getString(1), rs.getString(2), rs.getString(3),
						rs.getInt(4), rs.getInt(5), rs.getString(6)));
		assertEquals(EXPECTED, actual);
	}

	@Test
	@DisplayName("Mọi định danh trong danh mục là UUIDv7, mọi id và mọi slug đều duy nhất")
	void allCatalogIdsAreUuidV7AndUnique() {
		List<String> ids = this.jdbcTemplate.queryForList("select id from subject", String.class);
		assertEquals(EXPECTED.size(), ids.size());
		assertEquals(ids.size(), ids.stream().distinct().count());
		for (String id : ids) {
			assertEquals(7, UUID.fromString(id).version(), "id không phải UUIDv7: " + id);
		}
		Integer distinctSlugs = this.jdbcTemplate.queryForObject(
				"select count(distinct slug) from subject", Integer.class);
		assertEquals(EXPECTED.size(), distinctSlugs);
	}

	private int countByLevel(String schoolLevel) {
		return this.jdbcTemplate.queryForObject("select count(*) from subject where school_level = ?",
				Integer.class, schoolLevel);
	}

}
