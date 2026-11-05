package com.giaokhoa.server.exam;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.giaokhoa.server.assignment.ExamVersionSnapshots;
import com.giaokhoa.server.attachment.AttachmentStore;
import com.giaokhoa.server.files.BlobStorage;
import com.giaokhoa.server.files.FileBlob;
import com.giaokhoa.server.files.FileBlobRepository;
import com.giaokhoa.server.files.UuidV7;
import com.giaokhoa.server.math.FormulaBlock;
import com.giaokhoa.server.math.FormulaBlockRepository;
import com.giaokhoa.server.math.FormulaRender;
import com.giaokhoa.server.math.FormulaRenderRepository;
import com.giaokhoa.server.tenancy.MembershipChecker;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

// Kiểm thử bản chụp và sao chép phía đề trên khuôn của ExamEndpointTests: câu hỏi được gieo bằng hàng thật
// ở dữ kiện tính năng (cùng cách bộ kiểm thử đề vẫn làm), khối công thức và tệp đính kèm là hàng thật trên
// nền tệp và nền render; phát hành đóng băng snapshot qua đường thật của ExamService và phía đọc snapshot
// qua bản cài đặt JDBC thật của điểm nối lượt làm.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ExamCopySnapshotTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:exam129;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration", "app.files.root=./target/test-files-exam129" })
class ExamCopySnapshotTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final byte[] FAKE_PNG = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 7, 7,
			7 };

	private static final String CHOICE_CONTENT = "{\"stem\":\"2 + 2 bằng mấy?\",\"choices\":"
			+ "[{\"id\":\"c1\",\"text\":\"3\"},{\"id\":\"c2\",\"text\":\"4\"}],\"correctChoiceId\":\"c2\","
			+ "\"solution\":\"Vì 2 + 2 = 4.\"}";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private AttachmentStore attachmentStore;

	@Autowired
	private ExamVersionSnapshots snapshots;

	@Autowired
	private FormulaBlockRepository formulaBlocks;

	@Autowired
	private FormulaRenderRepository formulaRenders;

	@Autowired
	private BlobStorage storage;

	@Autowired
	private FileBlobRepository fileBlobs;

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
	@DisplayName("Phát hành đề đóng băng cả tệp đính kèm và ảnh công thức vào snapshot (không kèm nguồn), "
			+ "điểm nối đọc snapshot thật đọc lại đúng các mục đó kèm phân vùng nhà của blob, và sửa dữ kiện "
			+ "sống của câu sau phát hành không làm snapshot đổi (NT-02 phía đề)")
	void publishFreezesAttachmentsAndFormulaImages() throws Exception {
		String teacher = teacherToken("gv-de-129");
		UUID questionVersion = addQuestion("gv-de-129", "single_choice", CHOICE_CONTENT);
		UUID questionId = questionIdOf(questionVersion);
		UUID formulaBlob = seedFormula(questionVersion, "gv-de-129", "f-de", "\\\\omega_{DE129}");
		UUID contentBlob = seedImageBlob("gv-de-129");
		UUID answerBlob = seedImageBlob("gv-de-129");
		link(questionId, questionVersion, "content", contentBlob);
		link(questionId, questionVersion, "answer", answerBlob);
		JsonNode created = createExamOk(teacher, "{\"title\":\"Đề đóng băng\"}");
		UUID examId = UUID.fromString(created.get("id").asText());
		UUID versionId = UUID.fromString(created.get("versionId").asText());
		putDraftOk(teacher, examId, versionId, created.get("lockVersion").asLong(),
				"[" + itemJson(questionVersion, "5") + "]");
		publishOk(teacher, examId, versionId);

		String frozen = this.jdbcTemplate.queryForObject(
				"select content from exam_item_snapshot where exam_version_id = ? and position = 1", String.class,
				versionId);
		assertTrue(frozen.contains("\"attachments\""), frozen);
		assertTrue(frozen.contains(contentBlob.toString()), frozen);
		assertTrue(frozen.contains(answerBlob.toString()), frozen);
		assertTrue(frozen.contains("\"formulaImages\""), frozen);
		assertTrue(frozen.contains(formulaBlob.toString()), frozen);
		assertFalse(frozen.contains("DE129"), frozen);

		ExamVersionSnapshots.Snapshot snapshot = this.snapshots.findByVersionId(versionId).orElseThrow();
		assertEquals(1, snapshot.items().size());
		ExamVersionSnapshots.Item item = snapshot.items().get(0);
		assertEquals(2, item.attachments().size());
		assertEquals(1, item.formulaImages().size());
		assertEquals("user:gv-de-129", item.attachments().get(0).blobPartitionKey());
		assertEquals("user:gv-de-129", item.formulaImages().get(0).blobPartitionKey());
		assertEquals(formulaBlob, item.formulaImages().get(0).imageBlobId());

		this.jdbcTemplate.update("update question_version set stem = 'STEM-DA-SUA' where id = ?",
				questionVersion);
		link(questionId, questionVersion, "content", seedImageBlob("gv-de-129"));
		String after = this.jdbcTemplate.queryForObject(
				"select content from exam_item_snapshot where exam_version_id = ? and position = 1", String.class,
				versionId);
		assertEquals(frozen, after);
		JsonNode view = getExamOk(teacher, examId, versionId);
		assertEquals("2 + 2 bằng mấy?", view.get("items").get(0).get("content").get("stem").asText());
	}

	@Test
	@DisplayName("Sao chép đề tạo đề cá nhân mới có truy vết nguồn ở lõi, mang sẵn snapshot đã đóng băng; "
			+ "sửa nguồn rồi xoá hẳn câu nguồn không làm bản sao và snapshot của nó đổi hay hỏng (NT-08, B16)")
	void copyExamCarriesFrozenSnapshots() throws Exception {
		String teacher = teacherToken("gv-sao-de-129");
		UUID questionVersion = addQuestion("gv-sao-de-129", "single_choice", CHOICE_CONTENT);
		UUID questionId = questionIdOf(questionVersion);
		seedFormula(questionVersion, "gv-sao-de-129", "f-sao", "\\\\omega_{SAODE129}");
		link(questionId, questionVersion, "content", seedImageBlob("gv-sao-de-129"));
		JsonNode created = createExamOk(teacher, "{\"title\":\"Đề nguồn\"}");
		UUID examId = UUID.fromString(created.get("id").asText());
		UUID versionId = UUID.fromString(created.get("versionId").asText());
		putDraftOk(teacher, examId, versionId, created.get("lockVersion").asLong(),
				"[" + itemJson(questionVersion, "5") + "]");
		publishOk(teacher, examId, versionId);
		String frozen = this.jdbcTemplate.queryForObject(
				"select content from exam_item_snapshot where exam_version_id = ? and position = 1", String.class,
				versionId);

		MvcResult copyResult = this.mockMvc
				.perform(authed(post("/api/exams/" + examId + "/versions/" + versionId + "/copies"), teacher,
						null))
				.andExpect(status().isCreated()).andReturn();
		JsonNode copy = readJson(copyResult);
		UUID copyId = UUID.fromString(copy.get("id").asText());
		UUID copyVersionId = UUID.fromString(copy.get("versionId").asText());
		assertFalse(examId.equals(copyId));
		assertEquals("draft", copy.get("status").asText());
		assertEquals("gv-sao-de-129", copy.get("ownerUserSub").asText());
		assertEquals(examId.toString(), this.jdbcTemplate.queryForObject(
				"select source_resource_id from resource where id = ?", String.class, copyId));
		assertEquals(1, count("select count(*) from exam_item_snapshot where exam_version_id = ?",
				copyVersionId));
		assertEquals(frozen, this.jdbcTemplate.queryForObject(
				"select content from exam_item_snapshot where exam_version_id = ? and position = 1", String.class,
				copyVersionId));
		publishOk(teacher, copyId, copyVersionId);

		this.jdbcTemplate.update("update question_version set stem = 'STEM-NGUON-DA-SUA' where id = ?",
				questionVersion);
		this.jdbcTemplate.update("update formula_block set source = '\\\\omega_{SUA129}' "
				+ "where question_version_id = ?", questionVersion);
		assertEquals(frozen, this.jdbcTemplate.queryForObject(
				"select content from exam_item_snapshot where exam_version_id = ? and position = 1", String.class,
				copyVersionId));
		this.jdbcTemplate.update("delete from file_link where resource_version_id = ?", questionVersion);
		this.jdbcTemplate.update("delete from formula_render where formula_block_id in "
				+ "(select id from formula_block where question_version_id = ?)", questionVersion);
		this.jdbcTemplate.update("delete from formula_block where question_version_id = ?", questionVersion);
		this.jdbcTemplate.update("delete from question_version where id = ?", questionVersion);
		this.jdbcTemplate.update("delete from question where id = ?", questionId);
		JsonNode view = getExamOk(teacher, copyId, copyVersionId);
		assertEquals("published", view.get("status").asText());
		assertEquals("2 + 2 bằng mấy?", view.get("items").get(0).get("content").get("stem").asText());
	}

	@Test
	@DisplayName("Sao chép đề của giáo viên khác mà không có quyền xem và dùng bị từ chối và không tạo đề nào")
	void copyExamDeniedWithoutRights() throws Exception {
		String teacherA = teacherToken("gv-a-de-129");
		String teacherB = teacherToken("gv-b-de-129");
		UUID questionVersion = addQuestion("gv-a-de-129", "single_choice", CHOICE_CONTENT);
		JsonNode created = createExamOk(teacherA, "{\"title\":\"Đề của A\"}");
		UUID examId = UUID.fromString(created.get("id").asText());
		UUID versionId = UUID.fromString(created.get("versionId").asText());
		putDraftOk(teacherA, examId, versionId, created.get("lockVersion").asLong(),
				"[" + itemJson(questionVersion, "5") + "]");
		publishOk(teacherA, examId, versionId);
		MvcResult denied = this.mockMvc
				.perform(authed(post("/api/exams/" + examId + "/versions/" + versionId + "/copies"), teacherB,
						null))
				.andReturn();
		assertTrue(denied.getResponse().getStatus() == 403 || denied.getResponse().getStatus() == 404,
				body(denied));
		assertEquals(0, count("select count(*) from exam where owner_user_sub = 'gv-b-de-129'"));
	}

	private UUID seedFormula(UUID questionVersionId, String ownerSub, String blockId, String source) {
		UUID imageBlobId = seedImageBlob(ownerSub);
		TenantContexts.set(new TenantContext(ownerSub, null, null, List.of(), false, false, "user:" + ownerSub));
		try {
			FormulaBlock block = new FormulaBlock();
			block.setId(UuidV7.next());
			block.setQuestionVersionId(questionVersionId);
			block.setOwnerSub(ownerSub);
			block.setSection("stem");
			block.setBlockId(blockId);
			block.setPosition(0);
			block.setForm(FormulaBlock.FORM_SOURCE_BACKED);
			block.setSource(source.replace("\\\\", "\\"));
			block.setSystemOrigin(false);
			block.setDescription("mo-ta-" + blockId);
			OffsetDateTime now = OffsetDateTime.now();
			block.setCreatedAt(now);
			block.setUpdatedAt(now);
			this.formulaBlocks.saveAndFlush(block);
			FormulaRender render = new FormulaRender();
			render.setId(UuidV7.next());
			render.setFormulaBlockId(block.getId());
			render.setSourceRevision(0);
			render.setSourceHash("hash-" + blockId);
			render.setStatus(FormulaRender.STATUS_READY);
			render.setImageBlobId(imageBlobId);
			render.setImageWidth(30);
			render.setImageHeight(18);
			render.setBaselineOffset(4);
			render.setRendererVersion("fake-renderer@0");
			render.setRenderConfig("{}");
			render.setCreatedAt(now);
			render.setUpdatedAt(now);
			this.formulaRenders.saveAndFlush(render);
			return imageBlobId;
		}
		finally {
			TenantContexts.clear();
		}
	}

	private void link(UUID questionId, UUID questionVersionId, String position, UUID blobId) {
		this.attachmentStore.saveLink(new AttachmentStore.Link(UuidV7.next(), questionId, questionVersionId,
				position, null, blobId, "anh.png", null));
	}

	private UUID seedImageBlob(String ownerSub) {
		UUID blobId = UuidV7.next();
		String stagingKey = this.storage.stage(blobId, FAKE_PNG);
		this.storage.promote(stagingKey, "blobs/" + blobId);
		FileBlob blob = new FileBlob();
		blob.setId(blobId);
		blob.setStorageKey("blobs/" + blobId);
		blob.setContentType("image/png");
		blob.setSizeBytes(FAKE_PNG.length);
		blob.setChecksumSha256(sha256Bytes(FAKE_PNG));
		blob.setWidth(30);
		blob.setHeight(18);
		blob.setStatus(FileBlob.STATUS_READY);
		blob.setUploadedBySub(ownerSub);
		OffsetDateTime now = OffsetDateTime.now();
		blob.setCreatedAt(now);
		blob.setUpdatedAt(now);
		TenantContexts.set(new TenantContext(ownerSub, null, null, List.of(), false, false, "user:" + ownerSub));
		try {
			this.fileBlobs.saveAndFlush(blob);
		}
		finally {
			TenantContexts.clear();
		}
		return blobId;
	}

	private UUID addQuestion(String ownerSub, String type, String content) throws Exception {
		UUID bankId = UUID.randomUUID();
		this.jdbcTemplate.update(
				"insert into question_bank (id, owner_sub, name, created_at, updated_at) "
						+ "values (?, ?, 'Ngân hàng kiểm thử', current_timestamp, current_timestamp)",
				bankId, ownerSub);
		UUID questionId = UUID.randomUUID();
		UUID questionVersionId = UUID.randomUUID();
		this.jdbcTemplate.update(
				"insert into question (id, bank_id, owner_sub, created_at, updated_at) "
						+ "values (?, ?, ?, current_timestamp, current_timestamp)",
				questionId, bankId, ownerSub);
		JsonNode node = this.objectMapper.readTree(content);
		this.jdbcTemplate.update(
				"insert into question_version (id, question_id, owner_sub, version_number, question_type, "
						+ "content_contract_version, stem, choices, correct_choice_id, solution, rubric, "
						+ "content_hash, status, revision, created_at, updated_at) values (?, ?, ?, 1, ?, 1, ?, "
						+ "?, ?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', "
						+ "'draft', 0, current_timestamp, current_timestamp)",
				questionVersionId, questionId, ownerSub, type, node.get("stem").asText(),
				node.has("choices") ? this.objectMapper.writeValueAsString(node.get("choices")) : null,
				node.has("correctChoiceId") ? node.get("correctChoiceId").asText() : null,
				node.has("solution") ? node.get("solution").asText() : null,
				node.has("rubric") ? node.get("rubric").asText() : null);
		return questionVersionId;
	}

	private UUID questionIdOf(UUID questionVersionId) {
		return this.jdbcTemplate.queryForObject("select question_id from question_version where id = ?",
				UUID.class, questionVersionId);
	}

	private JsonNode createExamOk(String token, String body) throws Exception {
		MvcResult result = this.mockMvc.perform(authed(post("/api/exams"), token, null)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
				.andReturn();
		return readJson(result);
	}

	private JsonNode getExamOk(String token, UUID examId, UUID versionId) throws Exception {
		String url = "/api/exams/" + examId + (versionId == null ? "" : "?versionId=" + versionId);
		MvcResult result = this.mockMvc.perform(authed(get(url), token, null)).andExpect(status().isOk())
				.andReturn();
		return readJson(result);
	}

	private void putDraftOk(String token, UUID examId, UUID versionId, long lockVersion, String itemsJson)
			throws Exception {
		String body = "{\"lockVersion\":" + lockVersion + ",\"items\":" + itemsJson + "}";
		this.mockMvc
				.perform(authed(put("/api/exams/" + examId + "/versions/" + versionId), token, null)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk());
	}

	private JsonNode publishOk(String token, UUID examId, UUID versionId) throws Exception {
		MvcResult result = this.mockMvc
				.perform(authed(post("/api/exams/" + examId + "/versions/" + versionId + "/publish"), token,
						null))
				.andExpect(status().isOk()).andReturn();
		return readJson(result);
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

	private JsonNode readJson(MvcResult result) throws Exception {
		return this.objectMapper.readTree(body(result));
	}

	private int count(String sql, Object... args) {
		Integer value = this.jdbcTemplate.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	private String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString();
	}

	private String sha256Bytes(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private String teacherToken(String sub) throws Exception {
		return mint(sub, Map.of("realm_access", Map.of("roles", List.of("personal-author"))));
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
