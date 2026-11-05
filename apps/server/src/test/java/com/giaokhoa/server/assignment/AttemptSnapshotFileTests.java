package com.giaokhoa.server.assignment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.giaokhoa.server.attachment.AttachmentStore;
import com.giaokhoa.server.tenancy.MembershipChecker;
import com.giaokhoa.server.tenancy.TenantContext;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

// Kiểm thử luồng tệp của lượt làm trên khuôn của AssignmentEndpointTests: snapshot của phiên bản đề được
// thay bằng dữ kiện đóng băng có tệp đính kèm và ảnh công thức ở cả phần nội dung lẫn phần đáp án, kho
// blob được thay bằng bản giả lập theo đúng hợp đồng cổng; phần còn lại (giao bài, lượt làm, quyết quyền
// đọc tệp theo snapshot) là thật. Học viên chỉ thấy và chỉ tải được tệp thuộc phần trình bày của snapshot
// của chính lượt làm của mình.
@SpringBootTest(properties = {
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=" + AttemptSnapshotFileTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:attempt129;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration" })
class AttemptSnapshotFileTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	private static final UUID CLASS_1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");

	private static final UUID EXAM_RESOURCE = UUID.fromString("cccccccc-cccc-cccc-cccc-ccccccccccc1");

	private static final UUID VERSION_PUBLISHED = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddddd1");

	private static final UUID ITEM_CHOICE = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1");

	private static final String TEACHER_1 = "gv-trung-tam-1";

	private static final String STUDENT_1 = "hs-trung-tam-1";

	private static final String STUDENT_3 = "hs-3";

	private static final UUID BLOB_CONTENT = UUID.fromString("01890000-0000-7000-8000-00000000c001");

	private static final UUID BLOB_ANSWER = UUID.fromString("01890000-0000-7000-8000-00000000c002");

	private static final UUID BLOB_FORMULA = UUID.fromString("01890000-0000-7000-8000-00000000c003");

	private static final UUID BLOB_SOLUTION = UUID.fromString("01890000-0000-7000-8000-00000000c004");

	private static final byte[] PNG_CONTENT = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 1, 1, 1 };

	private static final byte[] PNG_FORMULA = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 2, 2, 2 };

	@Autowired
	private WebApplicationContext context;

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

	@MockitoBean
	private AttachmentStore attachmentStore;

	private MockMvc mockMvc;

	private final Map<UUID, byte[]> blobBytes = new HashMap<>();

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		lenient().when(this.membershipChecker.check(anyString(), anyString()))
				.thenReturn(MembershipChecker.Result.MEMBER);
		lenient().when(this.organizationIds.findOrganizationIdByAlias("trung-tam-1"))
				.thenReturn(Optional.of(UUID.fromString(ORG_1_ID)));
		Set<String> enrolments = new HashSet<>(Set.of(CLASS_1 + "|" + STUDENT_1, CLASS_1 + "|" + STUDENT_3));
		lenient().when(this.teaching.findClass(ORG_1_ID, CLASS_1))
				.thenReturn(Optional.of(new TeachingChecks.ClassView(CLASS_1, TEACHER_1)));
		lenient().when(this.teaching.isAssignedToTeach(anyString(), anyString(), any()))
				.thenAnswer(inv -> TEACHER_1.equals(inv.getArgument(0)) && ORG_1_ID.equals(inv.getArgument(1))
						&& CLASS_1.equals(inv.getArgument(2)));
		lenient().when(this.teaching.isEnrolled(anyString(), anyString(), any()))
				.thenAnswer(inv -> ORG_1_ID.equals(inv.getArgument(1))
						&& enrolments.contains(inv.getArgument(2) + "|" + inv.getArgument(0)));
		lenient().when(this.snapshots.findByVersionId(VERSION_PUBLISHED))
				.thenReturn(Optional.of(frozenSnapshot()));
		lenient().when(this.useDecisions.decideUse(any(), any(), any())).thenAnswer(inv -> {
			TenantContext caller = inv.getArgument(0);
			return TEACHER_1.equals(caller.sub()) ? ExamUseDecisions.Verdict.ALLOWED
					: ExamUseDecisions.Verdict.DENIED;
		});
		this.blobBytes.clear();
		this.blobBytes.put(BLOB_CONTENT, PNG_CONTENT);
		this.blobBytes.put(BLOB_FORMULA, PNG_FORMULA);
		this.blobBytes.put(BLOB_ANSWER, new byte[] { 9, 9, 9 });
		this.blobBytes.put(BLOB_SOLUTION, new byte[] { 8, 8, 8 });
		lenient().when(this.attachmentStore.findBlob(any())).thenAnswer(inv -> {
			UUID blobId = inv.getArgument(0);
			byte[] bytes = this.blobBytes.get(blobId);
			return bytes == null ? Optional.empty()
					: Optional.of(new AttachmentStore.Blob(blobId, "image/png", bytes.length, "chk", 30, 18,
							AttachmentStore.BLOB_READY));
		});
		lenient().when(this.attachmentStore.readBlob(any())).thenAnswer(inv -> {
			UUID blobId = inv.getArgument(0);
			return Optional.ofNullable(this.blobBytes.get(blobId));
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
	@DisplayName("Luồng học viên trên snapshot đã đóng băng: hình chiếu lượt làm chỉ chứa tệp và ảnh thuộc "
			+ "phần trình bày; học viên tải được đúng các tệp đó qua đường tệp của lượt làm, tệp thuộc phần "
			+ "đáp án và tệp lạ bị 404, học viên khác không tải được, giáo viên được phân công thì tải được")
	void studentReadsOnlyPresentationFilesOfOwnAttempt() throws Exception {
		String teacher = teacherToken(TEACHER_1);
		String student = studentToken(STUDENT_1);
		UUID assignmentId = createAssignment(teacher);
		MvcResult started = this.mockMvc
				.perform(post("/api/assignments/" + assignmentId + "/attempts")
						.header("Authorization", "Bearer " + student)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk()).andReturn();
		UUID attemptId = UUID.fromString(json(started).get("attemptId").asText());

		MvcResult view = this.mockMvc
				.perform(get("/api/attempts/" + attemptId).header("Authorization", "Bearer " + student)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk()).andReturn();
		String viewBody = body(view);
		JsonNode item = json(view).get("items").get(0);
		assertEquals(1, item.get("attachments").size());
		assertEquals(BLOB_CONTENT.toString(), item.get("attachments").get(0).get("blobId").asText());
		assertEquals(1, item.get("formulaImages").size());
		assertEquals(BLOB_FORMULA.toString(), item.get("formulaImages").get(0).get("imageBlobId").asText());
		assertFalse(viewBody.contains(BLOB_ANSWER.toString()), viewBody);
		assertFalse(viewBody.contains(BLOB_SOLUTION.toString()), viewBody);

		assertArrayEquals(PNG_CONTENT, fileBytes(student, attemptId, BLOB_CONTENT, 200));
		assertArrayEquals(PNG_FORMULA, fileBytes(student, attemptId, BLOB_FORMULA, 200));
		fileBytes(student, attemptId, BLOB_ANSWER, 404);
		fileBytes(student, attemptId, BLOB_SOLUTION, 404);
		fileBytes(student, attemptId, UUID.randomUUID(), 404);
		String otherStudent = studentToken(STUDENT_3);
		fileBytes(otherStudent, attemptId, BLOB_CONTENT, 404);
		assertArrayEquals(PNG_CONTENT, fileBytes(teacher, attemptId, BLOB_CONTENT, 200));
	}

	private ExamVersionSnapshots.Snapshot frozenSnapshot() {
		ExamVersionSnapshots.Item item = new ExamVersionSnapshots.Item(ITEM_CHOICE, "single_choice", 1,
				new BigDecimal("5"), "Câu hỏi có tệp và công thức.",
				List.of(new ExamVersionSnapshots.Choice("c1", "3"), new ExamVersionSnapshots.Choice("c2", "4")),
				"c2", null,
				List.of(new ExamVersionSnapshots.Attachment(BLOB_CONTENT, "content", null, "anh-noi-dung.png",
						"image/png", 30, 18, "user:gv-soan"),
						new ExamVersionSnapshots.Attachment(BLOB_ANSWER, "answer", null, "anh-dap-an.png",
								"image/png", 30, 18, "user:gv-soan")),
				List.of(new ExamVersionSnapshots.FormulaImage("f-than", "stem", 0, "ref-than", BLOB_FORMULA, 40,
						20, 5, "cong-thuc-than", "user:gv-soan"),
						new ExamVersionSnapshots.FormulaImage("f-giai", "solution", 1, "ref-giai", BLOB_SOLUTION,
								40, 20, 5, "cong-thuc-giai", "user:gv-soan")));
		return new ExamVersionSnapshots.Snapshot(VERSION_PUBLISHED, EXAM_RESOURCE, "published", false,
				List.of(item));
	}

	private UUID createAssignment(String teacherToken) throws Exception {
		String body = "{\"examVersionId\":\"" + VERSION_PUBLISHED + "\",\"classId\":\"" + CLASS_1
				+ "\",\"opensAt\":\"" + OffsetDateTime.now().minusHours(1) + "\",\"dueAt\":\""
				+ OffsetDateTime.now().plusHours(2) + "\"}";
		MvcResult result = this.mockMvc
				.perform(post("/api/assignments").header("Authorization", "Bearer " + teacherToken)
						.header("X-LMS-Organization-Alias", "trung-tam-1")
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andReturn();
		return UUID.fromString(json(result).get("id").asText());
	}

	private byte[] fileBytes(String token, UUID attemptId, UUID blobId, int expectedStatus) throws Exception {
		MvcResult result = this.mockMvc
				.perform(get("/api/attempts/" + attemptId + "/files/" + blobId)
						.header("Authorization", "Bearer " + token)
						.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andReturn();
		assertEquals(expectedStatus, result.getResponse().getStatus(),
				"blob " + blobId + ": " + result.getResponse().getContentAsString());
		return result.getResponse().getContentAsByteArray();
	}

	private JsonNode json(MvcResult result) throws Exception {
		return this.objectMapper.readTree(body(result));
	}

	private String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString();
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
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject)
				.issueTime(Date.from(now.minusSeconds(10))).expirationTime(Date.from(now.plusSeconds(300)));
		extraClaims.forEach(builder::claim);
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
		jwt.sign(new RSASSASigner((RSAPrivateKey) TestJwtConfiguration.KEY_PAIR.getPrivate()));
		return jwt.serialize();
	}

}
