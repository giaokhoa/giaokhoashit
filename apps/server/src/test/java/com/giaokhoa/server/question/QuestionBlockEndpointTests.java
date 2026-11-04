package com.giaokhoa.server.question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.giaokhoa.server.tenancy.MembershipChecker;
import com.jayway.jsonpath.JsonPath;
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

// Nghiệm thu cấu trúc khối của phiên bản câu hỏi trên đường thật: hai hợp đồng tách biệt — mặt soạn thảo có
// nguồn LaTeX khi được quyền, mặt hiển thị không có trường nguồn ở bất kỳ dạng nào — và quy tắc mang khối
// qua nháp mới cùng chủ so với sao chép sang bản sao. Chuỗi lọc bảo mật và tenancy của F3 chạy thật trên H2
// ở chế độ PostgreSQL; quyền qua seam QuestionRights thật (chủ sở hữu).
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + QuestionBlockEndpointTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:questionblocks;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration", "app.files.root=./target/test-files-blocks" })
class QuestionBlockEndpointTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String SOURCE_MARKER = "FRAC-BI-MAT-117";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private MembershipChecker membershipChecker;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
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
			NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic()).build();
			decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
			return decoder;
		}

	}

	@Test
	@DisplayName("Hai hợp đồng khối tách biệt: mặt soạn thảo của chủ chứa nguồn LaTeX và định danh khối ổn định, mặt hiển thị không chứa nguồn ở bất kỳ dạng nào và không lộ dạng khối")
	void draftingAndDisplayContractsAreSeparate() throws Exception {
		String teacher = token("gv-khoi-hai-mat");
		QuestionRef question = createEssayQuestion(teacher);
		MvcResult blocks = replaceBlocks(teacher, question, 0, blocksJson("\"" + SOURCE_MARKER + "\""));

		String draftBody = body(blocks);
		assertTrue(draftBody.contains(SOURCE_MARKER), draftBody);
		String firstBlockId = JsonPath.read(draftBody, "$.blocks[0].blockId");

		MvcResult display = this.mockMvc
				.perform(get("/api/questions/" + question.questionId() + "/versions/" + question.versionId()
						+ "/blocks").header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk()).andReturn();
		String displayBody = body(display);
		assertFalse(displayBody.contains(SOURCE_MARKER), displayBody);
		assertFalse(displayBody.contains("\"source\""), displayBody);
		assertFalse(displayBody.contains("mathSource"), displayBody);
		assertEquals(firstBlockId, JsonPath.read(displayBody, "$.blocks[0].blockId"));
	}

	@Test
	@DisplayName("Các trường do máy chủ quản (dạng khối, gốc hệ thống, ảnh đã kết xuất, trạng thái kết xuất) do máy khách gửi lên đều bị từ chối 422")
	void serverManagedFieldsAreRejected() throws Exception {
		String teacher = token("gv-truong-may-chu");
		QuestionRef question = createEssayQuestion(teacher);
		String withForm = "{\"revision\":0,\"blocks\":[{\"part\":\"stem\",\"kind\":\"formula\",\"source\":\"\\\\frac{1}{2}\",\"form\":\"render-only\"}]}";
		putBlocksExpect(teacher, question, withForm, 422);
		String withOrigin = "{\"revision\":0,\"blocks\":[{\"part\":\"stem\",\"kind\":\"text\",\"text\":\"than\",\"systemOrigin\":true}]}";
		putBlocksExpect(teacher, question, withOrigin, 422);
		String withImage = "{\"revision\":0,\"blocks\":[{\"part\":\"stem\",\"kind\":\"formula\",\"source\":\"x\",\"imageBlobId\":\"00000000-0000-7000-8000-000000000001\"}]}";
		putBlocksExpect(teacher, question, withImage, 422);
	}

	@Test
	@DisplayName("Thay khối với revision lệch bị 409, và phiên bản đã xuất bản bị khoá sửa khối với mã VERSION_LOCKED")
	void revisionConflictAndLockedVersionAreRejected() throws Exception {
		String teacher = token("gv-khoa-khoi");
		QuestionRef question = createEssayQuestion(teacher);
		putBlocksExpect(teacher, question,
				"{\"revision\":7,\"blocks\":[{\"part\":\"stem\",\"kind\":\"text\",\"text\":\"than\"}]}", 409);
		replaceBlocks(teacher, question, 0, blocksJson("\"x^2\""));
		this.mockMvc.perform(post("/api/questions/" + question.questionId() + "/versions/" + question.versionId()
				+ "/publish").header("Authorization", "Bearer " + teacher)).andExpect(status().isOk());
		MvcResult locked = putBlocks(teacher, question,
				"{\"revision\":2,\"blocks\":[{\"part\":\"stem\",\"kind\":\"text\",\"text\":\"than\"}]}");
		assertEquals(409, locked.getResponse().getStatus(), body(locked));
		assertTrue(body(locked).contains("VERSION_LOCKED"), body(locked));
	}

	@Test
	@DisplayName("NT-23: nội dung văn bản trỏ tới tệp ngoài (thẻ ảnh ngoài, ảnh markdown có URL) bị từ chối cả ở đường khối lẫn đường tạo câu hỏi cũ")
	void externalFileReferencesAreRejected() throws Exception {
		String teacher = token("gv-tham-chieu-ngoai");
		String bankId = createBank(teacher, "ngan-hang-nt23");
		QuestionRef question = createEssayQuestion(teacher, bankId);
		String imgTag = "{\"revision\":0,\"blocks\":[{\"part\":\"stem\",\"kind\":\"text\",\"text\":\"xem <img src=\\\"https://evil.example/a.png\\\">\"}]}";
		putBlocksExpect(teacher, question, imgTag, 422);
		String markdown = "{\"revision\":0,\"blocks\":[{\"part\":\"stem\",\"kind\":\"text\",\"text\":\"![anh](https://evil.example/a.png)\"}]}";
		putBlocksExpect(teacher, question, markdown, 422);

		MvcResult created = this.mockMvc
				.perform(post("/api/questions").header("Authorization", "Bearer " + teacher)
						.contentType(MediaType.APPLICATION_JSON)
						.content(essayBody(bankId, "than co <img src=https://evil.example/a.png>", "rubric")))
				.andReturn();
		assertEquals(422, created.getResponse().getStatus(), body(created));
	}

	@Test
	@DisplayName("Người không phải chủ không đọc được mặt soạn thảo và không thấy câu hỏi ở mặt hiển thị")
	void nonOwnerCannotUseBlockContracts() throws Exception {
		String teacher = token("gv-chu-khoi");
		QuestionRef question = createEssayQuestion(teacher);
		replaceBlocks(teacher, question, 0, blocksJson("\"x^2\""));
		String other = token("gv-ngoai-khoi");
		this.mockMvc.perform(get("/api/questions/" + question.questionId() + "/versions/" + question.versionId()
				+ "/blocks/draft").header("Authorization", "Bearer " + other)).andExpect(status().isForbidden());
		this.mockMvc.perform(get("/api/questions/" + question.questionId() + "/versions/" + question.versionId()
				+ "/blocks").header("Authorization", "Bearer " + other)).andExpect(status().isNotFound());
		this.mockMvc.perform(put("/api/questions/" + question.questionId() + "/versions/" + question.versionId()
				+ "/blocks").header("Authorization", "Bearer " + other).contentType(MediaType.APPLICATION_JSON)
				.content("{\"revision\":1,\"blocks\":[]}")).andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("Nền dữ kiện NT-04: tạo nháp mới của cùng câu hỏi giữ nguyên nguồn của chủ, còn sao chép tạo bản sao mà các khối công thức mất nguồn trong cả API soạn thảo lẫn hàng dữ kiện, trong khi bản gốc giữ nguyên")
	void newDraftKeepsSourceButCopyDropsIt() throws Exception {
		String teacher = token("gv-sao-chep-khoi");
		String bankId = createBank(teacher, "ngan-hang-sao-chep");
		QuestionRef question = createEssayQuestion(teacher, bankId);
		replaceBlocks(teacher, question, 0, blocksJson("\"" + SOURCE_MARKER + "\""));
		this.mockMvc.perform(post("/api/questions/" + question.questionId() + "/versions/" + question.versionId()
				+ "/publish").header("Authorization", "Bearer " + teacher)).andExpect(status().isOk());

		MvcResult newVersion = this.mockMvc
				.perform(post("/api/questions/" + question.questionId() + "/versions")
						.header("Authorization", "Bearer " + teacher).contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromVersionId\":\"" + question.versionId() + "\"}"))
				.andExpect(status().isOk()).andReturn();
		String newVersionId = JsonPath.read(body(newVersion), "$.versionId");
		MvcResult draftOfNew = this.mockMvc
				.perform(get("/api/questions/" + question.questionId() + "/versions/" + newVersionId + "/blocks/draft")
						.header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk()).andReturn();
		assertTrue(body(draftOfNew).contains(SOURCE_MARKER), body(draftOfNew));

		MvcResult copied = this.mockMvc
				.perform(post("/api/questions/" + question.questionId() + "/versions/" + question.versionId()
						+ "/copies").header("Authorization", "Bearer " + teacher)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"targetBankId\":\"" + bankId + "\"}"))
				.andExpect(status().isOk()).andReturn();
		String copiedQuestionId = JsonPath.read(body(copied), "$.id");
		String copiedVersionId = JsonPath.read(body(copied), "$.versions[0].versionId");
		MvcResult draftOfCopy = this.mockMvc
				.perform(get("/api/questions/" + copiedQuestionId + "/versions/" + copiedVersionId + "/blocks/draft")
						.header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk()).andReturn();
		assertFalse(body(draftOfCopy).contains(SOURCE_MARKER), body(draftOfCopy));
		Integer sourced = this.jdbcTemplate.queryForObject(
				"select count(*) from question_version_block where question_version_id = ? and math_source is not null",
				Integer.class, java.util.UUID.fromString(copiedVersionId));
		assertEquals(0, sourced);
		Integer original = this.jdbcTemplate.queryForObject(
				"select count(*) from question_version_block where question_version_id = ? and math_source is not null",
				Integer.class, java.util.UUID.fromString(question.versionId()));
		assertEquals(2, original);
	}

	@Test
	@DisplayName("Định danh khối ổn định: lưu lại với blockId giữ nguyên định danh, và khi nguồn không đổi thì giữ nguyên revision nguồn, trạng thái render và ảnh của khối; đổi nguồn thì revision nguồn tăng và trạng thái render quay về đang chờ")
	void blockIdsAreStableAcrossSaves() throws Exception {
		String teacher = token("gv-dinh-danh-on-dinh");
		QuestionRef question = createEssayQuestion(teacher);
		MvcResult first = replaceBlocks(teacher, question, 0, blocksJson("\"" + SOURCE_MARKER + "\""));
		String firstBody = body(first);
		String formulaId = JsonPath.read(firstBody, "$.blocks[1].blockId");
		String solutionId = JsonPath.read(firstBody, "$.blocks[2].blockId");
		int revision = ((Number) JsonPath.read(firstBody, "$.revision")).intValue();
		String blobId = "00000000-0000-7000-8000-0000000000aa";
		this.jdbcTemplate.update(
				"insert into file_blob (id, storage_key, content_type, size_bytes, checksum_sha256, status, "
						+ "uploaded_by_sub, partition_key) values (?, 'kho-test-117', 'image/png', 128, "
						+ "'aabbcc', 'READY', 'gv-dinh-danh-on-dinh', 'gv-dinh-danh-on-dinh')",
				java.util.UUID.fromString(blobId));
		this.jdbcTemplate.update(
				"update question_version_block set image_blob_id = ?, image_width = 132, image_height = 40, "
						+ "baseline_offset = 6, render_state = 'ready', source_revision = 3 where id = ?",
				java.util.UUID.fromString(blobId), java.util.UUID.fromString(formulaId));

		String second = "{\"revision\":" + revision + ",\"blocks\":["
				+ "{\"part\":\"stem\",\"kind\":\"text\",\"text\":\"tinh gia tri moi\"},"
				+ "{\"blockId\":\"" + formulaId + "\",\"part\":\"stem\",\"kind\":\"formula\",\"source\":\""
				+ SOURCE_MARKER + "\"},"
				+ "{\"part\":\"solution\",\"kind\":\"formula\",\"source\":\"\\\\frac{2}{2}\"}]}";
		MvcResult secondResult = putBlocks(teacher, question, second);
		assertEquals(200, secondResult.getResponse().getStatus(), body(secondResult));
		String secondBody = body(secondResult);
		assertEquals(formulaId, JsonPath.read(secondBody, "$.blocks[1].blockId"));
		assertEquals("ready", JsonPath.read(secondBody, "$.blocks[1].renderState"));
		assertEquals(3, ((Number) JsonPath.read(secondBody, "$.blocks[1].revision")).intValue());
		assertEquals(blobId, JsonPath.read(secondBody, "$.blocks[1].image.fileRef"));
		assertFalse(solutionId.equals(JsonPath.read(secondBody, "$.blocks[2].blockId")));

		int revision2 = ((Number) JsonPath.read(secondBody, "$.revision")).intValue();
		String third = "{\"revision\":" + revision2 + ",\"blocks\":[" + "{\"blockId\":\"" + formulaId
				+ "\",\"part\":\"stem\",\"kind\":\"formula\",\"source\":\"NGUON-MOI\"}]}";
		MvcResult thirdResult = putBlocks(teacher, question, third);
		assertEquals(200, thirdResult.getResponse().getStatus(), body(thirdResult));
		String thirdBody = body(thirdResult);
		assertEquals(formulaId, JsonPath.read(thirdBody, "$.blocks[0].blockId"));
		assertEquals("pending", JsonPath.read(thirdBody, "$.blocks[0].renderState"));
		assertEquals(4, ((Number) JsonPath.read(thirdBody, "$.blocks[0].revision")).intValue());
	}

	@Test
	@DisplayName("Định danh khối lạ hoặc lặp: blockId của một phiên bản khác và blockId bị dùng hai lần trong cùng một lượt lưu đều bị từ chối 422")
	void foreignOrDuplicateBlockIdsAreRejected() throws Exception {
		String teacher = token("gv-dinh-danh-la");
		QuestionRef first = createEssayQuestion(teacher);
		QuestionRef second = createEssayQuestion(teacher);
		MvcResult saved = replaceBlocks(teacher, first, 0, blocksJson("\"x^2\""));
		String foreignId = JsonPath.read(body(saved), "$.blocks[0].blockId");
		String foreign = "{\"revision\":0,\"blocks\":[{\"blockId\":\"" + foreignId
				+ "\",\"part\":\"stem\",\"kind\":\"text\",\"text\":\"than\"}]}";
		putBlocksExpect(teacher, second, foreign, 422);
		String duplicate = "{\"revision\":1,\"blocks\":["
				+ "{\"blockId\":\"" + foreignId + "\",\"part\":\"stem\",\"kind\":\"text\",\"text\":\"than\"},"
				+ "{\"blockId\":\"" + foreignId + "\",\"part\":\"stem\",\"kind\":\"text\",\"text\":\"than hai\"}]}";
		putBlocksExpect(teacher, first, duplicate, 422);
	}

	@Test
	@DisplayName("Giới hạn của hợp đồng soạn thảo: nguồn công thức dài quá 2000 ký tự bị từ chối 422")
	void formulaSourceOverLimitIsRejected() throws Exception {
		String teacher = token("gv-gioi-han-nguon");
		QuestionRef question = createEssayQuestion(teacher);
		String longSource = "x".repeat(2001);
		putBlocksExpect(teacher, question,
				"{\"revision\":0,\"blocks\":[{\"part\":\"stem\",\"kind\":\"formula\",\"source\":\"" + longSource
						+ "\"}]}",
				422);
	}

	private record QuestionRef(String questionId, String versionId) {
	}

	private String blocksJson(String stemSourceJson) {
		return "{\"revision\":0,\"blocks\":["
				+ "{\"part\":\"stem\",\"kind\":\"text\",\"text\":\"tinh gia tri\"},"
				+ "{\"part\":\"stem\",\"kind\":\"formula\",\"source\":" + stemSourceJson + "},"
				+ "{\"part\":\"solution\",\"kind\":\"formula\",\"source\":\"\\\\frac{2}{2}\"}]}";
	}

	private MvcResult replaceBlocks(String token, QuestionRef question, int revision, String json) throws Exception {
		MvcResult result = putBlocks(token, question, json.replace("\"revision\":0", "\"revision\":" + revision));
		assertEquals(200, result.getResponse().getStatus(), body(result));
		return result;
	}

	private MvcResult putBlocks(String token, QuestionRef question, String json) throws Exception {
		return this.mockMvc.perform(put("/api/questions/" + question.questionId() + "/versions/"
				+ question.versionId() + "/blocks").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
	}

	private void putBlocksExpect(String token, QuestionRef question, String json, int status) throws Exception {
		MvcResult result = putBlocks(token, question, json);
		assertEquals(status, result.getResponse().getStatus(), body(result));
	}

	private QuestionRef createEssayQuestion(String token) throws Exception {
		return createEssayQuestion(token, createBank(token, "ngan-hang-khoi-" + System.nanoTime()));
	}

	private QuestionRef createEssayQuestion(String token, String bankId) throws Exception {
		MvcResult result = this.mockMvc
				.perform(post("/api/questions").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON).content(essayBody(bankId, "than cau hoi", "rubric")))
				.andExpect(status().isOk()).andReturn();
		return new QuestionRef(JsonPath.read(body(result), "$.id"),
				JsonPath.read(body(result), "$.versions[0].versionId"));
	}

	private String createBank(String token, String name) throws Exception {
		MvcResult result = this.mockMvc
				.perform(post("/api/question-banks").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
				.andExpect(status().isOk()).andReturn();
		return JsonPath.read(body(result), "$.id");
	}

	private String essayBody(String bankId, String stem, String rubric) {
		return "{\"bankId\":\"" + bankId + "\",\"questionType\":\"essay_text\",\"stem\":\"" + stem
				+ "\",\"rubric\":\"" + rubric + "\",\"usageLimits\":\"chi-dung-noi-bo\"}";
	}

	private String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString();
	}

	private String token(String sub) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(sub)
				.issueTime(Date.from(now.minusSeconds(10))).expirationTime(Date.from(now.plusSeconds(300)))
				.claim("realm_access", Map.of("roles", List.of("personal-author"))).build();
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
		jwt.sign(new RSASSASigner((RSAPrivateKey) TestJwtConfiguration.KEY_PAIR.getPrivate()));
		return jwt.serialize();
	}

}
