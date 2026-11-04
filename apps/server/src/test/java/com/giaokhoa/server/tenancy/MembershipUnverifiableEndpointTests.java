package com.giaokhoa.server.tenancy;

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
import java.util.List;
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

// Kiểm chứng tiêu chí "nguồn kiểm membership lỗi" của đặc tả ở mức hành vi quan sát được: bộ này KHÔNG thay thành
// phần kiểm membership bằng bản giả lập như TenancyEndpointTests, mà để bean thật chạy với secret của service
// account để trống — một điều kiện "không xác minh được" theo hợp đồng ba kết quả của đặc tả, xảy ra trước mọi
// lời gọi tới Keycloak nên không cần mạng. Hệ quả phải thấy ở endpoint: request mang ngữ cảnh trung tâm bị từ
// chối 503 thay vì mở phân vùng theo claim, còn request không mang ngữ cảnh trung tâm vẫn chạy bình thường.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri="
		+ MembershipUnverifiableEndpointTests.ISSUER, "app.keycloak.membership.client-secret=",
		"spring.datasource.url=jdbc:h2:mem:membership-unverifiable;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration" })
class MembershipUnverifiableEndpointTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

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
	@DisplayName("Không xác minh được membership vì thiếu secret của service account: request mang ngữ cảnh trung tâm bị từ chối 503, request ở trang nền tảng vẫn chạy bình thường")
	void unverifiableMembershipRejectsCenterRequest() throws Exception {
		String token = mint("gv-1", Map.of("organization",
				Map.of("trung-tam-1", Map.of("id", ORG_1_ID, "realm_access", Map.of("roles", List.of("tenant-teacher"))))));
		this.mockMvc.perform(get("/api/tenancy-check/context").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isServiceUnavailable());
		this.mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"keycloakSub\":\"gv-1\"")));
	}

	private String mint(String subject, Map<String, Object> extraClaims) throws Exception {
		Instant now = Instant.now();
		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.subject(subject)
				.issueTime(Date.from(now.minusSeconds(10)))
				.expirationTime(Date.from(now.plusSeconds(300)));
		extraClaims.forEach(builder::claim);
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
		jwt.sign(new RSASSASigner((RSAPrivateKey) TestJwtConfiguration.KEY_PAIR.getPrivate()));
		return jwt.serialize();
	}

}
