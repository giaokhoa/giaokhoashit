package com.giaokhoa.server.exam;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.giaokhoa.server.tenancy.MembershipChecker;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Kiểm thử lát soạn đề trên cùng khuôn với TenancyEndpointTests: chuỗi lọc bảo mật thật, tầng dữ kiện thật
// (Flyway + Hibernate/JPA với discriminator) trên H2 ở chế độ PostgreSQL; membership được giả lập mặc định xác
// nhận còn hiệu lực. Cổng đọc phiên bản câu được thay bằng một nguồn có kiểm soát trong bộ nhớ để test chủ động
// được nội dung và quyền trên từng câu thành phần — đúng vai trò của feat câu hỏi khi nó nối bảng nội dung câu
// vào cổng này. Lớp RLS của PostgreSQL kiểm chứng riêng trên môi trường PostgreSQL thật.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ExamEndpointTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:exams;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration" })
class ExamEndpointTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	private static final String CENTER_ALIAS = "trung-tam-1";

	private static final String SINGLE_CHOICE_CONTENT = "{\"stem\":\"2 + 2 bằng mấy?\",\"choices\":"
			+ "[{\"id\":\"a\",\"text\":\"3\"},{\"id\":\"b\",\"text\":\"4\"}],"
			+ "\"correctChoiceId\":\"b\",\"solution\":\"2 + 2 = 4.\"}";

	private static final String ESSAY_CONTENT = "{\"stem\":\"Trình bày nguyên nhân thắng lợi.\","
			+ "\"rubric\":\"Đủ ba ý chính.\",\"solution\":\"Gợi ý chấm.\"}";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private MembershipChecker membershipChecker;

	@MockitoBean
	private QuestionVersionSource questionSource;

	private final Map<UUID, QuestionVersionSource.QuestionVersionInfo> questions = new ConcurrentHashMap<>();

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context)
				.apply(springSecurity())
				.build();
		lenient().when(this.membershipChecker.check(anyString(), anyString()))
				.thenReturn(MembershipChecker.Result.MEMBER);
		this.questions.clear();
		lenient().when(this.questionSource.find(any()))
				.thenAnswer(invocation -> Optional.ofNullable(this.questions.get(invocation.getArgument(0))));
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

	@Test
	@DisplayName("Mặc định cá nhân: giáo viên có personal-author tạo đề tại trang nền tảng và tại trang của một trung tâm đều ra đề cá nhân của chính mình, tenant-admin của trung tâm đó không đọc được")
	void personalIsDefaultOnPlatformAndCenterPages() throws Exception {
		String teacher = teacherMemberToken("gv-a");
		JsonNode onPlatform = createExamOk(teacher, null, "{\"title\":\"Đề tại nền tảng\"}");
		JsonNode onCenterPage = createExamOk(teacher, CENTER_ALIAS, "{\"title\":\"Đề tại trang trung tâm\"}");
		for (JsonNode created : List.of(onPlatform, onCenterPage)) {
			assertEquals("personal", created.get("workspaceKind").asText());
			assertEquals("gv-a", created.get("ownerUserSub").asText());
			assertEquals("draft", created.get("status").asText());
			assertEquals(1, created.get("versionNumber").asInt());
		}
		String admin = orgToken("qt-1", List.of("tenant-admin"));
		UUID examOnCenterPage = UUID.fromString(onCenterPage.get("id").asText());
		this.mockMvc.perform(authed(get("/api/exams/" + examOnCenterPage), admin, CENTER_ALIAS))
				.andExpect(status().isNotFound());
		UUID examOnPlatform = UUID.fromString(onPlatform.get("id").asText());
		this.mockMvc.perform(authed(get("/api/exams/" + examOnPlatform), admin, CENTER_ALIAS))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("Tài khoản không có personal-author tạo đề cá nhân bị 403 và không đề nào được tạo")
	void personalCreateRequiresPersonalAuthor() throws Exception {
		String student = mint("hs-1", Map.of());
		this.mockMvc.perform(authed(post("/api/exams"), student, null)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Đề của học sinh\"}"))
				.andExpect(status().isForbidden())
				.andExpect(content().string(containsString("\"code\":\"RESOURCE_FORBIDDEN\"")));
		Integer created = this.jdbcTemplate.queryForObject(
				"select count(*) from exam where created_by_sub = 'hs-1'", Integer.class);
		assertEquals(0, created);
	}

	@Test
	@DisplayName("Workspace trung tâm minh thị: tenant-admin tạo được đề thuộc trung tâm của mình và đọc được; thành viên thường tạo bị 403 và giáo viên thành viên đọc đề của trung tâm bị 403")
	void centerWorkspaceIsExplicitForTenantAdmin() throws Exception {
		String admin = orgToken("qt-1", List.of("tenant-admin"));
		JsonNode created = createExamOk(admin, CENTER_ALIAS, "{\"title\":\"Đề của trung tâm\",\"workspace\":\"center\"}");
		assertEquals("center", created.get("workspaceKind").asText());
		assertEquals(ORG_1_ID, created.get("ownerOrganizationId").asText());
		assertTrue(created.get("ownerUserSub").isNull());
		String member = orgToken("tv-1", List.of("tenant-member"));
		this.mockMvc.perform(authed(post("/api/exams"), member, CENTER_ALIAS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Đề thành viên tự tạo\",\"workspace\":\"center\"}"))
				.andExpect(status().isForbidden());
		String teacher = teacherMemberToken("gv-a");
		UUID examId = UUID.fromString(created.get("id").asText());
		this.mockMvc.perform(authed(get("/api/exams/" + examId), teacher, CENTER_ALIAS))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("Soạn đề kiểm quyền từng thành phần: mục trỏ tới câu của người khác mà người soạn không có quyền dùng thì toàn bộ lượt sửa bị 403 nêu rõ thành phần và bản nháp giữ nguyên; mục trỏ tới phiên bản câu không tồn tại bị 422 nêu rõ định danh")
	void composeChecksEveryComponent() throws Exception {
		String teacher = teacherToken("gv-a");
		UUID ownQuestion = addQuestion("gv-a", "single_choice", false, SINGLE_CHOICE_CONTENT);
		UUID otherQuestion = addQuestion("gv-b", "single_choice", false, SINGLE_CHOICE_CONTENT);
		JsonNode created = createExamOk(teacher, null, "{\"title\":\"Đề kiểm thành phần\"}");
		UUID examId = UUID.fromString(created.get("id").asText());
		UUID versionId = UUID.fromString(created.get("versionId").asText());
		long lockVersion = created.get("lockVersion").asLong();
		String blockedBody = "{\"lockVersion\":" + lockVersion + ",\"items\":["
				+ itemJson(ownQuestion, "5") + "," + itemJson(otherQuestion, "5") + "]}";
		this.mockMvc.perform(authed(put("/api/exams/" + examId + "/versions/" + versionId), teacher, null)
				.contentType(MediaType.APPLICATION_JSON).content(blockedBody))
				.andExpect(status().isForbidden())
				.andExpect(content().string(containsString(otherQuestion.toString())));
		UUID missing = UUID.randomUUID();
		String missingBody = "{\"lockVersion\":" + lockVersion + ",\"items\":[" + itemJson(missing, "5") + "]}";
		this.mockMvc.perform(authed(put("/api/exams/" + examId + "/versions/" + versionId), teacher, null)
				.contentType(MediaType.APPLICATION_JSON).content(missingBody))
				.andExpect(status().isUnprocessableContent())
				.andExpect(content().string(containsString(missing.toString())));
		JsonNode after = getExamOk(teacher, null, examId, null);
		assertEquals(0, after.get("items").size());
		assertEquals(lockVersion, after.get("lockVersion").asLong());
	}

	@Test
	@DisplayName("Thứ tự và điểm của mục: sửa bản nháp đặt mục theo thứ tự danh sách và điểm từng mục, đọc lại đúng cả hai; sắp xếp lại thứ tự ở bản nháp thì thứ tự mới có hiệu lực")
	void itemOrderAndScoresRoundTrip() throws Exception {
		String teacher = teacherToken("gv-a");
		UUID first = addQuestion("gv-a", "single_choice", false, SINGLE_CHOICE_CONTENT);
		UUID second = addQuestion("gv-a", "essay_text", false, ESSAY_CONTENT);
		JsonNode created = createExamOk(teacher, null, "{\"title\":\"Đề thứ tự và điểm\"}");
		UUID examId = UUID.fromString(created.get("id").asText());
		UUID versionId = UUID.fromString(created.get("versionId").asText());
		JsonNode updated = putDraftOk(teacher, examId, versionId, created.get("lockVersion").asLong(),
				"[" + itemJson(first, "2.5") + "," + itemJson(second, "7.5") + "]");
		assertEquals(first.toString(), updated.get("items").get(0).get("questionVersionId").asText());
		assertEquals(0, new BigDecimal("2.5").compareTo(updated.get("items").get(0).get("score").decimalValue()));
		assertEquals(second.toString(), updated.get("items").get(1).get("questionVersionId").asText());
		assertEquals(0, new BigDecimal("7.5").compareTo(updated.get("items").get(1).get("score").decimalValue()));
		JsonNode reordered = putDraftOk(teacher, examId, versionId, updated.get("lockVersion").asLong(),
				"[" + itemJson(second, "7.5") + "," + itemJson(first, "2.5") + "]");
		assertEquals(second.toString(), reordered.get("items").get(0).get("questionVersionId").asText());
		assertEquals(first.toString(), reordered.get("items").get(1).get("questionVersionId").asText());
	}

	@Test
	@DisplayName("Xem trước phân tầng đáp án: chủ sở hữu thấy đủ đáp án trong bản nháp của mình; tenant-admin đọc đề thuộc trung tâm thấy nội dung câu nhưng đáp án và tiêu chí chấm bị lược bỏ")
	void previewStripsAnswersByLayer() throws Exception {
		String teacher = teacherToken("gv-a");
		UUID ownQuestion = addQuestion("gv-a", "single_choice", false, SINGLE_CHOICE_CONTENT);
		JsonNode created = createExamOk(teacher, null, "{\"title\":\"Đề xem trước\"}");
		UUID examId = UUID.fromString(created.get("id").asText());
		UUID versionId = UUID.fromString(created.get("versionId").asText());
		putDraftOk(teacher, examId, versionId, created.get("lockVersion").asLong(),
				"[" + itemJson(ownQuestion, "5") + "]");
		JsonNode ownerView = getExamOk(teacher, null, examId, null);
		JsonNode ownerContent = ownerView.get("items").get(0).get("content");
		assertEquals("b", ownerContent.get("correctChoiceId").asText());
		assertTrue(ownerContent.has("solution"));

		String admin = orgToken("qt-1", List.of("tenant-admin"));
		String secondAdmin = orgToken("qt-2", List.of("tenant-admin"));
		UUID catalogQuestion = addQuestion("gv-b", "single_choice", true, SINGLE_CHOICE_CONTENT);
		UUID catalogEssay = addQuestion("gv-b", "essay_text", true, ESSAY_CONTENT);
		JsonNode centerExam = createExamOk(admin, CENTER_ALIAS,
				"{\"title\":\"Đề trung tâm xem trước\",\"workspace\":\"center\"}");
		UUID centerExamId = UUID.fromString(centerExam.get("id").asText());
		UUID centerVersionId = UUID.fromString(centerExam.get("versionId").asText());
		putDraftOkAt(admin, CENTER_ALIAS, centerExamId, centerVersionId, centerExam.get("lockVersion").asLong(),
				"[" + itemJson(catalogQuestion, "5") + "," + itemJson(catalogEssay, "6") + "]");
		JsonNode adminView = getExamOk(secondAdmin, CENTER_ALIAS, centerExamId, null);
		JsonNode adminContent = adminView.get("items").get(0).get("content");
		assertEquals("2 + 2 bằng mấy?", adminContent.get("stem").asText());
		assertTrue(adminContent.has("choices"));
		assertTrue(!adminContent.has("correctChoiceId"));
		assertTrue(!adminContent.has("solution"));
		JsonNode adminEssayContent = adminView.get("items").get(1).get("content");
		assertEquals("Trình bày nguyên nhân thắng lợi.", adminEssayContent.get("stem").asText());
		assertTrue(!adminEssayContent.has("rubric"));
		assertTrue(!adminEssayContent.has("solution"));
	}

	@Test
	@DisplayName("Phát hành đóng băng snapshot: phiên bản chuyển published, locked_at và content_hash được ghi, snapshot trong database chứa đủ nội dung câu, đáp án, tiêu chí chấm, điểm và thứ tự của từng mục")
	void publishFreezesSnapshot() throws Exception {
		String teacher = teacherToken("gv-a");
		UUID ownQuestion = addQuestion("gv-a", "single_choice", false, SINGLE_CHOICE_CONTENT);
		UUID catalogEssay = addQuestion("gv-b", "essay_text", true, ESSAY_CONTENT);
		JsonNode created = createExamOk(teacher, null, "{\"title\":\"Đề phát hành\"}");
		UUID examId = UUID.fromString(created.get("id").asText());
		UUID versionId = UUID.fromString(created.get("versionId").asText());
		putDraftOk(teacher, examId, versionId, created.get("lockVersion").asLong(),
				"[" + itemJson(ownQuestion, "4") + "," + itemJson(catalogEssay, "6") + "]");
		JsonNode published = publishOk(teacher, examId, versionId);
		assertEquals("published", published.get("status").asText());
		assertTrue(!published.get("lockedAt").isNull());
		String contentHash = published.get("contentHash").asText();
		assertEquals(64, contentHash.length());
		JsonNode firstContent = published.get("items").get(0).get("content");
		assertEquals("b", firstContent.get("correctChoiceId").asText());
		JsonNode secondContent = published.get("items").get(1).get("content");
		assertEquals("Đủ ba ý chính.", secondContent.get("rubric").asText());
		Integer snapshotCount = this.jdbcTemplate.queryForObject(
				"select count(*) from exam_item_snapshot where exam_version_id = ?", Integer.class, versionId);
		assertEquals(2, snapshotCount);
		String frozenFirst = this.jdbcTemplate.queryForObject(
				"select content from exam_item_snapshot where exam_version_id = ? and position = 1", String.class,
				versionId);
		assertTrue(frozenFirst.contains("\"correctChoiceId\":\"b\""));
		String frozenSecond = this.jdbcTemplate.queryForObject(
				"select content from exam_item_snapshot where exam_version_id = ? and position = 2", String.class,
				versionId);
		assertTrue(frozenSecond.contains("Đủ ba ý chính."));
	}

	@Test
	@DisplayName("Snapshot không đổi khi nguồn đổi: sau phát hành, sửa nội dung một câu nguồn và xoá một câu nguồn khác khỏi nguồn dữ kiện thì nội dung đề trả qua API và content_hash giữ nguyên")
	void snapshotSurvivesSourceChanges() throws Exception {
		String teacher = teacherToken("gv-a");
		UUID ownQuestion = addQuestion("gv-a", "single_choice", false, SINGLE_CHOICE_CONTENT);
		UUID catalogEssay = addQuestion("gv-b", "essay_text", true, ESSAY_CONTENT);
		JsonNode created = createExamOk(teacher, null, "{\"title\":\"Đề bất biến\"}");
		UUID examId = UUID.fromString(created.get("id").asText());
		UUID versionId = UUID.fromString(created.get("versionId").asText());
		putDraftOk(teacher, examId, versionId, created.get("lockVersion").asLong(),
				"[" + itemJson(ownQuestion, "4") + "," + itemJson(catalogEssay, "6") + "]");
		JsonNode published = publishOk(teacher, examId, versionId);
		String hashBefore = published.get("contentHash").asText();
		addQuestionAt(ownQuestion, "gv-a", "single_choice", false,
				"{\"stem\":\"ĐÃ SỬA TẠI NGUỒN\",\"choices\":[{\"id\":\"a\",\"text\":\"3\"}],"
						+ "\"correctChoiceId\":\"a\",\"solution\":\"Khác.\"}");
		this.questions.remove(catalogEssay);
		JsonNode after = getExamOk(teacher, null, examId, null);
		assertEquals(hashBefore, after.get("contentHash").asText());
		assertEquals("2 + 2 bằng mấy?", after.get("items").get(0).get("content").get("stem").asText());
		assertEquals("Đủ ba ý chính.", after.get("items").get(1).get("content").get("rubric").asText());
		String frozenFirst = this.jdbcTemplate.queryForObject(
				"select content from exam_item_snapshot where exam_version_id = ? and position = 1", String.class,
				versionId);
		assertTrue(frozenFirst.contains("2 + 2 bằng mấy?"));
	}

	@Test
	@DisplayName("Sửa bản đã phát hành: sửa trực tiếp bị 409 VERSION_LOCKED; tạo phiên bản nháp mới từ bản đã phát hành thì sửa được ở bản mới, bản đã phát hành và snapshot của nó giữ nguyên")
	void editingPublishedGoesThroughNewDraftVersion() throws Exception {
		String teacher = teacherToken("gv-a");
		UUID ownQuestion = addQuestion("gv-a", "single_choice", false, SINGLE_CHOICE_CONTENT);
		JsonNode created = createExamOk(teacher, null, "{\"title\":\"Đề sửa sau phát hành\"}");
		UUID examId = UUID.fromString(created.get("id").asText());
		UUID versionId = UUID.fromString(created.get("versionId").asText());
		putDraftOk(teacher, examId, versionId, created.get("lockVersion").asLong(),
				"[" + itemJson(ownQuestion, "4") + "]");
		JsonNode published = publishOk(teacher, examId, versionId);
		String hashBefore = published.get("contentHash").asText();
		String editBody = "{\"lockVersion\":" + published.get("lockVersion").asLong() + ",\"items\":["
				+ itemJson(ownQuestion, "9") + "]}";
		this.mockMvc.perform(authed(put("/api/exams/" + examId + "/versions/" + versionId), teacher, null)
				.contentType(MediaType.APPLICATION_JSON).content(editBody))
				.andExpect(status().isConflict())
				.andExpect(content().string(containsString("\"code\":\"VERSION_LOCKED\"")));
		MvcResult newVersionResult = this.mockMvc
				.perform(authed(post("/api/exams/" + examId + "/versions"), teacher, null)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceVersionId\":\"" + versionId + "\"}"))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode newVersion = readJson(newVersionResult);
		assertEquals(2, newVersion.get("versionNumber").asInt());
		assertEquals("draft", newVersion.get("status").asText());
		assertEquals(1, newVersion.get("items").size());
		UUID newVersionId = UUID.fromString(newVersion.get("versionId").asText());
		JsonNode edited = putDraftOk(teacher, examId, newVersionId, newVersion.get("lockVersion").asLong(),
				"[" + itemJson(ownQuestion, "9") + "]");
		assertEquals(0, new BigDecimal("9").compareTo(edited.get("items").get(0).get("score").decimalValue()));
		JsonNode oldVersion = getExamOk(teacher, null, examId, versionId);
		assertEquals("published", oldVersion.get("status").asText());
		assertEquals(hashBefore, oldVersion.get("contentHash").asText());
		assertEquals(0, new BigDecimal("4").compareTo(oldVersion.get("items").get(0).get("score").decimalValue()));
	}

	@Test
	@DisplayName("Chống ghi đè đồng thời: hai lượt sửa cùng một bản nháp với cùng lockVersion thì lượt lưu sau bị 409 VERSION_CONFLICT và nội dung của lượt trước giữ nguyên")
	void staleLockVersionIsRejected() throws Exception {
		String teacher = teacherToken("gv-a");
		UUID first = addQuestion("gv-a", "single_choice", false, SINGLE_CHOICE_CONTENT);
		UUID second = addQuestion("gv-a", "essay_text", false, ESSAY_CONTENT);
		JsonNode created = createExamOk(teacher, null, "{\"title\":\"Đề chống ghi đè\"}");
		UUID examId = UUID.fromString(created.get("id").asText());
		UUID versionId = UUID.fromString(created.get("versionId").asText());
		long readLockVersion = created.get("lockVersion").asLong();
		JsonNode firstSave = putDraftOk(teacher, examId, versionId, readLockVersion,
				"[" + itemJson(first, "5") + "]");
		assertNotEquals(readLockVersion, firstSave.get("lockVersion").asLong());
		String staleBody = "{\"lockVersion\":" + readLockVersion + ",\"items\":[" + itemJson(second, "5") + "]}";
		this.mockMvc.perform(authed(put("/api/exams/" + examId + "/versions/" + versionId), teacher, null)
				.contentType(MediaType.APPLICATION_JSON).content(staleBody))
				.andExpect(status().isConflict())
				.andExpect(content().string(containsString("\"code\":\"VERSION_CONFLICT\"")));
		JsonNode after = getExamOk(teacher, null, examId, null);
		assertEquals(1, after.get("items").size());
		assertEquals(first.toString(), after.get("items").get(0).get("questionVersionId").asText());
	}

	@Test
	@DisplayName("Đánh giá lại quyền tại thời điểm phát hành: quyền dùng trên một câu thành phần bị mất sau khi soạn thì phát hành bị 403 nêu rõ thành phần, phiên bản vẫn là draft và không snapshot nào được tạo")
	void publishReDecidesComponentRights() throws Exception {
		String teacher = teacherToken("gv-a");
		UUID mutableQuestion = addQuestion("gv-a", "single_choice", false, SINGLE_CHOICE_CONTENT);
		JsonNode created = createExamOk(teacher, null, "{\"title\":\"Đề mất quyền giữa chừng\"}");
		UUID examId = UUID.fromString(created.get("id").asText());
		UUID versionId = UUID.fromString(created.get("versionId").asText());
		putDraftOk(teacher, examId, versionId, created.get("lockVersion").asLong(),
				"[" + itemJson(mutableQuestion, "5") + "]");
		addQuestionAt(mutableQuestion, "gv-b", "single_choice", false, SINGLE_CHOICE_CONTENT);
		this.mockMvc.perform(authed(post("/api/exams/" + examId + "/versions/" + versionId + "/publish"),
				teacher, null))
				.andExpect(status().isForbidden())
				.andExpect(content().string(containsString(mutableQuestion.toString())));
		JsonNode after = getExamOk(teacher, null, examId, null);
		assertEquals("draft", after.get("status").asText());
		Integer snapshotCount = this.jdbcTemplate.queryForObject(
				"select count(*) from exam_item_snapshot where exam_version_id = ?", Integer.class, versionId);
		assertEquals(0, snapshotCount);
	}

	@Test
	@DisplayName("Trạng thái chặn của phát hành: phát hành một bản nháp chưa có mục nào bị 422; phát hành lại một phiên bản đã phát hành bị 409 STATE_CONFLICT")
	void publishStateGuards() throws Exception {
		String teacher = teacherToken("gv-a");
		JsonNode empty = createExamOk(teacher, null, "{\"title\":\"Đề rỗng\"}");
		UUID emptyExamId = UUID.fromString(empty.get("id").asText());
		UUID emptyVersionId = UUID.fromString(empty.get("versionId").asText());
		this.mockMvc.perform(authed(post("/api/exams/" + emptyExamId + "/versions/" + emptyVersionId + "/publish"),
				teacher, null))
				.andExpect(status().isUnprocessableContent())
				.andExpect(content().string(containsString("\"code\":\"VALIDATION_FAILED\"")));
		UUID ownQuestion = addQuestion("gv-a", "single_choice", false, SINGLE_CHOICE_CONTENT);
		JsonNode created = createExamOk(teacher, null, "{\"title\":\"Đề phát hành hai lần\"}");
		UUID examId = UUID.fromString(created.get("id").asText());
		UUID versionId = UUID.fromString(created.get("versionId").asText());
		putDraftOk(teacher, examId, versionId, created.get("lockVersion").asLong(),
				"[" + itemJson(ownQuestion, "5") + "]");
		publishOk(teacher, examId, versionId);
		this.mockMvc.perform(authed(post("/api/exams/" + examId + "/versions/" + versionId + "/publish"),
				teacher, null))
				.andExpect(status().isConflict())
				.andExpect(content().string(containsString("\"code\":\"STATE_CONFLICT\"")));
	}

	@Test
	@DisplayName("Quan hệ phiên bản–đề không khớp: sửa và phát hành bằng versionId thuộc về một đề khác bị 403 và không dữ kiện nào thay đổi")
	void versionOfAnotherExamIsForbidden() throws Exception {
		String teacher = teacherToken("gv-a");
		UUID ownQuestion = addQuestion("gv-a", "single_choice", false, SINGLE_CHOICE_CONTENT);
		JsonNode examA = createExamOk(teacher, null, "{\"title\":\"Đề A\"}");
		JsonNode examB = createExamOk(teacher, null, "{\"title\":\"Đề B\"}");
		UUID examAId = UUID.fromString(examA.get("id").asText());
		UUID versionAId = UUID.fromString(examA.get("versionId").asText());
		UUID versionBId = UUID.fromString(examB.get("versionId").asText());
		String body = "{\"lockVersion\":0,\"items\":[" + itemJson(ownQuestion, "5") + "]}";
		this.mockMvc.perform(authed(put("/api/exams/" + examAId + "/versions/" + versionBId), teacher, null)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isForbidden());
		this.mockMvc.perform(authed(post("/api/exams/" + examAId + "/versions/" + versionBId + "/publish"),
				teacher, null))
				.andExpect(status().isForbidden());
		JsonNode afterA = getExamOk(teacher, null, examAId, versionAId);
		assertEquals("draft", afterA.get("status").asText());
		assertEquals(0, afterA.get("items").size());
		JsonNode afterB = getExamOk(teacher, null, UUID.fromString(examB.get("id").asText()), versionBId);
		assertEquals("draft", afterB.get("status").asText());
		assertEquals(0, afterB.get("items").size());
	}

	@Test
	@DisplayName("Cách ly giữa các chủ sở hữu: giáo viên khác không đọc và không sửa được đề cá nhân của giáo viên này, mọi thao tác đều 404")
	void otherTeacherCannotReachPersonalExam() throws Exception {
		String teacherA = teacherToken("gv-a");
		String teacherB = teacherToken("gv-b");
		JsonNode created = createExamOk(teacherA, null, "{\"title\":\"Đề riêng của A\"}");
		UUID examId = UUID.fromString(created.get("id").asText());
		UUID versionId = UUID.fromString(created.get("versionId").asText());
		this.mockMvc.perform(authed(get("/api/exams/" + examId), teacherB, null))
				.andExpect(status().isNotFound());
		String body = "{\"lockVersion\":0,\"items\":[]}";
		this.mockMvc.perform(authed(put("/api/exams/" + examId + "/versions/" + versionId), teacherB, null)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isNotFound());
	}

	private UUID addQuestion(String ownerSub, String type, boolean catalogPublished, String content)
			throws Exception {
		UUID questionVersionId = UUID.randomUUID();
		addQuestionAt(questionVersionId, ownerSub, type, catalogPublished, content);
		return questionVersionId;
	}

	private void addQuestionAt(UUID questionVersionId, String ownerSub, String type, boolean catalogPublished,
			String content) throws Exception {
		this.questions.put(questionVersionId,
				new QuestionVersionSource.QuestionVersionInfo(UUID.randomUUID(), questionVersionId, type,
						"personal", ownerSub, null, catalogPublished, this.objectMapper.readTree(content)));
	}

	private JsonNode createExamOk(String token, String alias, String body) throws Exception {
		MvcResult result = this.mockMvc.perform(authed(post("/api/exams"), token, alias)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn();
		return readJson(result);
	}

	private JsonNode getExamOk(String token, String alias, UUID examId, UUID versionId) throws Exception {
		String url = "/api/exams/" + examId + (versionId == null ? "" : "?versionId=" + versionId);
		MvcResult result = this.mockMvc.perform(authed(get(url), token, alias))
				.andExpect(status().isOk())
				.andReturn();
		return readJson(result);
	}

	private JsonNode putDraftOk(String token, UUID examId, UUID versionId, long lockVersion, String itemsJson)
			throws Exception {
		return putDraftOkAt(token, null, examId, versionId, lockVersion, itemsJson);
	}

	private JsonNode putDraftOkAt(String token, String alias, UUID examId, UUID versionId, long lockVersion,
			String itemsJson) throws Exception {
		String body = "{\"lockVersion\":" + lockVersion + ",\"items\":" + itemsJson + "}";
		MvcResult result = this.mockMvc
				.perform(authed(put("/api/exams/" + examId + "/versions/" + versionId), token, alias)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andReturn();
		return readJson(result);
	}

	private JsonNode publishOk(String token, UUID examId, UUID versionId) throws Exception {
		MvcResult result = this.mockMvc
				.perform(authed(post("/api/exams/" + examId + "/versions/" + versionId + "/publish"), token, null))
				.andExpect(status().isOk())
				.andReturn();
		return readJson(result);
	}

	private JsonNode readJson(MvcResult result) throws Exception {
		return this.objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private static String itemJson(UUID questionVersionId, String score) {
		return "{\"questionVersionId\":\"" + questionVersionId + "\",\"score\":" + score + "}";
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token,
			String alias) {
		builder.header("Authorization", "Bearer " + token);
		if (alias != null) {
			builder.header("X-LMS-Organization-Alias", alias);
		}
		return builder;
	}

	private String teacherToken(String sub) throws Exception {
		return mint(sub, Map.of("realm_access", Map.of("roles", List.of("personal-author"))));
	}

	private String teacherMemberToken(String sub) throws Exception {
		return mint(sub, Map.of(
				"realm_access", Map.of("roles", List.of("personal-author")),
				"organization", Map.of(CENTER_ALIAS, orgEntry(ORG_1_ID, List.of("tenant-teacher")))));
	}

	private String orgToken(String sub, List<String> roles) throws Exception {
		return mint(sub, Map.of("organization", Map.of(CENTER_ALIAS, orgEntry(ORG_1_ID, roles))));
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
