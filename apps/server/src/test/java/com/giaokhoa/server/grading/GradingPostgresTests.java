package com.giaokhoa.server.grading;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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

import java.math.BigDecimal;
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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
import org.springframework.context.annotation.Primary;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.giaokhoa.server.tenancy.MembershipChecker;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

// Kiểm chứng trên PostgreSQL thật những gì H2 không thực thi được của khối chấm: chuỗi migration đầy đủ gồm
// V60/V61, khoá ngoại hợp thành sang bảng của khối giao/làm bài được gắn thật vì bảng tạm của điểm nối được dựng
// trước khi Flyway chạy, Row Level Security cô lập ba bảng chấm theo phân vùng, và một vòng nộp → chấm → công bố
// → xem kết quả trọn vẹn chạy bằng role runtime dưới RLS. Database test riêng được tạo trước lượt và xoá sau
// lượt theo khuôn PostgresRlsTests; các bảng tạm của điểm nối được cấp quyền và bật RLS giống hệt cách migration
// thật của các khối kề sẽ làm khi hợp nhất. Vì bảng tạm có trước Flyway, lượt test bật baselineOnMigrate với
// baseline version 0 để Flyway nhận schema chưa có bảng lịch sử mà vẫn áp toàn bộ migration từ V1; khẳng định
// phiên bản vì thế chỉ đếm các hàng migration kiểu SQL, bỏ qua hàng mốc baseline.
@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + GradingPostgresTests.ISSUER)
@EnabledIfEnvironmentVariable(named = "LMS_TEST_PG_URL", matches = ".+")
class GradingPostgresTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	private static final String ORG_2_ID = "22222222-2222-2222-2222-222222222222";

	private static final String ALIAS = "trung-tam-a";

	private static final String TEACHER = "gv-pg";

	private static final String STUDENT = "hs-pg";

	private static final String APP_PASSWORD = "app-secret";

	private static final UUID VERSION_FULL = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

	private static final UUID MCQ_1 = UUID.fromString("10000000-0000-0000-0000-000000000001");

	private static final UUID ESSAY = UUID.fromString("10000000-0000-0000-0000-000000000002");

	private static final UUID MCQ_2 = UUID.fromString("10000000-0000-0000-0000-000000000003");

	private static final String ADMIN_BASE_URL = System.getenv("LMS_TEST_PG_URL");

	private static final String ADMIN_USER = System.getenv("LMS_TEST_PG_USER");

	private static final String ADMIN_PASSWORD = System.getenv("LMS_TEST_PG_PASSWORD");

	private static final String TEST_DATABASE = "lms_server_test_"
			+ UUID.randomUUID().toString().replace("-", "").substring(0, 12);

	private static final AtomicBoolean SEAM_READY = new AtomicBoolean();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		createTestDatabase();
		createSeamTables();
		registry.add("spring.datasource.url", GradingPostgresTests::testDatabaseUrl);
		registry.add("spring.datasource.username", () -> "lms_server_app");
		registry.add("spring.datasource.password", () -> APP_PASSWORD);
		registry.add("spring.flyway.user", () -> ADMIN_USER);
		registry.add("spring.flyway.password", () -> ADMIN_PASSWORD);
		registry.add("spring.flyway.placeholders.lms-server-app-password", () -> APP_PASSWORD);
		registry.add("spring.flyway.baseline-on-migrate", () -> "true");
		registry.add("spring.flyway.baseline-version", () -> "0");
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

	private static void createSeamTables() {
		try (Connection connection = DriverManager.getConnection(testDatabaseUrl(), ADMIN_USER, ADMIN_PASSWORD)) {
			SeamTables.create(connection);
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Không dựng được bảng tạm của điểm nối trong " + TEST_DATABASE, ex);
		}
	}

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private SubmissionGrading submissionGrading;

	@Autowired
	private PlatformTransactionManager transactionManager;

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
		if (SEAM_READY.compareAndSet(false, true)) {
			prepareSeamTablesForRuntimeRole();
		}
	}

	@TestConfiguration
	static class TestSupportConfiguration {

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

		@Bean
		@Primary
		ExamSnapshotSource examSnapshotSource() {
			SnapshotItem mcq1 = new SnapshotItem(MCQ_1, SnapshotItem.TYPE_SINGLE_CHOICE, 1, new BigDecimal("4"),
					"Cau 1: 2 + 2 bang may?",
					List.of(new SnapshotItem.Choice("c1", "4"), new SnapshotItem.Choice("c2", "5")), "c1", null);
			SnapshotItem essay = new SnapshotItem(ESSAY, SnapshotItem.TYPE_ESSAY_TEXT, 2, new BigDecimal("6"),
					"Hay giai thich dinh luat bao toan nang luong.", List.of(), null,
					"RUBRIC-BI-MAT: neu dung dinh luat va cho vi du.");
			SnapshotItem mcq2 = new SnapshotItem(MCQ_2, SnapshotItem.TYPE_SINGLE_CHOICE, 3, new BigDecimal("2"),
					"Cau 3: Thu do cua Phap la?",
					List.of(new SnapshotItem.Choice("d1", "London"), new SnapshotItem.Choice("d2", "Paris")), "d2",
					null);
			Map<UUID, List<SnapshotItem>> byVersion = Map.of(VERSION_FULL, List.of(mcq1, essay, mcq2));
			return examVersionId -> byVersion.getOrDefault(examVersionId, List.of());
		}

	}

	@Test
	@DisplayName("Chuỗi migration tích hợp áp sạch toàn bộ các phiên bản trong cây gồm cả V60 và V61, và khoá ngoại hợp thành sang exam_attempt/exam_assignment được gắn thật khi các bảng đó đã có trong chuỗi")
	void migrationChainIncludesGradingVersionsAndBindsForeignKeys() {
		List<String> versions = adminJdbc().queryForList(
				"select version from flyway_schema_history where success and type = 'SQL' order by installed_rank",
				String.class);
		assertEquals(List.of("1", "2", "3", "10", "11", "20", "21", "40", "41", "60", "61"), versions);
		List<String> constraints = adminJdbc().queryForList(
				"select conname from pg_constraint where contype = 'f' and conname like '%_fk' order by conname",
				String.class);
		assertTrue(constraints.contains("attempt_item_grade_attempt_fk"));
		assertTrue(constraints.contains("attempt_grade_summary_attempt_fk"));
		assertTrue(constraints.contains("assignment_publication_assignment_fk"));
	}

	@Test
	@DisplayName("RLS cô lập cả ba bảng chấm theo phân vùng: chưa đặt biến ngữ cảnh thì không thấy hàng nào, đặt khoá của một trung tâm thì chỉ thấy hàng của trung tâm đó và ghi hàng mang khoá khác bị từ chối")
	void gradingTablesAreIsolatedByRlsPerPartition() throws Exception {
		JdbcTemplate admin = adminJdbc();
		UUID attempt1 = seedAttemptRow(admin, ORG_1_ID);
		UUID attempt2 = seedAttemptRow(admin, ORG_2_ID);
		UUID assignment1 = seedAssignmentRow(admin, ORG_1_ID);
		UUID assignment2 = seedAssignmentRow(admin, ORG_2_ID);
		admin.update(
				"insert into attempt_item_grade (id, partition_key, attempt_id, exam_item_id, score, max_score, source, grader_sub) values (?, ?, ?, ?, 1, 4, 'manual', 'rls-gv-1')",
				UUID.randomUUID(), ORG_1_ID, attempt1, MCQ_1);
		admin.update(
				"insert into attempt_item_grade (id, partition_key, attempt_id, exam_item_id, score, max_score, source, grader_sub) values (?, ?, ?, ?, 2, 4, 'manual', 'rls-gv-2')",
				UUID.randomUUID(), ORG_2_ID, attempt2, MCQ_1);
		admin.update(
				"insert into attempt_grade_summary (attempt_id, partition_key, graded_by_sub, graded_at) values (?, ?, 'rls-gv-1', current_timestamp)",
				attempt1, ORG_1_ID);
		admin.update(
				"insert into attempt_grade_summary (attempt_id, partition_key, graded_by_sub, graded_at) values (?, ?, 'rls-gv-2', current_timestamp)",
				attempt2, ORG_2_ID);
		admin.update(
				"insert into assignment_publication (assignment_id, partition_key, published_by_sub, published_at) values (?, ?, 'rls-gv-1', current_timestamp)",
				assignment1, ORG_1_ID);
		admin.update(
				"insert into assignment_publication (assignment_id, partition_key, published_by_sub, published_at) values (?, ?, 'rls-gv-2', current_timestamp)",
				assignment2, ORG_2_ID);
		try (Connection connection = appConnection()) {
			connection.setAutoCommit(false);
			assertEquals(0, countRows(connection, "select count(*) from attempt_item_grade where grader_sub like 'rls-gv-%'"));
			assertEquals(0, countRows(connection, "select count(*) from attempt_grade_summary where graded_by_sub like 'rls-gv-%'"));
			assertEquals(0, countRows(connection, "select count(*) from assignment_publication where published_by_sub like 'rls-gv-%'"));
			connection.rollback();
			setConfig(connection, "app.partition_key", ORG_1_ID);
			assertEquals(1, countRows(connection, "select count(*) from attempt_item_grade where grader_sub like 'rls-gv-%'"));
			assertEquals(1, countRows(connection, "select count(*) from attempt_grade_summary where graded_by_sub like 'rls-gv-%'"));
			assertEquals(1, countRows(connection, "select count(*) from assignment_publication where published_by_sub like 'rls-gv-%'"));
			assertThrows(SQLException.class, () -> {
				try (PreparedStatement insert = connection.prepareStatement(
						"insert into attempt_item_grade (id, partition_key, attempt_id, exam_item_id, score, max_score, source) values (?, ?, ?, ?, 1, 4, 'auto')")) {
					insert.setObject(1, UUID.randomUUID());
					insert.setString(2, ORG_2_ID);
					insert.setObject(3, attempt2);
					insert.setObject(4, MCQ_2);
					insert.executeUpdate();
				}
			});
			connection.rollback();
		}
	}

	@Test
	@DisplayName("Một vòng trọn trên PostgreSQL thật bằng role runtime dưới RLS: nộp kèm chấm tự động, giáo viên chấm tự luận tới GRADED, công bố sang PUBLISHED và học viên đọc được kết quả của mình không kèm đáp án đúng")
	void fullGradingFlowOnRealPostgres() throws Exception {
		JdbcTemplate admin = adminJdbc();
		UUID classId = UUID.randomUUID();
		admin.update(
				"insert into teaching_class (id, partition_key, course_id, name, teacher_sub, created_by_sub) values (?, ?, ?, ?, ?, ?)",
				classId, ORG_1_ID, null, "Lop PG", TEACHER, TEACHER);
		admin.update(
				"insert into class_member (id, partition_key, class_id, student_sub, enrolled_by_sub) values (?, ?, ?, ?, ?)",
				UUID.randomUUID(), ORG_1_ID, classId, STUDENT, TEACHER);
		UUID assignmentId = UUID.randomUUID();
		admin.update(
				"insert into exam_assignment (id, partition_key, class_id, exam_resource_id, exam_version_id, assigned_by_sub) values (?, ?, ?, ?, ?, ?)",
				assignmentId, ORG_1_ID, classId, UUID.randomUUID(), VERSION_FULL, TEACHER);
		UUID attemptId = UUID.randomUUID();
		admin.update(
				"insert into exam_attempt (id, partition_key, assignment_id, student_sub, status, started_at) values (?, ?, ?, ?, 'IN_PROGRESS', current_timestamp)",
				attemptId, ORG_1_ID, assignmentId, STUDENT);
		admin.update(
				"insert into attempt_answer (id, partition_key, attempt_id, exam_item_id, answer) values (?, ?, ?, ?, ?)",
				UUID.randomUUID(), ORG_1_ID, attemptId, MCQ_1, "{\"choiceId\":\"c1\"}");
		admin.update(
				"insert into attempt_answer (id, partition_key, attempt_id, exam_item_id, answer) values (?, ?, ?, ?, ?)",
				UUID.randomUUID(), ORG_1_ID, attemptId, ESSAY, "{\"text\":\"Bai lam PG\"}");
		simulateSubmit(attemptId);
		assertEquals("SUBMITTED", admin.queryForObject("select status from exam_attempt where id = ?", String.class,
				attemptId));
		assertEquals(new BigDecimal("4.00"), admin.queryForObject(
				"select score from attempt_item_grade where attempt_id = ? and exam_item_id = ?", BigDecimal.class,
				attemptId, MCQ_1));
		String teacher = token(TEACHER, List.of("tenant-teacher"));
		this.mockMvc.perform(post("/api/attempts/" + attemptId + "/grade")
				.header("Authorization", "Bearer " + teacher)
				.header("X-LMS-Organization-Alias", ALIAS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"items\":[{\"examItemId\":\"" + ESSAY + "\",\"score\":5}],\"comment\":\"Tot\"}"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"GRADED\"")))
				.andExpect(content().string(containsString("\"totalScore\":9")));
		this.mockMvc.perform(post("/api/assignments/" + assignmentId + "/publish-results")
				.header("Authorization", "Bearer " + teacher)
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk());
		assertEquals("PUBLISHED", admin.queryForObject("select status from exam_attempt where id = ?", String.class,
				attemptId));
		this.mockMvc.perform(get("/api/attempts/" + attemptId)
				.header("Authorization", "Bearer " + token(STUDENT, List.of("tenant-member")))
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"totalScore\":9")))
				.andExpect(content().string(not(containsString("correctChoiceId"))))
				.andExpect(content().string(not(containsString("rubric"))));
	}

	private void prepareSeamTablesForRuntimeRole() {
		adminJdbc().execute("""
				grant select, insert, update, delete on teaching_class, class_member, exam_assignment,
				    exam_attempt, attempt_answer to lms_server_app;
				alter table teaching_class enable row level security;
				alter table class_member enable row level security;
				alter table exam_assignment enable row level security;
				alter table exam_attempt enable row level security;
				alter table attempt_answer enable row level security;
				do $$
				begin
				    if not exists (select from pg_policies where schemaname = 'public'
				            and tablename = 'teaching_class' and policyname = 'teaching_class_partition_access') then
				        create policy teaching_class_partition_access on teaching_class
				            using (partition_key = current_setting('app.partition_key', true))
				            with check (partition_key = current_setting('app.partition_key', true));
				    end if;
				    if not exists (select from pg_policies where schemaname = 'public'
				            and tablename = 'class_member' and policyname = 'class_member_partition_access') then
				        create policy class_member_partition_access on class_member
				            using (partition_key = current_setting('app.partition_key', true))
				            with check (partition_key = current_setting('app.partition_key', true));
				    end if;
				    if not exists (select from pg_policies where schemaname = 'public'
				            and tablename = 'exam_assignment' and policyname = 'exam_assignment_partition_access') then
				        create policy exam_assignment_partition_access on exam_assignment
				            using (partition_key = current_setting('app.partition_key', true))
				            with check (partition_key = current_setting('app.partition_key', true));
				    end if;
				    if not exists (select from pg_policies where schemaname = 'public'
				            and tablename = 'exam_attempt' and policyname = 'exam_attempt_partition_access') then
				        create policy exam_attempt_partition_access on exam_attempt
				            using (partition_key = current_setting('app.partition_key', true))
				            with check (partition_key = current_setting('app.partition_key', true));
				    end if;
				    if not exists (select from pg_policies where schemaname = 'public'
				            and tablename = 'attempt_answer' and policyname = 'attempt_answer_partition_access') then
				        create policy attempt_answer_partition_access on attempt_answer
				            using (partition_key = current_setting('app.partition_key', true))
				            with check (partition_key = current_setting('app.partition_key', true));
				    end if;
				end
				$$;
				""");
	}

	private void simulateSubmit(UUID attemptId) {
		TenantContexts.set(new TenantContext(STUDENT, ALIAS, ORG_1_ID, List.of("tenant-member"), false, false,
				ORG_1_ID));
		try {
			new TransactionTemplate(this.transactionManager).executeWithoutResult(status -> {
				this.jdbcTemplate.update(
						"update exam_attempt set status = 'SUBMITTED', submitted_at = current_timestamp, submit_mode = 'manual' where id = ?",
						attemptId);
				this.submissionGrading.gradeOnSubmit(attemptId);
			});
		}
		finally {
			TenantContexts.clear();
		}
	}

	private UUID seedAttemptRow(JdbcTemplate admin, String partitionKey) {
		UUID attemptId = UUID.randomUUID();
		admin.update(
				"insert into exam_attempt (id, partition_key, assignment_id, student_sub, status) values (?, ?, ?, ?, 'SUBMITTED')",
				attemptId, partitionKey, UUID.randomUUID(), "hs-rls-" + partitionKey.substring(0, 1));
		return attemptId;
	}

	private UUID seedAssignmentRow(JdbcTemplate admin, String partitionKey) {
		UUID assignmentId = UUID.randomUUID();
		admin.update(
				"insert into exam_assignment (id, partition_key, class_id, exam_resource_id, exam_version_id, assigned_by_sub) values (?, ?, ?, ?, ?, ?)",
				assignmentId, partitionKey, UUID.randomUUID(), UUID.randomUUID(), VERSION_FULL, TEACHER);
		return assignmentId;
	}

	private JdbcTemplate adminJdbc() {
		return new JdbcTemplate(new DriverManagerDataSource(testDatabaseUrl(), ADMIN_USER, ADMIN_PASSWORD));
	}

	private Connection appConnection() throws SQLException {
		return DriverManager.getConnection(testDatabaseUrl(), "lms_server_app", APP_PASSWORD);
	}

	private int countRows(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
			rs.next();
			return rs.getInt(1);
		}
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

	private String token(String subject, List<String> roles) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.subject(subject)
				.issueTime(Date.from(now.minusSeconds(10)))
				.expirationTime(Date.from(now.plusSeconds(300)))
				.claim("organization", Map.of(ALIAS, orgEntry(ORG_1_ID, roles)));
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
		jwt.sign(new RSASSASigner((RSAPrivateKey) TestSupportConfiguration.KEY_PAIR.getPrivate()));
		return jwt.serialize();
	}

}
