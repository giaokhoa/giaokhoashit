package com.giaokhoa.server.assignment;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

// Kiểm thử hợp đồng riêng của tệp bài làm (NT-34) trên đường thật: chuỗi lọc bảo mật và ngữ cảnh tenant,
// tầng dữ kiện thật (Flyway + Hibernate/JPA với discriminator) trên H2 ở chế độ PostgreSQL. Điểm nối nền
// tệp của lát L1 được thay bằng bản giả lập có kiểm soát đúng khuôn các điểm nối khác của khối này: phiên
// tải lên và trạng thái chốt của blob do test điều khiển để dựng được các ca đang xử lý, chốt trong hạn và
// chốt sau hạn. Lớp RLS của PostgreSQL kiểm riêng trên PostgreSQL thật.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + SubmissionFileEndpointTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:submissionfiles;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration" })
class SubmissionFileEndpointTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	private static final UUID CLASS_1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");

	private static final UUID CLASS_2 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");

	private static final UUID VERSION_PUBLISHED = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddddd1");

	private static final UUID ITEM_CHOICE = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1");

	private static final String TEACHER_1 = "gv-trung-tam-1";

	private static final String TEACHER_2 = "gv2-trung-tam-1";

	private static final String STUDENT_1 = "hs-trung-tam-1";

	private static final String STUDENT_2 = "hs-trung-tam-2";

	private static final byte[] PNG_BYTES = "PNG-BYTES".getBytes(StandardCharsets.UTF_8);

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

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

	@MockitoBean
	private SubmissionFileBlobs blobs;

	private MockMvc mockMvc;

	private final Map<String, String> classTeachers = new HashMap<>();

	private final Set<String> enrolments = new HashSet<>();

	private final Map<UUID, SubmissionFileBlobs.SessionInfo> sessions = new HashMap<>();

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		lenient().when(this.membershipChecker.check(anyString(), anyString()))
				.thenReturn(MembershipChecker.Result.MEMBER);
		lenient().when(this.organizationIds.findOrganizationIdByAlias("trung-tam-1"))
				.thenReturn(Optional.of(UUID.fromString(ORG_1_ID)));

		this.classTeachers.clear();
		this.classTeachers.put(ORG_1_ID + "|" + CLASS_1, TEACHER_1);
		this.classTeachers.put(ORG_1_ID + "|" + CLASS_2, TEACHER_2);
		this.enrolments.clear();
		this.enrolments.add(CLASS_1 + "|" + STUDENT_1);
		this.enrolments.add(CLASS_1 + "|" + STUDENT_2);
		this.sessions.clear();

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
						&& this.enrolments.contains(inv.getArgument(2) + "|" + inv.getArgument(0)));
		lenient().when(this.teaching.classIdsTaughtBy(anyString(), anyString())).thenReturn(List.of());
		lenient().when(this.teaching.classIdsEnrolledBy(anyString(), anyString())).thenReturn(List.of());
		lenient().when(this.teaching.activeRoster(anyString(), any())).thenReturn(List.of());

		lenient().when(this.snapshots.findByVersionId(any()))
				.thenReturn(Optional.of(new ExamVersionSnapshots.Snapshot(VERSION_PUBLISHED,
						UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc1"), "published", false,
						List.of(new ExamVersionSnapshots.Item(ITEM_CHOICE, "single_choice", 1,
								new BigDecimal("5"), "Câu hỏi 1: 2 + 2 bằng mấy?",
								List.of(new ExamVersionSnapshots.Choice("c1", "3"),
										new ExamVersionSnapshots.Choice("c2", "4")),
								"c2", null)))));
		lenient().when(this.useDecisions.decideUse(any(), any(), any()))
				.thenReturn(ExamUseDecisions.Verdict.ALLOWED);

		lenient().when(this.blobs.findSession(any()))
				.thenAnswer(inv -> Optional.ofNullable(this.sessions.get(inv.getArgument(0))));
		lenient().when(this.blobs.readBlob(any())).thenReturn(Optional.of(PNG_BYTES));
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
	@DisplayName("NT-34a: tệp bài làm chỉ đọc được bởi học viên chủ bài làm và giáo viên được phân công dạy lớp; giáo viên cùng trung tâm không phân công bị 403, bạn học cùng lớp nhận 404")
	void readAccessFollowsAssignedPersons() throws Exception {
		String student1 = studentToken(STUDENT_1);
		UUID attemptId = startAttemptFor(student1);
		UUID sessionId = readySession(STUDENT_1, OffsetDateTime.now());
		MvcResult attached = attach(student1, attemptId, sessionId);
		UUID fileId = UUID.fromString(json(attached).get("id").asText());
		assertEquals("ready", json(attached).get("status").asText());
		assertTrue(json(attached).get("submitted").asBoolean());
		assertEquals(7, fileId.version());

		this.mockMvc
				.perform(get("/api/attempts/" + attemptId + "/files").header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"ready\"")));
		this.mockMvc
				.perform(get("/api/attempts/" + attemptId + "/files/" + fileId + "/content")
						.header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().bytes(PNG_BYTES));

		String teacher1 = teacherToken(TEACHER_1);
		this.mockMvc
				.perform(get("/api/attempts/" + attemptId + "/files").header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk());
		this.mockMvc
				.perform(get("/api/attempts/" + attemptId + "/files/" + fileId + "/content")
						.header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().bytes(PNG_BYTES));

		String teacher2 = teacherToken(TEACHER_2);
		this.mockMvc
				.perform(get("/api/attempts/" + attemptId + "/files").header("Authorization", "Bearer " + teacher2)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isForbidden());
		this.mockMvc
				.perform(get("/api/attempts/" + attemptId + "/files/" + fileId + "/content")
						.header("Authorization", "Bearer " + teacher2)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isForbidden());

		String classmate = studentToken(STUDENT_2);
		this.mockMvc
				.perform(get("/api/attempts/" + attemptId + "/files").header("Authorization", "Bearer " + classmate)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("NT-34b: tệp hoàn tất chốt sau hạn hiệu lực của lượt làm bị suy trạng thái từ chối và không tính là đã nộp; gắn mới sau hạn bị 409")
	void fileCompletedAfterDeadlineIsNotSubmitted() throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		String student1 = studentToken(STUDENT_1);
		UUID assignmentId = createAssignment(teacher1, OffsetDateTime.now().minusHours(1),
				OffsetDateTime.now().plusHours(2));
		UUID attemptId = startAttempt(student1, assignmentId);
		UUID sessionId = UUID.randomUUID();
		this.sessions.put(sessionId, new SubmissionFileBlobs.SessionInfo(sessionId, "OPEN", null, null,
				"bai-lam.png", null, null, STUDENT_1, null));
		MvcResult attached = attach(student1, attemptId, sessionId);
		assertEquals("processing", json(attached).get("status").asText());
		assertFalse(json(attached).get("submitted").asBoolean());

		this.mockMvc
				.perform(patch("/api/assignments/" + assignmentId).header("Authorization", "Bearer " + teacher1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"dueAt\":\"" + OffsetDateTime.now().minusMinutes(5) + "\"}"))
				.andExpect(status().isOk());
		UUID blobId = UUID.randomUUID();
		this.sessions.put(sessionId, new SubmissionFileBlobs.SessionInfo(sessionId, "COMPLETED", blobId, "READY",
				"bai-lam.png", "image/png", 1024L, STUDENT_1, OffsetDateTime.now()));

		MvcResult listed = this.mockMvc
				.perform(get("/api/attempts/" + attemptId + "/files").header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andReturn();
		assertEquals("rejected", json(listed).get(0).get("status").asText());
		assertFalse(json(listed).get(0).get("submitted").asBoolean());

		UUID lateSession = readySession(STUDENT_1, OffsetDateTime.now());
		this.mockMvc
				.perform(post("/api/attempts/" + attemptId + "/files").header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"uploadSessionId\":\"" + lateSession + "\"}"))
				.andExpect(status().isConflict());
	}

	@Test
	@DisplayName("NT-34c: sau khi bài đã nộp và khoá, gắn thêm, gỡ tệp đã có và gắn lại thay thế đều bị 409 và danh sách tệp giữ nguyên")
	void filesAreLockedAfterSubmit() throws Exception {
		String student1 = studentToken(STUDENT_1);
		UUID attemptId = startAttemptFor(student1);
		UUID firstSession = readySession(STUDENT_1, OffsetDateTime.now());
		UUID fileId = UUID.fromString(json(attach(student1, attemptId, firstSession)).get("id").asText());
		this.mockMvc
				.perform(post("/api/attempts/" + attemptId + "/submit").header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"SUBMITTED\"")));

		UUID secondSession = readySession(STUDENT_1, OffsetDateTime.now());
		this.mockMvc
				.perform(post("/api/attempts/" + attemptId + "/files").header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"uploadSessionId\":\"" + secondSession + "\"}"))
				.andExpect(status().isConflict());
		this.mockMvc
				.perform(delete("/api/attempts/" + attemptId + "/files/" + fileId)
						.header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isConflict());

		MvcResult listed = this.mockMvc
				.perform(get("/api/attempts/" + attemptId + "/files").header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andReturn();
		assertEquals(1, json(listed).size());
		assertEquals("ready", json(listed).get(0).get("status").asText());
	}

	@Test
	@DisplayName("NT-34d (phía server): trạng thái trả về khớp dữ kiện đã chốt — tệp đang xử lý mang processing và submitted=false, chỉ khi phiên chốt xong trong hạn mới thành ready và submitted=true")
	void statusReflectsFinalizedServerData() throws Exception {
		String student1 = studentToken(STUDENT_1);
		UUID attemptId = startAttemptFor(student1);
		UUID sessionId = UUID.randomUUID();
		this.sessions.put(sessionId, new SubmissionFileBlobs.SessionInfo(sessionId, "OPEN", null, null,
				"bai-lam.png", null, null, STUDENT_1, null));
		MvcResult attached = attach(student1, attemptId, sessionId);
		assertEquals("processing", json(attached).get("status").asText());
		assertFalse(json(attached).get("submitted").asBoolean());

		OffsetDateTime finalizedAt = OffsetDateTime.now();
		this.sessions.put(sessionId, new SubmissionFileBlobs.SessionInfo(sessionId, "COMPLETED", UUID.randomUUID(),
				"READY", "bai-lam.png", "image/png", 2048L, STUDENT_1, finalizedAt));
		MvcResult listed = this.mockMvc
				.perform(get("/api/attempts/" + attemptId + "/files").header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andReturn();
		assertEquals("ready", json(listed).get(0).get("status").asText());
		assertTrue(json(listed).get(0).get("submitted").asBoolean());
		assertEquals("image/png", json(listed).get(0).get("contentType").asText());
		assertEquals(2048, json(listed).get(0).get("sizeBytes").asLong());
	}

	@Test
	@DisplayName("Quyền gắn và hạn mức: phiên của người khác bị 403, định dạng ngoài PNG/JPEG/WebP bị 422, tệp thứ 11 bị 422, gắn lại cùng phiên trả nguyên hàng không tạo trùng")
	void attachOwnershipAndLimits() throws Exception {
		String student1 = studentToken(STUDENT_1);
		UUID attemptId = startAttemptFor(student1);

		UUID foreignSession = readySession(STUDENT_2, OffsetDateTime.now());
		this.mockMvc
				.perform(post("/api/attempts/" + attemptId + "/files").header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"uploadSessionId\":\"" + foreignSession + "\"}"))
				.andExpect(status().isForbidden());

		UUID pdfSession = UUID.randomUUID();
		this.sessions.put(pdfSession, new SubmissionFileBlobs.SessionInfo(pdfSession, "COMPLETED", UUID.randomUUID(),
				"READY", "bai-lam.pdf", "application/pdf", 1024L, STUDENT_1, OffsetDateTime.now()));
		this.mockMvc
				.perform(post("/api/attempts/" + attemptId + "/files").header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"uploadSessionId\":\"" + pdfSession + "\"}"))
				.andExpect(status().isUnprocessableContent());

		UUID first = readySession(STUDENT_1, OffsetDateTime.now());
		String firstFileId = json(attach(student1, attemptId, first)).get("id").asText();
		String retryFileId = json(attach(student1, attemptId, first)).get("id").asText();
		assertEquals(firstFileId, retryFileId);
		for (int i = 0; i < 9; i++) {
			attach(student1, attemptId, readySession(STUDENT_1, OffsetDateTime.now()));
		}
		UUID eleventh = readySession(STUDENT_1, OffsetDateTime.now());
		this.mockMvc
				.perform(post("/api/attempts/" + attemptId + "/files").header("Authorization", "Bearer " + student1)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"uploadSessionId\":\"" + eleventh + "\"}"))
				.andExpect(status().isUnprocessableContent());
	}

	private UUID readySession(String ownerSub, OffsetDateTime finalizedAt) {
		UUID sessionId = UUID.randomUUID();
		UUID blobId = UUID.randomUUID();
		this.sessions.put(sessionId, new SubmissionFileBlobs.SessionInfo(sessionId, "COMPLETED", blobId,
				"READY", "bai-lam.png", "image/png", 1024L, ownerSub, finalizedAt));
		// Blob của phiên phải tồn tại thật trong nền tệp: submission_file mang khoá ngoại sang file_blob.
		this.jdbcTemplate.update("insert into file_blob (id, storage_key, content_type, size_bytes, "
				+ "checksum_sha256, status, uploaded_by_sub, partition_key) values (?, ?, 'image/png', 1024, "
				+ "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'READY', ?, ?)",
				blobId, "test-" + blobId, ownerSub, ORG_1_ID);
		return sessionId;
	}

	private UUID startAttemptFor(String studentToken) throws Exception {
		String teacher1 = teacherToken(TEACHER_1);
		UUID assignmentId = createAssignment(teacher1, OffsetDateTime.now().minusHours(1),
				OffsetDateTime.now().plusHours(2));
		return startAttempt(studentToken, assignmentId);
	}

	private UUID createAssignment(String teacherToken, OffsetDateTime opensAt, OffsetDateTime dueAt)
			throws Exception {
		MvcResult result = this.mockMvc
				.perform(post("/api/assignments").header("Authorization", "Bearer " + teacherToken)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"examVersionId\":\"" + VERSION_PUBLISHED + "\",\"classId\":\"" + CLASS_1
								+ "\",\"opensAt\":\"" + opensAt + "\",\"dueAt\":\"" + dueAt + "\"}"))
				.andExpect(status().isOk())
				.andReturn();
		return UUID.fromString(json(result).get("id").asText());
	}

	private UUID startAttempt(String studentToken, UUID assignmentId) throws Exception {
		MvcResult result = this.mockMvc
				.perform(post("/api/assignments/" + assignmentId + "/attempts")
						.header("Authorization", "Bearer " + studentToken)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andReturn();
		return UUID.fromString(json(result).get("attemptId").asText());
	}

	private MvcResult attach(String studentToken, UUID attemptId, UUID sessionId) throws Exception {
		return this.mockMvc
				.perform(post("/api/attempts/" + attemptId + "/files")
						.header("Authorization", "Bearer " + studentToken)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"uploadSessionId\":\"" + sessionId + "\"}"))
				.andExpect(status().isOk())
				.andReturn();
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
