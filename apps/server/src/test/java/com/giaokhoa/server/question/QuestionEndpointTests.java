package com.giaokhoa.server.question;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.util.List;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.giaokhoa.server.tenancy.MembershipChecker;

// Kiểm thử lát ngân hàng câu hỏi cá nhân trên cùng khuôn với TenancyEndpointTests: chuỗi lọc bảo mật thật và
// tầng dữ kiện thật (Flyway + Hibernate/JPA) trên H2 ở chế độ PostgreSQL; chỉ bước tải JWKS qua mạng được thay
// bằng khoá RSA tĩnh và thành phần kiểm membership hiện hành được thay bằng bản giả lập mặc định xác nhận còn
// membership. Quyền ở đây là bản cài đặt thật theo chủ sở hữu; các ca quyền đến từ grant của lõi F4 được kiểm
// tại điểm nối quyết định quyền trong QuestionCopyRightsTests, còn lớp RLS của PostgreSQL được kiểm chứng riêng
// trong QuestionPostgresRlsTests vì H2 không thực thi RLS.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + QuestionEndpointTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:question;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration" })
class QuestionEndpointTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	@Autowired
	private WebApplicationContext context;

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
			NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic()).build();
			decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
			return decoder;
		}

	}

	@Test
	@DisplayName("Tạo ngân hàng và câu hỏi cần quyền soạn nền tảng: tài khoản không có quyền này bị từ chối 403 và không dữ kiện nào được ghi")
	void creatingRequiresPersonalAuthor() throws Exception {
		String student = mint("hs-f10-1", Map.of());
		this.mockMvc
			.perform(post("/api/question-banks").header("Authorization", "Bearer " + student)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"ngan-hang-cua-hoc-vien\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("RESOURCE_FORBIDDEN"));
		this.mockMvc
			.perform(post("/api/questions").header("Authorization", "Bearer " + student)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"bankId\":\"" + UUID.randomUUID() + "\",\"questionType\":\"essay_text\","
						+ "\"stem\":\"than-cau\",\"rubric\":\"tieu-chi\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("RESOURCE_FORBIDDEN"));
		this.mockMvc.perform(get("/api/question-banks").header("Authorization", "Bearer " + student))
			.andExpect(status().isOk())
			.andExpect(content().string("[]"));
		String teacher = teacher("gv-f10-1");
		String bankId = createBank(teacher, "ngan-hang-dau-tien");
		this.mockMvc.perform(get("/api/question-banks").header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(bankId))
			.andExpect(jsonPath("$[0].resourceType").value("question_bank"))
			.andExpect(jsonPath("$[0].workspaceKind").value("personal"))
			.andExpect(jsonPath("$[0].ownerUserSub").value("gv-f10-1"));
	}

	@Test
	@DisplayName("Câu hỏi tạo tại trang trung tâm — cả khi là thành viên và khi là khách của trung tâm đó — vẫn thuộc cá nhân của giáo viên và liệt kê được ở mọi trang")
	void createdAtCenterPageStaysPersonal() throws Exception {
		String teacher = mint("gv-f10-2", Map.of("realm_access", Map.of("roles", List.of("personal-author")),
				"organization", Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-teacher")))));
		String bankId = createBank(teacher, "ngan-hang-trang-nen-tang", null);
		String memberBankId = createBank(teacher, "ngan-hang-trang-trung-tam", "trung-tam-1");
		String guestBankId = createBank(teacher, "ngan-hang-khi-la-khach", "trung-tam-2");
		String questionId = createQuestion(teacher,
				essayBody(memberBankId, "than-cau-tai-trung-tam", "tieu-chi-cham"), "trung-tam-1");
		this.mockMvc.perform(get("/api/questions/" + questionId).header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.workspaceKind").value("personal"))
			.andExpect(jsonPath("$.ownerUserSub").value("gv-f10-2"));
		this.mockMvc.perform(get("/api/question-banks").header("Authorization", "Bearer " + teacher)
			.header("X-LMS-Organization-Alias", "trung-tam-1"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(bankId)))
			.andExpect(content().string(containsString(memberBankId)))
			.andExpect(content().string(containsString(guestBankId)));
		this.mockMvc.perform(get("/api/question-banks").header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString(memberBankId)));
	}

	@Test
	@DisplayName("Kho cá nhân của giáo viên khác không hiện trong danh sách của mình và đọc trực tiếp theo định danh trả 404")
	void otherTeachersBankIsInvisible() throws Exception {
		String owner = teacher("gv-f10-3");
		String bankId = createBank(owner, "ngan-hang-rieng-cua-gv3");
		String questionId = createQuestion(owner, essayBody(bankId, "than-cau-rieng", "tieu-chi-rieng"), null);
		String other = teacher("gv-f10-4");
		this.mockMvc.perform(get("/api/question-banks").header("Authorization", "Bearer " + other))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("ngan-hang-rieng-cua-gv3"))));
		this.mockMvc
			.perform(get("/api/questions/" + questionId).header("Authorization", "Bearer " + other))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	@DisplayName("Loại câu ngoài single_choice và essay_text bị từ chối 422 khi tạo")
	void unsupportedQuestionTypeIsRejected() throws Exception {
		String teacher = teacher("gv-f10-5");
		String bankId = createBank(teacher, "ngan-hang-loai-cau");
		this.mockMvc
			.perform(post("/api/questions").header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"bankId\":\"" + bankId + "\",\"questionType\":\"matching\",\"stem\":\"than-cau\"}"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	@DisplayName("Phát hành kiểm tra hợp lệ tối thiểu theo loại: trắc nghiệm thiếu lựa chọn, lựa chọn không mã hoặc đáp án sai chỗ và tự luận thiếu tiêu chí chấm đều bị 422 và phiên bản vẫn là bản nháp")
	void publishValidatesContentMinimum() throws Exception {
		String teacher = teacher("gv-f10-6");
		String bankId = createBank(teacher, "ngan-hang-phat-hanh");
		String mcId = createQuestion(teacher,
				"{\"bankId\":\"" + bankId + "\",\"questionType\":\"single_choice\",\"stem\":\"than-cau\","
						+ "\"choices\":[{\"id\":\"c1\",\"text\":\"lua-chon-mot\"}],\"correctChoiceId\":\"c9\"}",
				null);
		String mcVersionId = versionIdOf(teacher, mcId, 0);
		this.mockMvc
			.perform(post("/api/questions/" + mcId + "/versions/" + mcVersionId + "/publish")
				.header("Authorization", "Bearer " + teacher))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		String noIdMcId = createQuestion(teacher,
				"{\"bankId\":\"" + bankId + "\",\"questionType\":\"single_choice\",\"stem\":\"than-cau-khong-ma\","
						+ "\"choices\":[{\"text\":\"lua-chon-khong-ma\"},{\"id\":\"\",\"text\":\"lua-chon-ma-rong\"}],"
						+ "\"correctChoiceId\":\"c2\"}",
				null);
		String noIdMcVersionId = versionIdOf(teacher, noIdMcId, 0);
		this.mockMvc
			.perform(post("/api/questions/" + noIdMcId + "/versions/" + noIdMcVersionId + "/publish")
				.header("Authorization", "Bearer " + teacher))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		String essayId = createQuestion(teacher,
				"{\"bankId\":\"" + bankId + "\",\"questionType\":\"essay_text\",\"stem\":\"than-cau-tu-luan\"}",
				null);
		String essayVersionId = versionIdOf(teacher, essayId, 0);
		this.mockMvc
			.perform(post("/api/questions/" + essayId + "/versions/" + essayVersionId + "/publish")
				.header("Authorization", "Bearer " + teacher))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		this.mockMvc.perform(get("/api/questions/" + mcId).header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versions[0].status").value("draft"))
			.andExpect(jsonPath("$.versions[0].lockedAt").value(org.hamcrest.Matchers.nullValue()));
		this.mockMvc.perform(get("/api/questions/" + noIdMcId).header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versions[0].status").value("draft"))
			.andExpect(jsonPath("$.versions[0].lockedAt").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	@DisplayName("Phiên bản đã phát hành mang dấu khoá và content_hash, sửa trực tiếp bị 409 VERSION_LOCKED, và sửa qua phiên bản nháp mới không đổi nội dung của bản đã phát hành")
	void publishedVersionIsLockedAndEditedViaNewDraft() throws Exception {
		String teacher = teacher("gv-f10-7");
		String bankId = createBank(teacher, "ngan-hang-khoa");
		String questionId = createQuestion(teacher, mcBody(bankId, "than-cau-ban-dau", "c1"), null);
		String versionId = versionIdOf(teacher, questionId, 0);
		MvcResult published = this.mockMvc
			.perform(post("/api/questions/" + questionId + "/versions/" + versionId + "/publish")
				.header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("published"))
			.andExpect(jsonPath("$.lockedAt").exists())
			.andReturn();
		String publishedHash = JsonPath.read(published.getResponse().getContentAsString(), "$.contentHash");
		int publishedRevision = JsonPath.read(published.getResponse().getContentAsString(), "$.revision");
		this.mockMvc
			.perform(put("/api/questions/" + questionId + "/versions/" + versionId)
				.header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content(updateBody(publishedRevision, "than-cau-bi-sua-troi", "c1")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_LOCKED"));
		MvcResult newDraft = this.mockMvc
			.perform(post("/api/questions/" + questionId + "/versions")
				.header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fromVersionId\":\"" + versionId + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versionNumber").value(2))
			.andExpect(jsonPath("$.status").value("draft"))
			.andReturn();
		String draftId = JsonPath.read(newDraft.getResponse().getContentAsString(), "$.versionId");
		int draftRevision = JsonPath.read(newDraft.getResponse().getContentAsString(), "$.revision");
		this.mockMvc
			.perform(put("/api/questions/" + questionId + "/versions/" + draftId)
				.header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content(updateBody(draftRevision, "than-cau-ban-hai", "c2")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stem").value("than-cau-ban-hai"));
		this.mockMvc.perform(get("/api/questions/" + questionId).header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versions[0].status").value("published"))
			.andExpect(jsonPath("$.versions[0].contentHash").value(publishedHash))
			.andExpect(jsonPath("$.versions[0].stem").value("than-cau-ban-dau"))
			.andExpect(jsonPath("$.versions[1].stem").value("than-cau-ban-hai"));
	}

	@Test
	@DisplayName("Hai lượt sửa cùng một bản nháp từ cùng một revision: lượt sau bị 409 VERSION_CONFLICT và nội dung của lượt trước được giữ")
	void staleRevisionIsRejected() throws Exception {
		String teacher = teacher("gv-f10-8");
		String bankId = createBank(teacher, "ngan-hang-revision");
		String questionId = createQuestion(teacher, mcBody(bankId, "than-cau-goc", "c1"), null);
		String versionId = versionIdOf(teacher, questionId, 0);
		this.mockMvc
			.perform(put("/api/questions/" + questionId + "/versions/" + versionId)
				.header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content(updateBody(0, "than-cau-luot-mot", "c1")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revision").value(1));
		this.mockMvc
			.perform(put("/api/questions/" + questionId + "/versions/" + versionId)
				.header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content(updateBody(0, "than-cau-luot-hai-ghi-de", "c2")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		this.mockMvc.perform(get("/api/questions/" + questionId).header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versions[0].stem").value("than-cau-luot-mot"));
	}

	@Test
	@DisplayName("Phiên bản nêu trong đường dẫn không thuộc câu hỏi đó thì thao tác ghi bị 403 RESOURCE_FORBIDDEN")
	void versionOfAnotherQuestionIsForbidden() throws Exception {
		String teacher = teacher("gv-f10-9");
		String bankId = createBank(teacher, "ngan-hang-lech-phien-ban");
		String firstId = createQuestion(teacher, mcBody(bankId, "than-cau-mot", "c1"), null);
		String secondId = createQuestion(teacher, mcBody(bankId, "than-cau-hai", "c1"), null);
		String secondVersionId = versionIdOf(teacher, secondId, 0);
		this.mockMvc
			.perform(put("/api/questions/" + firstId + "/versions/" + secondVersionId)
				.header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content(updateBody(0, "than-cau-bi-lech", "c1")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("RESOURCE_FORBIDDEN"));
	}

	@Test
	@DisplayName("Sao chép câu của chính mình tạo một câu hỏi mới thuộc về mình, ở trạng thái nháp, mang truy vết nguồn và giới hạn của nguồn; tài khoản thứ ba không thấy bản sao")
	void copyOfOwnQuestionIsNewResource() throws Exception {
		String teacher = teacher("gv-f10-10");
		String bankId = createBank(teacher, "ngan-hang-sao-chep");
		String sourceId = createQuestion(teacher, mcBody(bankId, "than-cau-nguon", "c1"), null);
		String sourceVersionId = versionIdOf(teacher, sourceId, 0);
		MvcResult copy = this.mockMvc
			.perform(post("/api/questions/" + sourceId + "/versions/" + sourceVersionId + "/copies")
				.header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ownerUserSub").value("gv-f10-10"))
			.andExpect(jsonPath("$.sourceQuestionId").value(sourceId))
			.andExpect(jsonPath("$.sourceVersionId").value(sourceVersionId))
			.andExpect(jsonPath("$.usageLimits").value("chi-dung-noi-bo"))
			.andExpect(jsonPath("$.versions[0].status").value("draft"))
			.andExpect(jsonPath("$.versions[0].correctChoiceId").value("c1"))
			.andReturn();
		String copyId = JsonPath.read(copy.getResponse().getContentAsString(), "$.id");
		org.junit.jupiter.api.Assertions.assertNotEquals(sourceId, copyId);
		String third = teacher("gv-f10-11");
		this.mockMvc.perform(get("/api/questions/" + copyId).header("Authorization", "Bearer " + third))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("Mục ngân hàng trỏ đúng một phiên bản cụ thể, thêm trùng bị 409, và gỡ mục chỉ xoá mục chứ không xoá câu hỏi")
	void bankEntryPointsAtOneVersionAndRemovalKeepsQuestion() throws Exception {
		String teacher = teacher("gv-f10-12");
		String homeBankId = createBank(teacher, "ngan-hang-chua-cau");
		String shelfBankId = createBank(teacher, "ngan-hang-ke-trung-bay");
		String questionId = createQuestion(teacher, mcBody(homeBankId, "than-cau-trung-bay", "c1"), null);
		String versionId = versionIdOf(teacher, questionId, 0);
		MvcResult entry = this.mockMvc
			.perform(post("/api/question-banks/" + shelfBankId + "/entries")
				.header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"questionVersionId\":\"" + versionId + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.questionId").value(questionId))
			.andExpect(jsonPath("$.questionVersionId").value(versionId))
			.andReturn();
		String entryId = JsonPath.read(entry.getResponse().getContentAsString(), "$.id");
		this.mockMvc
			.perform(post("/api/question-banks/" + shelfBankId + "/entries")
				.header("Authorization", "Bearer " + teacher)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"questionVersionId\":\"" + versionId + "\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
		this.mockMvc
			.perform(delete("/api/question-banks/" + shelfBankId + "/entries/" + entryId)
				.header("Authorization", "Bearer " + teacher))
			.andExpect(status().isNoContent());
		this.mockMvc.perform(get("/api/questions/" + questionId).header("Authorization", "Bearer " + teacher))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versions[0].stem").value("than-cau-trung-bay"));
	}

	private String teacher(String sub) throws Exception {
		return mint(sub, Map.of("realm_access", Map.of("roles", List.of("personal-author"))));
	}

	private String createBank(String token, String name) throws Exception {
		return createBank(token, name, null);
	}

	private String createBank(String token, String name, String alias) throws Exception {
		var request = post("/api/question-banks").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"" + name + "\"}");
		if (alias != null) {
			request = request.header("X-LMS-Organization-Alias", alias);
		}
		MvcResult result = this.mockMvc.perform(request).andExpect(status().isOk()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}

	private String createQuestion(String token, String body, String alias) throws Exception {
		var request = post("/api/questions").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
		if (alias != null) {
			request = request.header("X-LMS-Organization-Alias", alias);
		}
		MvcResult result = this.mockMvc.perform(request).andExpect(status().isOk()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}

	private String versionIdOf(String token, String questionId, int index) throws Exception {
		MvcResult result = this.mockMvc
			.perform(get("/api/questions/" + questionId).header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.versions[" + index + "].versionId");
	}

	private String mcBody(String bankId, String stem, String correctChoiceId) {
		return "{\"bankId\":\"" + bankId + "\",\"questionType\":\"single_choice\",\"stem\":\"" + stem + "\","
				+ "\"choices\":[{\"id\":\"c1\",\"text\":\"lua-chon-mot\"},{\"id\":\"c2\",\"text\":\"lua-chon-hai\"}],"
				+ "\"correctChoiceId\":\"" + correctChoiceId + "\",\"solution\":\"loi-giai-mau\","
				+ "\"usageLimits\":\"chi-dung-noi-bo\"}";
	}

	private String essayBody(String bankId, String stem, String rubric) {
		return "{\"bankId\":\"" + bankId + "\",\"questionType\":\"essay_text\",\"stem\":\"" + stem
				+ "\",\"rubric\":\"" + rubric + "\"}";
	}

	private String updateBody(int revision, String stem, String correctChoiceId) {
		return "{\"revision\":" + revision + ",\"questionType\":\"single_choice\",\"stem\":\"" + stem + "\","
				+ "\"choices\":[{\"id\":\"c1\",\"text\":\"lua-chon-mot\"},{\"id\":\"c2\",\"text\":\"lua-chon-hai\"}],"
				+ "\"correctChoiceId\":\"" + correctChoiceId + "\",\"solution\":\"loi-giai-mau\"}";
	}

	private Map<String, Object> orgEntry(String id, List<String> roles) {
		return Map.of("id", id, "realm_access", Map.of("roles", roles));
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
