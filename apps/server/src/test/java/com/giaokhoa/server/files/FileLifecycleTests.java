package com.giaokhoa.server.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

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
import org.springframework.http.HttpMethod;
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

// Nghiệm thu vòng đời của nền tệp trên cùng đường thật như FileFoundationEndpointTests (H2 ở chế độ PostgreSQL,
// kho blob filesystem thật, dịch vụ quyết định quyền thật): dọn bản ghi phiên đã hết hạn, thu gom blob theo
// tham chiếu với thời hạn lưu và khoảng chờ được truyền tường minh qua tham số thời điểm nên kết quả xác định,
// cách ly muộn có lý do, và đường đọc không rò nội dung giữa hai tài khoản. Phần đồng thời thật của việc thu
// gom và phần sao lưu/phục hồi cần PostgreSQL thật, nằm ở FileLifecyclePostgresTests và
// FileRestorePostgresTests.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + FileLifecycleTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:files-lifecycle;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration", "app.files.root=./target/test-files-lifecycle",
		"app.files.max-file-size-bytes=8192", "app.files.max-megapixels=300" })
class FileLifecycleTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final Pattern RESOURCE_ID = Pattern.compile("\"resourceId\":\"([^\"]+)\"");

	private static final Pattern VERSION_ID = Pattern.compile("\"versionId\":\"([^\"]+)\"");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private FileService fileService;

	@Autowired
	private BlobStorage storage;

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
	@DisplayName("NT-20 (phần dọn bản ghi): phiên đã bị đánh dấu hết hạn ở một lượt quét trước bị xoá hẳn bản ghi ở lượt dọn sau, phiên còn hạn và phiên đã hoàn tất không bị đụng, và blob đã chốt của phiên bị dọn không bị xoá theo phiên")
	void expiredSessionRecordsArePurgedButTheirBlobsSurvive() throws Exception {
		String teacher = authorToken("gv-don-phien-xong");
		UUID[] target = createPersonalResource(teacher);
		String partition = "user:gv-don-phien-xong";
		OffsetDateTime t0 = OffsetDateTime.now();

		UUID openSession = createSession(teacher, target);
		UUID readySession = createSession(teacher, target);
		MvcResult uploaded = upload(teacher, null, readySession, png(4, 3, false), "tep-da-chot.png");
		assertEquals(200, uploaded.getResponse().getStatus(), body(uploaded));
		UUID readyBlob = UUID.fromString(JsonPath.read(body(uploaded), "$.blobId"));
		String keptLink = uploadAndAttach(teacher, target, "content", png(5, 3, false));
		UUID liveSession = createSession(teacher, target);

		this.jdbcTemplate.update("update upload_session set expires_at = ? where id in (?, ?)", t0.minusHours(1),
				openSession, readySession);
		assertEquals(2, this.fileService.expireStaleSessions(partition, t0));
		// Bản ghi vừa bị đánh dấu ở chính lượt này chưa bị xoá ngay: trạng thái hết hạn phải quan sát được trước
		// khi bản ghi rời hệ thống ở một lượt dọn sau.
		assertEquals(0, this.fileService.purgeExpiredSessions(partition, t0));
		assertEquals(2, this.fileService.purgeExpiredSessions(partition, t0.plusSeconds(1)));
		assertEquals(0, countRows("select count(*) from upload_session where id in (?, ?)", openSession,
				readySession));
		assertEquals(1, countRows("select count(*) from file_blob where id = ?", readyBlob));
		assertEquals(1, countRows("select count(*) from upload_session where id = ?", liveSession));
		assertEquals(200, get(teacher, null, "/api/files/links/" + keptLink + "/content").getResponse()
				.getStatus());
	}

	@Test
	@DisplayName("NT-17 (phần thu gom): blob hết mọi liên kết và phiên giữ, đã quá thời hạn lưu, chỉ bị xoá byte sau khi mốc thu gom qua khoảng chờ an toàn; blob còn liên kết và blob chưa hết hạn lưu cùng byte của chúng đều nguyên vẹn, lần thu gom ghi một dấu vết kiểm toán")
	void collectionDeletesOnlyUnreferencedBlobsPastRetentionAndGrace() throws Exception {
		String teacher = authorToken("gv-thu-gom");
		UUID[] target = createPersonalResource(teacher);
		String partition = "user:gv-thu-gom";
		OffsetDateTime now = OffsetDateTime.now();

		byte[] kept = png(4, 3, false);
		String keptLink = uploadAndAttach(teacher, target, "content", kept);
		UUID keptBlob = blobIdOfLink(keptLink);
		UUID oldOrphan = orphanBlob(teacher, partition, target, now);
		UUID youngOrphan = orphanBlob(teacher, partition, target, now);
		// Blob còn liên kết được đặt cùng tuổi với blob mồ côi bị thu gom: thứ cứu nó là tham chiếu, không phải
		// tuổi.
		this.jdbcTemplate.update("update file_blob set created_at = ? where id in (?, ?)", now.minusDays(31),
				keptBlob, oldOrphan);
		String orphanKey = storageKeyOf(oldOrphan);

		assertEquals(0, this.fileService.collectOrphanedBlobs(partition, now));
		assertTrue(isMarked(oldOrphan));
		assertFalse(isMarked(keptBlob));
		assertFalse(isMarked(youngOrphan));

		this.jdbcTemplate.update("update file_blob set gc_marked_at = ? where id = ?", now.minusHours(25),
				oldOrphan);
		assertEquals(1, this.fileService.collectOrphanedBlobs(partition, now));
		assertEquals(0, countRows("select count(*) from file_blob where id = ?", oldOrphan));
		assertThrows(UncheckedIOException.class, () -> this.storage.read(orphanKey));
		assertEquals(1, countRows("select count(*) from file_blob where id = ?", keptBlob));
		assertEquals(1, countRows("select count(*) from file_blob where id = ?", youngOrphan));
		MvcResult read = get(teacher, null, "/api/files/links/" + keptLink + "/content");
		assertEquals(200, read.getResponse().getStatus());
		assertArrayEquals(kept, read.getResponse().getContentAsByteArray());
		assertEquals(1, countRows(
				"select count(*) from file_audit_log where action = ? and blob_id = ? and actor_sub = ?",
				"blob_collected", oldOrphan, "system"));
	}

	@Test
	@DisplayName("NT-27: blob đã bị ghi mốc thu gom mà có lại một liên kết hợp lệ trong khoảng chờ thì không bị xoá, mốc được gỡ và nội dung đọc lại trọn vẹn qua liên kết mới")
	void markedBlobAttachedAgainBeforeCollectionSurvives() throws Exception {
		String teacher = authorToken("gv-cuu-blob");
		UUID[] first = createPersonalResource(teacher);
		UUID[] second = createPersonalResource(teacher);
		String partition = "user:gv-cuu-blob";
		OffsetDateTime now = OffsetDateTime.now();

		UUID blobId = orphanBlob(teacher, partition, first, now);
		this.jdbcTemplate.update("update file_blob set created_at = ? where id = ?", now.minusDays(31), blobId);
		assertEquals(0, this.fileService.collectOrphanedBlobs(partition, now));
		assertTrue(isMarked(blobId));

		MvcResult attached = post(teacher, null, "/api/files/links",
				attachBlobJson(blobId.toString(), second, "tep-cuu.png"));
		assertEquals(200, attached.getResponse().getStatus(), body(attached));
		String linkId = JsonPath.read(body(attached), "$.id");

		this.jdbcTemplate.update("update file_blob set gc_marked_at = ? where id = ?", now.minusHours(25),
				blobId);
		assertEquals(0, this.fileService.collectOrphanedBlobs(partition, now));
		assertEquals(1, countRows("select count(*) from file_blob where id = ?", blobId));
		assertFalse(isMarked(blobId));
		assertEquals(200, get(teacher, null, "/api/files/links/" + linkId + "/content").getResponse()
				.getStatus());
	}

	@Test
	@DisplayName("NT-24: cách ly muộn một blob đang được liên kết chặn đường đọc ngay, không sửa một byte nào của blob, giữ nguyên liên kết, ghi dấu vết có người thực hiện, thời điểm và lý do; blob bị cách ly là chứng cứ nên không bị thu gom")
	void lateQuarantineBlocksServingKeepsBytesAndRecordsReason() throws Exception {
		String platform = platformToken("admin-cach-ly-muon");
		MvcResult created = post(platform, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"system\",\"contentHash\":\"h-sys-muon\"}");
		assertEquals(200, created.getResponse().getStatus(), body(created));
		UUID[] target = { extract(RESOURCE_ID, created), extract(VERSION_ID, created) };
		byte[] png = png(4, 3, false);
		String linkId = uploadAndAttach(platform, target, "content", png);
		UUID blobId = blobIdOfLink(linkId);
		String storageKey = storageKeyOf(blobId);
		String checksumBefore = checksumOf(blobId);
		String reason = "Quét bảo mật phát hiện mã độc trong tệp sau khi tệp đã chốt";

		MvcResult quarantined = post(platform, null, "/api/files/blobs/" + blobId + "/quarantine",
				"{\"reason\":\"" + reason + "\"}");
		assertEquals(200, quarantined.getResponse().getStatus(), body(quarantined));

		assertEquals(404, get(platform, null, "/api/files/links/" + linkId + "/content").getResponse()
				.getStatus());
		assertEquals("QUARANTINED", this.jdbcTemplate.queryForObject("select status from file_blob where id = ?",
				String.class, blobId));
		assertEquals(checksumBefore, checksumOf(blobId));
		assertArrayEquals(png, this.storage.read(storageKey));
		assertEquals(1, countRows("select count(*) from file_link where id = ?", UUID.fromString(linkId)));
		Map<String, Object> auditRow = this.jdbcTemplate.queryForMap(
				"select actor_sub, detail, created_at from file_audit_log where action = ? and blob_id = ?",
				"quarantine", blobId);
		assertEquals("admin-cach-ly-muon", auditRow.get("actor_sub"));
		assertEquals(reason, auditRow.get("detail"));
		assertNotNull(auditRow.get("created_at"));

		this.jdbcTemplate.update("update file_blob set created_at = ? where id = ?",
				OffsetDateTime.now().minusDays(31), blobId);
		assertEquals(0, this.fileService.collectOrphanedBlobs("system", OffsetDateTime.now()));
		assertEquals(1, countRows("select count(*) from file_blob where id = ?", blobId));
	}

	@Test
	@DisplayName("NT-31 và B11: tệp đáp án giáo viên vừa đọc qua một đường đọc không lộ cho tài khoản khác gọi đúng đường đọc đó — tài khoản không quyền nhận lỗi và không nhận được byte nào của tệp, tiêu đề bộ nhớ đệm cấm lưu trữ, còn giáo viên đọc lại vẫn nguyên vẹn")
	void answerFileDoesNotLeakToAnotherAccountOnSameReadPath() throws Exception {
		String teacher = authorToken("gv-doc-dap-an");
		UUID[] target = createPersonalResource(teacher);
		byte[] png = png(4, 3, false);
		String answerLink = uploadAndAttach(teacher, target, "answer", png);

		MvcResult read = get(teacher, null, "/api/files/links/" + answerLink + "/content");
		assertEquals(200, read.getResponse().getStatus());
		assertArrayEquals(png, read.getResponse().getContentAsByteArray());
		String cacheControl = read.getResponse().getHeader("Cache-Control");
		assertTrue(cacheControl.contains("no-store"), cacheControl);
		assertTrue(cacheControl.contains("private"), cacheControl);

		String other = authorToken("hs-cung-trinh-duyet");
		MvcResult denied = get(other, null, "/api/files/links/" + answerLink + "/content");
		assertEquals(404, denied.getResponse().getStatus());
		assertFalse(Arrays.equals(png, denied.getResponse().getContentAsByteArray()));
		assertEquals(404, get(other, null, "/api/files/resources/" + target[0] + "/versions/" + target[1]
				+ "/links").getResponse().getStatus());

		MvcResult reread = get(teacher, null, "/api/files/links/" + answerLink + "/content");
		assertEquals(200, reread.getResponse().getStatus());
		assertArrayEquals(png, reread.getResponse().getContentAsByteArray());
	}

	// Chạy một blob qua đường thật tới trạng thái mồ côi: chốt byte bằng một phiên, cho phiên hết hạn, dọn bản
	// ghi phiên — blob còn lại không liên kết, không phiên giữ.
	private UUID orphanBlob(String token, String partition, UUID[] target, OffsetDateTime now) throws Exception {
		UUID sessionId = createSession(token, target);
		MvcResult uploaded = upload(token, null, sessionId, png(4, 3, false), "tep-mo-coi.png");
		assertEquals(200, uploaded.getResponse().getStatus(), body(uploaded));
		UUID blobId = UUID.fromString(JsonPath.read(body(uploaded), "$.blobId"));
		this.jdbcTemplate.update("update upload_session set expires_at = ? where id = ?", now.minusHours(1),
				sessionId);
		this.fileService.expireStaleSessions(partition, now);
		this.fileService.purgeExpiredSessions(partition, now.plusSeconds(1));
		assertEquals(0, countRows("select count(*) from upload_session where id = ?", sessionId));
		return blobId;
	}

	private boolean isMarked(UUID blobId) {
		return countRows("select count(*) from file_blob where id = ? and gc_marked_at is not null",
				blobId) == 1;
	}

	private UUID blobIdOfLink(String linkId) {
		return this.jdbcTemplate.queryForObject("select blob_id from file_link where id = ?", UUID.class,
				UUID.fromString(linkId));
	}

	private String storageKeyOf(UUID blobId) {
		return this.jdbcTemplate.queryForObject("select storage_key from file_blob where id = ?", String.class,
				blobId);
	}

	private String checksumOf(UUID blobId) {
		return this.jdbcTemplate.queryForObject("select checksum_sha256 from file_blob where id = ?", String.class,
				blobId);
	}

	private UUID[] createPersonalResource(String token) throws Exception {
		MvcResult created = post(token, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"personal\",\"contentHash\":\"h1\"}");
		assertEquals(200, created.getResponse().getStatus(), body(created));
		return new UUID[] { extract(RESOURCE_ID, created), extract(VERSION_ID, created) };
	}

	private String sessionJson(UUID[] target, String displayName) {
		return "{\"resourceId\":\"" + target[0] + "\",\"versionId\":\"" + target[1] + "\",\"displayName\":\""
				+ displayName + "\"}";
	}

	private String attachBlobJson(String blobId, UUID[] target, String displayName) {
		return "{\"blobId\":\"" + blobId + "\",\"resourceId\":\"" + target[0] + "\",\"versionId\":\"" + target[1]
				+ "\",\"position\":\"content\",\"displayName\":\"" + displayName + "\"}";
	}

	private UUID createSession(String token, UUID[] target) throws Exception {
		MvcResult session = post(token, null, "/api/files/upload-sessions", sessionJson(target, "tep.png"));
		assertEquals(200, session.getResponse().getStatus(), body(session));
		return UUID.fromString(JsonPath.read(body(session), "$.id"));
	}

	private String uploadAndAttach(String token, UUID[] target, String position, byte[] bytes) throws Exception {
		UUID sessionId = createSession(token, target);
		MvcResult uploaded = upload(token, null, sessionId, bytes, "tep.png");
		assertEquals(200, uploaded.getResponse().getStatus(), body(uploaded));
		MvcResult attached = post(token, null, "/api/files/upload-sessions/" + sessionId + "/attach",
				"{\"position\":\"" + position + "\"}");
		assertEquals(200, attached.getResponse().getStatus(), body(attached));
		return JsonPath.read(body(attached), "$.id");
	}

	private int countRows(String sql, Object... args) {
		Integer count = this.jdbcTemplate.queryForObject(sql, Integer.class, args);
		return (count != null) ? count : 0;
	}

	private MvcResult upload(String token, String alias, UUID sessionId, byte[] bytes, String filename)
			throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", filename, "application/octet-stream", bytes);
		var builder = MockMvcRequestBuilders
				.multipart(HttpMethod.PUT, "/api/files/upload-sessions/" + sessionId + "/content").file(file)
				.header("Authorization", "Bearer " + token);
		if (alias != null) {
			builder.header("X-LMS-Organization-Alias", alias);
		}
		return this.mockMvc.perform(builder).andReturn();
	}

	private MvcResult post(String token, String alias, String path, String json) throws Exception {
		MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post(path)
				.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json);
		if (alias != null) {
			builder.header("X-LMS-Organization-Alias", alias);
		}
		return this.mockMvc.perform(builder).andReturn();
	}

	private MvcResult get(String token, String alias, String path) throws Exception {
		MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.get(path)
				.header("Authorization", "Bearer " + token);
		if (alias != null) {
			builder.header("X-LMS-Organization-Alias", alias);
		}
		return this.mockMvc.perform(builder).andReturn();
	}

	private String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString();
	}

	private UUID extract(Pattern pattern, MvcResult result) throws Exception {
		var matcher = pattern.matcher(body(result));
		if (!matcher.find()) {
			throw new AssertionError("response missing field: " + body(result));
		}
		return UUID.fromString(matcher.group(1));
	}

	private static byte[] png(int width, int height, boolean noise) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		if (noise) {
			java.util.Random random = new java.util.Random(42);
			for (int x = 0; x < width; x++) {
				for (int y = 0; y < height; y++) {
					image.setRGB(x, y, random.nextInt(0xFFFFFF));
				}
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private String authorToken(String sub) throws Exception {
		return mint(sub, Map.of("realm_access", Map.of("roles", List.of("personal-author"))));
	}

	private String platformToken(String sub) throws Exception {
		return mint(sub, Map.of("realm_access", Map.of("roles", List.of("platform-admin"))));
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
