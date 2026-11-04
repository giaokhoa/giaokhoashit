package com.giaokhoa.server.question;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
