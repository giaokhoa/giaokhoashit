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
import java.util.List;

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
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// Các bộ test khác thay JwtDecoder bằng validator chỉ kiểm issuer nên chưa từng thực thi điều kiện aud, trong khi
// cấu hình chạy thật đặt spring.security.oauth2.resourceserver.jwt.audiences=lms-api và token thiếu aud đó phải bị
// từ chối 401. Bộ này dựng validator đúng cách tài liệu Spring Security mô tả cho việc kiểm aud theo chương trình
// (validator aud cộng DelegatingOAuth2TokenValidator trên validator issuer mặc định), vẫn dùng khoá RSA tĩnh thay
// bước tải JWKS qua mạng như các bộ test còn lại.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + JwtAudienceTests.ISSUER,
		"spring.security.oauth2.resourceserver.jwt.audiences=lms-api",
		"spring.datasource.url=jdbc:h2:mem:audience;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration" })
class JwtAudienceTests {

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
			OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience() != null
					&& jwt.getAudience().contains("lms-api")
					? OAuth2TokenValidatorResult.success()
					: OAuth2TokenValidatorResult.failure(
							new OAuth2Error("invalid_token", "The required audience is missing", null));
			decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
					JwtValidators.createDefaultWithIssuer(ISSUER), audienceValidator));
			return decoder;
		}

	}

	@Test
	@DisplayName("Token mang aud là lms-api thì qua được cổng kiểm chứng")
	void tokenWithRequiredAudienceIsAccepted() throws Exception {
		String token = mint(List.of("lms-api"));
		this.mockMvc.perform(get("/api/ping").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("Token hợp lệ về chữ ký và issuer nhưng không có aud thì bị từ chối 401")
	void tokenWithoutAudienceIsRejected() throws Exception {
		String token = mint(List.of());
		this.mockMvc.perform(get("/api/ping").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("Token mang aud của client khác chứ không phải lms-api thì bị từ chối 401")
	void tokenWithDifferentAudienceIsRejected() throws Exception {
		String token = mint(List.of("lms-web"));
		this.mockMvc.perform(get("/api/ping").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	private String mint(List<String> audiences) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.subject("aud-user")
				.issueTime(Date.from(now.minusSeconds(10)))
				.expirationTime(Date.from(now.plusSeconds(300)));
		if (!audiences.isEmpty()) {
			builder.audience(audiences);
		}
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
		jwt.sign(new RSASSASigner((RSAPrivateKey) TestJwtConfiguration.KEY_PAIR.getPrivate()));
		return jwt.serialize();
	}

}
