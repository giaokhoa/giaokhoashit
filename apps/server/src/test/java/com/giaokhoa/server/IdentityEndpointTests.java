package com.giaokhoa.server;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

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

// Kiểm thử endpoint danh tính /api/me trên cùng khuôn với ProtectedEndpointTests: chuỗi lọc
// bảo mật thật qua MockMvc, JwtDecoder test ký bằng khoá RSA tĩnh tự sinh nhưng giữ nguyên
// kiểm chứng chữ ký, issuer và thời hạn như cấu hình chạy thật.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + IdentityEndpointTests.ISSUER,
		"spring.flyway.locations=classpath:db/migration" })
class IdentityEndpointTests {

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
	@DisplayName("Trả 401 khi request tới /api/me không kèm token")
	void missingTokenIsRejected() throws Exception {
		this.mockMvc.perform(get("/api/me"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("Trả 401 khi token của /api/me ký bằng khoá khác với khoá của issuer")
	void wrongSigningKeyIsRejected() throws Exception {
		String token = mint(ISSUER, TestJwtConfiguration.OTHER_KEY_PAIR, Map.of());
		this.mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	private String mint(String issuer, KeyPair keyPair, Map<String, Object> extraClaims) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
				.issuer(issuer)
				.subject("test-user")
				.issueTime(Date.from(now.minusSeconds(10)))
				.expirationTime(Date.from(now.plusSeconds(300)));
		extraClaims.forEach(builder::claim);
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
		jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
		return jwt.serialize();
	}

}
