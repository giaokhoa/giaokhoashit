package com.giaokhoa.server.grading;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

// Kiểm thử khối chấm trên H2 theo khuôn của TenancyEndpointTests: chuỗi bảo mật thật, tầng dữ kiện thật (Flyway
// chạy V60 trên các bảng tạm của điểm nối được dựng trước khi context khởi động) và nguồn snapshot là bản giả lập
// có kiểm soát đúng hình dạng snapshot đã đóng băng. Điểm nối nộp bài được gọi trong đúng giao dịch chuyển trạng
// thái nộp, mô phỏng đúng cách khối giao/làm bài sẽ gọi khi hợp nhất. Phần Row Level Security của khối này được
// kiểm chứng riêng trên PostgreSQL thật ở GradingPostgresTests. Vì bảng tạm có trước Flyway, cấu hình test bật
// baselineOnMigrate với baseline version 0 — cơ chế Flyway dành cho schema chưa có bảng lịch sử nhưng đã có đối
// tượng — để toàn bộ migration vẫn áp từ V1; chuỗi chạy thật không có đối tượng nào có trước nên không dùng cơ
// chế này.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + GradingEndpointTests.ISSUER,
		"spring.datasource.url=" + GradingEndpointTests.DB_URL, "spring.datasource.username=sa",
		"spring.datasource.password=", "spring.flyway.locations=classpath:db/migration",
		"spring.flyway.baseline-on-migrate=true", "spring.flyway.baseline-version=0" })
class GradingEndpointTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	static final String DB_URL = "jdbc:h2:mem:grading;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

	private static final String ORG_ID = "11111111-1111-1111-1111-111111111111";

	private static final String ALIAS = "trung-tam-a";

	private static final String TEACHER = "gv-phu-trach";

	private static final String OTHER_TEACHER = "gv-khac";

	private static final String OWNER = "gv-chu-de";

	private static final String ADMIN = "admin-trung-tam";

	private static final String STUDENT_1 = "hs-mot";

	private static final String STUDENT_2 = "hs-hai";

	private static final UUID VERSION_FULL = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

	private static final UUID VERSION_MCQ = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

	private static final UUID MCQ_1 = UUID.fromString("10000000-0000-0000-0000-000000000001");

	private static final UUID ESSAY = UUID.fromString("10000000-0000-0000-0000-000000000002");

	private static final UUID MCQ_2 = UUID.fromString("10000000-0000-0000-0000-000000000003");

	@DynamicPropertySource
	static void seamTables(DynamicPropertyRegistry registry) throws Exception {
		try (Connection connection = DriverManager.getConnection(DB_URL, "sa", "")) {
			SeamTables.create(connection);
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
			Map<UUID, List<SnapshotItem>> byVersion = Map.of(VERSION_FULL, List.of(mcq1, essay, mcq2), VERSION_MCQ,
					List.of(mcq1, mcq2));
			return examVersionId -> byVersion.getOrDefault(examVersionId, List.of());
		}

	}

	@Test
	@DisplayName("Tại thời điểm nộp: trắc nghiệm đúng được trọn điểm của mục, chọn sai được 0, nguồn điểm là auto, mục tự luận chưa có điểm và lượt làm vẫn SUBMITTED")
	void autoGradesMcqAtSubmitAndLeavesEssayPending() {
		UUID classId = seedClass(TEACHER, TEACHER);
		seedMember(classId, STUDENT_1, false);
		UUID assignmentId = seedAssignment(classId, VERSION_FULL);
		UUID attemptId = seedAttempt(assignmentId, STUDENT_1, "IN_PROGRESS");
		seedAnswer(attemptId, MCQ_1, "{\"choiceId\":\"c1\"}");
		seedAnswer(attemptId, MCQ_2, "{\"choiceId\":\"d1\"}");
		seedAnswer(attemptId, ESSAY, "{\"text\":\"Noi dung bai tu luan\"}");
		simulateSubmit(attemptId, STUDENT_1);
		assertEquals("SUBMITTED", attemptStatus(attemptId));
		assertEquals(new BigDecimal("4.00"), itemScore(attemptId, MCQ_1));
		assertEquals(new BigDecimal("0.00"), itemScore(attemptId, MCQ_2));
		assertEquals("auto", itemSource(attemptId, MCQ_1));
		assertEquals(2, gradeCount(attemptId));
		assertEquals(0, summaryCount(attemptId));
	}

	@Test
	@DisplayName("Đề chỉ gồm trắc nghiệm: nộp là chấm xong toàn bộ, mục bỏ trống được 0, lượt làm sang GRADED và có tổng hợp với người hoàn tất để trống")
	void autoGradesAllMcqAttemptStraightToGraded() {
		UUID classId = seedClass(TEACHER, TEACHER);
		seedMember(classId, STUDENT_1, false);
		UUID assignmentId = seedAssignment(classId, VERSION_MCQ);
		UUID attemptId = seedAttempt(assignmentId, STUDENT_1, "IN_PROGRESS");
		seedAnswer(attemptId, MCQ_1, "{\"choiceId\":\"c1\"}");
		simulateSubmit(attemptId, STUDENT_1);
		assertEquals("GRADED", attemptStatus(attemptId));
		assertEquals(new BigDecimal("0.00"), itemScore(attemptId, MCQ_2));
		assertEquals(1, summaryCount(attemptId));
		assertNull(summaryGradedBy(attemptId));
	}

	@Test
	@DisplayName("Chấm tự động là một-lần: gọi lại điểm nối trên cùng lượt làm không ghi thêm hàng và không đổi điểm đã ghi")
	void gradeOnSubmitIsIdempotent() {
		UUID classId = seedClass(TEACHER, TEACHER);
		seedMember(classId, STUDENT_1, false);
		UUID assignmentId = seedAssignment(classId, VERSION_MCQ);
		UUID attemptId = seedAttempt(assignmentId, STUDENT_1, "IN_PROGRESS");
		seedAnswer(attemptId, MCQ_1, "{\"choiceId\":\"c2\"}");
		simulateSubmit(attemptId, STUDENT_1);
		assertEquals("GRADED", attemptStatus(attemptId));
		this.jdbcTemplate.update("update exam_attempt set status = 'SUBMITTED' where id = ?", attemptId);
		runHook(attemptId, STUDENT_1);
		runHook(attemptId, STUDENT_1);
		assertEquals(2, gradeCount(attemptId));
		assertEquals(new BigDecimal("0.00"), itemScore(attemptId, MCQ_1));
		assertEquals(new BigDecimal("0.00"), itemScore(attemptId, MCQ_2));
		assertEquals(1, summaryCount(attemptId));
	}

	@Test
	@DisplayName("Giáo viên phụ trách chấm tự luận kèm nhận xét từng mục và nhận xét chung: đủ mọi mục thì lượt làm sang GRADED, có đúng một tổng hợp ghi người chấm và nhận xét chung")
	void manualGradeCompletesAttemptAndCreatesSummary() throws Exception {
		UUID classId = seedClass(TEACHER, TEACHER);
		seedMember(classId, STUDENT_1, false);
		UUID assignmentId = seedAssignment(classId, VERSION_FULL);
		UUID attemptId = seedAttempt(assignmentId, STUDENT_1, "IN_PROGRESS");
		seedAnswer(attemptId, MCQ_1, "{\"choiceId\":\"c1\"}");
		seedAnswer(attemptId, ESSAY, "{\"text\":\"Bai lam tu luan\"}");
		simulateSubmit(attemptId, STUDENT_1);
		assertEquals(0, summaryCount(attemptId));
		String body = "{\"items\":[{\"examItemId\":\"" + ESSAY
				+ "\",\"score\":5,\"comment\":\"Nhan xet muc tu luan\"}],\"comment\":\"Nhan xet chung\"}";
		this.mockMvc.perform(post("/api/attempts/" + attemptId + "/grade")
				.header("Authorization", "Bearer " + token(TEACHER, List.of("tenant-teacher")))
				.header("X-LMS-Organization-Alias", ALIAS)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"GRADED\"")))
				.andExpect(content().string(containsString("\"totalScore\":9")))
				.andExpect(content().string(containsString("\"maxScore\":12")))
				.andExpect(content().string(containsString("\"provisional\":false")));
		assertEquals("GRADED", attemptStatus(attemptId));
		assertEquals("manual", itemSource(attemptId, ESSAY));
		assertEquals(TEACHER, itemGrader(attemptId, ESSAY));
		assertEquals(1, summaryCount(attemptId));
		assertEquals(TEACHER, summaryGradedBy(attemptId));
		assertEquals("Nhan xet chung", summaryComment(attemptId));
	}

	@Test
	@DisplayName("Chấm chưa đủ mọi mục của snapshot: lượt làm vẫn SUBMITTED, kết quả báo tạm tính và chưa có hàng tổng hợp nào")
	void partialGradingKeepsAttemptSubmittedWithoutSummary() throws Exception {
		UUID classId = seedClass(TEACHER, TEACHER);
		seedMember(classId, STUDENT_1, false);
		UUID assignmentId = seedAssignment(classId, VERSION_FULL);
		UUID attemptId = seedAttempt(assignmentId, STUDENT_1, "SUBMITTED");
		String body = "{\"items\":[{\"examItemId\":\"" + ESSAY + "\",\"score\":5}]}";
		this.mockMvc.perform(post("/api/attempts/" + attemptId + "/grade")
				.header("Authorization", "Bearer " + token(TEACHER, List.of("tenant-teacher")))
				.header("X-LMS-Organization-Alias", ALIAS)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"SUBMITTED\"")))
				.andExpect(content().string(containsString("\"provisional\":true")));
		assertEquals("SUBMITTED", attemptStatus(attemptId));
		assertEquals(0, summaryCount(attemptId));
	}

	@Test
	@DisplayName("Công bố chuyển đúng các lượt GRADED sang PUBLISHED, không đụng lượt chưa chấm xong hay đang làm, ghi đúng một dấu công bố và công bố lại là một-lần")
	void publishFlipsOnlyGradedAttemptsAndIsIdempotent() throws Exception {
		UUID classId = seedClass(TEACHER, TEACHER);
		seedMember(classId, STUDENT_1, false);
		seedMember(classId, STUDENT_2, false);
		UUID mcqAssignment = seedAssignment(classId, VERSION_MCQ);
		UUID gradedAttempt = seedAttempt(mcqAssignment, STUDENT_1, "IN_PROGRESS");
		seedAnswer(gradedAttempt, MCQ_1, "{\"choiceId\":\"c1\"}");
		simulateSubmit(gradedAttempt, STUDENT_1);
		assertEquals("GRADED", attemptStatus(gradedAttempt));
		UUID fullAssignment = seedAssignment(classId, VERSION_FULL);
		UUID submittedAttempt = seedAttempt(fullAssignment, STUDENT_1, "SUBMITTED");
		UUID inProgressAttempt = seedAttempt(fullAssignment, STUDENT_2, "IN_PROGRESS");
		String teacher = token(TEACHER, List.of("tenant-teacher"));
		this.mockMvc.perform(post("/api/assignments/" + mcqAssignment + "/publish-results")
				.header("Authorization", "Bearer " + teacher)
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"publishedBySub\":\"" + TEACHER + "\"")));
		assertEquals("PUBLISHED", attemptStatus(gradedAttempt));
		OffsetDateTime firstPublishedAt = publicationPublishedAt(mcqAssignment);
		this.mockMvc.perform(post("/api/assignments/" + fullAssignment + "/publish-results")
				.header("Authorization", "Bearer " + teacher)
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk());
		assertEquals("SUBMITTED", attemptStatus(submittedAttempt));
		assertEquals("IN_PROGRESS", attemptStatus(inProgressAttempt));
		this.mockMvc.perform(post("/api/assignments/" + mcqAssignment + "/publish-results")
				.header("Authorization", "Bearer " + teacher)
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk());
		assertEquals(1, publicationCount(mcqAssignment));
		assertEquals(firstPublishedAt, publicationPublishedAt(mcqAssignment));
	}

	@Test
	@DisplayName("Lượt làm chấm xong sau khi lần giao đã công bố tự chuyển thẳng sang PUBLISHED ngay trong giao dịch chấm hoàn tất")
	void gradingCompletedAfterPublicationFlipsStraightToPublished() throws Exception {
		UUID classId = seedClass(TEACHER, TEACHER);
		seedMember(classId, STUDENT_1, false);
		UUID assignmentId = seedAssignment(classId, VERSION_FULL);
		String teacher = token(TEACHER, List.of("tenant-teacher"));
		this.mockMvc.perform(post("/api/assignments/" + assignmentId + "/publish-results")
				.header("Authorization", "Bearer " + teacher)
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk());
		UUID attemptId = seedAttempt(assignmentId, STUDENT_1, "IN_PROGRESS");
		seedAnswer(attemptId, MCQ_1, "{\"choiceId\":\"c1\"}");
		seedAnswer(attemptId, ESSAY, "{\"text\":\"Bai lam\"}");
		simulateSubmit(attemptId, STUDENT_1);
		assertEquals("SUBMITTED", attemptStatus(attemptId));
		String body = "{\"items\":[{\"examItemId\":\"" + ESSAY + "\",\"score\":6}]}";
		this.mockMvc.perform(post("/api/attempts/" + attemptId + "/grade")
				.header("Authorization", "Bearer " + teacher)
				.header("X-LMS-Organization-Alias", ALIAS)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"PUBLISHED\"")));
		assertEquals("PUBLISHED", attemptStatus(attemptId));
	}

	@Test
	@DisplayName("Học viên không thấy bất kỳ trường điểm nào trước công bố dù lượt làm đã GRADED; sau công bố thấy đủ điểm và nhận xét của mình; đáp án đúng và tiêu chí chấm không xuất hiện ở cả hai thời điểm")
	void studentSeesScoresOnlyAfterPublication() throws Exception {
		UUID classId = seedClass(TEACHER, TEACHER);
		seedMember(classId, STUDENT_1, false);
		UUID assignmentId = seedAssignment(classId, VERSION_MCQ);
		UUID attemptId = seedAttempt(assignmentId, STUDENT_1, "IN_PROGRESS");
		seedAnswer(attemptId, MCQ_1, "{\"choiceId\":\"c1\"}");
		simulateSubmit(attemptId, STUDENT_1);
		assertEquals("GRADED", attemptStatus(attemptId));
		String student = token(STUDENT_1, List.of("tenant-member"));
		this.mockMvc.perform(get("/api/attempts/" + attemptId)
				.header("Authorization", "Bearer " + student)
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"GRADED\"")))
				.andExpect(content().string(containsString("\"result\":null")))
				.andExpect(content().string(not(containsString("totalScore"))))
				.andExpect(content().string(not(containsString("\"score\""))))
				.andExpect(content().string(not(containsString("correctChoiceId"))))
				.andExpect(content().string(not(containsString("rubric"))));
		String teacher = token(TEACHER, List.of("tenant-teacher"));
		this.mockMvc.perform(post("/api/assignments/" + assignmentId + "/publish-results")
				.header("Authorization", "Bearer " + teacher)
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk());
		this.mockMvc.perform(get("/api/attempts/" + attemptId)
				.header("Authorization", "Bearer " + student)
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"PUBLISHED\"")))
				.andExpect(content().string(containsString("\"totalScore\":4")))
				.andExpect(content().string(containsString("\"maxScore\":6")))
				.andExpect(content().string(not(containsString("correctChoiceId"))))
				.andExpect(content().string(not(containsString("rubric"))))
				.andExpect(content().string(not(containsString("RUBRIC-BI-MAT"))));
	}

	@Test
	@DisplayName("Chấm lại trước công bố ghi đè đúng một hàng điểm của mục; sau khi lượt làm đã PUBLISHED, chấm lại bị 409 và điểm đã công bố không đổi")
	void regradeBeforePublicationOverwritesAndAfterPublicationConflicts() throws Exception {
		UUID classId = seedClass(TEACHER, TEACHER);
		seedMember(classId, STUDENT_1, false);
		UUID assignmentId = seedAssignment(classId, VERSION_FULL);
		UUID attemptId = seedAttempt(assignmentId, STUDENT_1, "IN_PROGRESS");
		seedAnswer(attemptId, MCQ_1, "{\"choiceId\":\"c1\"}");
		seedAnswer(attemptId, ESSAY, "{\"text\":\"Bai lam\"}");
		simulateSubmit(attemptId, STUDENT_1);
		String teacher = token(TEACHER, List.of("tenant-teacher"));
		gradeEssay(teacher, attemptId, 5);
		assertEquals("GRADED", attemptStatus(attemptId));
		this.mockMvc.perform(post("/api/attempts/" + attemptId + "/grade")
				.header("Authorization", "Bearer " + teacher)
				.header("X-LMS-Organization-Alias", ALIAS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"items\":[{\"examItemId\":\"" + ESSAY + "\",\"score\":3}]}"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"totalScore\":7")));
		assertEquals(1, itemGradeCount(attemptId, ESSAY));
		assertEquals(new BigDecimal("3.00"), itemScore(attemptId, ESSAY));
		this.mockMvc.perform(post("/api/assignments/" + assignmentId + "/publish-results")
				.header("Authorization", "Bearer " + teacher)
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk());
		assertEquals("PUBLISHED", attemptStatus(attemptId));
		this.mockMvc.perform(post("/api/attempts/" + attemptId + "/grade")
				.header("Authorization", "Bearer " + teacher)
				.header("X-LMS-Organization-Alias", ALIAS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"items\":[{\"examItemId\":\"" + ESSAY + "\",\"score\":1}]}"))
				.andExpect(status().isConflict())
				.andExpect(content().string(containsString("\"code\":\"STATE_CONFLICT\"")));
		assertEquals(new BigDecimal("3.00"), itemScore(attemptId, ESSAY));
	}

	@Test
	@DisplayName("Học viên bị rút khỏi lớp vẫn được giáo viên chấm và công bố như thường, vẫn giữ kết quả đã công bố của mình và vẫn có một hàng trong bảng bài nộp với dấu đã rút")
	void removedStudentKeepsPublishedResultAndSubmissionsRow() throws Exception {
		UUID classId = seedClass(TEACHER, TEACHER);
		seedMember(classId, STUDENT_1, false);
		UUID assignmentId = seedAssignment(classId, VERSION_FULL);
		UUID attemptId = seedAttempt(assignmentId, STUDENT_1, "IN_PROGRESS");
		seedAnswer(attemptId, MCQ_1, "{\"choiceId\":\"c1\"}");
		seedAnswer(attemptId, ESSAY, "{\"text\":\"Bai lam\"}");
		simulateSubmit(attemptId, STUDENT_1);
		this.jdbcTemplate.update("update class_member set removed_at = current_timestamp where class_id = ? and student_sub = ?",
				classId, STUDENT_1);
		String teacher = token(TEACHER, List.of("tenant-teacher"));
		gradeEssay(teacher, attemptId, 5);
		assertEquals("GRADED", attemptStatus(attemptId));
		this.mockMvc.perform(post("/api/assignments/" + assignmentId + "/publish-results")
				.header("Authorization", "Bearer " + teacher)
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk());
		this.mockMvc.perform(get("/api/assignments/" + assignmentId + "/submissions")
				.header("Authorization", "Bearer " + teacher)
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"studentSub\":\"" + STUDENT_1 + "\"")))
				.andExpect(content().string(containsString("\"removed\":true")))
				.andExpect(content().string(containsString("\"published\":true")))
				.andExpect(content().string(containsString("\"totalScore\":9")));
		String student = token(STUDENT_1, List.of("tenant-member"));
		this.mockMvc.perform(get("/api/attempts/" + attemptId)
				.header("Authorization", "Bearer " + student)
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"PUBLISHED\"")))
				.andExpect(content().string(containsString("\"totalScore\":9")));
	}

	@Test
	@DisplayName("Ở trạng thái SUBMITTED học viên cũng không thấy trường điểm nào trong lượt làm của mình dù mục trắc nghiệm đã có điểm tự động")
	void studentSeesNoScoresWhileSubmittedEither() throws Exception {
		UUID classId = seedClass(TEACHER, TEACHER);
		seedMember(classId, STUDENT_1, false);
		UUID assignmentId = seedAssignment(classId, VERSION_FULL);
		UUID attemptId = seedAttempt(assignmentId, STUDENT_1, "IN_PROGRESS");
		seedAnswer(attemptId, MCQ_1, "{\"choiceId\":\"c1\"}");
		seedAnswer(attemptId, ESSAY, "{\"text\":\"Bai lam\"}");
		simulateSubmit(attemptId, STUDENT_1);
		assertEquals("SUBMITTED", attemptStatus(attemptId));
		this.mockMvc.perform(get("/api/attempts/" + attemptId)
				.header("Authorization", "Bearer " + token(STUDENT_1, List.of("tenant-member")))
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"SUBMITTED\"")))
				.andExpect(content().string(containsString("\"result\":null")))
				.andExpect(content().string(not(containsString("totalScore"))))
				.andExpect(content().string(not(containsString("\"score\""))))
				.andExpect(content().string(not(containsString("correctChoiceId"))))
				.andExpect(content().string(not(containsString("rubric"))));
	}

	@Test
	@DisplayName("Thay phụ trách lớp thì quyền chấm đi theo phân công hiện hành: giáo viên cũ bị 403 ngay, giáo viên mới chấm được cả lượt đã nộp từ trước khi thay")
	void teacherReassignmentMovesGradingRights() throws Exception {
		UUID classId = seedClass(TEACHER, TEACHER);
		seedMember(classId, STUDENT_1, false);
		UUID assignmentId = seedAssignment(classId, VERSION_FULL);
		UUID attemptId = seedAttempt(assignmentId, STUDENT_1, "SUBMITTED");
		this.jdbcTemplate.update("update teaching_class set teacher_sub = ? where id = ?", OTHER_TEACHER, classId);
		this.mockMvc.perform(post("/api/attempts/" + attemptId + "/grade")
				.header("Authorization", "Bearer " + token(TEACHER, List.of("tenant-teacher")))
				.header("X-LMS-Organization-Alias", ALIAS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"items\":[{\"examItemId\":\"" + ESSAY + "\",\"score\":5}]}"))
				.andExpect(status().isForbidden())
				.andExpect(content().string(containsString("\"code\":\"RESOURCE_FORBIDDEN\"")));
		assertEquals(0, gradeCount(attemptId));
		gradeEssay(token(OTHER_TEACHER, List.of("tenant-teacher")), attemptId, 5);
		assertEquals(new BigDecimal("5.00"), itemScore(attemptId, ESSAY));
		assertEquals(OTHER_TEACHER, itemGrader(attemptId, ESSAY));
	}

	@Test
	@DisplayName("Học viên của trung tâm khác không đọc được bảng bài nộp lẫn lượt làm của lần giao ở trung tâm này: cả hai đều 404 theo phân vùng của trang đang mở")
	void crossCenterStudentCannotReadAssignmentData() throws Exception {
		UUID classId = seedClass(TEACHER, TEACHER);
		seedMember(classId, STUDENT_1, false);
		UUID assignmentId = seedAssignment(classId, VERSION_FULL);
		UUID attemptId = seedAttempt(assignmentId, STUDENT_1, "SUBMITTED");
		String outsider = mint("hs-trung-tam-b", Map.of("organization",
				Map.of("trung-tam-b", orgEntry("22222222-2222-2222-2222-222222222222", List.of("tenant-member")))));
		this.mockMvc.perform(get("/api/assignments/" + assignmentId + "/submissions")
				.header("Authorization", "Bearer " + outsider)
				.header("X-LMS-Organization-Alias", "trung-tam-b"))
				.andExpect(status().isNotFound())
				.andExpect(content().string(containsString("\"code\":\"RESOURCE_NOT_FOUND\"")));
		this.mockMvc.perform(get("/api/attempts/" + attemptId)
				.header("Authorization", "Bearer " + outsider)
				.header("X-LMS-Organization-Alias", "trung-tam-b"))
				.andExpect(status().isNotFound())
				.andExpect(content().string(containsString("\"code\":\"RESOURCE_NOT_FOUND\"")));
	}

	@Test
	@DisplayName("Chỉ giáo viên phụ trách hiện hành chấm và công bố được: giáo viên khác, người tạo lớp, tenant-admin và học viên đều bị 403 khi chấm/công bố; tenant-admin vẫn đọc được bảng bài nộp")
	void onlyAssignedTeacherMayGradeAndPublish() throws Exception {
		UUID classId = seedClass(TEACHER, OWNER);
		seedMember(classId, STUDENT_1, false);
		UUID assignmentId = seedAssignment(classId, VERSION_FULL);
		UUID attemptId = seedAttempt(assignmentId, STUDENT_1, "SUBMITTED");
		String gradeBody = "{\"items\":[{\"examItemId\":\"" + ESSAY + "\",\"score\":5}]}";
		for (String intruder : List.of(token(OTHER_TEACHER, List.of("tenant-teacher")),
				token(OWNER, List.of("tenant-teacher")), token(ADMIN, List.of("tenant-admin")),
				token(STUDENT_1, List.of("tenant-member")))) {
			this.mockMvc.perform(post("/api/attempts/" + attemptId + "/grade")
					.header("Authorization", "Bearer " + intruder)
					.header("X-LMS-Organization-Alias", ALIAS)
					.contentType(MediaType.APPLICATION_JSON)
					.content(gradeBody))
					.andExpect(status().isForbidden())
					.andExpect(content().string(containsString("\"code\":\"RESOURCE_FORBIDDEN\"")));
		}
		for (String intruder : List.of(token(OTHER_TEACHER, List.of("tenant-teacher")),
				token(ADMIN, List.of("tenant-admin")))) {
			this.mockMvc.perform(post("/api/assignments/" + assignmentId + "/publish-results")
					.header("Authorization", "Bearer " + intruder)
					.header("X-LMS-Organization-Alias", ALIAS))
					.andExpect(status().isForbidden())
					.andExpect(content().string(containsString("\"code\":\"RESOURCE_FORBIDDEN\"")));
		}
		assertEquals(0, gradeCount(attemptId));
		assertEquals(0, publicationCount(assignmentId));
		this.mockMvc.perform(get("/api/assignments/" + assignmentId + "/submissions")
				.header("Authorization", "Bearer " + token(OTHER_TEACHER, List.of("tenant-teacher")))
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isForbidden());
		this.mockMvc.perform(get("/api/assignments/" + assignmentId + "/submissions")
				.header("Authorization", "Bearer " + token(ADMIN, List.of("tenant-admin")))
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"studentSub\":\"" + STUDENT_1 + "\"")));
	}

	@Test
	@DisplayName("Học viên đọc lượt làm của học viên khác nhận 404 không lộ dữ kiện; giáo viên không phụ trách đọc bị 403; giáo viên phụ trách đọc được hình chiếu chấm có đáp án đúng và tiêu chí")
	void attemptReadAccessRules() throws Exception {
		UUID classId = seedClass(TEACHER, TEACHER);
		seedMember(classId, STUDENT_1, false);
		seedMember(classId, STUDENT_2, false);
		UUID assignmentId = seedAssignment(classId, VERSION_FULL);
		UUID attemptId = seedAttempt(assignmentId, STUDENT_1, "IN_PROGRESS");
		seedAnswer(attemptId, MCQ_1, "{\"choiceId\":\"c1\"}");
		seedAnswer(attemptId, ESSAY, "{\"text\":\"Bai lam\"}");
		simulateSubmit(attemptId, STUDENT_1);
		this.mockMvc.perform(get("/api/attempts/" + attemptId)
				.header("Authorization", "Bearer " + token(STUDENT_2, List.of("tenant-member")))
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isNotFound())
				.andExpect(content().string(containsString("\"code\":\"RESOURCE_NOT_FOUND\"")))
				.andExpect(content().string(not(containsString(STUDENT_1))));
		this.mockMvc.perform(get("/api/attempts/" + attemptId)
				.header("Authorization", "Bearer " + token(OTHER_TEACHER, List.of("tenant-teacher")))
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isForbidden());
		this.mockMvc.perform(get("/api/attempts/" + attemptId)
				.header("Authorization", "Bearer " + token(TEACHER, List.of("tenant-teacher")))
				.header("X-LMS-Organization-Alias", ALIAS))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"studentSub\":\"" + STUDENT_1 + "\"")))
				.andExpect(content().string(containsString("\"correctChoiceId\":\"c1\"")))
				.andExpect(content().string(containsString("RUBRIC-BI-MAT")))
				.andExpect(content().string(containsString("\"source\":\"auto\"")));
	}

	@Test
	@DisplayName("Chấm lượt đang IN_PROGRESS bị 409; chấm tay mục trắc nghiệm, điểm ngoài khoảng của mục và mục không thuộc snapshot bị 422; mọi lần gọi sai đều không ghi điểm nào")
	void gradeValidationAndStateConflictsWriteNothing() throws Exception {
		UUID classId = seedClass(TEACHER, TEACHER);
		seedMember(classId, STUDENT_1, false);
		UUID assignmentId = seedAssignment(classId, VERSION_FULL);
		String teacher = token(TEACHER, List.of("tenant-teacher"));
		UUID inProgress = seedAttempt(assignmentId, STUDENT_1, "IN_PROGRESS");
		this.mockMvc.perform(post("/api/attempts/" + inProgress + "/grade")
				.header("Authorization", "Bearer " + teacher)
				.header("X-LMS-Organization-Alias", ALIAS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"items\":[{\"examItemId\":\"" + ESSAY + "\",\"score\":5}]}"))
				.andExpect(status().isConflict())
				.andExpect(content().string(containsString("\"code\":\"STATE_CONFLICT\"")));
		UUID submitted = seedAttempt(assignmentId, STUDENT_2, "SUBMITTED");
		List<String> invalidBodies = List.of(
				"{\"items\":[{\"examItemId\":\"" + MCQ_1 + "\",\"score\":1}]}",
				"{\"items\":[{\"examItemId\":\"" + ESSAY + "\",\"score\":7}]}",
				"{\"items\":[{\"examItemId\":\"" + ESSAY + "\",\"score\":-1}]}",
				"{\"items\":[{\"examItemId\":\"" + UUID.randomUUID() + "\",\"score\":1}]}",
				"{\"items\":[{\"examItemId\":\"" + ESSAY + "\",\"score\":5},{\"examItemId\":\"" + MCQ_1
						+ "\",\"score\":1}]}");
		for (String body : invalidBodies) {
			this.mockMvc.perform(post("/api/attempts/" + submitted + "/grade")
					.header("Authorization", "Bearer " + teacher)
					.header("X-LMS-Organization-Alias", ALIAS)
					.contentType(MediaType.APPLICATION_JSON)
					.content(body))
					.andExpect(status().isUnprocessableEntity())
					.andExpect(content().string(containsString("\"code\":\"VALIDATION_FAILED\"")));
		}
		assertEquals(0, gradeCount(submitted));
		assertEquals("SUBMITTED", attemptStatus(submitted));
		assertEquals(0, summaryCount(submitted));
	}

	private void gradeEssay(String teacherToken, UUID attemptId, int score) throws Exception {
		this.mockMvc.perform(post("/api/attempts/" + attemptId + "/grade")
				.header("Authorization", "Bearer " + teacherToken)
				.header("X-LMS-Organization-Alias", ALIAS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"items\":[{\"examItemId\":\"" + ESSAY + "\",\"score\":" + score + "}]}"))
				.andExpect(status().isOk());
	}

	private void simulateSubmit(UUID attemptId, String studentSub) {
		TenantContexts.set(new TenantContext(studentSub, ALIAS, ORG_ID, List.of("tenant-member"), false, false,
				ORG_ID));
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

	private void runHook(UUID attemptId, String studentSub) {
		TenantContexts.set(new TenantContext(studentSub, ALIAS, ORG_ID, List.of("tenant-member"), false, false,
				ORG_ID));
		try {
			new TransactionTemplate(this.transactionManager)
					.executeWithoutResult(status -> this.submissionGrading.gradeOnSubmit(attemptId));
		}
		finally {
			TenantContexts.clear();
		}
	}

	private UUID seedClass(String teacherSub, String createdBySub) {
		UUID id = UUID.randomUUID();
		this.jdbcTemplate.update(
				"insert into teaching_class (id, partition_key, course_id, name, teacher_sub, created_by_sub) values (?, ?, ?, ?, ?, ?)",
				id, ORG_ID, null, "Lop kiem thu", teacherSub, createdBySub);
		return id;
	}

	private void seedMember(UUID classId, String studentSub, boolean removed) {
		this.jdbcTemplate.update(
				"insert into class_member (id, partition_key, class_id, student_sub, enrolled_by_sub, removed_at) values (?, ?, ?, ?, ?, ?)",
				UUID.randomUUID(), ORG_ID, classId, studentSub, TEACHER,
				removed ? OffsetDateTime.now() : null);
	}

	private UUID seedAssignment(UUID classId, UUID examVersionId) {
		UUID id = UUID.randomUUID();
		this.jdbcTemplate.update(
				"insert into exam_assignment (id, partition_key, class_id, exam_resource_id, exam_version_id, assigned_by_sub) values (?, ?, ?, ?, ?, ?)",
				id, ORG_ID, classId, UUID.randomUUID(), examVersionId, TEACHER);
		return id;
	}

	private UUID seedAttempt(UUID assignmentId, String studentSub, String status) {
		UUID id = UUID.randomUUID();
		boolean started = !"NOT_STARTED".equals(status);
		boolean submitted = "SUBMITTED".equals(status) || "GRADED".equals(status) || "PUBLISHED".equals(status);
		this.jdbcTemplate.update(
				"insert into exam_attempt (id, partition_key, assignment_id, student_sub, status, started_at, submitted_at, submit_mode) values (?, ?, ?, ?, ?, ?, ?, ?)",
				id, ORG_ID, assignmentId, studentSub, status, started ? OffsetDateTime.now() : null,
				submitted ? OffsetDateTime.now() : null, submitted ? "manual" : null);
		return id;
	}

	private void seedAnswer(UUID attemptId, UUID examItemId, String answerJson) {
		this.jdbcTemplate.update(
				"insert into attempt_answer (id, partition_key, attempt_id, exam_item_id, answer) values (?, ?, ?, ?, ?)",
				UUID.randomUUID(), ORG_ID, attemptId, examItemId, answerJson);
	}

	private String attemptStatus(UUID attemptId) {
		return this.jdbcTemplate.queryForObject("select status from exam_attempt where id = ?", String.class,
				attemptId);
	}

	private int gradeCount(UUID attemptId) {
		return this.jdbcTemplate.queryForObject("select count(*) from attempt_item_grade where attempt_id = ?",
				Integer.class, attemptId);
	}

	private int itemGradeCount(UUID attemptId, UUID examItemId) {
		return this.jdbcTemplate.queryForObject(
				"select count(*) from attempt_item_grade where attempt_id = ? and exam_item_id = ?", Integer.class,
				attemptId, examItemId);
	}

	private BigDecimal itemScore(UUID attemptId, UUID examItemId) {
		return this.jdbcTemplate.queryForObject(
				"select score from attempt_item_grade where attempt_id = ? and exam_item_id = ?", BigDecimal.class,
				attemptId, examItemId);
	}

	private String itemSource(UUID attemptId, UUID examItemId) {
		return this.jdbcTemplate.queryForObject(
				"select source from attempt_item_grade where attempt_id = ? and exam_item_id = ?", String.class,
				attemptId, examItemId);
	}

	private String itemGrader(UUID attemptId, UUID examItemId) {
		return this.jdbcTemplate.queryForObject(
				"select grader_sub from attempt_item_grade where attempt_id = ? and exam_item_id = ?", String.class,
				attemptId, examItemId);
	}

	private int summaryCount(UUID attemptId) {
		return this.jdbcTemplate.queryForObject("select count(*) from attempt_grade_summary where attempt_id = ?",
				Integer.class, attemptId);
	}

	private String summaryGradedBy(UUID attemptId) {
		return this.jdbcTemplate.queryForObject("select graded_by_sub from attempt_grade_summary where attempt_id = ?",
				String.class, attemptId);
	}

	private String summaryComment(UUID attemptId) {
		return this.jdbcTemplate.queryForObject(
				"select overall_comment from attempt_grade_summary where attempt_id = ?", String.class, attemptId);
	}

	private int publicationCount(UUID assignmentId) {
		return this.jdbcTemplate.queryForObject(
				"select count(*) from assignment_publication where assignment_id = ?", Integer.class, assignmentId);
	}

	private OffsetDateTime publicationPublishedAt(UUID assignmentId) {
		return this.jdbcTemplate.queryForObject(
				"select published_at from assignment_publication where assignment_id = ?", OffsetDateTime.class,
				assignmentId);
	}

	private Map<String, Object> orgEntry(String id, List<String> roles) {
		return Map.of("id", id, "realm_access", Map.of("roles", roles));
	}

	private String token(String subject, List<String> roles) throws Exception {
		return mint(subject, Map.of("organization", Map.of(ALIAS, orgEntry(ORG_ID, roles))));
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
		jwt.sign(new RSASSASigner((RSAPrivateKey) TestSupportConfiguration.KEY_PAIR.getPrivate()));
		return jwt.serialize();
	}

}
