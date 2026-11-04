package com.giaokhoa.server.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.giaokhoa.server.access.AccessAction;
import com.giaokhoa.server.access.AccessDecisionService;
import com.giaokhoa.server.tenancy.MembershipChecker;
import com.giaokhoa.server.tenancy.TenantContext;
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

// Nghiệm thu nền tệp trên đường thật của ứng dụng: chuỗi lọc bảo mật và khung tenancy của F3, tầng dữ kiện thật
// (Flyway + Hibernate/JPA với discriminator) trên H2 ở chế độ PostgreSQL, kho blob filesystem thật tại thư mục
// tạm của lượt test, và dịch vụ quyết định quyền thật của lõi. Giới hạn dung lượng và điểm ảnh được hạ nhỏ qua
// cấu hình của riêng lớp này để các ca vượt giới hạn chạy bằng tệp mẫu nhỏ. Lớp RLS của PostgreSQL cho các bảng
// tệp được kiểm chứng riêng trong FilePostgresRlsTests.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + FileFoundationEndpointTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:files;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration", "app.files.root=./target/test-files-foundation",
		"app.files.max-file-size-bytes=8192", "app.files.max-megapixels=300" })
class FileFoundationEndpointTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	private static final String ALIAS_1 = "trung-tam-1";

	private static final Pattern RESOURCE_ID = Pattern.compile("\"resourceId\":\"([^\"]+)\"");

	private static final Pattern VERSION_ID = Pattern.compile("\"versionId\":\"([^\"]+)\"");

	private static final Pattern ASSIGNMENT_ID = Pattern.compile("\"assignmentId\":\"([^\"]+)\"");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private FileService fileService;

	@Autowired
	private AccessDecisionService decisions;

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
	@DisplayName("Luồng tải lên trọn vòng trên tài nguyên cá nhân: xin phiên, chốt byte thành blob bất biến khớp checksum đã kiểm chứng, gắn vào bản nháp, đọc lại qua liên kết và ghi đủ dấu vết kiểm toán")
	void fullUploadFlowOnPersonalResource() throws Exception {
		String teacher = authorToken("gv-tai-len");
		UUID[] target = createPersonalResource(teacher);
		byte[] png = png(4, 3, false);

		MvcResult session = post(teacher, null, "/api/files/upload-sessions",
				sessionJson(target, "anh-minh-hoa.png"));
		assertEquals(200, session.getResponse().getStatus(), body(session));
		assertEquals("uploading", JsonPath.read(body(session), "$.state"));
		UUID sessionId = UUID.fromString(JsonPath.read(body(session), "$.id"));

		MvcResult uploaded = upload(teacher, null, sessionId, png, "anh-minh-hoa.png");
		assertEquals(200, uploaded.getResponse().getStatus(), body(uploaded));
		assertEquals("ready", JsonPath.read(body(uploaded), "$.state"));
		String blobId = JsonPath.read(body(uploaded), "$.blobId");

		MvcResult attached = post(teacher, null, "/api/files/upload-sessions/" + sessionId + "/attach",
				"{\"position\":\"content\"}");
		assertEquals(200, attached.getResponse().getStatus(), body(attached));
		String linkId = JsonPath.read(body(attached), "$.id");
		assertEquals(blobId, JsonPath.read(body(attached), "$.blobId"));
		assertEquals("image/png", JsonPath.read(body(attached), "$.contentType"));
		assertEquals("ready", JsonPath.read(body(attached), "$.state"));

		MvcResult read = get(teacher, null, "/api/files/links/" + linkId + "/content");
		assertEquals(200, read.getResponse().getStatus());
		assertEquals(png.length, read.getResponse().getContentAsByteArray().length);
		assertTrue(read.getResponse().getHeader("Cache-Control").contains("no-store"));

		String checksum = this.jdbcTemplate.queryForObject("select checksum_sha256 from file_blob where id = ?",
				String.class, UUID.fromString(blobId));
		assertEquals(sha256(png), checksum);
		assertEquals(1, countRows("select count(*) from file_link where id = ?", UUID.fromString(linkId)));
		assertEquals(3, countRows("select count(*) from file_audit_log where actor_sub = ?", "gv-tai-len"));
	}

	@Test
	@DisplayName("NT-19: tệp giả mạo loại bằng đuôi hay Content-Type, tệp ngoài danh sách cho phép, tệp vượt dung lượng và vượt điểm ảnh đều bị từ chối ở bước kiểm chứng, và phiên vẫn dùng tiếp được sau các lần từ chối")
	void forgedAndOversizedFilesAreRejected() throws Exception {
		String teacher = authorToken("gv-kiem-loai");
		UUID[] target = createPersonalResource(teacher);
		UUID sessionId = createSession(teacher, target);

		assertEquals(422, upload(teacher, null, sessionId, "day chi la van ban".getBytes(StandardCharsets.UTF_8),
				"anh.png").getResponse().getStatus());
		byte[] brokenPng = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4 };
		assertEquals(422, upload(teacher, null, sessionId, brokenPng, "anh.png").getResponse().getStatus());
		assertEquals(422, upload(teacher, null, sessionId,
				"<html><body>x</body></html>".getBytes(StandardCharsets.UTF_8), "trang.html").getResponse()
				.getStatus());
		assertEquals(422,
				upload(teacher, null, sessionId, new byte[] { 0x50, 0x4B, 0x03, 0x04, 9, 9 }, "goi.zip")
						.getResponse().getStatus());
		assertEquals(422, upload(teacher, null, sessionId,
				"\\frac{1}{2}".getBytes(StandardCharsets.UTF_8), "cong-thuc.tex").getResponse().getStatus());
		assertEquals(422, upload(teacher, null, sessionId, png(64, 64, true), "anh-lon.png").getResponse()
				.getStatus());
		assertEquals(422, upload(teacher, null, sessionId, png(20, 20, false), "anh-nhieu-diem.png").getResponse()
				.getStatus());

		MvcResult valid = upload(teacher, null, sessionId, webpHeader(), "anh-webp.webp");
		assertEquals(200, valid.getResponse().getStatus(), body(valid));
		MvcResult attached = post(teacher, null, "/api/files/upload-sessions/" + sessionId + "/attach",
				"{\"position\":\"content\"}");
		assertEquals(200, attached.getResponse().getStatus(), body(attached));
		assertEquals("image/webp", JsonPath.read(body(attached), "$.contentType"));
		assertEquals(1, countRows("select count(*) from file_blob where uploaded_by_sub = ?", "gv-kiem-loai"));
	}

	@Test
	@DisplayName("NT-28: retry việc chốt byte và việc gắn không tạo blob hay liên kết thừa, và gọi hoàn tất với một đích khác đích đã khoá trong phiên bị từ chối")
	void completionRetryIsIdempotentAndTargetIsLocked() throws Exception {
		String teacher = authorToken("gv-retry");
		UUID[] target = createPersonalResource(teacher);
		UUID[] other = createPersonalResource(teacher);
		UUID sessionId = createSession(teacher, target);
		byte[] png = png(4, 3, false);

		MvcResult first = upload(teacher, null, sessionId, png, "anh.png");
		String blobId = JsonPath.read(body(first), "$.blobId");
		MvcResult retried = upload(teacher, null, sessionId, png, "anh.png");
		assertEquals(blobId, JsonPath.read(body(retried), "$.blobId"));
		assertEquals(1, countRows("select count(*) from file_blob where uploaded_by_sub = ?", "gv-retry"));

		MvcResult wrongTarget = post(teacher, null, "/api/files/upload-sessions/" + sessionId + "/attach",
				"{\"position\":\"content\",\"resourceId\":\"" + other[0] + "\",\"versionId\":\"" + other[1] + "\"}");
		assertEquals(422, wrongTarget.getResponse().getStatus(), body(wrongTarget));

		MvcResult attached = post(teacher, null, "/api/files/upload-sessions/" + sessionId + "/attach",
				"{\"position\":\"content\"}");
		String linkId = JsonPath.read(body(attached), "$.id");
		MvcResult attachRetried = post(teacher, null, "/api/files/upload-sessions/" + sessionId + "/attach",
				"{\"position\":\"content\"}");
		assertEquals(linkId, JsonPath.read(body(attachRetried), "$.id"));
		assertEquals(1, countRows("select count(*) from file_link where session_id = ?", sessionId));
	}

	@Test
	@DisplayName("NT-30 và B3: gắn blob của người khác bằng định danh bị từ chối và không tạo liên kết nào, trong khi chính chủ blob gắn lại blob đó sang phiên bản khác của mình thì được")
	void attachingAnotherUsersBlobByIdIsRejected() throws Exception {
		String owner = authorToken("gv-chu-blob");
		UUID[] first = createPersonalResource(owner);
		UUID sessionId = createSession(owner, first);
		upload(owner, null, sessionId, png(4, 3, false), "anh.png");
		MvcResult uploaded = getSessionBlob(owner, sessionId);
		String blobId = JsonPath.read(body(uploaded), "$.blobId");

		String stranger = authorToken("gv-nguoi-la-blob");
		UUID[] strangerTarget = createPersonalResource(stranger);
		MvcResult denied = post(stranger, null, "/api/files/links",
				attachBlobJson(blobId, strangerTarget, "anh-copy.png"));
		// Blob nằm ngoài mọi phân vùng người lạ nhìn thấy: 404 theo khuôn chống liệt kê của lõi quyền, và
		// không liên kết nào được tạo.
		assertEquals(404, denied.getResponse().getStatus(), body(denied));
		assertEquals(0, countRows("select count(*) from file_link where resource_version_id = ?", strangerTarget[1]));

		UUID[] second = createPersonalResource(owner);
		MvcResult reused = post(owner, null, "/api/files/links", attachBlobJson(blobId, second, "anh-dung-lai.png"));
		assertEquals(200, reused.getResponse().getStatus(), body(reused));
		String linkId = JsonPath.read(body(reused), "$.id");
		assertEquals(200, get(owner, null, "/api/files/links/" + linkId + "/content").getResponse().getStatus());
	}

	@Test
	@DisplayName("B3 trong cùng phân vùng trung tâm: blob nhìn thấy được nhưng người gọi không có quyền dùng — không phải người tải lên và không đọc được tài nguyên nguồn — thì gắn bị từ chối 403")
	void attachingVisibleButUnusableBlobInCenterIsForbidden() throws Exception {
		String admin = adminToken("admin-gan-blob-tt");
		UUID collectionOne = createCollection(admin);
		UUID collectionTwo = createCollection(admin);
		assign(admin, collectionOne, "question", "gv-a-blob-tt", "bank-editor");
		assign(admin, collectionTwo, "question", "gv-b-blob-tt", "bank-editor");
		String memberA = memberToken("gv-a-blob-tt", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		String memberB = memberToken("gv-b-blob-tt", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));

		MvcResult createdA = post(memberA, ALIAS_1, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"center\",\"collectionId\":\"" + collectionOne
						+ "\"}");
		assertEquals(200, createdA.getResponse().getStatus(), body(createdA));
		UUID[] targetA = { extract(RESOURCE_ID, createdA), extract(VERSION_ID, createdA) };
		UUID sessionA = createSession(memberA, ALIAS_1, targetA);
		MvcResult uploadedA = upload(memberA, ALIAS_1, sessionA, png(4, 3, false), "anh-a.png");
		assertEquals(200, uploadedA.getResponse().getStatus(), body(uploadedA));
		String centerBlobId = JsonPath.read(body(uploadedA), "$.blobId");
		MvcResult attachedA = post(memberA, ALIAS_1, "/api/files/upload-sessions/" + sessionA + "/attach",
				"{\"position\":\"content\"}");
		assertEquals(200, attachedA.getResponse().getStatus(), body(attachedA));

		MvcResult createdB = post(memberB, ALIAS_1, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"center\",\"collectionId\":\"" + collectionTwo
						+ "\"}");
		assertEquals(200, createdB.getResponse().getStatus(), body(createdB));
		UUID[] targetB = { extract(RESOURCE_ID, createdB), extract(VERSION_ID, createdB) };
		MvcResult denied = post(memberB, ALIAS_1, "/api/files/links",
				attachBlobJson(centerBlobId, targetB, "anh-b.png"));
		assertEquals(403, denied.getResponse().getStatus(), body(denied));
		assertEquals(0, countRows("select count(*) from file_link where resource_version_id = ?", targetB[1]));
	}

	@Test
	@DisplayName("B1 và B2: đường đọc quyết theo đúng liên kết và vị trí — người lạ không đọc được tệp nào, người chỉ có view_content qua grant đọc được tệp nội dung nhưng không đọc được tệp đáp án và không thấy nó trong danh sách liên kết")
	void readPathSeparatesContentAndAnswerRights() throws Exception {
		String owner = authorToken("gv-doc-tep");
		UUID[] target = createPersonalResource(owner);
		String contentLink = uploadAndAttach(owner, target, "content", png(4, 3, false));
		String answerLink = uploadAndAttach(owner, target, "answer", png(5, 3, false));

		String stranger = authorToken("gv-xa-la");
		assertEquals(404,
				get(stranger, null, "/api/files/links/" + contentLink + "/content").getResponse().getStatus());
		assertEquals(404, get(stranger, null, "/api/files/resources/" + target[0] + "/versions/" + target[1]
				+ "/links").getResponse().getStatus());

		String viewer = authorToken("gv-duoc-xem");
		grantToUser(owner, target, "view_content", "gv-duoc-xem");
		assertEquals(200,
				get(viewer, null, "/api/files/links/" + contentLink + "/content").getResponse().getStatus());
		assertEquals(403,
				get(viewer, null, "/api/files/links/" + answerLink + "/content").getResponse().getStatus());
		MvcResult list = get(viewer, null,
				"/api/files/resources/" + target[0] + "/versions/" + target[1] + "/links");
		assertEquals(200, list.getResponse().getStatus(), body(list));
		assertTrue(body(list).contains(contentLink), body(list));
		assertFalse(body(list).contains(answerLink), body(list));

		assertEquals(200, get(owner, null, "/api/files/links/" + answerLink + "/content").getResponse().getStatus());
	}

	@Test
	@DisplayName("B7 và NT-20: phân công bị thu hồi giữa lúc tải lên thì bước gắn bằng phiên cũ bị từ chối, không liên kết nào được tạo")
	void revokedAssignmentCannotCompleteAttach() throws Exception {
		String admin = adminToken("admin-go-phan-cong");
		UUID collectionId = createCollection(admin);
		UUID assignmentId = assign(admin, collectionId, "question", "gv-bi-go", "bank-editor");
		String editor = memberToken("gv-bi-go", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		MvcResult created = post(editor, ALIAS_1, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"center\",\"collectionId\":\"" + collectionId + "\"}");
		assertEquals(200, created.getResponse().getStatus(), body(created));
		UUID[] target = { extract(RESOURCE_ID, created), extract(VERSION_ID, created) };

		UUID sessionId = createSession(editor, ALIAS_1, target);
		MvcResult uploaded = upload(editor, ALIAS_1, sessionId, png(4, 3, false), "anh-trung-tam.png");
		assertEquals(200, uploaded.getResponse().getStatus(), body(uploaded));

		MvcResult revoked = post(admin, ALIAS_1, "/api/access-check/assignments/" + assignmentId + "/revoke", "{}");
		assertEquals(200, revoked.getResponse().getStatus(), body(revoked));
		MvcResult attach = post(editor, ALIAS_1, "/api/files/upload-sessions/" + sessionId + "/attach",
				"{\"position\":\"content\"}");
		assertEquals(403, attach.getResponse().getStatus(), body(attach));
		assertEquals(0, countRows("select count(*) from file_link where resource_version_id = ?", target[1]));
	}

	@Test
	@DisplayName("NT-20: phiên tải lên quá hạn không hoàn tất được nữa và lượt dọn theo phân vùng đánh dấu phiên thành hết hạn")
	void staleSessionCannotCompleteAndIsExpiredByCleanup() throws Exception {
		String teacher = authorToken("gv-phien-do");
		UUID[] target = createPersonalResource(teacher);
		UUID sessionId = createSession(teacher, target);
		this.jdbcTemplate.update("update upload_session set expires_at = ? where id = ?",
				OffsetDateTime.now().minusHours(1), sessionId);

		MvcResult uploaded = upload(teacher, null, sessionId, png(4, 3, false), "anh.png");
		assertEquals(409, uploaded.getResponse().getStatus(), body(uploaded));

		int expired = this.fileService.expireStaleSessions("user:gv-phien-do", OffsetDateTime.now());
		assertEquals(1, expired);
		String status = this.jdbcTemplate.queryForObject("select status from upload_session where id = ?",
				String.class, sessionId);
		assertEquals("EXPIRED", status);
	}

	@Test
	@DisplayName("Kho blob bất biến ở tầng lưu trữ: chốt hai lần vào cùng một khoá bị từ chối và byte đã chốt đọc lại nguyên vẹn")
	void storageKeyCannotBeOverwritten() throws Exception {
		UUID sessionId = UUID.randomUUID();
		byte[] original = png(4, 3, false);
		String key = "test-bat-bien-" + UUID.randomUUID();
		String stagingKey = this.storage.stage(sessionId, original);
		this.storage.promote(stagingKey, key);
		String secondKey = this.storage.stage(sessionId, png(6, 6, false));
		assertThrows(IllegalStateException.class, () -> this.storage.promote(secondKey, key));
		assertEquals(original.length, this.storage.read(key).length);
	}

	@Test
	@DisplayName("NT-24 (phần cách ly): quản trị nền tảng cách ly được blob của tài nguyên hệ thống và đường đọc bị chặn ngay, dấu vết cách ly được ghi; byte của blob không bị sửa")
	void quarantinedBlobIsBlockedFromServing() throws Exception {
		String platform = platformToken("admin-nen-tang-tep");
		MvcResult created = post(platform, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"system\",\"contentHash\":\"h-sys\"}");
		assertEquals(200, created.getResponse().getStatus(), body(created));
		UUID[] target = { extract(RESOURCE_ID, created), extract(VERSION_ID, created) };
		String linkId = uploadAndAttach(platform, target, "content", png(4, 3, false));
		UUID sessionBlob = this.jdbcTemplate.queryForObject("select blob_id from file_link where id = ?",
				UUID.class, UUID.fromString(linkId));
		String checksumBefore = this.jdbcTemplate.queryForObject("select checksum_sha256 from file_blob where id = ?",
				String.class, sessionBlob);

		assertEquals(403, post(authorToken("gv-thuong"), null, "/api/files/blobs/" + sessionBlob + "/quarantine",
				"{}").getResponse().getStatus());
		MvcResult quarantined = post(platform, null, "/api/files/blobs/" + sessionBlob + "/quarantine", "{}");
		assertEquals(200, quarantined.getResponse().getStatus(), body(quarantined));
		assertEquals(404, get(platform, null, "/api/files/links/" + linkId + "/content").getResponse().getStatus());
		assertEquals("QUARANTINED", this.jdbcTemplate.queryForObject("select status from file_blob where id = ?",
				String.class, sessionBlob));
		assertEquals(checksumBefore, this.jdbcTemplate.queryForObject(
				"select checksum_sha256 from file_blob where id = ?", String.class, sessionBlob));
		assertEquals(1, countRows("select count(*) from file_audit_log where action = ? and blob_id = ?",
				"quarantine", sessionBlob));
	}

	@Test
	@DisplayName("Quyền nguồn LaTeX qua dịch vụ quyết định: chủ cá nhân có cả đọc và sửa nguồn, người khác không có; phân công biên tập của trung tâm có, quản trị trung tâm và phân công duyệt không có; cấp nguồn qua grant bị từ chối")
	void mathSourceDecisionsFollowSpecMatrix() throws Exception {
		String owner = authorToken("gv-nguon");
		UUID[] personal = createPersonalResource(owner);
		assertTrue(decide(owner, null, personal, "read_math_source"));
		assertTrue(decide(owner, null, personal, "edit_math_source"));
		assertFalse(decide(authorToken("gv-ngoai"), null, personal, "read_math_source"));

		MvcResult grant = post(owner, null, "/api/access-check/grants",
				"{\"resourceId\":\"" + personal[0] + "\",\"resourceVersionId\":\"" + personal[1]
						+ "\",\"action\":\"read_math_source\",\"granteeType\":\"user\",\"granteeUserSub\":\"gv-ngoai\"}");
		assertEquals(422, grant.getResponse().getStatus(), body(grant));

		String admin = adminToken("admin-nguon");
		UUID collectionId = createCollection(admin);
		assign(admin, collectionId, "question", "gv-bien-tap-nguon", "bank-editor");
		assign(admin, collectionId, "question", "gv-duyet-nguon", "bank-publisher");
		String editor = memberToken("gv-bien-tap-nguon", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		MvcResult created = post(editor, ALIAS_1, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"center\",\"collectionId\":\"" + collectionId + "\"}");
		assertEquals(200, created.getResponse().getStatus(), body(created));
		UUID[] center = { extract(RESOURCE_ID, created), extract(VERSION_ID, created) };
		assertTrue(decide(editor, ALIAS_1, center, "read_math_source"));
		assertTrue(decide(editor, ALIAS_1, center, "edit_math_source"));
		assertFalse(decide(admin, ALIAS_1, center, "read_math_source"));
		String reviewer = memberToken("gv-duyet-nguon", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"));
		assertFalse(decide(reviewer, ALIAS_1, center, "read_math_source"));
	}

	@Test
	@DisplayName("NT-29: biên tập viên không có view_answer thì đọc được nguồn của khối thân câu nhưng bị từ chối nguồn của khối thuộc đáp án, còn chủ sở hữu đọc được cả hai")
	void answerAreaSourceRequiresViewAnswer() throws Exception {
		UUID collectionId = insertCollectionRow("admin-vung-dap-an");
		UUID resourceId = insertCenterResource(collectionId, "admin-vung-dap-an");
		UUID versionId = insertVersionRow(resourceId, ORG_1_ID);
		this.jdbcTemplate.update(
				"insert into collection_assignment (id, organization_id, partition_key, collection_id, resource_type, assignee_sub, assignment_role, assigned_by_sub) values (?, ?, ?, ?, ?, ?, ?, ?)",
				UUID.randomUUID(), ORG_1_ID, ORG_1_ID, collectionId, "question", "gv-bien-tap-vung", "bank-editor",
				"admin-vung-dap-an");

		TenantContext editor = new TenantContext("gv-bien-tap-vung", ALIAS_1, ORG_1_ID, List.of("tenant-teacher"),
				false, false, ORG_1_ID);
		assertTrue(this.decisions.decideMathSource(editor, resourceId, versionId,
				AccessAction.READ_MATH_SOURCE, false).allowed());
		assertFalse(this.decisions.decideMathSource(editor, resourceId, versionId,
				AccessAction.READ_MATH_SOURCE, true).allowed());

		UUID[] personal = insertPersonalResource("gv-chu-vung");
		TenantContext owner = new TenantContext("gv-chu-vung", null, null, List.of(), false, false,
				"user:gv-chu-vung");
		assertTrue(this.decisions.decideMathSource(owner, personal[0], personal[1],
				AccessAction.READ_MATH_SOURCE, true).allowed());
	}

	@Test
	@DisplayName("Sửa nguồn trên phiên bản đã xuất bản bị khoá: quyết định sửa nguồn trả mã VERSION_LOCKED sau khi phát hành")
	void editMathSourceIsLockedAfterPublish() throws Exception {
		String owner = authorToken("gv-khoa-nguon");
		UUID[] target = createPersonalResource(owner);
		MvcResult published = post(owner, null,
				"/api/access-check/resources/" + target[0] + "/versions/" + target[1] + "/publish", "{}");
		assertEquals(200, published.getResponse().getStatus(), body(published));
		MvcResult result = decideResult(owner, null, target, "edit_math_source");
		assertTrue(body(result).contains("\"allowed\":false"), body(result));
		assertTrue(body(result).contains("\"reasonCode\":\"VERSION_LOCKED\""), body(result));
	}

	private UUID[] createPersonalResource(String token) throws Exception {
		MvcResult created = post(token, null, "/api/access-check/resources",
				"{\"resourceType\":\"question\",\"workspace\":\"personal\",\"contentHash\":\"h1\"}");
		assertEquals(200, created.getResponse().getStatus(), body(created));
		return new UUID[] { extract(RESOURCE_ID, created), extract(VERSION_ID, created) };
	}

	private UUID createCollection(String admin) throws Exception {
		MvcResult result = post(admin, ALIAS_1, "/api/access-check/resources",
				"{\"resourceType\":\"question-bank\",\"workspace\":\"center\"}");
		assertEquals(200, result.getResponse().getStatus(), body(result));
		return extract(RESOURCE_ID, result);
	}

	private UUID assign(String admin, UUID collectionId, String resourceType, String assignee, String role)
			throws Exception {
		MvcResult result = post(admin, ALIAS_1, "/api/access-check/assignments",
				"{\"collectionId\":\"" + collectionId + "\",\"resourceType\":\"" + resourceType
						+ "\",\"assigneeSub\":\"" + assignee + "\",\"assignmentRole\":\"" + role + "\"}");
		assertEquals(200, result.getResponse().getStatus(), body(result));
		return extract(ASSIGNMENT_ID, result);
	}

	private void grantToUser(String grantor, UUID[] target, String action, String grantee) throws Exception {
		MvcResult result = post(grantor, null, "/api/access-check/grants",
				"{\"resourceId\":\"" + target[0] + "\",\"resourceVersionId\":\"" + target[1] + "\",\"action\":\""
						+ action + "\",\"granteeType\":\"user\",\"granteeUserSub\":\"" + grantee + "\"}");
		assertEquals(200, result.getResponse().getStatus(), body(result));
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
		return createSession(token, null, target);
	}

	private UUID createSession(String token, String alias, UUID[] target) throws Exception {
		MvcResult session = post(token, alias, "/api/files/upload-sessions", sessionJson(target, "tep.png"));
		assertEquals(200, session.getResponse().getStatus(), body(session));
		return UUID.fromString(JsonPath.read(body(session), "$.id"));
	}

	private MvcResult getSessionBlob(String token, UUID sessionId) throws Exception {
		MvcResult retried = upload(token, null, sessionId, png(4, 3, false), "tep.png");
		assertEquals(200, retried.getResponse().getStatus(), body(retried));
		return retried;
	}

	private String uploadAndAttach(String token, UUID[] target, String position, byte[] bytes) throws Exception {
		return uploadAndAttach(token, null, target, position, bytes);
	}

	private String uploadAndAttach(String token, String alias, UUID[] target, String position, byte[] bytes)
			throws Exception {
		UUID sessionId = createSession(token, alias, target);
		MvcResult uploaded = upload(token, alias, sessionId, bytes, "tep.png");
		assertEquals(200, uploaded.getResponse().getStatus(), body(uploaded));
		MvcResult attached = post(token, alias, "/api/files/upload-sessions/" + sessionId + "/attach",
				"{\"position\":\"" + position + "\"}");
		assertEquals(200, attached.getResponse().getStatus(), body(attached));
		return JsonPath.read(body(attached), "$.id");
	}

	private boolean decide(String token, String alias, UUID[] target, String action) throws Exception {
		MvcResult result = decideResult(token, alias, target, action);
		return body(result).contains("\"allowed\":true");
	}

	private MvcResult decideResult(String token, String alias, UUID[] target, String action) throws Exception {
		MvcResult result = post(token, alias, "/api/access-check/decisions",
				"{\"resourceId\":\"" + target[0] + "\",\"resourceVersionId\":\"" + target[1] + "\",\"action\":\""
						+ action + "\"}");
		assertEquals(200, result.getResponse().getStatus(), body(result));
		return result;
	}

	private UUID insertCollectionRow(String createdBy) {
		UUID id = UUID.randomUUID();
		this.jdbcTemplate.update(
				"insert into resource (id, resource_type, workspace_kind, owner_type, owner_organization_id, partition_key, custody_kind, created_by_sub) values (?, ?, ?, ?, ?, ?, ?, ?)",
				id, "question-bank", "center", "organization", ORG_1_ID, ORG_1_ID, "owned", createdBy);
		return id;
	}

	private UUID insertCenterResource(UUID collectionId, String createdBy) {
		UUID id = UUID.randomUUID();
		this.jdbcTemplate.update(
				"insert into resource (id, resource_type, workspace_kind, owner_type, owner_organization_id, collection_id, partition_key, custody_kind, created_by_sub) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
				id, "question", "center", "organization", ORG_1_ID, collectionId, ORG_1_ID, "owned", createdBy);
		return id;
	}

	private UUID insertVersionRow(UUID resourceId, String partitionKey) {
		UUID id = UUID.randomUUID();
		this.jdbcTemplate.update(
				"insert into resource_version (id, resource_id, partition_key, version_number, status, content_hash, created_by_sub) values (?, ?, ?, ?, ?, ?, ?)",
				id, resourceId, partitionKey, 1, "draft", "hash", "admin-vung-dap-an");
		return id;
	}

	private UUID[] insertPersonalResource(String ownerSub) {
		UUID resourceId = UUID.randomUUID();
		this.jdbcTemplate.update(
				"insert into resource (id, resource_type, workspace_kind, owner_type, owner_user_sub, partition_key, custody_kind, created_by_sub) values (?, ?, ?, ?, ?, ?, ?, ?)",
				resourceId, "question", "personal", "user", ownerSub, "user:" + ownerSub, "owned", ownerSub);
		UUID versionId = insertVersionRow(resourceId, "user:" + ownerSub);
		return new UUID[] { resourceId, versionId };
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

	private static byte[] webpHeader() {
		byte[] bytes = new byte[34];
		System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
		System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
		System.arraycopy("VP8X".getBytes(StandardCharsets.US_ASCII), 0, bytes, 12, 4);
		bytes[24] = 3;
		bytes[27] = 2;
		return bytes;
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

	private String memberToken(String sub, String alias, String orgId, List<String> orgRoles) throws Exception {
		return mint(sub, Map.of("organization", Map.of(alias, orgEntry(orgId, orgRoles))));
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
