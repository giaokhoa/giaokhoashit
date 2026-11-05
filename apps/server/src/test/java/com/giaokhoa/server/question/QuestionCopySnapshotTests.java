package com.giaokhoa.server.question;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.giaokhoa.server.files.BlobStorage;
import com.giaokhoa.server.files.FileBlob;
import com.giaokhoa.server.files.FileBlobRepository;
import com.giaokhoa.server.files.UuidV7;
import com.giaokhoa.server.math.FormulaBlock;
import com.giaokhoa.server.math.FormulaBlockRepository;
import com.giaokhoa.server.math.FormulaRender;
import com.giaokhoa.server.math.FormulaRenderRepository;
import com.giaokhoa.server.math.MathRenderer;
import com.giaokhoa.server.math.MathRenderer.MathRenderException;
import com.giaokhoa.server.tenancy.MembershipChecker;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

// Kiểm thử lát sao chép và bản chụp phía câu hỏi trên khuôn của QuestionCopyRightsTests: quyết định quyền của
// tính năng được thay bằng bản giả lập có kiểm soát theo từng tài khoản (chủ sở hữu, người được cấp sao chép,
// người được cấp soạn) vì bản cài đặt theo chủ sở hữu chưa có dữ kiện cấp quyền nối vào; mọi tầng còn lại
// (lọc bảo mật, service, JPA, nền tệp, cổng render giả lập tất định) là thật trên H2 ở chế độ PostgreSQL.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + QuestionCopySnapshotTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:copy129;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration", "app.files.root=./target/test-files-copy129" })
class QuestionCopySnapshotTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final byte[] FAKE_PNG = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2,
			3 };

	static final FakeRenderer FAKE = new FakeRenderer();

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

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

	@MockitoBean
	private QuestionRights rights;

	private MockMvc mockMvc;

	private UUID stubSourceId;

	private String stubCopierSub;

	private boolean stubCopierViewAnswer;

	private String stubEditorSub;

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		lenient().when(this.membershipChecker.check(anyString(), anyString()))
				.thenReturn(MembershipChecker.Result.MEMBER);
		FAKE.reset();
		this.stubSourceId = null;
		this.stubCopierSub = null;
		this.stubCopierViewAnswer = false;
		this.stubEditorSub = null;
		lenient().when(this.rights.personalAuthor(any())).thenReturn(true);
		lenient().when(this.rights.allowed(any(), any(), any(), anyString())).thenAnswer(inv -> {
			TenantContext caller = inv.getArgument(0);
			Question question = inv.getArgument(1);
			String action = inv.getArgument(3);
			if (question.getOwnerSub().equals(caller.sub())) {
				return true;
			}
			if (this.stubSourceId != null && this.stubSourceId.equals(question.getId())) {
				if (caller.sub().equals(this.stubCopierSub)) {
					return switch (action) {
						case QuestionRights.COPY, QuestionRights.VIEW_CONTENT -> true;
						case QuestionRights.VIEW_ANSWER -> this.stubCopierViewAnswer;
						default -> false;
					};
				}
				if (caller.sub().equals(this.stubEditorSub)) {
					return switch (action) {
						case QuestionRights.EDIT_DRAFT, QuestionRights.EDIT_MATH_SOURCE,
								QuestionRights.VIEW_CONTENT, QuestionRights.READ_MATH_SOURCE ->
							true;
						default -> false;
					};
				}
			}
			return false;
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

		@Bean
		@Primary
		MathRenderer fakeRenderer() {
			return FAKE;
		}

	}

	static class FakeRenderer implements MathRenderer {

		volatile String failWith;

		void reset() {
			this.failWith = null;
		}

		@Override
		public RenderedImage render(String source, boolean display) {
			if (this.failWith != null) {
				throw new MathRenderException(this.failWith);
			}
			return new RenderedImage(FAKE_PNG, 40 + source.length(), 20, 5, "fake-renderer@0", "{}");
		}

	}

	@Test
	@DisplayName("NT-04, NT-32: người được cấp sao chép nhận tài nguyên mới của chính mình với truy vết nguồn, "
			+ "mọi khối công thức thành chỉ-có-ảnh dùng ngay được, không dữ kiện nguồn nào của hệ thống lọt sang "
			+ "bản sao kể cả trong hàng đã lưu, tệp đáp án không theo khi thiếu view_answer, và truy vết không mở "
			+ "quyền đọc nguồn của bản gốc cho người sao chép")
	void copyByAnotherTeacherDropsAllSources() throws Exception {
		String owner = authorToken("gv-nguon-129");
		String copier = authorToken("gv-sao-129");
		String[] source = createQuestion(owner, "ngan-hang-nguon-129");
		UUID questionId = UUID.fromString(source[0]);
		UUID versionId = UUID.fromString(source[1]);
		putFormula(owner, source, "f-than", "stem", "\\\\alpha_{SYS129}");
		putFormula(owner, source, "f-giai", "solution", "\\\\beta_{SYS129}");
		String blocksBody = "{\"revision\":0,\"blocks\":["
				+ "{\"part\":\"stem\",\"kind\":\"text\",\"text\":\"than-cau-goc\"},"
				+ "{\"part\":\"stem\",\"kind\":\"formula\",\"source\":\"\\\\gamma_{L1SYS129}\"},"
				+ "{\"part\":\"solution\",\"kind\":\"formula\",\"source\":\"\\\\delta_{L1SYS129}\"}]}";
		MvcResult blocksSaved = this.mockMvc
				.perform(put(blocksUrl(source)).header("Authorization", "Bearer " + owner)
						.contentType(MediaType.APPLICATION_JSON).content(blocksBody))
				.andExpect(status().isOk()).andReturn();
		String l1FormulaId = JsonPath.read(body(blocksSaved), "$.blocks[1].blockId");
		UUID l1ImageBlob = seedPlainBlobRow("gv-nguon-129");
		this.jdbcTemplate.update(
				"update question_version_block set image_blob_id = ?, image_width = 30, image_height = 18, "
						+ "baseline_offset = 4, render_state = 'ready', source_revision = 1 where id = ?",
				l1ImageBlob, UUID.fromString(l1FormulaId));
		this.jdbcTemplate.update("update formula_block set system_origin = true where question_version_id = ?",
				versionId);
		this.jdbcTemplate.update(
				"update question_version_block set system_origin = true where question_version_id = ?", versionId);
		byte[] contentPng = png(9, 6);
		String contentBlob = attach(owner, source, "content", contentPng);
		attach(owner, source, "answer", png(7, 5));

		this.stubSourceId = questionId;
		this.stubCopierSub = "gv-sao-129";
		this.stubCopierViewAnswer = false;
		String copierBank = createBank(copier, "ngan-hang-dich-129");
		MvcResult copied = this.mockMvc
				.perform(post("/api/questions/" + source[0] + "/versions/" + source[1] + "/copies")
						.header("Authorization", "Bearer " + copier).contentType(MediaType.APPLICATION_JSON)
						.content("{\"targetBankId\":\"" + copierBank + "\"}"))
				.andExpect(status().isOk()).andReturn();
		String copyBody = body(copied);
		String copyId = JsonPath.read(copyBody, "$.id");
		String copyVersionId = JsonPath.read(copyBody, "$.versions[0].versionId");
		assertFalse(source[0].equals(copyId));
		assertEquals("gv-sao-129", JsonPath.read(copyBody, "$.ownerUserSub"));
		assertEquals(source[0], JsonPath.read(copyBody, "$.sourceQuestionId"));
		String[] copy = { copyId, copyVersionId };

		String copyBlocks = body(this.mockMvc
				.perform(get(blocksUrl(copy) + "/draft").header("Authorization", "Bearer " + copier))
				.andExpect(status().isOk()).andReturn());
		assertFalse(copyBlocks.contains("SYS129"), copyBlocks);
		assertTrue(copyBlocks.contains(l1ImageBlob.toString()), copyBlocks);
		String copyFormulas = body(this.mockMvc
				.perform(get(formulaUrl(copy) + "/draft").header("Authorization", "Bearer " + copier))
				.andExpect(status().isOk()).andReturn());
		assertFalse(copyFormulas.contains("SYS129"), copyFormulas);
		assertTrue(copyFormulas.contains("\"form\":\"render-only\""), copyFormulas);
		assertEquals(0, count("select count(*) from question_version_block where question_version_id = ? "
				+ "and math_source is not null", UUID.fromString(copyVersionId)));
		assertEquals(0, count("select count(*) from formula_block where question_version_id = ? "
				+ "and source is not null", UUID.fromString(copyVersionId)));
		assertEquals(2, count("select count(*) from formula_block where question_version_id = ? "
				+ "and system_origin = true", UUID.fromString(copyVersionId)));
		assertEquals(2, count("select count(*) from formula_render r join formula_block b "
				+ "on r.formula_block_id = b.id join file_blob fb on fb.id = r.image_blob_id "
				+ "where b.question_version_id = ? and r.status = 'ready' "
				+ "and fb.partition_key = 'user:gv-sao-129'", UUID.fromString(copyVersionId)));
		MvcResult copyImage = this.mockMvc
				.perform(get(formulaUrl(copy) + "/f-than/image").header("Authorization", "Bearer " + copier))
				.andExpect(status().isOk()).andReturn();
		assertArrayEquals(FAKE_PNG, copyImage.getResponse().getContentAsByteArray());

		String copyLinks = body(this.mockMvc
				.perform(get(attachUrl(copy)).header("Authorization", "Bearer " + copier))
				.andExpect(status().isOk()).andReturn());
		String copyLinkId = JsonPath.read(copyLinks, "$[0].linkId");
		String copyBlobId = JsonPath.read(copyLinks, "$[0].blobId");
		assertFalse(contentBlob.equals(copyBlobId), copyLinks);
		assertEquals("user:gv-sao-129", this.jdbcTemplate.queryForObject(
				"select partition_key from file_blob where id = ?", String.class, UUID.fromString(copyBlobId)));
		assertEquals(1,
				count("select count(*) from file_link where resource_version_id = ?", UUID.fromString(copyVersionId)));
		MvcResult copyFile = this.mockMvc
				.perform(get(attachUrl(copy) + "/" + copyLinkId + "/content")
						.header("Authorization", "Bearer " + copier))
				.andExpect(status().isOk()).andReturn();
		assertArrayEquals(contentPng, copyFile.getResponse().getContentAsByteArray());

		String sourceDraftForCopier = body(this.mockMvc
				.perform(get(formulaUrl(source) + "/draft").header("Authorization", "Bearer " + copier))
				.andReturn());
		assertFalse(sourceDraftForCopier.contains("SYS129"), sourceDraftForCopier);
		assertEquals(2, count("select count(*) from question_version_block where question_version_id = ? "
				+ "and math_source is not null", versionId));
		assertEquals(2, count("select count(*) from formula_block where question_version_id = ? "
				+ "and source is not null", versionId));
	}

	@Test
	@DisplayName("NT-05: tự soạn nguồn mới trên một khối chỉ-có-ảnh của bản sao thì nguồn mới chỉ thuộc đúng "
			+ "khối đó của bản sao, các khối chỉ-có-ảnh còn lại giữ nguyên và bản gốc không đổi")
	void authorSourceOnCopyAffectsOnlyThatBlock() throws Exception {
		String owner = authorToken("gv-nguon-nt05");
		String copier = authorToken("gv-sao-nt05");
		String[] source = createQuestion(owner, "ngan-hang-nt05");
		UUID versionId = UUID.fromString(source[1]);
		seedRenderOnlyBlock("gv-nguon-nt05", versionId, "f-anh");
		seedRenderOnlyBlock("gv-nguon-nt05", versionId, "f-anh-hai");
		putFormula(owner, source, "f-co-nguon", "stem", "\\\\zeta_{GOC129}");

		this.stubSourceId = UUID.fromString(source[0]);
		this.stubCopierSub = "gv-sao-nt05";
		this.stubCopierViewAnswer = true;
		String copierBank = createBank(copier, "ngan-hang-dich-nt05");
		MvcResult copied = this.mockMvc
				.perform(post("/api/questions/" + source[0] + "/versions/" + source[1] + "/copies")
						.header("Authorization", "Bearer " + copier).contentType(MediaType.APPLICATION_JSON)
						.content("{\"targetBankId\":\"" + copierBank + "\"}"))
				.andExpect(status().isOk()).andReturn();
		String copyId = JsonPath.read(body(copied), "$.id");
		String copyVersionId = JsonPath.read(body(copied), "$.versions[0].versionId");
		String[] copy = { copyId, copyVersionId };

		this.mockMvc
				.perform(post(formulaUrl(copy) + "/f-anh/author-source").header("Authorization", "Bearer " + copier)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"revision\":0,\"source\":\"\\\\epsilon_{MOI129}\"}"))
				.andExpect(status().isOk());
		String copyDraft = body(this.mockMvc
				.perform(get(formulaUrl(copy) + "/draft").header("Authorization", "Bearer " + copier))
				.andExpect(status().isOk()).andReturn());
		assertTrue(copyDraft.contains("MOI129"), copyDraft);
		assertFalse(copyDraft.contains("GOC129"), copyDraft);
		assertEquals(1, count("select count(*) from formula_block where question_version_id = ? "
				+ "and source is not null", UUID.fromString(copyVersionId)));
		assertEquals(2, count("select count(*) from formula_block where question_version_id = ? "
				+ "and form = 'render-only'", UUID.fromString(copyVersionId)));
		assertEquals(0, count("select count(*) from formula_block where question_version_id = ? "
				+ "and block_id = 'f-anh' and system_origin = true", UUID.fromString(copyVersionId)));
		assertEquals(1, count("select count(*) from formula_block where question_version_id = ? "
				+ "and source is not null", versionId));
		assertEquals(2, count("select count(*) from formula_block where question_version_id = ? "
				+ "and form = 'render-only'", versionId));
	}

	@Test
	@DisplayName("NT-07, B15: bản sao chỉ-có-ảnh phát hành được ngay khi mọi ảnh đã sẵn sàng, và bị chặn ở "
			+ "cổng phát hành khi một ảnh render của bản sao chưa sẵn sàng")
	void publishCopyRequiresReadyImages() throws Exception {
		String owner = authorToken("gv-phat-hanh-129");
		String[] source = createQuestion(owner, "ngan-hang-phat-hanh-129");
		putFormula(owner, source, "f-san-sang", "stem", "\\\\theta_{SANSANG129}");
		MvcResult copied = copyAs(owner, source, null);
		String copyId = JsonPath.read(body(copied), "$.id");
		String copyVersionId = JsonPath.read(body(copied), "$.versions[0].versionId");
		this.mockMvc
				.perform(post("/api/questions/" + copyId + "/versions/" + copyVersionId + "/publish")
						.header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk());

		String[] broken = createQuestion(owner, "ngan-hang-loi-render-129");
		FAKE.failWith = "RENDER_FAILED";
		putFormula(owner, broken, "f-loi", "stem", "\\\\iota_{LOI129}");
		FAKE.failWith = null;
		MvcResult brokenCopy = copyAs(owner, broken, null);
		String brokenCopyId = JsonPath.read(body(brokenCopy), "$.id");
		String brokenCopyVersionId = JsonPath.read(body(brokenCopy), "$.versions[0].versionId");
		MvcResult publish = this.mockMvc
				.perform(post("/api/questions/" + brokenCopyId + "/versions/" + brokenCopyVersionId + "/publish")
						.header("Authorization", "Bearer " + owner))
				.andReturn();
		assertTrue(publish.getResponse().getStatus() >= 400 && publish.getResponse().getStatus() < 500,
				body(publish));
		String view = body(this.mockMvc
				.perform(get("/api/questions/" + brokenCopyId).header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk()).andReturn());
		assertTrue(view.contains("\"status\":\"draft\""), view);
	}

	@Test
	@DisplayName("NT-33: sửa văn bản thuần trên bản sao chỉ-có-ảnh qua đường lưu khối không kèm nguồn thì "
			+ "ảnh, hash và revision nguồn của khối công thức giữ nguyên, không đòi nguồn, và bản sao vẫn phát "
			+ "hành được")
	void plainTextEditOnCopyKeepsFormulaImage() throws Exception {
		String owner = authorToken("gv-m2-129");
		String[] source = createQuestion(owner, "ngan-hang-m2-129");
		String blocksBody = "{\"revision\":0,\"blocks\":["
				+ "{\"part\":\"stem\",\"kind\":\"text\",\"text\":\"van-ban-goc\"},"
				+ "{\"part\":\"stem\",\"kind\":\"formula\",\"source\":\"\\\\eta_{L1M2}\"}]}";
		MvcResult blocksSaved = this.mockMvc
				.perform(put(blocksUrl(source)).header("Authorization", "Bearer " + owner)
						.contentType(MediaType.APPLICATION_JSON).content(blocksBody))
				.andExpect(status().isOk()).andReturn();
		String formulaBlockId = JsonPath.read(body(blocksSaved), "$.blocks[1].blockId");
		UUID imageBlob = seedPlainBlobRow("gv-m2-129");
		this.jdbcTemplate.update(
				"update question_version_block set image_blob_id = ?, image_width = 30, image_height = 18, "
						+ "baseline_offset = 4, render_state = 'ready', source_revision = 2 where id = ?",
				imageBlob, UUID.fromString(formulaBlockId));
		MvcResult copied = copyAs(owner, source, null);
		String copyId = JsonPath.read(body(copied), "$.id");
		String copyVersionId = JsonPath.read(body(copied), "$.versions[0].versionId");
		String[] copy = { copyId, copyVersionId };
		String copyDraft = body(this.mockMvc
				.perform(get(blocksUrl(copy) + "/draft").header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk()).andReturn());
		int revision = ((Number) JsonPath.read(copyDraft, "$.revision")).intValue();
		String copyFormulaId = JsonPath.read(copyDraft, "$.blocks[1].blockId");
		String editBody = "{\"revision\":" + revision + ",\"blocks\":["
				+ "{\"part\":\"stem\",\"kind\":\"text\",\"text\":\"van-ban-moi\"},"
				+ "{\"blockId\":\"" + copyFormulaId + "\",\"part\":\"stem\",\"kind\":\"formula\"}]}";
		MvcResult edited = this.mockMvc
				.perform(put(blocksUrl(copy)).header("Authorization", "Bearer " + owner)
						.contentType(MediaType.APPLICATION_JSON).content(editBody))
				.andExpect(status().isOk()).andReturn();
		assertEquals(imageBlob.toString(), JsonPath.read(body(edited), "$.blocks[1].image.fileRef"));
		assertEquals(2, ((Number) JsonPath.read(body(edited), "$.blocks[1].revision")).intValue());
		assertEquals(0, count("select count(*) from question_version_block where question_version_id = ? "
				+ "and math_source is not null", UUID.fromString(copyVersionId)));
		this.mockMvc
				.perform(post("/api/questions/" + copyId + "/versions/" + copyVersionId + "/publish")
						.header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("NT-02 phía câu hỏi: tạo phiên bản nháp mới từ bản đã phát hành mang theo khối công thức kèm "
			+ "nguồn, khối nội dung và tệp đính kèm, trong khi bản đã phát hành giữ nguyên")
	void newVersionCarriesFormulaAndAttachments() throws Exception {
		String owner = authorToken("gv-chot-129");
		String[] source = createQuestion(owner, "ngan-hang-chot-129");
		putFormula(owner, source, "f-chot", "stem", "\\\\kappa_{CHOT129}");
		String blocksBody = "{\"revision\":0,\"blocks\":["
				+ "{\"part\":\"stem\",\"kind\":\"formula\",\"source\":\"\\\\lambda_{CHOT129}\"}]}";
		MvcResult blocksSaved = this.mockMvc
				.perform(put(blocksUrl(source)).header("Authorization", "Bearer " + owner)
						.contentType(MediaType.APPLICATION_JSON).content(blocksBody))
				.andExpect(status().isOk()).andReturn();
		String l1BlockId = JsonPath.read(body(blocksSaved), "$.blocks[0].blockId");
		UUID l1Image = seedPlainBlobRow("gv-chot-129");
		this.jdbcTemplate.update(
				"update question_version_block set image_blob_id = ?, image_width = 30, image_height = 18, "
						+ "baseline_offset = 4, render_state = 'ready', source_revision = 1 where id = ?",
				l1Image, UUID.fromString(l1BlockId));
		String blob = attach(owner, source, "content", png(8, 8));
		this.mockMvc
				.perform(post("/api/questions/" + source[0] + "/versions/" + source[1] + "/publish")
						.header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk());
		MvcResult newVersion = this.mockMvc
				.perform(post("/api/questions/" + source[0] + "/versions")
						.header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromVersionId\":\"" + source[1] + "\"}"))
				.andExpect(status().isOk()).andReturn();
		String newVersionId = JsonPath.read(body(newVersion), "$.versionId");
		String[] draft = { source[0], newVersionId };
		String formulaDraft = body(this.mockMvc
				.perform(get(formulaUrl(draft) + "/draft").header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk()).andReturn());
		assertTrue(formulaDraft.contains("CHOT129"), formulaDraft);
		String blocksDraft = body(this.mockMvc
				.perform(get(blocksUrl(draft) + "/draft").header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk()).andReturn());
		assertTrue(blocksDraft.contains("CHOT129"), blocksDraft);
		String links = body(this.mockMvc
				.perform(get(attachUrl(draft)).header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk()).andReturn());
		assertTrue(links.contains(blob), links);
		String view = body(this.mockMvc
				.perform(get("/api/questions/" + source[0]).header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk()).andReturn());
		assertTrue(view.contains("\"status\":\"published\""), view);
	}

	@Test
	@DisplayName("B13: bản ghi render của khối có-nguồn gắn đúng revision của khối; revision bị lệch ở dữ "
			+ "kiện lưu thì cổng phát hành chặn và phiên bản vẫn là bản nháp")
	void revisionTamperBlocksPublish() throws Exception {
		String owner = authorToken("gv-b13-129");
		String[] source = createQuestion(owner, "ngan-hang-b13-129");
		putFormula(owner, source, "f-b13", "stem", "\\\\mu_{B13129}");
		this.mockMvc
				.perform(post("/api/questions/" + source[0] + "/versions/" + source[1] + "/publish")
						.header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk());
		MvcResult newVersion = this.mockMvc
				.perform(post("/api/questions/" + source[0] + "/versions")
						.header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromVersionId\":\"" + source[1] + "\"}"))
				.andExpect(status().isOk()).andReturn();
		String newVersionId = JsonPath.read(body(newVersion), "$.versionId");
		assertEquals(1, count("select count(*) from formula_render r join formula_block b "
				+ "on r.formula_block_id = b.id where b.question_version_id = ? "
				+ "and r.source_revision = b.revision and r.status = 'ready'", UUID.fromString(newVersionId)));
		this.jdbcTemplate.update("update formula_block set revision = 7 where question_version_id = ?",
				UUID.fromString(newVersionId));
		MvcResult publish = this.mockMvc
				.perform(post("/api/questions/" + source[0] + "/versions/" + newVersionId + "/publish")
						.header("Authorization", "Bearer " + owner))
				.andReturn();
		assertTrue(publish.getResponse().getStatus() >= 400 && publish.getResponse().getStatus() < 500,
				body(publish));
	}

	@Test
	@DisplayName("NT-29 phía công thức: người được cấp soạn có edit_math_source nhưng không có view_answer "
			+ "thì sửa và đọc được nguồn của khối thân câu, nhưng bị từ chối đọc và sửa nguồn của khối lời giải")
	void editorSourceRightsFollowBlocks() throws Exception {
		String owner = authorToken("gv-nguon-nt29");
		String editor = authorToken("gv-bien-tap-nt29");
		String[] source = createQuestion(owner, "ngan-hang-nt29");
		putFormula(owner, source, "f-than", "stem", "\\\\nu_{THAN129}");
		putFormula(owner, source, "f-giai", "solution", "\\\\xi_{GIAI129}");
		this.stubSourceId = UUID.fromString(source[0]);
		this.stubEditorSub = "gv-bien-tap-nt29";
		String draft = body(this.mockMvc
				.perform(get(formulaUrl(source) + "/draft").header("Authorization", "Bearer " + editor))
				.andExpect(status().isOk()).andReturn());
		assertTrue(draft.contains("THAN129"), draft);
		assertFalse(draft.contains("GIAI129"), draft);
		int stemRevision = ((Number) JsonPath.read(draft, "$[0].revision")).intValue();
		int solutionRevision = ((Number) JsonPath.read(draft, "$[1].revision")).intValue();
		this.mockMvc
				.perform(put(formulaUrl(source) + "/f-than").header("Authorization", "Bearer " + editor)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"revision\":" + stemRevision + ",\"source\":\"\\\\nu_{SUA129}\"}"))
				.andExpect(status().isOk());
		this.mockMvc
				.perform(put(formulaUrl(source) + "/f-giai").header("Authorization", "Bearer " + editor)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"revision\":" + solutionRevision + ",\"source\":\"\\\\xi_{SUA129}\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("NT-15 phía phiên tải lên: phiên đã chốt không nhận thêm byte mới và chốt lại chỉ trả đúng "
			+ "liên kết cũ, nên blob chuẩn đang được dùng không bị ghi đè qua đường phát lại phiên")
	void completedUploadSessionCannotOverwriteBlob() throws Exception {
		String owner = authorToken("gv-nt15-129");
		String[] target = createQuestion(owner, "ngan-hang-nt15-129");
		byte[] first = png(9, 6);
		byte[] second = png(12, 4);
		String sessionId = openSession(owner, target, "content");
		upload(owner, target, sessionId, first);
		MvcResult completed = complete(owner, target, sessionId);
		String linkId = JsonPath.read(body(completed), "$.linkId");
		String blobId = JsonPath.read(body(completed), "$.blobId");
		String checksum = JsonPath.read(body(completed), "$.checksumSha256");
		assertEquals(sha256Bytes(first), checksum);
		this.mockMvc
				.perform(multipart(attachUrl(target) + "/sessions/" + sessionId + "/content")
						.file(new MockMultipartFile("file", "anh-moi.png", "image/png", second))
						.header("Authorization", "Bearer " + owner))
				.andExpect(status().isConflict());
		MvcResult recompleted = complete(owner, target, sessionId);
		assertEquals(linkId, JsonPath.read(body(recompleted), "$.linkId"));
		MvcResult read = this.mockMvc
				.perform(get(attachUrl(target) + "/" + linkId + "/content")
						.header("Authorization", "Bearer " + owner))
				.andExpect(status().isOk()).andReturn();
		assertArrayEquals(first, read.getResponse().getContentAsByteArray());
		assertEquals(sha256Bytes(first), this.jdbcTemplate.queryForObject(
				"select checksum_sha256 from file_blob where id = ?", String.class, UUID.fromString(blobId)));
	}

	private MvcResult copyAs(String token, String[] source, String targetBankId) throws Exception {
		String content = targetBankId == null ? "" : "{\"targetBankId\":\"" + targetBankId + "\"}";
		var request = post("/api/questions/" + source[0] + "/versions/" + source[1] + "/copies")
				.header("Authorization", "Bearer " + token);
		if (targetBankId != null) {
			request = request.contentType(MediaType.APPLICATION_JSON).content(content);
		}
		return this.mockMvc.perform(request).andExpect(status().isOk()).andReturn();
	}

	private void seedRenderOnlyBlock(String ownerSub, UUID versionId, String blockId) {
		UUID imageBlobId = seedImageBlob(ownerSub);
		TenantContexts.set(new TenantContext(ownerSub, null, null, List.of(), false, false, "user:" + ownerSub));
		try {
			FormulaBlock block = new FormulaBlock();
			block.setId(UuidV7.next());
			block.setQuestionVersionId(versionId);
			block.setOwnerSub(ownerSub);
			block.setSection("stem");
			block.setBlockId(blockId);
			block.setPosition(0);
			block.setForm(FormulaBlock.FORM_RENDER_ONLY);
			block.setSystemOrigin(true);
			block.setDescription("mo-ta-" + blockId);
			OffsetDateTime now = OffsetDateTime.now();
			block.setCreatedAt(now);
			block.setUpdatedAt(now);
			this.formulaBlocks.saveAndFlush(block);
			FormulaRender render = new FormulaRender();
			render.setId(UuidV7.next());
			render.setFormulaBlockId(block.getId());
			render.setSourceRevision(0);
			render.setSourceHash("");
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
		}
		finally {
			TenantContexts.clear();
		}
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

	private UUID seedPlainBlobRow(String ownerSub) {
		UUID blobId = UuidV7.next();
		this.jdbcTemplate.update(
				"insert into file_blob (id, storage_key, content_type, size_bytes, checksum_sha256, status, "
						+ "uploaded_by_sub, partition_key) values (?, ?, 'image/png', 128, 'aabbcc', 'READY', ?, ?)",
				blobId, "kho-test-" + blobId, ownerSub, "user:" + ownerSub);
		return blobId;
	}

	private String attach(String token, String[] target, String position, byte[] bytes) throws Exception {
		String sessionId = openSession(token, target, position);
		upload(token, target, sessionId, bytes);
		MvcResult completed = complete(token, target, sessionId);
		return JsonPath.read(body(completed), "$.blobId");
	}

	private String openSession(String token, String[] target, String position) throws Exception {
		MvcResult result = this.mockMvc
				.perform(post(attachUrl(target) + "/sessions").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"position\":\"" + position + "\",\"displayName\":\"anh.png\"}"))
				.andExpect(status().isOk()).andReturn();
		return JsonPath.read(body(result), "$.sessionId");
	}

	private void upload(String token, String[] target, String sessionId, byte[] bytes) throws Exception {
		this.mockMvc
				.perform(multipart(attachUrl(target) + "/sessions/" + sessionId + "/content")
						.file(new MockMultipartFile("file", "anh.png", "image/png", bytes))
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
	}

	private MvcResult complete(String token, String[] target, String sessionId) throws Exception {
		return this.mockMvc
				.perform(post(attachUrl(target) + "/sessions/" + sessionId + "/complete")
						.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isOk()).andReturn();
	}

	private void putFormula(String token, String[] target, String blockId, String section, String sourceJson)
			throws Exception {
		this.mockMvc
				.perform(put(formulaUrl(target) + "/" + blockId).header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"section\":\"" + section + "\",\"source\":\"" + sourceJson + "\"}"))
				.andExpect(status().isOk());
	}

	private String[] createQuestion(String token, String bankName) throws Exception {
		String bankId = createBank(token, bankName);
		MvcResult question = this.mockMvc
				.perform(post("/api/questions").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"bankId\":\"" + bankId
								+ "\",\"questionType\":\"essay_text\",\"stem\":\"than-cau\",\"rubric\":\"tieu-chi-cham\"}"))
				.andExpect(status().isOk()).andReturn();
		String questionId = JsonPath.read(body(question), "$.id");
		String versionId = JsonPath.read(body(question), "$.versions[0].versionId");
		return new String[] { questionId, versionId };
	}

	private String createBank(String token, String name) throws Exception {
		MvcResult bank = this.mockMvc
				.perform(post("/api/question-banks").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
				.andExpect(status().isOk()).andReturn();
		return JsonPath.read(body(bank), "$.id");
	}

	private String blocksUrl(String[] target) {
		return "/api/questions/" + target[0] + "/versions/" + target[1] + "/blocks";
	}

	private String formulaUrl(String[] target) {
		return "/api/questions/" + target[0] + "/versions/" + target[1] + "/formula-blocks";
	}

	private String attachUrl(String[] target) {
		return "/api/questions/" + target[0] + "/versions/" + target[1] + "/attachments";
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

	private String sha256Bytes(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private String authorToken(String sub) throws Exception {
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
