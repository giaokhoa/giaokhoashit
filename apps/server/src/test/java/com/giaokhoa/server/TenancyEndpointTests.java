package com.giaokhoa.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.giaokhoa.server.tenancy.MembershipChecker;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// Kiểm thử khung tenancy trên cùng khuôn với ProfileEndpointTests: chuỗi lọc bảo mật thật (gồm filter ngữ cảnh
// tenant đứng sau filter Bearer) và tầng dữ kiện thật (Flyway + Hibernate/JPA với discriminator) trên H2 ở chế độ
// PostgreSQL; chỉ bước tải JWKS qua mạng được thay bằng khoá RSA tĩnh, và thành phần kiểm membership hiện hành được
// thay bằng bản giả lập có kiểm soát (mặc định xác nhận còn membership). Lớp RLS của PostgreSQL không chạy trên H2
// nên được kiểm chứng riêng trên môi trường PostgreSQL thật.
@SpringBootTest(properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + TenancyEndpointTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:tenancy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration" })
class TenancyEndpointTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_1_ID = "11111111-1111-1111-1111-111111111111";

	private static final String ORG_2_ID = "22222222-2222-2222-2222-222222222222";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbcTemplate;

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
			NimbusJwtDecoder decoder = NimbusJwtDecoder
					.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic())
					.build();
			decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
			return decoder;
		}

	}

	@Test
	@DisplayName("Context tại trang trung tâm trả khoá phân vùng là Organization ID và vai đọc trong mục của trung tâm đó")
	void centerContextUsesOrganizationIdAndOrgRoles() throws Exception {
		String token = mint("gv-1", Map.of("organization",
				Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-teacher")))));
		this.mockMvc.perform(get("/api/tenancy-check/context").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"partitionKey\":\"" + ORG_1_ID + "\"")))
				.andExpect(content().string(containsString("\"organizationRoles\":[\"tenant-teacher\"]")))
				.andExpect(content().string(containsString("\"guest\":false")));
	}

	@Test
	@DisplayName("Vai của hai trung tâm không lẫn chéo: tại trang trung-tam-2 chỉ thấy vai của trung-tam-2 dù token mang vai admin của trung-tam-1")
	void rolesDoNotBleedAcrossOrganizations() throws Exception {
		Map<String, Object> claims = Map.of(
				"realm_access", Map.of("roles", List.of("tenant-admin")),
				"organization", Map.of(
						"trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-admin")),
						"trung-tam-2", orgEntry(ORG_2_ID, List.of("tenant-member"))));
		String token = mint("hai-ngu-canh", claims);
		this.mockMvc.perform(get("/api/tenancy-check/context").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", "trung-tam-2"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"organizationRoles\":[\"tenant-member\"]")))
				.andExpect(content().string(not(containsString("tenant-admin"))));
		this.mockMvc.perform(get("/api/tenancy-check/context").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"organizationRoles\":[\"tenant-admin\"]")));
	}

	@Test
	@DisplayName("Record tạo ở trung tâm 1 không xuất hiện ở trung tâm 2 và ngược lại")
	void recordsAreIsolatedBetweenCenters() throws Exception {
		String gv1 = mint("gv-1", Map.of("organization",
				Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-teacher")))));
		String gv2 = mint("gv-2", Map.of("organization",
				Map.of("trung-tam-2", orgEntry(ORG_2_ID, List.of("tenant-teacher")))));
		this.mockMvc.perform(post("/api/tenancy-check/records").header("Authorization", "Bearer " + gv1)
				.header("X-LMS-Organization-Alias", "trung-tam-1")
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"cau-hoi-tt1\"}"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"partitionKey\":\"" + ORG_1_ID + "\"")));
		this.mockMvc.perform(post("/api/tenancy-check/records").header("Authorization", "Bearer " + gv2)
				.header("X-LMS-Organization-Alias", "trung-tam-2")
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"cau-hoi-tt2\"}"))
				.andExpect(status().isOk());
		this.mockMvc.perform(get("/api/tenancy-check/records").header("Authorization", "Bearer " + gv1)
				.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("cau-hoi-tt1")))
				.andExpect(content().string(not(containsString("cau-hoi-tt2"))));
		this.mockMvc.perform(get("/api/tenancy-check/records").header("Authorization", "Bearer " + gv2)
				.header("X-LMS-Organization-Alias", "trung-tam-2"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("cau-hoi-tt2")))
				.andExpect(content().string(not(containsString("cau-hoi-tt1"))));
	}

	@Test
	@DisplayName("Khách tại trang trung tâm mình không phải thành viên: context báo khách, đọc record rỗng, ghi record bị 403, hồ sơ của chính mình vẫn đọc được")
	void guestSeesNoCenterDataButKeepsOwnProfile() throws Exception {
		String hs1 = mint("hs-1", Map.of("organization",
				Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-member")))));
		this.mockMvc.perform(get("/api/tenancy-check/context").header("Authorization", "Bearer " + hs1)
				.header("X-LMS-Organization-Alias", "trung-tam-2"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"guest\":true")))
				.andExpect(content().string(containsString("\"organizationRoles\":[]")))
				.andExpect(content().string(containsString("\"partitionKey\":null")));
		this.mockMvc.perform(get("/api/tenancy-check/records").header("Authorization", "Bearer " + hs1)
				.header("X-LMS-Organization-Alias", "trung-tam-2"))
				.andExpect(status().isOk())
				.andExpect(content().string("[]"));
		this.mockMvc.perform(post("/api/tenancy-check/records").header("Authorization", "Bearer " + hs1)
				.header("X-LMS-Organization-Alias", "trung-tam-2")
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"khach-ghi\"}"))
				.andExpect(status().isForbidden());
		this.mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + hs1)
				.header("X-LMS-Organization-Alias", "trung-tam-2"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"keycloakSub\":\"hs-1\"")));
	}

	@Test
	@DisplayName("Tài khoản tự do tại trang nền tảng có phân vùng cá nhân của riêng mình và không đọc được record cá nhân của tài khoản khác")
	void freeAccountUsesPersonalPartition() throws Exception {
		String free1 = mint("gv-tu-do-1", Map.of());
		String free2 = mint("gv-tu-do-2", Map.of());
		this.mockMvc.perform(get("/api/tenancy-check/context").header("Authorization", "Bearer " + free1))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"partitionKey\":\"user:gv-tu-do-1\"")));
		this.mockMvc.perform(post("/api/tenancy-check/records").header("Authorization", "Bearer " + free1)
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"rieng-cua-toi\"}"))
				.andExpect(status().isOk());
		this.mockMvc.perform(get("/api/tenancy-check/records").header("Authorization", "Bearer " + free1))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("rieng-cua-toi")));
		this.mockMvc.perform(get("/api/tenancy-check/records").header("Authorization", "Bearer " + free2))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString("rieng-cua-toi"))));
	}

	@Test
	@DisplayName("Tài khoản platform-admin tại trang nền tảng ghi ở phân vùng hệ thống và record đó không xuất hiện ở ngữ cảnh trung tâm hay cá nhân")
	void platformAdminUsesSystemPartition() throws Exception {
		String admin = mint("platform-admin-1",
				Map.of("realm_access", Map.of("roles", List.of("platform-admin"))));
		this.mockMvc.perform(get("/api/tenancy-check/context").header("Authorization", "Bearer " + admin))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"partitionKey\":\"system\"")))
				.andExpect(content().string(containsString("\"platformAdmin\":true")));
		this.mockMvc.perform(post("/api/tenancy-check/records").header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"cau-he-thong\"}"))
				.andExpect(status().isOk());
		String gv1 = mint("gv-1", Map.of("organization",
				Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-teacher")))));
		this.mockMvc.perform(get("/api/tenancy-check/records").header("Authorization", "Bearer " + gv1)
				.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString("cau-he-thong"))));
		String free = mint("gv-tu-do-1", Map.of());
		this.mockMvc.perform(get("/api/tenancy-check/records").header("Authorization", "Bearer " + free))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString("cau-he-thong"))));
	}

	@Test
	@DisplayName("Token cũ sau khi bị gỡ membership ở trung-tam-1 thành khách tại trung-tam-1 nhưng vẫn là thành viên tại trung-tam-2 và vẫn đọc được hồ sơ của mình")
	void revokedMembershipDowngradesToGuestOnlyAtThatCenter() throws Exception {
		when(this.membershipChecker.check(ORG_1_ID, "hai-ngu-canh"))
				.thenReturn(MembershipChecker.Result.NOT_MEMBER);
		when(this.membershipChecker.check(ORG_2_ID, "hai-ngu-canh"))
				.thenReturn(MembershipChecker.Result.MEMBER);
		String token = mint("hai-ngu-canh", Map.of("organization", Map.of(
				"trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-admin")),
				"trung-tam-2", orgEntry(ORG_2_ID, List.of("tenant-member")))));
		this.mockMvc.perform(get("/api/tenancy-check/context").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"guest\":true")))
				.andExpect(content().string(containsString("\"organizationRoles\":[]")))
				.andExpect(content().string(containsString("\"partitionKey\":null")))
				.andExpect(content().string(not(containsString("tenant-admin"))));
		this.mockMvc.perform(get("/api/tenancy-check/records").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string("[]"));
		this.mockMvc.perform(post("/api/tenancy-check/records").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", "trung-tam-1")
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"da-bi-go\"}"))
				.andExpect(status().isForbidden());
		this.mockMvc.perform(get("/api/tenancy-check/context").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", "trung-tam-2"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"partitionKey\":\"" + ORG_2_ID + "\"")))
				.andExpect(content().string(containsString("\"organizationRoles\":[\"tenant-member\"]")))
				.andExpect(content().string(containsString("\"guest\":false")));
		this.mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"keycloakSub\":\"hai-ngu-canh\"")));
	}

	@Test
	@DisplayName("Không xác minh được membership thì request vào ngữ cảnh trung tâm bị từ chối 503, còn đường không mang ngữ cảnh trung tâm vẫn chạy")
	void unverifiableMembershipRejectsCenterRequest() throws Exception {
		when(this.membershipChecker.check(anyString(), anyString()))
				.thenReturn(MembershipChecker.Result.UNVERIFIABLE);
		String token = mint("gv-1", Map.of("organization",
				Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-teacher")))));
		this.mockMvc.perform(get("/api/tenancy-check/context").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isServiceUnavailable());
		this.mockMvc.perform(get("/api/tenancy-check/records").header("Authorization", "Bearer " + token)
				.header("X-LMS-Organization-Alias", "trung-tam-1"))
				.andExpect(status().isServiceUnavailable());
		this.mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"keycloakSub\":\"gv-1\"")));
	}

	@Test
	@DisplayName("Partition key giả trong thân request bị bỏ qua: hàng lưu trong database mang khoá của ngữ cảnh thật")
	void forgedPartitionKeyInBodyIsIgnored() throws Exception {
		String gv1 = mint("gv-1", Map.of("organization",
				Map.of("trung-tam-1", orgEntry(ORG_1_ID, List.of("tenant-teacher")))));
		this.mockMvc.perform(post("/api/tenancy-check/records").header("Authorization", "Bearer " + gv1)
				.header("X-LMS-Organization-Alias", "trung-tam-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"gia-mao\",\"partitionKey\":\"" + ORG_2_ID + "\"}"))
				.andExpect(status().isOk());
		String stored = this.jdbcTemplate.queryForObject(
				"select partition_key from tenant_record where content = 'gia-mao'", String.class);
		assertEquals(ORG_1_ID, stored);
	}

	private Map<String, Object> orgEntry(String id, List<String> roles) {
		return Map.of("id", id, "realm_access", Map.of("roles", roles));
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
