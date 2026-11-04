package com.giaokhoa.server.attachment;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import javax.imageio.ImageIO;

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
import org.springframework.mock.web.MockMultipartFile;
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
import com.giaokhoa.server.tenancy.TenantContexts;

// Kiểm thử lát tệp đính kèm trên cùng khuôn với QuestionEndpointTests: chuỗi lọc bảo mật thật và tầng dữ
// kiện thật của feat câu hỏi (Flyway + JPA trên H2 ở chế độ PostgreSQL); phần kho đi qua cổng AttachmentStore
// với bản cài đặt thật trên nền tệp L1 (ba bảng blob/phiên/liên kết và cổng byte BlobStorage), nên các ca ở
// đây kiểm cả hợp đồng của cổng lẫn việc dữ kiện đính kèm thật sự nằm trên nền đó.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + AttachmentEndpointTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:attachment;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration" })
class AttachmentEndpointTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private AttachmentStore store;

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
	@DisplayName("Luồng trọn vẹn: mở phiên, tải ảnh, xem trước đúng bản đã xử lý, gắn vào nháp, đọc lại đúng byte và phát hành được")
	void fullAttachmentFlow() throws Exception {
		String teacher = teacher("gv-l2a-1");
		String[] target = draftQuestion(teacher, "ngan-hang-l2a-1");
		byte[] png = png(9, 6);

		String sessionId = openSession(teacher, target, "content");
		MvcResult uploaded = upload(teacher, target, sessionId, png, "anh-minh-hoa.png")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ready"))
				.andExpect(jsonPath("$.contentType").value("image/png"))
				.andExpect(jsonPath("$.width").value(9))
				.andReturn();
		String checksum = JsonPath.read(uploaded.getResponse().getContentAsString(), "$.checksumSha256");
		assertEquals(ImageProcessing.sha256(png), checksum);

		MvcResult preview = this.mockMvc
				.perform(get(previewUrl(target, sessionId)).header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andReturn();
		assertArrayEquals(png, preview.getResponse().getContentAsByteArray());

		MvcResult completed = complete(teacher, target, sessionId, "{}").andExpect(status().isOk()).andReturn();
		String linkId = JsonPath.read(completed.getResponse().getContentAsString(), "$.linkId");
		String blobChecksum = JsonPath.read(completed.getResponse().getContentAsString(), "$.checksumSha256");
		assertEquals(checksum, blobChecksum);

		MvcResult read = this.mockMvc
				.perform(get(baseUrl(target) + "/" + linkId + "/content").header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("private")))
				.andReturn();
		assertArrayEquals(png, read.getResponse().getContentAsByteArray());

		this.mockMvc.perform(post("/api/questions/" + target[0] + "/versions/" + target[1] + "/publish")
				.header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk());
		this.mockMvc
				.perform(get(baseUrl(target) + "/" + linkId + "/content").header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("NT-19: tệp giả mạo loại, tệp ngoài danh sách cho phép và tệp vượt giới hạn bị từ chối ở bước tải lên và phiên không sẵn sàng")
	void rejectedUploadsNeverBecomeReady() throws Exception {
		String teacher = teacher("gv-l2a-2");
		String[] target = draftQuestion(teacher, "ngan-hang-l2a-2");

		String spoofSession = openSession(teacher, target, "content");
		upload(teacher, target, spoofSession, "<html>gia-mao</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8),
				"anh.png")
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("FILE_REJECTED"));
		complete(teacher, target, spoofSession, "{}").andExpect(status().isConflict());

		String texSession = openSession(teacher, target, "content");
		upload(teacher, target, texSession, "\\frac{1}{2}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
				"cong-thuc.tex")
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("FILE_REJECTED"));

		String bigSession = openSession(teacher, target, "content");
		byte[] header = png(3, 3);
		byte[] oversized = new byte[5 * 1024 * 1024 + 1];
		System.arraycopy(header, 0, oversized, 0, header.length);
		upload(teacher, target, bigSession, oversized, "anh-lon.png")
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("FILE_REJECTED"));
	}

	@Test
	@DisplayName("NT-26 và NT-28: byte vùng tạm bị thay sau kiểm tra thì chốt bị từ chối; retry hoàn tất không tạo liên kết thừa và không đổi được đích")
	void tamperedStagingAndRetryRules() throws Exception {
		String teacher = teacher("gv-l2a-3");
		String[] target = draftQuestion(teacher, "ngan-hang-l2a-3");

		String tamperedSession = openSession(teacher, target, "content");
		upload(teacher, target, tamperedSession, png(4, 4), "anh-goc.png").andExpect(status().isOk());
		this.store.stage(UUID.fromString(tamperedSession), png(8, 8));
		complete(teacher, target, tamperedSession, "{}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
		this.mockMvc.perform(get(baseUrl(target)).header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		String sessionId = openSession(teacher, target, "content");
		upload(teacher, target, sessionId, png(4, 4), "anh-hop-le.png").andExpect(status().isOk());
		MvcResult first = complete(teacher, target, sessionId, "{}").andExpect(status().isOk()).andReturn();
		String linkId = JsonPath.read(first.getResponse().getContentAsString(), "$.linkId");
		MvcResult retry = complete(teacher, target, sessionId, "{}").andExpect(status().isOk()).andReturn();
		assertEquals(linkId, JsonPath.read(retry.getResponse().getContentAsString(), "$.linkId"));
		this.mockMvc.perform(get(baseUrl(target)).header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		String otherVersion = UUID.randomUUID().toString();
		complete(teacher, target, sessionId, "{\"versionId\":\"" + otherVersion + "\"}")
				.andExpect(status().isConflict());
		String freshSession = openSession(teacher, target, "content");
		upload(teacher, target, freshSession, png(4, 4), "anh-khac.png").andExpect(status().isOk());
		complete(teacher, target, freshSession, "{\"versionId\":\"" + otherVersion + "\"}")
				.andExpect(status().isConflict());
		this.mockMvc.perform(get(baseUrl(target)).header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	@DisplayName("B1, B2 và NT-30: đọc tệp bằng định danh ở sai ngữ cảnh trả 404, người không phải chủ sở hữu không đọc được tệp và không dùng được phiên của người khác")
	void contextAndOwnershipGuards() throws Exception {
		String owner = teacher("gv-l2a-4");
		String[] target = draftQuestion(owner, "ngan-hang-l2a-4");
		String sessionId = openSession(owner, target, "answer");
		upload(owner, target, sessionId, png(4, 4), "anh-dap-an.png").andExpect(status().isOk());
		MvcResult completed = complete(owner, target, sessionId, "{}").andExpect(status().isOk()).andReturn();
		String linkId = JsonPath.read(completed.getResponse().getContentAsString(), "$.linkId");

		String other = teacher("gv-l2a-5");
		this.mockMvc
				.perform(get(baseUrl(target) + "/" + linkId + "/content").header("Authorization", "Bearer " + other))
				.andExpect(status().isForbidden());
		this.mockMvc.perform(get(baseUrl(target)).header("Authorization", "Bearer " + other))
				.andExpect(status().isForbidden());
		String[] otherTarget = draftQuestion(other, "ngan-hang-l2a-5");
		String otherSession = openSession(other, otherTarget, "content");
		upload(other, target, sessionId, png(4, 4), "anh-chiem-phien.png").andExpect(status().isNotFound());
		complete(other, target, sessionId, "{}").andExpect(status().isNotFound());
		upload(other, otherTarget, otherSession, png(4, 4), "anh-cua-minh.png").andExpect(status().isOk());

		this.mockMvc
				.perform(get("/api/questions/" + UUID.randomUUID() + "/versions/" + target[1] + "/attachments/"
						+ linkId + "/content").header("Authorization", "Bearer " + owner))
				.andExpect(status().isNotFound());
		this.mockMvc
				.perform(get("/api/questions/" + target[0] + "/versions/" + UUID.randomUUID() + "/attachments/"
						+ linkId + "/content").header("Authorization", "Bearer " + owner))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("B6 và NT-20: phiên bản còn phiên tải lên chưa chốt thì phát hành bị chặn 409; gắn vào phiên bản đã khoá bị từ chối")
	void publishGateAndLockedVersionGuards() throws Exception {
		String teacher = teacher("gv-l2a-6");
		String[] target = draftQuestion(teacher, "ngan-hang-l2a-6");
		String sessionId = openSession(teacher, target, "content");
		upload(teacher, target, sessionId, png(4, 4), "anh-chua-gan.png").andExpect(status().isOk());
		this.mockMvc
				.perform(post("/api/questions/" + target[0] + "/versions/" + target[1] + "/publish")
						.header("Authorization", "Bearer " + teacher))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_READY"));
		complete(teacher, target, sessionId, "{}").andExpect(status().isOk());
		this.mockMvc
				.perform(post("/api/questions/" + target[0] + "/versions/" + target[1] + "/publish")
						.header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk());

		String lateSession = null;
		try {
			lateSession = openSession(teacher, target, "content");
		}
		catch (AssertionError expected) {
			// Mở phiên trên phiên bản đã khoá đã bị từ chối ngay: đúng một trong hai điểm chặn cần chứng minh.
			lateSession = null;
		}
		if (lateSession != null) {
			upload(teacher, target, lateSession, png(4, 4), "anh-muon.png").andExpect(status().isOk());
			complete(teacher, target, lateSession, "{}").andExpect(status().isConflict());
		}
	}

	@Test
	@DisplayName("NT-20: phiên tải lên dở đã quá hạn thì bị đánh dấu hết hạn ở cổng phát hành và không chặn phát hành nữa")
	void expiredStaleSessionDoesNotBlockPublish() throws Exception {
		String teacher = teacher("gv-l2a-7");
		String[] target = draftQuestion(teacher, "ngan-hang-l2a-7");
		String sessionId = openSession(teacher, target, "content");
		upload(teacher, target, sessionId, png(4, 4), "anh-bo-do.png").andExpect(status().isOk());

		// Đẩy phiên về quá hạn qua chính cổng kho, như một phiên bị bỏ dở quá thời hạn cấu hình mà không còn
		// ai chạm tới; lời gọi cổng kho trực tiếp cần ngữ cảnh tenant của chính chủ phiên như một request thật.
		TenantContexts.set(new TenantContext("gv-l2a-7", null, null, List.of(), false, false, "user:gv-l2a-7"));
		try {
			AttachmentStore.Session session = this.store.findSession(UUID.fromString(sessionId)).orElseThrow();
			this.store.saveSession(new AttachmentStore.Session(session.id(), session.uploaderSub(),
					session.questionId(), session.versionId(), session.position(), session.blockId(),
					session.displayName(), session.status(), session.contentType(), session.sizeBytes(),
					session.width(), session.height(), session.stagedChecksum(), session.blobId(),
					session.linkId(), session.expiresAt().minusHours(48)));
		}
		finally {
			TenantContexts.clear();
		}

		this.mockMvc
				.perform(post("/api/questions/" + target[0] + "/versions/" + target[1] + "/publish")
						.header("Authorization", "Bearer " + teacher))
				.andExpect(status().isOk());

		TenantContexts.set(new TenantContext("gv-l2a-7", null, null, List.of(), false, false, "user:gv-l2a-7"));
		try {
			assertEquals("EXPIRED",
					this.store.findSession(UUID.fromString(sessionId)).orElseThrow().status());
		}
		finally {
			TenantContexts.clear();
		}
	}

	private String baseUrl(String[] target) {
		return "/api/questions/" + target[0] + "/versions/" + target[1] + "/attachments";
	}

	private String previewUrl(String[] target, String sessionId) {
		return baseUrl(target) + "/sessions/" + sessionId + "/preview";
	}

	private String openSession(String token, String[] target, String position) throws Exception {
		MvcResult result = this.mockMvc
				.perform(post(baseUrl(target) + "/sessions").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"position\":\"" + position + "\",\"displayName\":\"anh.png\"}"))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.sessionId");
	}

	private org.springframework.test.web.servlet.ResultActions upload(String token, String[] target, String sessionId,
			byte[] bytes, String fileName) throws Exception {
		return this.mockMvc.perform(multipart(baseUrl(target) + "/sessions/" + sessionId + "/content")
				.file(new MockMultipartFile("file", fileName, "image/png", bytes))
				.header("Authorization", "Bearer " + token));
	}

	private org.springframework.test.web.servlet.ResultActions complete(String token, String[] target,
			String sessionId, String body) throws Exception {
		return this.mockMvc.perform(post(baseUrl(target) + "/sessions/" + sessionId + "/complete")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private String[] draftQuestion(String token, String bankName) throws Exception {
		MvcResult bank = this.mockMvc
				.perform(post("/api/question-banks").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"" + bankName + "\"}"))
				.andExpect(status().isOk())
				.andReturn();
		String bankId = JsonPath.read(bank.getResponse().getContentAsString(), "$.id");
		MvcResult question = this.mockMvc
				.perform(post("/api/questions").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"bankId\":\"" + bankId
								+ "\",\"questionType\":\"essay_text\",\"stem\":\"than-cau\",\"rubric\":\"tieu-chi\"}"))
				.andExpect(status().isOk())
				.andReturn();
		String questionId = JsonPath.read(question.getResponse().getContentAsString(), "$.id");
		String versionId = JsonPath.read(question.getResponse().getContentAsString(), "$.versions[0].versionId");
		return new String[] { questionId, versionId };
	}

	private byte[] png(int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, 0x224466);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private String teacher(String sub) throws Exception {
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
