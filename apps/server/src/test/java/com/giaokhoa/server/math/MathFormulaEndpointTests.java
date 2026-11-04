package com.giaokhoa.server.math;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
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
import com.giaokhoa.server.tenancy.MembershipChecker;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

// Bộ kiểm của lát LaTeX trên khuôn H2 như các bộ endpoint khác: chuỗi bảo mật thật và tầng dữ kiện thật, chỉ
// cổng render được thay bằng bản giả lập điều khiển được (độ trễ, lỗi, thứ tự hoàn tất) để các ca đồng thời
// chạy tất định; đường render thật qua worker Node được kiểm riêng trong NodeMathRenderWorkerTests.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + MathFormulaEndpointTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:mathformula;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration", "app.files.root=./target/test-files-math" })
class MathFormulaEndpointTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final byte[] FAKE_PNG = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3 };

	static final FakeRenderer FAKE = new FakeRenderer();

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FormulaRenderRepository renders;

	@Autowired
	private FormulaBlockRepository blocks;

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
		FAKE.reset();
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

	// Bộ render giả lập: hành vi mặc định là thành công tức thì với ảnh cố định; test điều khiển lỗi và độ
	// trễ qua các trường công khai để dựng các ca render lỗi, worker chết và hai revision hoàn tất ngược thứ
	// tự một cách tất định.
	static class FakeRenderer implements MathRenderer {

		volatile String failWith;

		volatile String blockOnSource;

		volatile CountDownLatch entered = new CountDownLatch(0);

		volatile CountDownLatch release = new CountDownLatch(0);

		void reset() {
			this.failWith = null;
			this.blockOnSource = null;
			this.entered = new CountDownLatch(0);
			this.release = new CountDownLatch(0);
		}

		void blockOn(String source) {
			this.blockOnSource = source;
			this.entered = new CountDownLatch(1);
			this.release = new CountDownLatch(1);
		}

		@Override
		public RenderedImage render(String source, boolean display) {
			if (source.equals(this.blockOnSource)) {
				this.entered.countDown();
				try {
					if (!this.release.await(10, TimeUnit.SECONDS)) {
						throw new MathRenderException("RENDER_TIMEOUT");
					}
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new MathRenderException("RENDER_INTERRUPTED");
				}
			}
			if (this.failWith != null) {
				throw new MathRenderException(this.failWith);
			}
			return new RenderedImage(FAKE_PNG, 40 + source.length(), 20, 5, "fake-renderer@0", "{}");
		}

	}

	@Test
	@DisplayName("NT-01: chủ sở hữu sửa nguồn trong bản nháp thì ảnh mới sinh đúng revision của nguồn đã sửa và hash của bản ghi render khớp hash nguồn; tài khoản khác sửa bị từ chối và không dữ kiện nào đổi")
	void ownerEditsSourceAndRenderMatchesRevision() throws Exception {
		String teacher = authorToken("gv-latex-1");
		String other = authorToken("gv-latex-2");
		String[] ids = createQuestion(teacher);
		String base = base(ids);
		String source = "\\frac{x+1}{2}";
		this.mockMvc
			.perform(put(base + "/f1").header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"section\":\"stem\",\"source\":\"" + json(source) + "\",\"description\":\"phân số x cộng một phần hai\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.form").value("source-backed"))
			.andExpect(jsonPath("$.renderState").value("ready"))
			.andExpect(jsonPath("$.source").value(source))
			.andExpect(jsonPath("$.image.width").value(40 + source.length()));
		FormulaBlock block = this.blocks.findByQuestionVersionIdAndBlockId(UUID.fromString(ids[1]), "f1").orElseThrow();
		FormulaRender render = this.renders.findByFormulaBlockIdAndSourceRevision(block.getId(), block.getRevision())
			.orElseThrow();
		assertEquals(sha256(source), render.getSourceHash());
		assertEquals("fake-renderer@0", render.getRendererVersion());
		this.mockMvc
			.perform(put(base + "/f1").header("Authorization", "Bearer " + other)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"revision\":" + block.getRevision() + ",\"source\":\"\\\\frac{y}{3}\"}"))
			.andExpect(status().isForbidden());
		this.mockMvc.perform(get(base + "/draft").header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].source").value(source));
	}

	@Test
	@DisplayName("NT-10 và phần dữ liệu của NT-16a: mặt hiển thị không mang nguồn ở bất kỳ trường nào, ảnh chỉ đọc được qua đường có kiểm quyền, và đáp ứng ghi mô tả hỗ trợ đọc đi cùng ảnh")
	void displayViewNeverCarriesSource() throws Exception {
		String teacher = authorToken("gv-latex-1");
		String student = mint("hs-latex-1", Map.of());
		String[] ids = createQuestion(teacher);
		String base = base(ids);
		String marker = "DAUNHANDIEN010";
		String source = "\\frac{" + marker + "}{2}";
		this.mockMvc
			.perform(put(base + "/f1").header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"section\":\"stem\",\"source\":\"" + json(source) + "\",\"description\":\"phân số đặc biệt\"}"))
			.andExpect(status().isOk());
		MvcResult display = this.mockMvc.perform(get(base).header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].image.fileRef").isNotEmpty())
			.andExpect(jsonPath("$[0].description").value("phân số đặc biệt"))
			.andReturn();
		String body = display.getResponse().getContentAsString();
		assertTrue(!body.contains(marker), "Mặt hiển thị không được chứa bất kỳ phần nào của nguồn");
		assertTrue(!body.contains("\"source\""), "Mặt hiển thị không có trường nguồn ở bất kỳ dạng nào");
		this.mockMvc.perform(get(base).header("Authorization", "Bearer " + student))
			.andExpect(status().isNotFound());
		this.mockMvc.perform(get(base + "/f1/image").header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", containsString("no-store")))
			.andExpect(content().bytes(FAKE_PNG));
		this.mockMvc.perform(get(base + "/f1/image").header("Authorization", "Bearer " + student))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("NT-11: render lỗi thì bản nháp vẫn lưu với trạng thái lỗi an toàn không chứa nguồn, và phát hành bị chặn; nguồn vượt giới hạn độ dài bị từ chối ngay tại API")
	void renderFailureKeepsDraftAndBlocksPublish() throws Exception {
		String teacher = authorToken("gv-latex-1");
		String[] ids = createQuestion(teacher);
		String base = base(ids);
		FAKE.failWith = "RENDER_FAILED";
		this.mockMvc
			.perform(put(base + "/f1").header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"section\":\"stem\",\"source\":\"\\\\frac{DAUNHANDIEN011}{\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.renderState").value("failed"))
			.andExpect(jsonPath("$.errorCode").value("RENDER_FAILED"));
		this.mockMvc
			.perform(post("/api/questions/" + ids[0] + "/versions/" + ids[1] + "/publish")
				.header("Authorization", "Bearer " + teacher))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		String tooLong = "x".repeat(2001);
		this.mockMvc
			.perform(put(base + "/f2").header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"section\":\"stem\",\"source\":\"" + tooLong + "\"}"))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	@DisplayName("NT-12: kết quả render của revision cũ hoàn tất sau revision mới thì không gắn vào revision mới; bản ghi cũ đóng dấu STALE_REVISION và mặt soạn thảo chỉ cho thấy kết quả của revision mới")
	void staleRenderResultDoesNotOverwrite() throws Exception {
		String teacher = authorToken("gv-latex-1");
		String[] ids = createQuestion(teacher);
		String base = base(ids);
		String sourceA = "\\frac{a}{1}";
		String sourceB = "\\frac{b}{2}";
		FAKE.blockOn(sourceA);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<MvcResult> first = pool.submit(() -> this.mockMvc
				.perform(put(base + "/f1").header("Authorization", "Bearer " + teacher)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"section\":\"stem\",\"source\":\"" + json(sourceA) + "\"}"))
				.andReturn());
			assertTrue(FAKE.entered.await(10, TimeUnit.SECONDS), "Tác vụ render đầu phải đang chạy dở");
			this.mockMvc
				.perform(put(base + "/f1").header("Authorization", "Bearer " + teacher)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"revision\":0,\"source\":\"" + json(sourceB) + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.renderState").value("ready"))
				.andExpect(jsonPath("$.source").value(sourceB));
			FAKE.release.countDown();
			first.get(15, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdownNow();
		}
		FormulaBlock block = this.blocks.findByQuestionVersionIdAndBlockId(UUID.fromString(ids[1]), "f1").orElseThrow();
		assertEquals(sourceB, block.getSource());
		List<FormulaRender> all = this.renders.findByFormulaBlockIdOrderBySourceRevisionDesc(block.getId());
		assertEquals(2, all.size());
		assertEquals(FormulaRender.STATUS_FAILED, all.get(1).getStatus());
		assertEquals("STALE_REVISION", all.get(1).getErrorCode());
		assertEquals(FormulaRender.STATUS_READY, all.get(0).getStatus());
		assertEquals(sha256(sourceB), all.get(0).getSourceHash());
	}

	@Test
	@DisplayName("NT-13: worker chết giữa chừng thì tác vụ thử lại an toàn qua thao tác render lại, phát hành được đúng một lần và không phiên bản nào tham chiếu tới ảnh chưa tồn tại")
	void retryAfterWorkerFailure() throws Exception {
		String teacher = authorToken("gv-latex-1");
		String[] ids = createQuestion(teacher);
		String base = base(ids);
		FAKE.failWith = "RENDERER_UNAVAILABLE";
		this.mockMvc
			.perform(put(base + "/f1").header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"section\":\"stem\",\"source\":\"\\\\sqrt{x}\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.renderState").value("failed"))
			.andExpect(jsonPath("$.errorCode").value("RENDERER_UNAVAILABLE"));
		FAKE.failWith = null;
		this.mockMvc.perform(post(base + "/f1/render").header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.renderState").value("ready"))
			.andExpect(jsonPath("$.image.fileRef").isNotEmpty());
		this.mockMvc
			.perform(post("/api/questions/" + ids[0] + "/versions/" + ids[1] + "/publish")
				.header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk());
		this.mockMvc
			.perform(post("/api/questions/" + ids[0] + "/versions/" + ids[1] + "/publish")
				.header("Authorization", "Bearer " + teacher))
			.andExpect(status().isConflict());
	}

	@Test
	@DisplayName("NT-25: đang render dở thì phát hành bị chặn; khi render xong và nguồn sửa tiếp thì bản phát hành cuối khớp đúng hash của nguồn đã chốt")
	void publishWaitsForCurrentRevisionRender() throws Exception {
		String teacher = authorToken("gv-latex-1");
		String[] ids = createQuestion(teacher);
		String base = base(ids);
		String sourceA = "\\frac{a}{1}";
		String sourceB = "\\frac{b}{9}";
		FAKE.blockOn(sourceA);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<MvcResult> first = pool.submit(() -> this.mockMvc
				.perform(put(base + "/f1").header("Authorization", "Bearer " + teacher)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"section\":\"stem\",\"source\":\"" + json(sourceA) + "\"}"))
				.andReturn());
			assertTrue(FAKE.entered.await(10, TimeUnit.SECONDS), "Tác vụ render phải đang chạy dở");
			this.mockMvc
				.perform(post("/api/questions/" + ids[0] + "/versions/" + ids[1] + "/publish")
					.header("Authorization", "Bearer " + teacher))
				.andExpect(status().isUnprocessableContent());
			FAKE.release.countDown();
			first.get(15, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdownNow();
		}
		this.mockMvc
			.perform(put(base + "/f1").header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"revision\":0,\"source\":\"" + json(sourceB) + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.renderState").value("ready"));
		this.mockMvc
			.perform(post("/api/questions/" + ids[0] + "/versions/" + ids[1] + "/publish")
				.header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk());
		FormulaBlock block = this.blocks.findByQuestionVersionIdAndBlockId(UUID.fromString(ids[1]), "f1").orElseThrow();
		FormulaRender current = this.renders
			.findByFormulaBlockIdAndSourceRevision(block.getId(), block.getRevision())
			.orElseThrow();
		assertEquals(sha256(sourceB), current.getSourceHash());
	}

	@Test
	@DisplayName("Tự soạn nguồn mới cho khối chỉ-có-ảnh: khối chuyển thành có-nguồn của chủ bản sao và render lại, trong khi sửa nguồn trực tiếp trên khối chỉ-có-ảnh bị từ chối và các khối khác không đổi")
	void authorSourceOnRenderOnlyBlock() throws Exception {
		String teacher = authorToken("gv-latex-1");
		String[] ids = createQuestion(teacher);
		String base = base(ids);
		UUID versionId = UUID.fromString(ids[1]);
		FormulaBlock inherited = new FormulaBlock();
		inherited.setId(UUID.fromString("01890000-0000-7000-8000-000000000001"));
		inherited.setQuestionVersionId(versionId);
		inherited.setOwnerSub("gv-latex-1");
		inherited.setSection("stem");
		inherited.setBlockId("ke-thua");
		inherited.setPosition(0);
		inherited.setForm(FormulaBlock.FORM_RENDER_ONLY);
		inherited.setSystemOrigin(true);
		inherited.setDescription("căn bậc hai của x");
		inherited.setCreatedAt(java.time.OffsetDateTime.now());
		inherited.setUpdatedAt(java.time.OffsetDateTime.now());
		this.blocks.saveAndFlush(inherited);
		FormulaRender ready = new FormulaRender();
		ready.setId(UUID.fromString("01890000-0000-7000-8000-000000000002"));
		ready.setFormulaBlockId(inherited.getId());
		ready.setSourceRevision(0);
		ready.setSourceHash("");
		ready.setStatus(FormulaRender.STATUS_READY);
		ready.setImageBlobId(seedImageBlob("gv-latex-1"));
		ready.setImageWidth(30);
		ready.setImageHeight(18);
		ready.setBaselineOffset(4);
		ready.setRendererVersion("fake-renderer@0");
		ready.setRenderConfig("{}");
		ready.setCreatedAt(java.time.OffsetDateTime.now());
		ready.setUpdatedAt(java.time.OffsetDateTime.now());
		this.renders.saveAndFlush(ready);
		this.mockMvc.perform(get(base).header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].form").value("render-only"))
			.andExpect(jsonPath("$[0].renderState").value("ready"));
		this.mockMvc
			.perform(put(base + "/ke-thua").header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"revision\":0,\"source\":\"\\\\sqrt{y}\"}"))
			.andExpect(status().isConflict());
		this.mockMvc
			.perform(post(base + "/ke-thua/author-source").header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"revision\":0,\"source\":\"\\\\sqrt{x}\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.form").value("source-backed"))
			.andExpect(jsonPath("$.source").value("\\sqrt{x}"))
			.andExpect(jsonPath("$.renderState").value("ready"));
		FormulaBlock authored = this.blocks.findByQuestionVersionIdAndBlockId(versionId, "ke-thua").orElseThrow();
		assertTrue(!authored.isSystemOrigin(), "Nguồn tự soạn là của chủ bản sao, không còn là nguồn gốc hệ thống");
	}

	@Test
	@DisplayName("Trường do server quyết định bị từ chối thay vì âm thầm bỏ qua, phần nội dung lạ bị từ chối, và số khối công thức của một phiên bản bị chặn ở giới hạn cấu hình")
	void serverManagedFieldsAndLimits() throws Exception {
		String teacher = authorToken("gv-latex-1");
		String[] ids = createQuestion(teacher);
		String base = base(ids);
		this.mockMvc
			.perform(put(base + "/f1").header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"section\":\"stem\",\"source\":\"x\",\"form\":\"render-only\"}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		this.mockMvc
			.perform(put(base + "/f1").header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"section\":\"khong-hop-le\",\"source\":\"x\"}"))
			.andExpect(status().isUnprocessableContent());
		for (int i = 0; i < 50; i++) {
			this.mockMvc
				.perform(put(base + "/khoi-" + i).header("Authorization", "Bearer " + teacher)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"section\":\"stem\",\"source\":\"x_" + i + "\"}"))
				.andExpect(status().isOk());
		}
		this.mockMvc
			.perform(put(base + "/khoi-50").header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"section\":\"stem\",\"source\":\"x\"}"))
			.andExpect(status().isUnprocessableContent());
	}

	private String base(String[] ids) {
		return "/api/questions/" + ids[0] + "/versions/" + ids[1] + "/formula-blocks";
	}

	// Ảnh của bản ghi render seed sẵn là một blob thật của nền tệp: byte chốt qua cổng lưu trữ ở khoá bất
	// biến và hàng blob ghi trong phân vùng cá nhân của chủ khối, đúng hình dạng đường render thật tạo ra.
	private UUID seedImageBlob(String ownerSub) {
		UUID blobId = UUID.fromString("01890000-0000-7000-8000-000000000003");
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
		java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
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

	private String sha256Bytes(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private String[] createQuestion(String teacher) throws Exception {
		MvcResult bank = this.mockMvc
			.perform(post("/api/question-banks").header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"ngan-hang-latex\"}"))
			.andExpect(status().isOk())
			.andReturn();
		String bankId = JsonPath.read(bank.getResponse().getContentAsString(), "$.id");
		MvcResult question = this.mockMvc
			.perform(post("/api/questions").header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"bankId\":\"" + bankId + "\",\"questionType\":\"essay_text\",\"stem\":\"than-cau\","
						+ "\"rubric\":\"tieu-chi-cham\"}"))
			.andExpect(status().isOk())
			.andReturn();
		String body = question.getResponse().getContentAsString();
		return new String[] { JsonPath.read(body, "$.id"), JsonPath.read(body, "$.versions[0].versionId") };
	}

	private String json(String raw) {
		return raw.replace("\\", "\\\\");
	}

	private String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
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
