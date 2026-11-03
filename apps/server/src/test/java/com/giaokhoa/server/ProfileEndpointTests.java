package com.giaokhoa.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// Bộ test chạy toàn bộ chuỗi lọc bảo mật thật và tầng hồ sơ (Flyway + jOOQ) trên H2 ở chế độ PostgreSQL;
// chỉ bước tải JWKS qua mạng được thay bằng khoá RSA tĩnh giống khuôn của ProtectedEndpointTests.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ProfileEndpointTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:profile;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=" })
class ProfileEndpointTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final Pattern ID_PATTERN = Pattern.compile("\"id\":\"([^\"]+)\"");

	@Autowired
	private WebApplicationContext context;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context)
				.apply(springSecurity())
				.build();
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
			NimbusJwtDecoder decoder = NimbusJwtDecoder
					.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic())
					.build();
			decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
			return decoder;
		}

	}

	@Test
	@DisplayName("Trả 401 khi gọi endpoint hồ sơ không kèm token")
	void missingTokenIsRejected() throws Exception {
		this.mockMvc.perform(get("/api/profile"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("Lần gọi đầu tự tạo hồ sơ theo sub của token và lần gọi sau trả đúng cùng một hồ sơ")
	void firstCallCreatesProfile() throws Exception {
		String token = mint("first-user");
		MvcResult first = this.mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"keycloakSub\":\"first-user\"")))
				.andExpect(content().string(containsString("\"customFields\":{}")))
				.andReturn();
		MvcResult second = this.mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();
		assertEquals(extractId(first), extractId(second));
	}

	@Test
	@DisplayName("Cập nhật hồ sơ của chính mình rồi đọc lại thấy đúng giá trị mới")
	void updateAndReadBack() throws Exception {
		String token = mint("editor-user");
		this.mockMvc.perform(put("/api/profile").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"displayName\":\"Nguyen Van A\",\"avatarUrl\":\"https://example.org/a.png\"}"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"displayName\":\"Nguyen Van A\"")));
		this.mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"displayName\":\"Nguyen Van A\"")))
				.andExpect(content().string(containsString("\"avatarUrl\":\"https://example.org/a.png\"")));
	}

	@Test
	@DisplayName("Tạo định nghĩa custom-field, gán giá trị cho chính mình rồi đọc lại thấy đúng giá trị")
	void customFieldDefinitionsAndValues() throws Exception {
		String token = mint("field-user");
		this.mockMvc.perform(post("/api/custom-field-definitions").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fieldKey\":\"school_code\",\"label\":\"Ma truong\"}"))
				.andExpect(status().isCreated())
				.andExpect(content().string(containsString("\"fieldKey\":\"school_code\"")));
		this.mockMvc.perform(post("/api/custom-field-definitions").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fieldKey\":\"school_code\",\"label\":\"Trung lap\"}"))
				.andExpect(status().isConflict());
		this.mockMvc.perform(put("/api/profile").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"customFields\":{\"school_code\":\"TH-01\"}}"))
				.andExpect(status().isOk());
		this.mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"school_code\":\"TH-01\"")));
		this.mockMvc.perform(get("/api/custom-field-definitions").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"fieldKey\":\"school_code\"")));
	}

	@Test
	@DisplayName("Trả 400 khi gán giá trị cho custom-field chưa được định nghĩa")
	void unknownCustomFieldIsRejected() throws Exception {
		String token = mint("field-user-2");
		this.mockMvc.perform(put("/api/profile").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"customFields\":{\"no_such_field\":\"x\"}}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("Trả 400 khi tạo định nghĩa custom-field với fieldKey không hợp lệ")
	void invalidFieldKeyIsRejected() throws Exception {
		String token = mint("field-user-3");
		this.mockMvc.perform(post("/api/custom-field-definitions").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fieldKey\":\"Bad Key\",\"label\":\"Sai dinh dang\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("Người dùng khác không thấy dữ kiện hồ sơ và custom-field của người dùng trước")
	void usersAreIsolated() throws Exception {
		String tokenA = mint("iso-a");
		String tokenB = mint("iso-b");
		this.mockMvc.perform(post("/api/custom-field-definitions").header("Authorization", "Bearer " + tokenA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fieldKey\":\"iso_code\",\"label\":\"Ma rieng\"}"))
				.andExpect(status().isCreated());
		this.mockMvc.perform(put("/api/profile").header("Authorization", "Bearer " + tokenA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"displayName\":\"Chi A\",\"customFields\":{\"iso_code\":\"A-SECRET\"}}"))
				.andExpect(status().isOk());
		this.mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + tokenB))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"keycloakSub\":\"iso-b\"")))
				.andExpect(content().string(not(containsString("Chi A"))))
				.andExpect(content().string(not(containsString("A-SECRET"))))
				.andExpect(content().string(containsString("\"customFields\":{}")));
	}

	private String extractId(MvcResult result) throws Exception {
		Matcher matcher = ID_PATTERN.matcher(result.getResponse().getContentAsString());
		if (!matcher.find()) {
			throw new AssertionError("response has no id: " + result.getResponse().getContentAsString());
		}
		return matcher.group(1);
	}

	private String mint(String subject) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.subject(subject)
				.issueTime(Date.from(now.minusSeconds(10)))
				.expirationTime(Date.from(now.plusSeconds(300)))
				.build();
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
		jwt.sign(new RSASSASigner((RSAPrivateKey) TestJwtConfiguration.KEY_PAIR.getPrivate()));
		return jwt.serialize();
	}

}
