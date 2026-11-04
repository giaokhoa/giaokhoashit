package com.giaokhoa.server.assignment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Kiểm chứng trực tiếp bean thật trên H2 ở chế độ PostgreSQL: driver của H2 trả cột uuid của native query theo
// hình dạng khác PostgreSQL, nên hai hàm đọc danh sách lớp phải dùng cùng chuyển đổi tường minh như findClass.
@SpringBootTest(properties = {
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=" + JpaTeachingChecksTests.ISSUER,
		"spring.datasource.url=jdbc:h2:mem:jpa-teaching-checks;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
				+ "DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.flyway.locations=classpath:db/migration" })
class JpaTeachingChecksTests {

	static final String ISSUER = "http://localhost:8180/realms/giaokhoa";

	private static final String ORG_ID = "11111111-1111-1111-1111-111111111111";

	private static final String OTHER_ORG_ID = "22222222-2222-2222-2222-222222222222";

	private static final String TEACHER = "gv-phu-trach";

	private static final String OTHER_TEACHER = "gv-khac";

	private static final String STUDENT = "hs-ghi-danh";

	private static final String OTHER_STUDENT = "hs-khac";

	private static final UUID TAUGHT_CLASS_1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");

	private static final UUID TAUGHT_CLASS_2 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");

	private static final UUID OTHER_TEACHER_CLASS = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3");

	private static final UUID OTHER_ORG_CLASS = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4");

	private static final UUID REMOVED_CLASS = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa5");

	@Autowired
	private JpaTeachingChecks teachingChecks;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@BeforeEach
	void setUp() {
		this.jdbcTemplate.update("delete from class_member");
		this.jdbcTemplate.update("delete from teaching_class");
		seedClass(TAUGHT_CLASS_1, ORG_ID, TEACHER);
		seedClass(TAUGHT_CLASS_2, ORG_ID, TEACHER);
		seedClass(OTHER_TEACHER_CLASS, ORG_ID, OTHER_TEACHER);
		seedClass(OTHER_ORG_CLASS, OTHER_ORG_ID, TEACHER);
		seedClass(REMOVED_CLASS, ORG_ID, TEACHER);
		seedMember(TAUGHT_CLASS_1, ORG_ID, STUDENT, false);
		seedMember(TAUGHT_CLASS_2, ORG_ID, STUDENT, false);
		seedMember(REMOVED_CLASS, ORG_ID, STUDENT, true);
		seedMember(OTHER_TEACHER_CLASS, ORG_ID, OTHER_STUDENT, false);
		seedMember(OTHER_ORG_CLASS, OTHER_ORG_ID, STUDENT, false);
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
	@DisplayName("Đọc danh sách lớp giáo viên phụ trách trên H2: UUID từ native query được chuyển đổi đúng thay vì ép kiểu thẳng")
	void classIdsTaughtByConvertsNativeUuidValues() {
		List<UUID> classIds = inTransaction(() -> this.teachingChecks.classIdsTaughtBy(ORG_ID, TEACHER));

		assertEquals(Set.of(TAUGHT_CLASS_1, TAUGHT_CLASS_2, REMOVED_CLASS), Set.copyOf(classIds));
	}

	@Test
	@DisplayName("Đọc danh sách lớp học viên đang ghi danh trên H2: UUID được chuyển đổi đúng và ghi danh đã rút không được tính")
	void classIdsEnrolledByConvertsNativeUuidValues() {
		List<UUID> classIds = inTransaction(() -> this.teachingChecks.classIdsEnrolledBy(ORG_ID, STUDENT));

		assertEquals(Set.of(TAUGHT_CLASS_1, TAUGHT_CLASS_2), Set.copyOf(classIds));
	}

	private <T> T inTransaction(java.util.function.Supplier<T> work) {
		return new TransactionTemplate(this.transactionManager).execute(status -> work.get());
	}

	private void seedClass(UUID classId, String partitionKey, String teacherSub) {
		this.jdbcTemplate.update(
				"insert into teaching_class (id, partition_key, course_id, name, teacher_sub, created_by_sub) "
						+ "values (?, ?, ?, ?, ?, ?)",
				classId, partitionKey, null, "Lớp kiểm chứng JpaTeachingChecks", teacherSub, teacherSub);
	}

	private void seedMember(UUID classId, String partitionKey, String studentSub, boolean removed) {
		this.jdbcTemplate.update(
				"insert into class_member (id, partition_key, class_id, student_sub, enrolled_by_sub, removed_at) "
						+ "values (?, ?, ?, ?, ?, ?)",
				UUID.randomUUID(), partitionKey, classId, studentSub, TEACHER,
				removed ? java.time.OffsetDateTime.now() : null);
	}

}
