package com.giaokhoa.server.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
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

// Kiểm chứng các điểm nối tích hợp của lát đề trên đường thật trọn vẹn: câu được tạo bằng chính endpoint của
// feat ngân hàng câu hỏi (không gieo hàng, không nguồn giả), soạn vào đề qua cổng đọc thật, quyết định quyền đi
// qua dịch vụ quyết định của lõi quyền, và các hàng lõi cùng hàng phụ thuộc được đối chiếu trực tiếp trong
// database sau khi phát hành.
@SpringBootTest(properties = {
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ExamCoreIntegrationTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:examint;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration" })
class ExamCoreIntegrationTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	private static final String CENTER_ALIAS = "trung-tam-1";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

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
	@DisplayName("Tích hợp đường soạn và phát hành: câu tạo bằng endpoint của ngân hàng câu hỏi được soạn vào đề với đúng nội dung thật, phát hành ghi hàng resource_version ở lõi khớp content_hash của phiên bản đề và đúng một hàng resource_dependency cho từng câu thành phần")
	void composeAndPublishUsesRealQuestionBankContent() throws Exception {
		String teacher = teacherToken("gv-i");
		UUID bankId = createBank(teacher, "Ngân hàng tích hợp");
		JsonNode choice = createQuestion(teacher, bankId,
				"{\"bankId\":\"" + bankId + "\",\"questionType\":\"single_choice\","
						+ "\"stem\":\"2 + 2 bằng mấy?\",\"choices\":[{\"id\":\"a\",\"text\":\"3\"},"
						+ "{\"id\":\"b\",\"text\":\"4\"}],\"correctChoiceId\":\"b\",\"solution\":\"2 + 2 = 4.\"}");
		JsonNode essay = createQuestion(teacher, bankId,
				"{\"bankId\":\"" + bankId + "\",\"questionType\":\"essay_text\","
						+ "\"stem\":\"Trình bày nguyên nhân thắng lợi.\",\"rubric\":\"Đủ ba ý chính.\","
						+ "\"solution\":\"Gợi ý chấm.\"}");
		UUID choiceQuestionId = UUID.fromString(choice.get("id").asText());
		UUID choiceVersionId = UUID.fromString(choice.get("versions").get(0).get("versionId").asText());
		UUID essayQuestionId = UUID.fromString(essay.get("id").asText());
		UUID essayVersionId = UUID.fromString(essay.get("versions").get(0).get("versionId").asText());

		JsonNode created = createExamOk(teacher, null, "{\"title\":\"Đề tích hợp\"}");
		UUID examId = UUID.fromString(created.get("id").asText());
		UUID versionId = UUID.fromString(created.get("versionId").asText());
		String items = "[{\"questionVersionId\":\"" + choiceVersionId + "\",\"score\":4},"
				+ "{\"questionVersionId\":\"" + essayVersionId + "\",\"score\":6}]";
		JsonNode drafted = putDraftOk(teacher, examId, versionId, created.get("lockVersion").asLong(), items);
		JsonNode firstContent = drafted.get("items").get(0).get("content");
		assertEquals("2 + 2 bằng mấy?", firstContent.get("stem").asText());
		assertEquals("b", firstContent.get("correctChoiceId").asText());
		assertEquals("Đủ ba ý chính.", drafted.get("items").get(1).get("content").get("rubric").asText());

		Integer draftMirror = this.jdbcTemplate.queryForObject(
				"select count(*) from resource_version where id = ? and resource_id = ? and status = 'draft' "
						+ "and partition_key = 'user:gv-i'",
				Integer.class, versionId, examId);
		assertEquals(1, draftMirror);
		Integer questionRows = this.jdbcTemplate.queryForObject(
				"select count(*) from resource where id in (?, ?) and resource_type = 'question' "
						+ "and collection_id = ? and partition_key = 'user:gv-i'",
				Integer.class, choiceQuestionId, essayQuestionId, bankId);
		assertEquals(2, questionRows);

		JsonNode published = publishOk(teacher, examId, versionId);
		assertEquals("published", published.get("status").asText());
		String examHash = this.jdbcTemplate.queryForObject(
				"select content_hash from exam_version where id = ?", String.class, versionId);
		Integer publishedMirror = this.jdbcTemplate.queryForObject(
				"select count(*) from resource_version where id = ? and resource_id = ? "
						+ "and status = 'published' and content_hash = ? and locked_at is not null",
				Integer.class, versionId, examId, examHash);
		assertEquals(1, publishedMirror);
		Integer dependencies = this.jdbcTemplate.queryForObject(
				"select count(*) from resource_dependency where resource_id = ? and resource_version_id = ? "
						+ "and partition_key = 'user:gv-i'",
				Integer.class, examId, versionId);
		assertEquals(2, dependencies);
		Integer choiceDependency = this.jdbcTemplate.queryForObject(
				"select count(*) from resource_dependency where resource_version_id = ? "
						+ "and depends_on_resource_id = ? and depends_on_version_id = ?",
				Integer.class, versionId, choiceQuestionId, choiceVersionId);
		assertEquals(1, choiceDependency);
		Integer essayDependency = this.jdbcTemplate.queryForObject(
				"select count(*) from resource_dependency where resource_version_id = ? "
						+ "and depends_on_resource_id = ? and depends_on_version_id = ?",
				Integer.class, versionId, essayQuestionId, essayVersionId);
		assertEquals(1, essayDependency);
		String frozen = this.jdbcTemplate.queryForObject(
				"select content from exam_item_snapshot where exam_version_id = ? and position = 1", String.class,
				versionId);
		assertTrue(frozen.contains("2 + 2 bằng mấy?"));
	}

	@Test
	@DisplayName("Tích hợp quyết quyền qua lõi: tenant-admin của trung tâm đọc được nội dung đề thuộc trung tâm đã phát hành nhưng đáp án bị lược bỏ kể cả với người soạn, vì quyết định xem đáp án đi qua dịch vụ quyết định của lõi quyền mà nguồn tenant-admin không có hành động đó")
	void tenantAdminCannotSeeAnswersOfPublishedCenterExam() throws Exception {
		String admin = adminAuthorToken("qt-i");
		String secondAdmin = orgToken("qt-j", List.of("tenant-admin"));
		UUID bankId = createBankAt(admin, CENTER_ALIAS, "Ngân hàng của quản trị viên");
		JsonNode question = createQuestionAt(admin, CENTER_ALIAS, bankId,
				"{\"bankId\":\"" + bankId + "\",\"questionType\":\"single_choice\","
						+ "\"stem\":\"2 + 2 bằng mấy?\",\"choices\":[{\"id\":\"a\",\"text\":\"3\"},"
						+ "{\"id\":\"b\",\"text\":\"4\"}],\"correctChoiceId\":\"b\",\"solution\":\"2 + 2 = 4.\"}");
		UUID questionVersionId = UUID.fromString(question.get("versions").get(0).get("versionId").asText());
		JsonNode centerExam = createExamOk(admin, CENTER_ALIAS,
				"{\"title\":\"Đề trung tâm tích hợp\",\"workspace\":\"center\"}");
		UUID examId = UUID.fromString(centerExam.get("id").asText());
		UUID versionId = UUID.fromString(centerExam.get("versionId").asText());
		putDraftOkAt(admin, CENTER_ALIAS, examId, versionId, centerExam.get("lockVersion").asLong(),
				"[{\"questionVersionId\":\"" + questionVersionId + "\",\"score\":5}]");
		this.mockMvc
				.perform(authed(post("/api/exams/" + examId + "/versions/" + versionId + "/publish"), admin,
						CENTER_ALIAS))
				.andExpect(status().isOk());
		for (String viewer : List.of(admin, secondAdmin)) {
			JsonNode view = getExamOk(viewer, CENTER_ALIAS, examId);
			JsonNode content = view.get("items").get(0).get("content");
			assertEquals("2 + 2 bằng mấy?", content.get("stem").asText());
			assertTrue(content.has("choices"));
			assertTrue(!content.has("correctChoiceId"));
			assertTrue(!content.has("solution"));
		}
		Integer centerResource = this.jdbcTemplate.queryForObject(
				"select count(*) from resource where id = ? and resource_type = 'exam' "
						+ "and workspace_kind = 'center' and partition_key = ?",
				Integer.class, examId, ORG_1_ID);
		assertEquals(1, centerResource);
	}

	private UUID createBank(String token, String name) throws Exception {
		return createBankAt(token, null, name);
	}

	private UUID createBankAt(String token, String alias, String name) throws Exception {
		MvcResult result = this.mockMvc
				.perform(authed(post("/api/question-banks"), token, alias)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
				.andExpect(status().isOk())
				.andReturn();
		return UUID.fromString(readJson(result).get("id").asText());
	}

	private JsonNode createQuestion(String token, UUID bankId, String body) throws Exception {
		return createQuestionAt(token, null, bankId, body);
	}

	private JsonNode createQuestionAt(String token, String alias, UUID bankId, String body) throws Exception {
		MvcResult result = this.mockMvc
				.perform(authed(post("/api/questions"), token, alias)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andReturn();
		return readJson(result);
	}

	private JsonNode createExamOk(String token, String alias, String body) throws Exception {
		MvcResult result = this.mockMvc.perform(authed(post("/api/exams"), token, alias)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn();
		return readJson(result);
	}

	private JsonNode getExamOk(String token, String alias, UUID examId) throws Exception {
		MvcResult result = this.mockMvc.perform(authed(get("/api/exams/" + examId), token, alias))
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

	private String adminAuthorToken(String sub) throws Exception {
		return mint(sub, Map.of(
				"realm_access", Map.of("roles", List.of("personal-author")),
				"organization", Map.of(CENTER_ALIAS, orgEntry(ORG_1_ID, List.of("tenant-admin")))));
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
