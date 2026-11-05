package com.giaokhoa.server.assignment;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.giaokhoa.server.tenancy.MembershipChecker;
import com.giaokhoa.server.tenancy.TenantContext;

// Kiểm thử luồng giao/làm bài trên đường thật: chuỗi lọc bảo mật và ngữ cảnh tenant của khung tenancy, tầng dữ
// kiện thật (Flyway + Hibernate/JPA với discriminator) trên H2 ở chế độ PostgreSQL. Ba điểm nối chưa hợp nhất
// được thay bằng bản giả lập có kiểm soát, đúng khuôn khung tenancy dùng cho MembershipChecker: phép kiểm
// dạy/ghi danh của khối tổ chức dạy học (dữ kiện lớp L1/L2 và ghi danh giữ trong các tập trạng thái của test),
// snapshot của feat đề (hai phiên bản: đã phát hành và nháp, cộng một bản chờ duyệt), và dịch vụ quyết quyền
// của lõi quyền (chủ đề được phép giao, người khác bị từ chối). Lớp RLS của PostgreSQL kiểm riêng trên
// PostgreSQL thật.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + AssignmentEndpointTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:assignment;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration" })
class AssignmentEndpointTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	private static final String ORG_2_ID = "22222222-2222-2222-2222-222222222222";

	private static final UUID CLASS_1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");

	private static final UUID CLASS_2 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");

	private static final UUID CLASS_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1");

	private static final UUID EXAM_RESOURCE = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc1");

	private static final UUID VERSION_PUBLISHED = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddddd1");

	private static final UUID VERSION_DRAFT = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddddd2");

	private static final UUID VERSION_PENDING = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddddd3");

	private static final UUID ITEM_CHOICE = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1");

	private static final UUID ITEM_ESSAY = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2");

	private static final String TEACHER_1 = "gv-trung-tam-1";

	private static final String TEACHER_2 = "gv2-trung-tam-1";

	private static final String ADMIN_1 = "hai-ngu-canh";

	private static final String STUDENT_1 = "hs-trung-tam-1";

	private static final String STUDENT_2 = "hs-trung-tam-2";

	private static final String STUDENT_3 = "hs-3";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private MembershipChecker membershipChecker;

	@MockitoBean
	private TeachingChecks teaching;

	@MockitoBean
	private OrganizationIds organizationIds;

	@MockitoBean
	private ExamVersionSnapshots snapshots;

	@MockitoBean
	private ExamUseDecisions useDecisions;

	private MockMvc mockMvc;

	private final Map<String, String> classTeachers = new HashMap<>();

	private final Set<String> enrolments = new HashSet<>();

	private final Set<String> removedEnrolments = new HashSet<>();

	private final Map<UUID, ExamVersionSnapshots.Snapshot> snapshotByVersion = new HashMap<>();

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		lenient().when(this.membershipChecker.check(anyString(), anyString()))
				.thenReturn(MembershipChecker.Result.MEMBER);
		lenient().when(this.organizationIds.findOrganizationIdByAlias("trung-tam-1"))
				.thenReturn(Optional.of(UUID.fromString(ORG_1_ID)));
		lenient().when(this.organizationIds.findOrganizationIdByAlias("trung-tam-2"))
				.thenReturn(Optional.of(UUID.fromString(ORG_2_ID)));

		this.classTeachers.clear();
		this.classTeachers.put(ORG_1_ID + "|" + CLASS_1, TEACHER_1);
		this.classTeachers.put(ORG_1_ID + "|" + CLASS_2, TEACHER_2);
		this.classTeachers.put(ORG_2_ID + "|" + CLASS_B, TEACHER_2);
		this.enrolments.clear();
		this.removedEnrolments.clear();
		enrol(STUDENT_1, CLASS_1);
		enrol(STUDENT_3, CLASS_1);

		lenient().when(this.teaching.findClass(anyString(), any()))
				.thenAnswer(inv -> {
					String teacher = this.classTeachers.get(inv.getArgument(0) + "|" + inv.getArgument(1));
					return teacher == null ? Optional.empty()
							: Optional.of(new TeachingChecks.ClassView(inv.getArgument(1), teacher));
				});
		lenient().when(this.teaching.isAssignedToTeach(anyString(), anyString(), any()))
				.thenAnswer(inv -> inv.getArgument(0)
						.equals(this.classTeachers.get(inv.getArgument(1) + "|" + inv.getArgument(2))));
		lenient().when(this.teaching.isEnrolled(anyString(), anyString(), any()))
				.thenAnswer(inv -> ORG_1_ID.equals(inv.getArgument(1))
						&& this.enrolments.contains(inv.getArgument(2) + "|" + inv.getArgument(0))
						&& !this.removedEnrolments.contains(inv.getArgument(2) + "|" + inv.getArgument(0)));
		lenient().when(this.teaching.classIdsTaughtBy(anyString(), anyString()))
				.thenAnswer(inv -> this.classTeachers.entrySet().stream()
						.filter(entry -> entry.getKey().startsWith(inv.getArgument(0) + "|")
								&& inv.getArgument(1).equals(entry.getValue()))
						.map(entry -> UUID.fromString(entry.getKey().substring(entry.getKey().indexOf('|') + 1)))
						.toList());
		lenient().when(this.teaching.classIdsEnrolledBy(anyString(), anyString()))
				.thenAnswer(inv -> this.enrolments.stream()
						.filter(key -> key.endsWith("|" + inv.getArgument(1))
								&& !this.removedEnrolments.contains(key))
						.map(key -> UUID.fromString(key.substring(0, key.indexOf('|'))))
						.toList());
		lenient().when(this.teaching.activeRoster(anyString(), any()))
				.thenAnswer(inv -> this.enrolments.stream()
						.filter(key -> key.startsWith(inv.getArgument(1) + "|")
								&& !this.removedEnrolments.contains(key))
						.map(key -> key.substring(key.indexOf('|') + 1))
						.sorted()
						.toList());

		this.snapshotByVersion.clear();
		this.snapshotByVersion.put(VERSION_PUBLISHED, publishedSnapshot("Câu hỏi 1: 2 + 2 bằng mấy?"));
		this.snapshotByVersion.put(VERSION_DRAFT, new ExamVersionSnapshots.Snapshot(VERSION_DRAFT, EXAM_RESOURCE,
				"draft", false, List.of()));
		this.snapshotByVersion.put(VERSION_PENDING, new ExamVersionSnapshots.Snapshot(VERSION_PENDING,
				EXAM_RESOURCE, "published", true, List.of()));
		lenient().when(this.snapshots.findByVersionId(any()))
				.thenAnswer(inv -> Optional.ofNullable(this.snapshotByVersion.get(inv.getArgument(0))));

		lenient().when(this.useDecisions.decideUse(any(), any(), any()))
				.thenAnswer(inv -> {
					TenantContext caller = inv.getArgument(0);
					UUID versionId = inv.getArgument(2);
					if (VERSION_PENDING.equals(versionId)) {
						return ExamUseDecisions.Verdict.PENDING_REVIEW;
					}
					return TEACHER_1.equals(caller.sub()) ? ExamUseDecisions.Verdict.ALLOWED
							: ExamUseDecisions.Verdict.DENIED;
				});
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
	@DisplayName("Giao bài đủ hai cổng quyền: giáo viên phụ trách có hành động giao thì giao được và hàng lần giao ghim đúng phiên bản; thiếu một trong hai cổng đều bị 403")
	void createAssignmentRequiresBothGates() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		MvcResult created = this.mockMvc
				.perform(post("/api/assignments").header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignmentJson(VERSION_PUBLISHED, CLASS_1, OffsetDateTime.now().minusHours(1),
								OffsetDateTime.now().plusHours(2))))
				.andExpect(status().isOk())
				.andReturn();
		UUID assignmentId = UUID.fromString(json(created).get("id").asText());
		UUID storedVersion = this.jdbcTemplate.queryForObject(
				"select exam_version_id from exam_assignment where id = ?", UUID.class, assignmentId);
		assertEquals(VERSION_PUBLISHED, storedVersion);

		// Giáo viên 1 không phụ trách lớp 2.
		this.mockMvc
				.perform(post("/api/assignments").header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignmentJson(VERSION_PUBLISHED, CLASS_2, OffsetDateTime.now().minusHours(1),
								OffsetDateTime.now().plusHours(2))))
				.andExpect(status().isForbidden());
		// Admin của trung tâm nhưng không dạy lớp 1.
		this.mockMvc
				.perform(post("/api/assignments").header("Authorization", "Bearer " + adminToken())
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignmentJson(VERSION_PUBLISHED, CLASS_1, OffsetDateTime.now().minusHours(1),
								OffsetDateTime.now().plusHours(2))))
				.andExpect(status().isForbidden());
		// Giáo viên 2 phụ trách lớp 2 nhưng không có hành động giao trên đề cá nhân của giáo viên 1.
		this.mockMvc
				.perform(post("/api/assignments").header("Authorization", "Bearer " + teacherToken(TEACHER_2))
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignmentJson(VERSION_PUBLISHED, CLASS_2, OffsetDateTime.now().minusHours(1),
								OffsetDateTime.now().plusHours(2))))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("Chỉ giao bản đã phát hành: bản nháp bị 409, bản chờ duyệt bị 403, cửa sổ ngược bị 422, lớp của trung tâm khác bị 404")
	void createAssignmentValidatesVersionAndClass() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		this.mockMvc
				.perform(post("/api/assignments").header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignmentJson(VERSION_DRAFT, CLASS_1, OffsetDateTime.now().minusHours(1),
								OffsetDateTime.now().plusHours(2))))
				.andExpect(status().isConflict());
		this.mockMvc
				.perform(post("/api/assignments").header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignmentJson(VERSION_PENDING, CLASS_1, OffsetDateTime.now().minusHours(1),
								OffsetDateTime.now().plusHours(2))))
				.andExpect(status().isForbidden());
		this.mockMvc
				.perform(post("/api/assignments").header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignmentJson(VERSION_PUBLISHED, CLASS_1, OffsetDateTime.now().plusHours(2),
								OffsetDateTime.now().plusHours(1))))
				.andExpect(status().isUnprocessableContent())
				.andExpect(content().string(containsString("\"code\":\"VALIDATION_FAILED\"")));
		this.mockMvc
				.perform(post("/api/assignments").header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignmentJson(VERSION_PUBLISHED, CLASS_B, OffsetDateTime.now().minusHours(1),
								OffsetDateTime.now().plusHours(2))))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("Bắt đầu đúng cửa sổ và đúng người: trước mốc mở thì học viên không thấy lần giao và bắt đầu bị 409; người không ghi danh và giáo viên gọi thay đều bị 403")
	void startAttemptWindowAndPersons() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		String student1 = studentToken(STUDENT_1);
		UUID futureAssignment = createAssignment(teacher1, VERSION_PUBLISHED, CLASS_1,
				OffsetDateTime.now().plusHours(1), OffsetDateTime.now().plusHours(3));
		this.mockMvc
				.perform(get("/api/assignments").header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString(futureAssignment.toString()))));
		this.mockMvc
				.perform(post("/api/assignments/" + futureAssignment + "/attempts")
						.header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isConflict());

		UUID openAssignment = createAssignment(teacher1, VERSION_PUBLISHED, CLASS_1,
				OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(2));
		this.mockMvc
				.perform(post("/api/assignments/" + openAssignment + "/attempts")
						.header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"IN_PROGRESS\"")));
		String freeAccount = mint("gv-tu-do", Map.of());
		this.mockMvc
				.perform(post("/api/assignments/" + openAssignment + "/attempts")
						.header("Authorization", "Bearer " + freeAccount)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isForbidden());
		this.mockMvc
				.perform(post("/api/assignments/" + openAssignment + "/attempts")
						.header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("Hình chiếu cho học viên chứa thân câu, lựa chọn và điểm nhưng không chứa đáp án đúng hay tiêu chí chấm ở bất kỳ trường nào")
	void studentProjectionHidesAnswers() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		String student1 = studentToken(STUDENT_1);
		UUID assignmentId = createAssignment(teacher1, VERSION_PUBLISHED, CLASS_1,
				OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(2));
		MvcResult started = this.mockMvc
				.perform(post("/api/assignments/" + assignmentId + "/attempts")
						.header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andReturn();
		String startedBody = started.getResponse().getContentAsString();
		assertTrue(startedBody.contains("Câu hỏi 1: 2 + 2 bằng mấy?"));
		assertTrue(startedBody.contains("\"points\":5"));
		assertTrue(!startedBody.contains("correctChoiceId"));
		assertTrue(!startedBody.contains("RUBRIC-BI-MAT"));
		UUID attemptId = UUID.fromString(json(started).get("attemptId").asText());
		this.mockMvc
				.perform(get("/api/attempts/" + attemptId).header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString("correctChoiceId"))))
				.andExpect(content().string(not(containsString("RUBRIC-BI-MAT"))));
	}

	@Test
	@DisplayName("Một học viên chỉ có một lượt cho một lần giao: bắt đầu hai lần trả cùng attemptId, câu trả lời đã lưu còn nguyên, database có đúng một hàng lượt làm")
	void startTwiceReturnsSameAttempt() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		String student1 = studentToken(STUDENT_1);
		UUID assignmentId = createAssignment(teacher1, VERSION_PUBLISHED, CLASS_1,
				OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(2));
		MvcResult first = startAttempt(student1, assignmentId);
		UUID attemptId = UUID.fromString(json(first).get("attemptId").asText());
		saveAnswers(student1, attemptId, "[{\"examItemId\":\"" + ITEM_ESSAY
				+ "\",\"answer\":{\"text\":\"bai lam cua toi\"}}]");
		MvcResult second = startAttempt(student1, assignmentId);
		assertEquals(attemptId.toString(), json(second).get("attemptId").asText());
		assertTrue(second.getResponse().getContentAsString().contains("bai lam cua toi"));
		Integer count = this.jdbcTemplate.queryForObject(
				"select count(*) from exam_attempt where assignment_id = ? and student_sub = ?", Integer.class,
				assignmentId, STUDENT_1);
		assertEquals(1, count);
	}

	@Test
	@DisplayName("Lưu từng phần chỉ ghi mục được nêu; mục không thuộc snapshot hoặc lựa chọn sai đều bị 422 và không ghi gì")
	void saveAnswersPartialAndValidated() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		String student1 = studentToken(STUDENT_1);
		UUID assignmentId = createAssignment(teacher1, VERSION_PUBLISHED, CLASS_1,
				OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(2));
		UUID attemptId = UUID.fromString(json(startAttempt(student1, assignmentId)).get("attemptId").asText());
		saveAnswers(student1, attemptId,
				"[{\"examItemId\":\"" + ITEM_CHOICE + "\",\"answer\":{\"choiceId\":\"c2\"}}]");
		MvcResult view = this.mockMvc
				.perform(get("/api/attempts/" + attemptId).header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode answers = json(view).get("answers");
		assertEquals(1, answers.size());
		assertEquals(ITEM_CHOICE.toString(), answers.get(0).get("examItemId").asText());

		this.mockMvc
				.perform(put("/api/attempts/" + attemptId + "/answers")
						.header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"answers\":[{\"examItemId\":\"" + UUID.randomUUID()
								+ "\",\"answer\":{\"choiceId\":\"c1\"}}]}"))
				.andExpect(status().isUnprocessableContent());
		this.mockMvc
				.perform(put("/api/attempts/" + attemptId + "/answers")
						.header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"answers\":[{\"examItemId\":\"" + ITEM_CHOICE
								+ "\",\"answer\":{\"choiceId\":\"c9\"}}]}"))
				.andExpect(status().isUnprocessableContent());
		Integer stored = this.jdbcTemplate.queryForObject(
				"select count(*) from attempt_answer where attempt_id = ?", Integer.class, attemptId);
		assertEquals(1, stored);
	}

	@Test
	@DisplayName("Khách đã ghi danh làm được bài như thành viên; trước khi ghi danh thì bị 403 và không thấy lần giao")
	void guestStudentFlow() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		String guest = mint(STUDENT_2, Map.of("organization",
				Map.of("trung-tam-2", orgEntry(ORG_2_ID, List.of("tenant-member")))));
		UUID assignmentId = createAssignment(teacher1, VERSION_PUBLISHED, CLASS_1,
				OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(2));
		this.mockMvc
				.perform(post("/api/assignments/" + assignmentId + "/attempts")
						.header("Authorization", "Bearer " + guest)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isForbidden());
		this.mockMvc
				.perform(get("/api/assignments").header("Authorization", "Bearer " + guest)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString(assignmentId.toString()))));

		enrol(STUDENT_2, CLASS_1);
		MvcResult started = startAttempt(guest, assignmentId);
		UUID attemptId = UUID.fromString(json(started).get("attemptId").asText());
		saveAnswers(guest, attemptId,
				"[{\"examItemId\":\"" + ITEM_CHOICE + "\",\"answer\":{\"choiceId\":\"c2\"}}]");
		this.mockMvc
				.perform(post("/api/attempts/" + attemptId + "/submit")
						.header("Authorization", "Bearer " + guest)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"SUBMITTED\"")));
	}

	@Test
	@DisplayName("Nộp chống trùng: nộp ba lần đều trả SUBMITTED với cùng submitted_at và database vẫn có đúng một lượt làm")
	void submitIsIdempotent() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		String student1 = studentToken(STUDENT_1);
		UUID assignmentId = createAssignment(teacher1, VERSION_PUBLISHED, CLASS_1,
				OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(2));
		UUID attemptId = UUID.fromString(json(startAttempt(student1, assignmentId)).get("attemptId").asText());
		String firstSubmittedAt = null;
		for (int i = 0; i < 3; i++) {
			MvcResult result = this.mockMvc
					.perform(post("/api/attempts/" + attemptId + "/submit")
							.header("Authorization", "Bearer " + student1)
							.header("X-LMS-Organization-Alias", "trung-tam-1"))
					.andExpect(status().isOk())
					.andExpect(content().string(containsString("\"status\":\"SUBMITTED\"")))
					.andReturn();
			String submittedAt = json(result).get("submittedAt").asText();
			if (firstSubmittedAt == null) {
				firstSubmittedAt = submittedAt;
			}
			assertEquals(firstSubmittedAt, submittedAt);
		}
		Integer count = this.jdbcTemplate.queryForObject(
				"select count(*) from exam_attempt where assignment_id = ? and student_sub = ?", Integer.class,
				assignmentId, STUDENT_1);
		assertEquals(1, count);
	}

	@Test
	@DisplayName("Chốt hết hạn: lần đọc đầu tiên sau hạn cho thấy lượt đã SUBMITTED dạng auto_deadline với câu trả lời gần nhất còn nguyên; lưu thêm bị 409; nộp tay sau hạn trả kết quả chốt đó")
	void deadlineFinalizesAttemptLazily() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		String student1 = studentToken(STUDENT_1);
		UUID assignmentId = createAssignment(teacher1, VERSION_PUBLISHED, CLASS_1,
				OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(2));
		UUID attemptId = UUID.fromString(json(startAttempt(student1, assignmentId)).get("attemptId").asText());
		saveAnswers(student1, attemptId,
				"[{\"examItemId\":\"" + ITEM_CHOICE + "\",\"answer\":{\"choiceId\":\"c1\"}}]");
		OffsetDateTime pastDue = OffsetDateTime.now().minusMinutes(5);
		this.mockMvc
				.perform(patch("/api/assignments/" + assignmentId).header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"dueAt\":\"" + pastDue + "\"}"))
				.andExpect(status().isOk());
		MvcResult view = this.mockMvc
				.perform(get("/api/attempts/" + attemptId).header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"SUBMITTED\"")))
				.andExpect(content().string(containsString("\"submitMode\":\"auto_deadline\"")))
				.andReturn();
		assertTrue(view.getResponse().getContentAsString().contains("\"choiceId\":\"c1\""));
		this.mockMvc
				.perform(put("/api/attempts/" + attemptId + "/answers")
						.header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"answers\":[{\"examItemId\":\"" + ITEM_ESSAY
								+ "\",\"answer\":{\"text\":\"den muon\"}}]}"))
				.andExpect(status().isConflict());
		this.mockMvc
				.perform(post("/api/attempts/" + attemptId + "/submit")
						.header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"submitMode\":\"auto_deadline\"")));
		Integer answers = this.jdbcTemplate.queryForObject(
				"select count(*) from attempt_answer where attempt_id = ?", Integer.class, attemptId);
		assertEquals(1, answers);
	}

	@Test
	@DisplayName("Nội dung lượt làm chỉ lấy từ snapshot của đúng phiên bản đã ghim: đề có phiên bản mới thì bài đã giao vẫn giữ nội dung của phiên bản đã giao")
	void attemptContentComesOnlyFromPinnedSnapshot() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		String student1 = studentToken(STUDENT_1);
		UUID assignmentId = createAssignment(teacher1, VERSION_PUBLISHED, CLASS_1,
				OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(2));
		UUID attemptId = UUID.fromString(json(startAttempt(student1, assignmentId)).get("attemptId").asText());
		UUID version2 = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddddd9");
		this.snapshotByVersion.put(version2, publishedSnapshot("Câu hỏi 1 PHIÊN BẢN MỚI"));
		this.mockMvc
				.perform(get("/api/attempts/" + attemptId).header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Câu hỏi 1: 2 + 2 bằng mấy?")))
				.andExpect(content().string(not(containsString("PHIÊN BẢN MỚI"))));
	}

	@Test
	@DisplayName("Chủ đề không còn phụ trách lớp thì không đọc được bảng trạng thái hay lượt làm; học viên đọc lượt của bạn học trả 404 không kèm dữ kiện")
	void examOwnerWithoutTeachingCannotSeeAttempts() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		String student1 = studentToken(STUDENT_1);
		enrol(STUDENT_2, CLASS_1);
		String classmate = mint(STUDENT_2, Map.of("organization",
				Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-member")))));
		UUID assignmentId = createAssignment(teacher1, VERSION_PUBLISHED, CLASS_1,
				OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(2));
		UUID attemptId = UUID.fromString(json(startAttempt(student1, assignmentId)).get("attemptId").asText());
		this.mockMvc
				.perform(get("/api/attempts/" + attemptId).header("Authorization", "Bearer " + classmate)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isNotFound());
		this.classTeachers.put(ORG_1_ID + "|" + CLASS_1, TEACHER_2);
		this.mockMvc
				.perform(get("/api/assignments/" + assignmentId + "/submissions")
						.header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isForbidden());
		this.mockMvc
				.perform(get("/api/attempts/" + attemptId).header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("Bảng trạng thái trả đúng ba trạng thái của lớp và giữ học viên đã nộp dù sau đó bị gỡ ghi danh, với dấu đã gỡ")
	void submissionsTableReflectsClassStates() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		String student1 = studentToken(STUDENT_1);
		enrol(STUDENT_2, CLASS_1);
		String student2 = mint(STUDENT_2, Map.of("organization",
				Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-member")))));
		UUID assignmentId = createAssignment(teacher1, VERSION_PUBLISHED, CLASS_1,
				OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusHours(2));
		UUID attempt1 = UUID.fromString(json(startAttempt(student1, assignmentId)).get("attemptId").asText());
		this.mockMvc
				.perform(post("/api/attempts/" + attempt1 + "/submit")
						.header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk());
		startAttempt(student2, assignmentId);
		MvcResult table = this.mockMvc
				.perform(get("/api/assignments/" + assignmentId + "/submissions")
						.header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andReturn();
		Map<String, JsonNode> rowsByStudent = new HashMap<>();
		json(table).forEach(row -> rowsByStudent.put(row.get("studentSub").asText(), row));
		assertEquals("SUBMITTED", rowsByStudent.get(STUDENT_1).get("status").asText());
		assertEquals("IN_PROGRESS", rowsByStudent.get(STUDENT_2).get("status").asText());
		assertEquals("NOT_STARTED", rowsByStudent.get(STUDENT_3).get("status").asText());

		this.removedEnrolments.add(CLASS_1 + "|" + STUDENT_1);
		MvcResult afterRemoval = this.mockMvc
				.perform(get("/api/assignments/" + assignmentId + "/submissions")
						.header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andReturn();
		Map<String, JsonNode> afterByStudent = new HashMap<>();
		json(afterRemoval).forEach(row -> afterByStudent.put(row.get("studentSub").asText(), row));
		assertEquals("SUBMITTED", afterByStudent.get(STUDENT_1).get("status").asText());
		assertTrue(afterByStudent.get(STUDENT_1).get("removed").asBoolean());
	}

	@Test
	@DisplayName("Rút lần giao chưa có lượt làm thì thành công; đã có lượt làm thì bị 409")
	void deleteAssignmentRules() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		String student1 = studentToken(STUDENT_1);
		UUID empty = createAssignment(teacher1, VERSION_PUBLISHED, CLASS_1, OffsetDateTime.now().minusHours(1),
				OffsetDateTime.now().plusHours(2));
		this.mockMvc
				.perform(delete("/api/assignments/" + empty).header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isNoContent());
		UUID used = createAssignment(teacher1, VERSION_PUBLISHED, CLASS_1, OffsetDateTime.now().minusHours(1),
				OffsetDateTime.now().plusHours(2));
		startAttempt(student1, used);
		this.mockMvc
				.perform(delete("/api/assignments/" + used).header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isConflict());
	}

	private void enrol(String studentSub, UUID classId) {
		this.enrolments.add(classId + "|" + studentSub);
	}

	private ExamVersionSnapshots.Snapshot publishedSnapshot(String prompt) {
		return new ExamVersionSnapshots.Snapshot(VERSION_PUBLISHED, EXAM_RESOURCE, "published", false,
				List.of(new ExamVersionSnapshots.Item(ITEM_CHOICE, "single_choice", 1, new BigDecimal("5"),
								prompt,
								List.of(new ExamVersionSnapshots.Choice("c1", "3"),
										new ExamVersionSnapshots.Choice("c2", "4")),
								"c2", null, List.of(), List.of()),
						new ExamVersionSnapshots.Item(ITEM_ESSAY, "essay_text", 2, new BigDecimal("5"),
								"Trình bày cách giải.", List.of(), null, "RUBRIC-BI-MAT", List.of(), List.of())));
	}

	private UUID createAssignment(String teacherToken, UUID versionId, UUID classId, OffsetDateTime opensAt,
			OffsetDateTime dueAt) throws Exception {
		MvcResult result = this.mockMvc
				.perform(post("/api/assignments").header("Authorization", "Bearer " + teacherToken)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(assignmentJson(versionId, classId, opensAt, dueAt)))
				.andExpect(status().isOk())
				.andReturn();
		return UUID.fromString(json(result).get("id").asText());
	}

	private MvcResult startAttempt(String studentToken, UUID assignmentId) throws Exception {
		return this.mockMvc
				.perform(post("/api/assignments/" + assignmentId + "/attempts")
						.header("Authorization", "Bearer " + studentToken)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andReturn();
	}

	private void saveAnswers(String studentToken, UUID attemptId, String answersJson) throws Exception {
		this.mockMvc
				.perform(put("/api/attempts/" + attemptId + "/answers")
						.header("Authorization", "Bearer " + studentToken)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"answers\":" + answersJson + "}"))
				.andExpect(status().isOk());
	}

	private String assignmentJson(UUID versionId, UUID classId, OffsetDateTime opensAt, OffsetDateTime dueAt) {
		return "{\"examVersionId\":\"" + versionId + "\",\"classId\":\"" + classId + "\",\"opensAt\":\""
				+ opensAt + "\",\"dueAt\":\"" + dueAt + "\"}";
	}

	private JsonNode json(MvcResult result) throws Exception {
		return this.objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private String teacherToken(String sub) throws Exception {
		return mint(sub, Map.of("organization",
				Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-teacher")))));
	}

	private String studentToken(String sub) throws Exception {
		return mint(sub, Map.of("organization",
				Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-member")))));
	}

	private String adminToken() throws Exception {
		return mint(ADMIN_1, Map.of("organization",
				Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-admin")))));
	}

	private Map<String, Object> orgEntry(String id, List<String> roles) {
		return Map.of("id", id, "realm_access", Map.of("roles", roles));
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
		jwt.sign(new RSASSASigner((RSAPrivateKey) TestJwtConfiguration.KEY_PAIR.getPrivate()));
		return jwt.serialize();
	}

}
