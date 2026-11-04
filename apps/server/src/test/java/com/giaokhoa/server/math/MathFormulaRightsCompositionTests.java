package com.giaokhoa.server.math;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.giaokhoa.server.question.Question;
import com.giaokhoa.server.question.QuestionBank;
import com.giaokhoa.server.question.QuestionBankRepository;
import com.giaokhoa.server.question.QuestionRepository;
import com.giaokhoa.server.question.QuestionRights;
import com.giaokhoa.server.question.QuestionVersion;
import com.giaokhoa.server.question.QuestionVersionRepository;
import com.giaokhoa.server.tenancy.MembershipChecker;

// Ca hợp quyền tới từng thuộc tính theo tinh thần NT-29: một biên tập viên có edit_draft, edit_math_source
// và read_math_source nhưng không có view_answer thì sửa và đọc được nguồn của khối ở thân câu, nhưng bị từ
// chối cả đọc lẫn sửa nguồn của khối nằm trong lời giải. Quyền ở đây được giả lập tại đúng điểm nối quyết
// quyền dùng chung, vì nguồn quyền phân công của lõi F4 chưa có dữ kiện trong bản hiện tại.
@SpringBootTest(properties = {
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=" + MathFormulaRightsCompositionTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:mathrights;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration", "app.files.root=./target/test-files-math-rights" })
class MathFormulaRightsCompositionTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private QuestionBankRepository banks;

	@Autowired
	private QuestionRepository questions;

	@Autowired
	private QuestionVersionRepository versions;

	@MockitoBean
	private MembershipChecker membershipChecker;

	@MockitoBean
	private QuestionRights rights;

	private MockMvc mockMvc;

	private UUID questionId;

	private UUID versionId;

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		lenient().when(this.membershipChecker.check(anyString(), anyString()))
			.thenReturn(MembershipChecker.Result.MEMBER);
		lenient().when(this.rights.allowed(any(), any(), any(), anyString())).thenReturn(true);
		lenient().when(this.rights.allowed(any(), any(), any(),
				ArgumentMatchers.eq(QuestionRights.VIEW_ANSWER)))
			.thenReturn(false);
		OffsetDateTime now = OffsetDateTime.now();
		QuestionBank bank = new QuestionBank();
		bank.setId(UUID.randomUUID());
		bank.setOwnerSub("chu-cau-hoi");
		bank.setName("ngan-hang");
		bank.setCreatedAt(now);
		bank.setUpdatedAt(now);
		this.banks.saveAndFlush(bank);
		Question question = new Question();
		question.setId(UUID.randomUUID());
		question.setOwnerSub("chu-cau-hoi");
		question.setBankId(bank.getId());
		question.setCreatedAt(now);
		question.setUpdatedAt(now);
		this.questions.saveAndFlush(question);
		QuestionVersion version = new QuestionVersion();
		version.setId(UUID.randomUUID());
		version.setQuestionId(question.getId());
		version.setOwnerSub("chu-cau-hoi");
		version.setVersionNumber(1);
		version.setStatus(QuestionVersion.STATUS_DRAFT);
		version.setQuestionType(QuestionVersion.TYPE_ESSAY_TEXT);
		version.setContentContractVersion(QuestionVersion.CONTENT_CONTRACT_VERSION);
		version.setStem("than-cau");
		version.setRubric("tieu-chi");
		version.setContentHash("hash");
		version.setCreatedAt(now);
		version.setUpdatedAt(now);
		this.versions.saveAndFlush(version);
		this.questionId = question.getId();
		this.versionId = version.getId();
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
			return (source, display) -> new MathRenderer.RenderedImage(new byte[] { 1 }, 10, 10, 2, "fake@0", "{}");
		}

	}

	@Test
	@DisplayName("Biên tập viên không có view_answer: sửa và đọc được nguồn ở thân câu nhưng bị từ chối sửa nguồn trong lời giải và nguồn lời giải không xuất hiện ở mặt soạn thảo")
	void editorWithoutViewAnswerCannotTouchSolutionSource() throws Exception {
		String editor = mint("bien-tap-vien");
		String base = "/api/questions/" + this.questionId + "/versions/" + this.versionId + "/formula-blocks";
		this.mockMvc
			.perform(put(base + "/than").header("Authorization", "Bearer " + editor)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"section\":\"stem\",\"source\":\"\\\\frac{a}{b}\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.source").value("\\frac{a}{b}"));
		this.mockMvc
			.perform(put(base + "/giai").header("Authorization", "Bearer " + editor)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"section\":\"solution\",\"source\":\"\\\\frac{c}{d}\"}"))
			.andExpect(status().isForbidden());
		this.mockMvc.perform(get(base + "/draft").header("Authorization", "Bearer " + editor))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].source").value("\\frac{a}{b}"));
	}

	private String mint(String subject) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER)
			.subject(subject)
			.issueTime(Date.from(now.minusSeconds(10)))
			.expirationTime(Date.from(now.plusSeconds(300)))
			.claim("realm_access", Map.of("roles", List.of("personal-author")))
			.build();
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
		jwt.sign(new RSASSASigner((RSAPrivateKey) TestJwtConfiguration.KEY_PAIR.getPrivate()));
		return jwt.serialize();
	}

}
