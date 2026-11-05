package com.giaokhoa.server.access;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.giaokhoa.server.math.MathRenderer;
import com.giaokhoa.server.tenancy.MembershipChecker;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

// Kiểm thử bản nhận khi nộp trên khuôn của AccessEndpointTests (lõi quyền thật, token có claim tổ chức)
// kết hợp đường câu hỏi thật: nội dung đóng băng của bản nhận không chứa nguồn ở bất kỳ khối nào, tệp của
// bản nhận thuộc phân vùng của trung tâm và đọc được qua đường tệp của lõi, và các thao tác phía cá nhân
// sau khi nộp không chạm được vào bản nhận. Cổng render được thay bằng bản giả lập tất định như ở lát
// công thức; mọi tầng còn lại là thật trên H2 ở chế độ PostgreSQL.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + SubmissionReceiptTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:receipt129;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration", "app.files.root=./target/test-files-receipt129" })
class SubmissionReceiptTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	private static final String ALIAS_1 = "trung-tam-1";

	private static final Pattern SUBMISSION_ID = Pattern.compile("\"submissionId\":\"([^\"]+)\"");

	private static final Pattern RECEIVED_RESOURCE_ID = Pattern.compile("\"receivedResourceId\":\"([^\"]+)\"");

	private static final Pattern REQUEST_ID = Pattern.compile("\"requestId\":\"([^\"]+)\"");

	private static final Pattern RESOURCE_ID = Pattern.compile("\"resourceId\":\"([^\"]+)\"");

	private static final Pattern GRANT_ID = Pattern.compile("\"grantId\":\"([^\"]+)\"");

	private static final byte[] FAKE_PNG = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2,
			3 };

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

		@Bean
		@Primary
		MathRenderer fakeRenderer() {
			return (source, display) -> new MathRenderer.RenderedImage(FAKE_PNG, 40 + source.length(), 20, 5,
					"fake-renderer@0", "{}");
		}

	}

	@Test
	@DisplayName("NT-32: nộp một câu tự soạn thì bản nhận đóng băng đủ phần trình bày và đáp án của chính "
			+ "người nộp nhưng không chứa nguồn LaTeX ở bất kỳ khối nào, phiên bản nguồn ở lõi bị chốt, và "
			+ "tệp của bản nhận nằm ở phân vùng của trung tâm, đọc được qua đường tệp của lõi")
	void submitFreezesRenderOnlyReceipt() throws Exception {
		String teacher = authorMemberToken("gv-nop-129", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		String admin = adminToken("admin-nhan-129");
		String[] question = authorQuestion(teacher, "gv-nop-129", "NOP129");
		byte[] contentPng = png(9, 6);
		attach(teacher, question, "content", contentPng);
		attach(teacher, question, "answer", png(7, 5));
		Receipt receipt = submitAndAccept(admin, teacher, "gv-nop-129", question);

		String snapshot = this.jdbcTemplate.queryForObject(
				"select content_snapshot from submission_item where id = ?", String.class, receipt.itemId());
		assertNotNull(snapshot);
		assertTrue(snapshot.contains("than-cau-nop"), snapshot);
		assertTrue(snapshot.contains("tieu-chi-nop"), snapshot);
		assertFalse(snapshot.contains("NOP129"), snapshot);
		assertEquals(3, count("select count(*) from file_link where resource_version_id = ?",
				receipt.receivedVersionId()));
		assertNotNull(this.jdbcTemplate.queryForObject("select locked_at from resource_version where id = ?",
				Object.class, UUID.fromString(question[1])));

		MvcResult content = receiptContent(admin, ALIAS_1, receipt);
		assertEquals(200, content.getResponse().getStatus(), body(content));
		assertFalse(body(content).contains("NOP129"), body(content));
		String member = memberToken("hv-doc-ban-nhan", ALIAS_1, ORG_1_ID, List.of("tenant-member"));
		assertEquals(200, receiptContent(member, ALIAS_1, receipt).getResponse().getStatus());
		String outsider = authorToken("gv-ngoai-129");
		assertEquals(403, receiptContent(outsider, null, receipt).getResponse().getStatus());

		JsonNode links = json(receiptLinks(admin, receipt));
		UUID attachmentLink = null;
		for (JsonNode link : links) {
			if (link.get("blockId").isNull() && "content".equals(link.get("position").asText())) {
				attachmentLink = UUID.fromString(link.get("id").asText());
				UUID blobId = UUID.fromString(link.get("blobId").asText());
				assertEquals(ORG_1_ID, this.jdbcTemplate.queryForObject(
						"select partition_key from file_blob where id = ?", String.class, blobId));
			}
		}
		assertNotNull(attachmentLink);
		MvcResult bytes = this.mockMvc
				.perform(authed(MockMvcRequestBuilders.get("/api/files/links/" + attachmentLink + "/content"), admin, ALIAS_1))
				.andExpect(status().isOk()).andReturn();
		assertArrayEquals(contentPng, bytes.getResponse().getContentAsByteArray());
	}

	@Test
	@DisplayName("NT-06, NT-18: chuỗi sao chép, nộp, chia sẻ rồi sao chép tiếp không làm nguồn hệ thống "
			+ "xuất hiện ở bất kỳ dữ kiện đã lưu nào ngoài bản gốc; grant hành động nguồn bị từ chối; và truy "
			+ "vết của bản nhận không mở quyền đọc bản nháp của tài nguyên trung gian cho người được chia sẻ")
	void chainCopySubmitShareNeverLeaksSystemSource() throws Exception {
		String teacher = authorMemberToken("gv-chuoi-129", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		String admin = adminToken("admin-chuoi-129");
		String[] question = authorQuestion(teacher, "gv-chuoi-129", "SYSNOP");
		UUID versionId = UUID.fromString(question[1]);
		this.jdbcTemplate.update("update formula_block set system_origin = true where question_version_id = ?",
				versionId);
		this.jdbcTemplate.update(
				"update question_version_block set system_origin = true where question_version_id = ?", versionId);
		byte[] contentPng = png(10, 7);
		attach(teacher, question, "content", contentPng);

		MvcResult copied = this.mockMvc
				.perform(MockMvcRequestBuilders.post("/api/questions/" + question[0] + "/versions/" + question[1] + "/copies")
						.header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk()).andReturn();
		String copyId = JsonPath.read(body(copied), "$.id");
		String copyVersionId = JsonPath.read(body(copied), "$.versions[0].versionId");
		assertEquals(0, count("select count(*) from formula_block where question_version_id = ? "
				+ "and source is not null", UUID.fromString(copyVersionId)));

		Receipt receipt = submitAndAccept(admin, teacher, "gv-chuoi-129",
				new String[] { copyId, copyVersionId });
		String member = memberToken("hv-nhan-chia-se", ALIAS_1, ORG_1_ID, List.of("tenant-member"));
		grant(admin, receipt.receivedId(), receipt.receivedVersionId(), "view_content", "hv-nhan-chia-se");
		assertEquals(200, receiptLinks(member, receipt).getResponse().getStatus());
		assertEquals(200, receiptContent(member, ALIAS_1, receipt).getResponse().getStatus());

		MvcResult sourceGrant = post(admin, ALIAS_1, "/api/access-check/grants",
				"{\"resourceId\":\"" + receipt.receivedId() + "\",\"resourceVersionId\":\""
						+ receipt.receivedVersionId()
						+ "\",\"action\":\"read_math_source\",\"granteeType\":\"user\",\"granteeUserSub\":\"hv-nhan-chia-se\"}");
		assertEquals(422, sourceGrant.getResponse().getStatus(), body(sourceGrant));

		MvcResult draftPeek = this.mockMvc
				.perform(MockMvcRequestBuilders.get("/api/questions/" + copyId + "/versions/" + copyVersionId + "/blocks/draft")
						.header("Authorization", "Bearer " + member))
				.andReturn();
		assertTrue(draftPeek.getResponse().getStatus() == 403 || draftPeek.getResponse().getStatus() == 404,
				body(draftPeek));
		assertFalse(body(draftPeek).contains("SYSNOP"), body(draftPeek));

		this.mockMvc
				.perform(MockMvcRequestBuilders.post("/api/questions/" + copyId + "/versions/" + copyVersionId + "/copies")
						.header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk());
		assertEquals(2, count("select count(*) from question_version_block where math_source like '%SYSNOP%'"));
		assertEquals(1, count("select count(*) from formula_block where source like '%SYSNOP%'"));
		assertEquals(0, count("select count(*) from submission_item where content_snapshot like '%SYSNOP%'"));
	}

	@Test
	@DisplayName("B5, B10, NT-15: thu hồi grant chỉ cắt quyền của đúng người bị thu hồi, người được cấp "
			+ "còn lại và trung tâm vẫn đọc được bản nhận; xoá hẳn câu cá nhân ở dữ kiện tính năng cũng "
			+ "không làm hỏng nội dung đóng băng và tệp của bản nhận")
	void revocationAndPersonalDeletionDoNotBreakReceipt() throws Exception {
		String teacher = authorMemberToken("gv-b5-129", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		String admin = adminToken("admin-b5-129");
		String[] question = authorQuestion(teacher, "gv-b5-129", "B5129");
		byte[] contentPng = png(11, 8);
		attach(teacher, question, "content", contentPng);
		Receipt receipt = submitAndAccept(admin, teacher, "gv-b5-129", question);

		String memberOne = memberToken("hv-mot-129", ALIAS_1, ORG_1_ID, List.of("tenant-member"));
		String memberTwo = memberToken("hv-hai-129", ALIAS_1, ORG_1_ID, List.of("tenant-member"));
		UUID grantOne = grant(admin, receipt.receivedId(), receipt.receivedVersionId(), "view_content",
				"hv-mot-129");
		grant(admin, receipt.receivedId(), receipt.receivedVersionId(), "view_content", "hv-hai-129");
		assertEquals(200, receiptLinks(memberOne, receipt).getResponse().getStatus());
		assertEquals(200, receiptLinks(memberTwo, receipt).getResponse().getStatus());
		MvcResult revoked = post(admin, ALIAS_1, "/api/access-check/grants/" + grantOne + "/revoke",
				"{\"granteeUserSub\":\"hv-mot-129\"}");
		assertEquals(200, revoked.getResponse().getStatus(), body(revoked));
		assertEquals(403, receiptLinks(memberOne, receipt).getResponse().getStatus());
		assertEquals(200, receiptLinks(memberTwo, receipt).getResponse().getStatus());
		assertTrue(decide(memberTwo, receipt.receivedId(), receipt.receivedVersionId(), "view_content"));
		assertFalse(decide(memberOne, receipt.receivedId(), receipt.receivedVersionId(), "view_content"));

		UUID attachmentLink = null;
		for (JsonNode link : json(receiptLinks(memberTwo, receipt))) {
			if (link.get("blockId").isNull() && "content".equals(link.get("position").asText())) {
				attachmentLink = UUID.fromString(link.get("id").asText());
			}
		}
		assertNotNull(attachmentLink);
		UUID versionId = UUID.fromString(question[1]);
		this.jdbcTemplate.update("delete from file_link where resource_version_id = ?", versionId);
		this.jdbcTemplate.update("delete from question_version_block where question_version_id = ?", versionId);
		this.jdbcTemplate.update("delete from formula_render where formula_block_id in "
				+ "(select id from formula_block where question_version_id = ?)", versionId);
		this.jdbcTemplate.update("delete from formula_block where question_version_id = ?", versionId);
		this.jdbcTemplate.update("delete from question_version where id = ?", versionId);
		this.jdbcTemplate.update("delete from question where id = ?", UUID.fromString(question[0]));
		assertEquals(404, this.mockMvc
				.perform(MockMvcRequestBuilders.get("/api/questions/" + question[0]).header("Authorization", "Bearer " + teacher))
				.andReturn().getResponse().getStatus());
		assertEquals(200, receiptContent(admin, ALIAS_1, receipt).getResponse().getStatus());
		MvcResult bytes = this.mockMvc
				.perform(authed(MockMvcRequestBuilders.get("/api/files/links/" + attachmentLink + "/content"), memberTwo, ALIAS_1))
				.andExpect(status().isOk()).andReturn();
		assertArrayEquals(contentPng, bytes.getResponse().getContentAsByteArray());
	}

	@Test
	@DisplayName("NT-08: token mới không còn claim tổ chức thì không đọc được bản nhận và tệp của bản nhận "
			+ "nữa, trong khi trung tâm vẫn đọc được và kho cá nhân của giáo viên vẫn hoạt động bình thường")
	void accessEndsWhenOrganizationClaimEnds() throws Exception {
		String teacher = authorMemberToken("gv-nt08-129", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		String admin = adminToken("admin-nt08-129");
		String[] question = authorQuestion(teacher, "gv-nt08-129", "NT08129");
		attach(teacher, question, "content", png(6, 6));
		Receipt receipt = submitAndAccept(admin, teacher, "gv-nt08-129", question);

		String freshToken = authorToken("gv-nt08-129");
		assertEquals(403, receiptContent(freshToken, null, receipt).getResponse().getStatus());
		MvcResult links = receiptLinks(freshToken, receipt);
		assertTrue(links.getResponse().getStatus() == 403 || links.getResponse().getStatus() == 404,
				body(links));
		assertEquals(200, receiptContent(admin, ALIAS_1, receipt).getResponse().getStatus());
		this.mockMvc
				.perform(MockMvcRequestBuilders.get("/api/questions/" + question[0]).header("Authorization", "Bearer " + freshToken))
				.andExpect(status().isOk());
	}

	private record Receipt(UUID submissionId, UUID itemId, UUID receivedId, UUID receivedVersionId) {
	}

	private Receipt submitAndAccept(String admin, String teacher, String teacherSub, String[] question)
			throws Exception {
		UUID destination = createCollection(admin);
		MvcResult request = post(admin, ALIAS_1, "/api/access-check/handover-requests",
				"{\"requestedTeacherSub\":\"" + teacherSub
						+ "\",\"resourceTypes\":\"question\",\"destinationCollectionId\":\"" + destination
						+ "\"}");
		assertEquals(200, request.getResponse().getStatus(), body(request));
		UUID requestId = extract(REQUEST_ID, request);
		MvcResult submitted = post(teacher, null,
				"/api/access-check/handover-requests/" + requestId + "/submissions",
				"{\"items\":[{\"sourceResourceId\":\"" + question[0] + "\",\"sourceVersionId\":\"" + question[1]
						+ "\"}]}");
		assertEquals(200, submitted.getResponse().getStatus(), body(submitted));
		UUID submissionId = extract(SUBMISSION_ID, submitted);
		UUID receivedId = extract(RECEIVED_RESOURCE_ID, submitted);
		UUID itemId = this.jdbcTemplate.queryForObject("select id from submission_item where submission_id = ?",
				UUID.class, submissionId);
		UUID receivedVersionId = this.jdbcTemplate.queryForObject(
				"select received_version_id from submission_item where submission_id = ?", UUID.class,
				submissionId);
		MvcResult reviewed = post(admin, ALIAS_1, "/api/access-check/submissions/" + submissionId + "/review",
				"{\"decision\":\"accept\"}");
		assertEquals(200, reviewed.getResponse().getStatus(), body(reviewed));
		return new Receipt(submissionId, itemId, receivedId, receivedVersionId);
	}

	private String[] authorQuestion(String teacher, String ownerSub, String marker) throws Exception {
		MvcResult bank = this.mockMvc
				.perform(MockMvcRequestBuilders.post("/api/question-banks").header("Authorization", "Bearer " + teacher)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"ngan-hang-\"}"))
				.andExpect(status().isOk()).andReturn();
		String bankId = JsonPath.read(body(bank), "$.id");
		MvcResult created = this.mockMvc
				.perform(MockMvcRequestBuilders.post("/api/questions").header("Authorization", "Bearer " + teacher)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"bankId\":\"" + bankId
								+ "\",\"questionType\":\"essay_text\",\"stem\":\"than-cau-nop\",\"rubric\":\"tieu-chi-nop\"}"))
				.andExpect(status().isOk()).andReturn();
		String questionId = JsonPath.read(body(created), "$.id");
		String versionId = JsonPath.read(body(created), "$.versions[0].versionId");
		String[] target = { questionId, versionId };
		String blocksBody = "{\"revision\":0,\"blocks\":["
				+ "{\"part\":\"stem\",\"kind\":\"text\",\"text\":\"than-cau-nop\"},"
				+ "{\"part\":\"stem\",\"kind\":\"formula\",\"source\":\"\\\\alpha_{" + marker + "}\"},"
				+ "{\"part\":\"solution\",\"kind\":\"formula\",\"source\":\"\\\\beta_{" + marker + "}\"}]}";
		this.mockMvc
				.perform(put("/api/questions/" + questionId + "/versions/" + versionId + "/blocks")
						.header("Authorization", "Bearer " + teacher).contentType(MediaType.APPLICATION_JSON)
						.content(blocksBody))
				.andExpect(status().isOk());
		this.mockMvc
				.perform(put("/api/questions/" + questionId + "/versions/" + versionId + "/formula-blocks/f-nop")
						.header("Authorization", "Bearer " + teacher).contentType(MediaType.APPLICATION_JSON)
						.content("{\"section\":\"stem\",\"source\":\"\\\\gamma_{" + marker + "}\"}"))
				.andExpect(status().isOk());
		return target;
	}

	private void attach(String teacher, String[] target, String position, byte[] bytes) throws Exception {
		String base = "/api/questions/" + target[0] + "/versions/" + target[1] + "/attachments";
		MvcResult session = this.mockMvc
				.perform(MockMvcRequestBuilders.post(base + "/sessions").header("Authorization", "Bearer " + teacher)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"position\":\"" + position + "\",\"displayName\":\"anh.png\"}"))
				.andExpect(status().isOk()).andReturn();
		String sessionId = JsonPath.read(body(session), "$.sessionId");
		this.mockMvc
				.perform(multipart(base + "/sessions/" + sessionId + "/content")
						.file(new MockMultipartFile("file", "anh.png", "image/png", bytes))
						.header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk());
		this.mockMvc
				.perform(MockMvcRequestBuilders.post(base + "/sessions/" + sessionId + "/complete")
						.header("Authorization", "Bearer " + teacher).contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isOk());
	}

	private MvcResult receiptContent(String token, String alias, Receipt receipt) throws Exception {
		return this.mockMvc
				.perform(authed(MockMvcRequestBuilders.get("/api/access-check/submissions/" + receipt.submissionId() + "/items/"
						+ receipt.itemId() + "/content"), token, alias))
				.andReturn();
	}

	private MvcResult receiptLinks(String token, Receipt receipt) throws Exception {
		return this.mockMvc
				.perform(authed(MockMvcRequestBuilders.get("/api/files/resources/" + receipt.receivedId() + "/versions/"
						+ receipt.receivedVersionId() + "/links"), token, ALIAS_1))
				.andReturn();
	}

	private UUID grant(String admin, UUID resourceId, UUID versionId, String action, String grantee)
			throws Exception {
		MvcResult result = post(admin, ALIAS_1, "/api/access-check/grants",
				"{\"resourceId\":\"" + resourceId + "\",\"resourceVersionId\":\"" + versionId + "\",\"action\":\""
						+ action + "\",\"granteeType\":\"user\",\"granteeUserSub\":\"" + grantee + "\"}");
		assertEquals(200, result.getResponse().getStatus(), body(result));
		return extract(GRANT_ID, result);
	}

	private boolean decide(String token, UUID resourceId, UUID versionId, String action) throws Exception {
		MvcResult result = post(token, ALIAS_1, "/api/access-check/decisions",
				"{\"resourceId\":\"" + resourceId + "\",\"resourceVersionId\":\"" + versionId + "\",\"action\":\""
						+ action + "\"}");
		return body(result).contains("\"allowed\":true");
	}

	private UUID createCollection(String admin) throws Exception {
		MvcResult result = post(admin, ALIAS_1, "/api/access-check/resources",
				"{\"resourceType\":\"question-bank\",\"workspace\":\"center\"}");
		assertEquals(200, result.getResponse().getStatus(), body(result));
		return extract(RESOURCE_ID, result);
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token,
			String alias) {
		builder.header("Authorization", "Bearer " + token);
		if (alias != null) {
			builder.header("X-LMS-Organization-Alias", alias);
		}
		return builder;
	}

	private MvcResult post(String token, String alias, String path, String json) throws Exception {
		return this.mockMvc.perform(authed(MockMvcRequestBuilders.post(path), token, alias).contentType(MediaType.APPLICATION_JSON)
				.content(json)).andReturn();
	}

	private UUID extract(Pattern pattern, MvcResult result) throws Exception {
		var matcher = pattern.matcher(body(result));
		if (!matcher.find()) {
			throw new AssertionError("response missing field: " + body(result));
		}
		return UUID.fromString(matcher.group(1));
	}

	private JsonNode json(MvcResult result) throws Exception {
		return this.objectMapper.readTree(body(result));
	}

	private int count(String sql, Object... args) {
		Integer value = this.jdbcTemplate.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	private String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString();
	}

	private byte[] png(int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, 0x224466);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private String authorToken(String sub) throws Exception {
		return mint(sub, Map.of("realm_access", Map.of("roles", List.of("personal-author"))));
	}

	private String memberToken(String sub, String alias, String orgId, List<String> orgRoles) throws Exception {
		return mint(sub, Map.of("organization", Map.of(alias, orgEntry(orgId, orgRoles))));
	}

	private String authorMemberToken(String sub, String alias, String orgId, List<String> orgRoles)
			throws Exception {
		return mint(sub, Map.of("realm_access", Map.of("roles", List.of("personal-author")), "organization",
				Map.of(alias, orgEntry(orgId, orgRoles))));
	}

	private String adminToken(String sub) throws Exception {
		return memberToken(sub, ALIAS_1, ORG_1_ID, List.of("tenant-admin"));
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
