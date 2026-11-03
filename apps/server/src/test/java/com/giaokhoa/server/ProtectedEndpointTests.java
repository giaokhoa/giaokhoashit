package com.giaokhoa.server;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// Bộ test chạy toàn bộ chuỗi lọc bảo mật thật của ứng dụng qua MockMvc. JwtDecoder của test
// dùng khoá RSA tĩnh tự sinh thay cho bước tải JWKS từ issuer qua mạng, nhưng vẫn là
// NimbusJwtDecoder kèm validator issuer + hạn token giống cấu hình chạy thật, nên chữ ký,
// issuer và thời hạn của token đều được kiểm chứng thật.
@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri="
		+ ProtectedEndpointTests.ISSUER)
class ProtectedEndpointTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

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

		static final KeyPair OTHER_KEY_PAIR = generateKeyPair();

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
	@DisplayName("Endpoint health trả UP và không yêu cầu token")
	void healthIsPublic() throws Exception {
		this.mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"UP\"")));
		this.mockMvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"status\":\"UP\"")));
	}

	@Test
	@DisplayName("Trả 401 khi request tới endpoint được bảo vệ không kèm token")
	void missingTokenIsRejected() throws Exception {
		this.mockMvc.perform(get("/api/ping"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("Trả 200 và danh tính trong token khi request kèm JWT hợp lệ")
	void validTokenIsAccepted() throws Exception {
		String token = mint(ISSUER, TestJwtConfiguration.KEY_PAIR, Instant.now().plusSeconds(300));
		this.mockMvc.perform(get("/api/ping").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"subject\":\"test-user\"")));
	}

	@Test
	@DisplayName("Trả 401 khi token ký bằng khoá khác với khoá của issuer")
	void wrongSigningKeyIsRejected() throws Exception {
		String token = mint(ISSUER, TestJwtConfiguration.OTHER_KEY_PAIR, Instant.now().plusSeconds(300));
		this.mockMvc.perform(get("/api/ping").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("Trả 401 khi token do issuer khác phát hành")
	void wrongIssuerIsRejected() throws Exception {
		String token = mint("http://localhost:8180/realms/other", TestJwtConfiguration.KEY_PAIR,
				Instant.now().plusSeconds(300));
		this.mockMvc.perform(get("/api/ping").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("Trả 401 khi token đã hết hạn")
	void expiredTokenIsRejected() throws Exception {
		String token = mint(ISSUER, TestJwtConfiguration.KEY_PAIR, Instant.now().minusSeconds(60));
		this.mockMvc.perform(get("/api/ping").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	private String mint(String issuer, KeyPair keyPair, Instant expiresAt) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer(issuer)
				.subject("test-user")
				.issueTime(Date.from(now.minusSeconds(10)))
				.expirationTime(Date.from(expiresAt))
				.build();
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
		jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
		return jwt.serialize();
	}

}
