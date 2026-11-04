package com.giaokhoa.server.question;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.giaokhoa.server.tenancy.MembershipChecker;

// Kiểm thử tại điểm nối quyết định quyền của feat: nguồn quyền đến từ grant của lõi F4 chưa có dữ kiện nên các
// ca "người khác được cấp quyền" không chạy được bằng bản cài đặt theo chủ sở hữu. Bộ này thay thành phần quyết
// định quyền bằng bản giả lập có kiểm soát để kiểm đúng hành vi của service khi quyết định thay đổi: sao chép
// không có view_answer thì đáp án bị lược ngay từ dữ kiện lưu, sao chép không có copy thì bị chặn và không tạo
// gì, và đọc một phiên bản chỉ thấy đáp án khi quyết định view_answer cho phép. Phần còn lại của đường đi (lọc
// bảo mật, service, JPA) là thật trên H2 như QuestionEndpointTests.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + QuestionCopyRightsTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:questionrights;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration" })
class QuestionCopyRightsTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private MembershipChecker membershipChecker;

	@MockitoBean
	private QuestionRights rights;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context)
			.apply(springSecurity())
			.build();
		lenient().when(this.membershipChecker.check(anyString(), anyString()))
			.thenReturn(MembershipChecker.Result.MEMBER);
		lenient().when(this.rights.personalAuthor(any())).thenReturn(true);
		lenient().when(this.rights.allowed(any(), any(), any(), anyString())).thenReturn(false);
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
	@DisplayName("Có quyền copy nhưng không có view_answer trên phiên bản nguồn: bản sao gồm thân câu và cấu trúc câu, không chứa đáp án, lời giải hay tiêu chí chấm ngay trong dữ kiện đã lưu, nhưng giữ truy vết và giới hạn của nguồn")
	void copyWithoutViewAnswerStripsAnswers() throws Exception {
		String actor = mint("gv-duoc-cap-copy", Map.of());
		String bankId = createBank(actor, "ngan-hang-dich-sao-chep");
		String sourceId = createBankAndQuestion(actor, bankId);
		String sourceVersionId = versionIdOf(actor, sourceId);
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.COPY))).thenReturn(true);
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.VIEW_CONTENT))).thenReturn(true);
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.READ_MATH_SOURCE))).thenReturn(true);
		MvcResult copy = this.mockMvc
			.perform(post("/api/questions/" + sourceId + "/versions/" + sourceVersionId + "/copies")
				.header("Authorization", "Bearer " + actor)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetBankId\":\"" + bankId + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sourceQuestionId").value(sourceId))
			.andExpect(jsonPath("$.sourceVersionId").value(sourceVersionId))
			.andExpect(jsonPath("$.usageLimits").value("gioi-han-cua-nguon"))
			.andExpect(jsonPath("$.versions[0].stem").value("than-cau-nguon"))
			.andExpect(jsonPath("$.versions[0].correctChoiceId").doesNotExist())
			.andExpect(jsonPath("$.versions[0].solution").doesNotExist())
			.andReturn();
		String copyId = JsonPath.read(copy.getResponse().getContentAsString(), "$.id");
		// Đọc lại bản sao khi quyết định view_answer đã bật: phần đáp án vẫn vắng, chứng tỏ chúng bị lược ngay
		// từ dữ kiện lưu của bản sao chứ không chỉ bị che ở lớp hiển thị.
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.VIEW_ANSWER))).thenReturn(true);
		this.mockMvc.perform(get("/api/questions/" + copyId).header("Authorization", "Bearer " + actor))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versions[0].correctChoiceId").doesNotExist())
			.andExpect(jsonPath("$.versions[0].solution").doesNotExist());
	}

	@Test
	@DisplayName("Không có quyền copy trên phiên bản nguồn: thao tác sao chép bị từ chối 403 và không tài nguyên nào được tạo")
	void copyWithoutCopyRightCreatesNothing() throws Exception {
		String actor = mint("gv-khong-duoc-copy", Map.of());
		String bankId = createBank(actor, "ngan-hang-bi-chan-sao-chep");
		String sourceId = createBankAndQuestion(actor, bankId);
		String sourceVersionId = versionIdOf(actor, sourceId);
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.VIEW_CONTENT))).thenReturn(true);
		this.mockMvc
			.perform(post("/api/questions/" + sourceId + "/versions/" + sourceVersionId + "/copies")
				.header("Authorization", "Bearer " + actor)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetBankId\":\"" + bankId + "\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("RESOURCE_FORBIDDEN"));
		Integer copies = this.jdbcTemplate.queryForObject(
				"select count(*) from question where source_question_id = ?", Integer.class, UUID.fromString(sourceId));
		assertEquals(0, copies);
	}

	@Test
	@DisplayName("Có quyền copy nhưng không có view_content trên phiên bản nguồn: thao tác sao chép bị từ chối 403 và không tài nguyên nào được tạo")
	void copyWithoutViewContentCreatesNothing() throws Exception {
		String actor = mint("gv-khong-duoc-doc", Map.of());
		String bankId = createBank(actor, "ngan-hang-bi-chan-doc");
		String sourceId = createBankAndQuestion(actor, bankId);
		String sourceVersionId = versionIdOf(actor, sourceId);
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.COPY))).thenReturn(true);
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.VIEW_CONTENT))).thenReturn(false);
		this.mockMvc
			.perform(post("/api/questions/" + sourceId + "/versions/" + sourceVersionId + "/copies")
				.header("Authorization", "Bearer " + actor)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetBankId\":\"" + bankId + "\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("RESOURCE_FORBIDDEN"));
		Integer copies = this.jdbcTemplate.queryForObject(
				"select count(*) from question where source_question_id = ?", Integer.class, UUID.fromString(sourceId));
		assertEquals(0, copies);
	}

	@Test
	@DisplayName("Có quyền copy và view_content trên phiên bản nguồn nhưng không có quyền tạo ở đích: thao tác sao chép bị từ chối 403 và không tài nguyên nào được tạo")
	void copyWithoutCreateRightAtDestinationCreatesNothing() throws Exception {
		String actor = mint("gv-khong-duoc-tao", Map.of());
		String bankId = createBank(actor, "ngan-hang-bi-chan-tao");
		String sourceId = createBankAndQuestion(actor, bankId);
		String sourceVersionId = versionIdOf(actor, sourceId);
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.COPY))).thenReturn(true);
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.VIEW_CONTENT))).thenReturn(true);
		when(this.rights.personalAuthor(any())).thenReturn(false);
		this.mockMvc
			.perform(post("/api/questions/" + sourceId + "/versions/" + sourceVersionId + "/copies")
				.header("Authorization", "Bearer " + actor)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetBankId\":\"" + bankId + "\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("RESOURCE_FORBIDDEN"));
		Integer copies = this.jdbcTemplate.queryForObject(
				"select count(*) from question where source_question_id = ?", Integer.class, UUID.fromString(sourceId));
		assertEquals(0, copies);
	}

	@Test
	@DisplayName("Đáp án, lời giải và tiêu chí chấm của một phiên bản chỉ xuất hiện trong dữ kiện trả về khi người gọi có view_answer trên đúng phiên bản đó")
	void answersAppearOnlyWithViewAnswer() throws Exception {
		String actor = mint("gv-doc-cau", Map.of());
		String bankId = createBank(actor, "ngan-hang-doc-cau");
		String questionId = createBankAndQuestion(actor, bankId);
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.VIEW_CONTENT))).thenReturn(true);
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.READ_MATH_SOURCE))).thenReturn(true);
		this.mockMvc.perform(get("/api/questions/" + questionId).header("Authorization", "Bearer " + actor))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versions[0].stem").value("than-cau-nguon"))
			.andExpect(jsonPath("$.versions[0].correctChoiceId").doesNotExist())
			.andExpect(jsonPath("$.versions[0].solution").doesNotExist())
			.andExpect(content().string(not(containsString("loi-giai-bi-mat"))));
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.VIEW_ANSWER))).thenReturn(true);
		this.mockMvc.perform(get("/api/questions/" + questionId).header("Authorization", "Bearer " + actor))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versions[0].correctChoiceId").value("c1"))
			.andExpect(jsonPath("$.versions[0].solution").value("loi-giai-bi-mat"));
	}

	@Test
	@DisplayName("Mặt xem cũ: người gọi có view_answer nhưng không có read_math_source không nhận được nguồn LaTeX trong bất kỳ trường văn bản nào; có read_math_source thì nhận đủ thân câu, lựa chọn và lời giải")
	void viewResponseHidesLatexWithoutReadMathSource() throws Exception {
		String actor = mint("gv-xem-che-nguon", Map.of());
		String bankId = createBank(actor, "ngan-hang-xem-che-nguon");
		MvcResult created = this.mockMvc
			.perform(post("/api/questions").header("Authorization", "Bearer " + actor)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"bankId\":\"" + bankId + "\",\"questionType\":\"single_choice\","
						+ "\"stem\":\"tinh \\\\frac{NGUON-BI-MAT-125}{2}\",\"choices\":[{\"id\":\"c1\",\"text\":\"dung\"},"
						+ "{\"id\":\"c2\",\"text\":\"sai\"}],\"correctChoiceId\":\"c1\","
						+ "\"solution\":\"giai \\\\frac{NGUON-BI-MAT-125}{3}\"}"))
			.andExpect(status().isOk())
			.andReturn();
		String questionId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.VIEW_CONTENT))).thenReturn(true);
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.VIEW_ANSWER))).thenReturn(true);
		this.mockMvc.perform(get("/api/questions/" + questionId).header("Authorization", "Bearer " + actor))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versions[0].stem").doesNotExist())
			.andExpect(jsonPath("$.versions[0].choices").doesNotExist())
			.andExpect(jsonPath("$.versions[0].solution").doesNotExist())
			.andExpect(jsonPath("$.versions[0].correctChoiceId").value("c1"))
			.andExpect(content().string(not(containsString("NGUON-BI-MAT-125"))));
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.READ_MATH_SOURCE))).thenReturn(true);
		this.mockMvc.perform(get("/api/questions/" + questionId).header("Authorization", "Bearer " + actor))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versions[0].stem").value(containsString("NGUON-BI-MAT-125")))
			.andExpect(jsonPath("$.versions[0].solution").value(containsString("NGUON-BI-MAT-125")));
	}

	@Test
	@DisplayName("NT-29 chiều đọc: biên tập viên không có view_answer không thấy khối thuộc đáp án hay lời giải ở cả mặt soạn thảo lẫn mặt hiển thị, nhưng vẫn đọc được nguồn của khối thân câu")
	void editorWithoutViewAnswerCannotReadAnswerBlocks() throws Exception {
		String actor = mint("gv-bien-tap-thieu-view-answer", Map.of());
		allowBlockRights();
		String bankId = createBank(actor, "ngan-hang-nt29-doc");
		String[] ids = createEssay(actor, bankId);
		putBlocks(actor, ids[0], ids[1], blockSetJson(0)).andExpect(status().isOk());
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.VIEW_ANSWER))).thenReturn(false);
		MvcResult draft = this.mockMvc
			.perform(get("/api/questions/" + ids[0] + "/versions/" + ids[1] + "/blocks/draft")
				.header("Authorization", "Bearer " + actor))
			.andExpect(status().isOk())
			.andReturn();
		String draftBody = draft.getResponse().getContentAsString();
		assertTrue(draftBody.contains("NGUON-THAN-CAU"), draftBody);
		assertFalse(draftBody.contains("NGUON-DAP-AN"), draftBody);
		assertFalse(draftBody.contains("LOI-GIAI-VAN-BAN"), draftBody);
		assertEquals(2, (int) JsonPath.read(draftBody, "$.blocks.length()"));
		MvcResult display = this.mockMvc
			.perform(get("/api/questions/" + ids[0] + "/versions/" + ids[1] + "/blocks")
				.header("Authorization", "Bearer " + actor))
			.andExpect(status().isOk())
			.andReturn();
		String displayBody = display.getResponse().getContentAsString();
		assertEquals(2, (int) JsonPath.read(displayBody, "$.blocks.length()"));
		assertFalse(displayBody.contains("LOI-GIAI-VAN-BAN"), displayBody);
	}

	@Test
	@DisplayName("NT-29 chiều ghi: biên tập viên không có view_answer bị từ chối khi ghi khối thuộc đáp án; ghi khối thân câu được chấp nhận và các khối đáp án cũ không bị lượt lưu đó xoá mất")
	void editorWithoutViewAnswerCannotWriteAnswerBlocks() throws Exception {
		String actor = mint("gv-bien-tap-thieu-view-answer-ghi", Map.of());
		allowBlockRights();
		String bankId = createBank(actor, "ngan-hang-nt29-ghi");
		String[] ids = createEssay(actor, bankId);
		MvcResult setup = putBlocks(actor, ids[0], ids[1], blockSetJson(0)).andExpect(status().isOk()).andReturn();
		int revision = ((Number) JsonPath.read(setup.getResponse().getContentAsString(), "$.revision")).intValue();
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.VIEW_ANSWER))).thenReturn(false);
		String withAnswer = "{\"revision\":" + revision
				+ ",\"blocks\":[{\"part\":\"solution\",\"kind\":\"formula\",\"source\":\"NGUON-MOI\"}]}";
		putBlocks(actor, ids[0], ids[1], withAnswer).andExpect(status().isForbidden());
		String stemOnly = "{\"revision\":" + revision + ",\"blocks\":["
				+ "{\"part\":\"stem\",\"kind\":\"text\",\"text\":\"than cau moi\"},"
				+ "{\"part\":\"stem\",\"kind\":\"formula\",\"source\":\"NGUON-THAN-CAU-MOI\"}]}";
		putBlocks(actor, ids[0], ids[1], stemOnly).andExpect(status().isOk());
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.VIEW_ANSWER))).thenReturn(true);
		MvcResult draft = this.mockMvc
			.perform(get("/api/questions/" + ids[0] + "/versions/" + ids[1] + "/blocks/draft")
				.header("Authorization", "Bearer " + actor))
			.andExpect(status().isOk())
			.andReturn();
		String draftBody = draft.getResponse().getContentAsString();
		assertTrue(draftBody.contains("NGUON-DAP-AN"), draftBody);
		assertTrue(draftBody.contains("NGUON-THAN-CAU-MOI"), draftBody);
		assertEquals(4, (int) JsonPath.read(draftBody, "$.blocks.length()"));
	}

	@Test
	@DisplayName("Sửa nguồn là hành động độc lập với sửa nội dung: có edit_draft nhưng không có edit_math_source thì bị từ chối khi ghi khối công thức, vẫn ghi được khối văn bản, và cùng payload công thức được chấp nhận khi có edit_math_source")
	void editDraftWithoutEditMathSourceCannotWriteFormula() throws Exception {
		String actor = mint("gv-khong-sua-nguon", Map.of());
		allowBlockRights();
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.EDIT_MATH_SOURCE))).thenReturn(false);
		String bankId = createBank(actor, "ngan-hang-khong-sua-nguon");
		String[] ids = createEssay(actor, bankId);
		putBlocks(actor, ids[0], ids[1],
				"{\"revision\":0,\"blocks\":[{\"part\":\"stem\",\"kind\":\"formula\",\"source\":\"x^2\"}]}")
			.andExpect(status().isForbidden());
		MvcResult textOnly = putBlocks(actor, ids[0], ids[1],
				"{\"revision\":0,\"blocks\":[{\"part\":\"stem\",\"kind\":\"text\",\"text\":\"than cau\"}]}")
			.andExpect(status().isOk())
			.andReturn();
		int revision = ((Number) JsonPath.read(textOnly.getResponse().getContentAsString(), "$.revision")).intValue();
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.EDIT_MATH_SOURCE))).thenReturn(true);
		putBlocks(actor, ids[0], ids[1], "{\"revision\":" + revision
				+ ",\"blocks\":[{\"part\":\"stem\",\"kind\":\"formula\",\"source\":\"x^2\"}]}")
			.andExpect(status().isOk());
	}

	private void allowBlockRights() {
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.VIEW_CONTENT))).thenReturn(true);
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.VIEW_ANSWER))).thenReturn(true);
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.EDIT_DRAFT))).thenReturn(true);
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.READ_MATH_SOURCE))).thenReturn(true);
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.EDIT_MATH_SOURCE))).thenReturn(true);
	}

	private String[] createEssay(String token, String bankId) throws Exception {
		MvcResult result = this.mockMvc
			.perform(post("/api/questions").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"bankId\":\"" + bankId + "\",\"questionType\":\"essay_text\",\"stem\":\"than cau\","
						+ "\"rubric\":\"tieu chi cham\"}"))
			.andExpect(status().isOk())
			.andReturn();
		String body = result.getResponse().getContentAsString();
		return new String[] { JsonPath.read(body, "$.id"), JsonPath.read(body, "$.versions[0].versionId") };
	}

	private ResultActions putBlocks(String token, String questionId, String versionId, String json)
			throws Exception {
		return this.mockMvc.perform(put("/api/questions/" + questionId + "/versions/" + versionId + "/blocks")
			.header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private String blockSetJson(int revision) {
		return "{\"revision\":" + revision + ",\"blocks\":["
				+ "{\"part\":\"stem\",\"kind\":\"text\",\"text\":\"than cau\"},"
				+ "{\"part\":\"stem\",\"kind\":\"formula\",\"source\":\"NGUON-THAN-CAU\"},"
				+ "{\"part\":\"solution\",\"kind\":\"text\",\"text\":\"LOI-GIAI-VAN-BAN\"},"
				+ "{\"part\":\"solution\",\"kind\":\"formula\",\"source\":\"NGUON-DAP-AN\"}]}";
	}

	private String createBank(String token, String name) throws Exception {
		MvcResult result = this.mockMvc
			.perform(post("/api/question-banks").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\"}"))
			.andExpect(status().isOk())
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}

	private String createBankAndQuestion(String token, String bankId) throws Exception {
		MvcResult result = this.mockMvc
			.perform(post("/api/questions").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"bankId\":\"" + bankId + "\",\"questionType\":\"single_choice\","
						+ "\"stem\":\"than-cau-nguon\",\"choices\":[{\"id\":\"c1\",\"text\":\"dung\"},"
						+ "{\"id\":\"c2\",\"text\":\"sai\"}],\"correctChoiceId\":\"c1\","
						+ "\"solution\":\"loi-giai-bi-mat\",\"usageLimits\":\"gioi-han-cua-nguon\"}"))
			.andExpect(status().isOk())
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}

	private String versionIdOf(String token, String questionId) throws Exception {
		when(this.rights.allowed(any(), any(), any(), eq(QuestionRights.VIEW_CONTENT))).thenReturn(true);
		MvcResult result = this.mockMvc
			.perform(get("/api/questions/" + questionId).header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.versions[0].versionId");
	}

	private String mint(String subject, Map<String, Object> extraClaims) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder().issuer(ISSUER)
			.subject(subject)
			.issueTime(Date.from(now.minusSeconds(10)))
			.expirationTime(Date.from(now.plusSeconds(300)));
		extraClaims.forEach(builder::claim);
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
		jwt.sign(new RSASSASigner((RSAPrivateKey) TestJwtConfiguration.KEY_PAIR.getPrivate()));
		return jwt.serialize();
	}

}
