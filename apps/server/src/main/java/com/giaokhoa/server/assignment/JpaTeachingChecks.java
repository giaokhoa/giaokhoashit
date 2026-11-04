package com.giaokhoa.server.assignment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Component;

/**
 * Bản đọc dữ kiện lớp/ghi danh bằng truy vấn native trên đúng session mà tầng dịch vụ đã ghim về phân vùng của
 * trang: biến ngữ cảnh RLS do điểm chặn giao dịch của khung tenancy đặt nằm trên connection của session này,
 * nên các câu truy vấn luôn chạy dưới chính sách RLS của hai bảng `teaching_class`/`class_member`, đồng thời
 * vẫn kèm điều kiện khoá phân vùng tường minh để kết quả đúng theo Organization được hỏi kể cả với người gọi ở
 * chế độ khách. Mô hình cột bám đúng mô hình dữ kiện của khối tổ chức dạy học.
 */
@Component
public class JpaTeachingChecks implements TeachingChecks {

	@PersistenceContext
	private EntityManager entityManager;

	@Override
	public boolean isAssignedToTeach(String teacherSub, String organizationId, UUID classId) {
		return findClass(organizationId, classId).map(view -> teacherSub.equals(view.teacherSub())).orElse(false);
	}

	@Override
	public boolean isEnrolled(String studentSub, String organizationId, UUID classId) {
		Number count = (Number) this.entityManager.createNativeQuery("""
				select count(*) from class_member
				where partition_key = :partitionKey and class_id = :classId and student_sub = :studentSub
					and removed_at is null
				""")
				.setParameter("partitionKey", organizationId)
				.setParameter("classId", classId)
				.setParameter("studentSub", studentSub)
				.getSingleResult();
		return count.longValue() > 0;
	}

	@Override
	public Optional<ClassView> findClass(String organizationId, UUID classId) {
		return this.entityManager
				.createNativeQuery("select id, teacher_sub from teaching_class"
						+ " where partition_key = :partitionKey and id = :classId")
				.setParameter("partitionKey", organizationId)
				.setParameter("classId", classId)
				.getResultStream()
				.map(row -> {
					Object[] columns = (Object[]) row;
					return new ClassView(toUuid(columns[0]), (String) columns[1]);
				})
				.findFirst();
	}

	// Truy vấn native trả cột uuid theo hình dạng riêng của từng driver (PostgreSQL là UUID, H2 là mảng
	// byte) nên phải chuyển đổi tường minh thay vì ép kiểu thẳng.
	private static UUID toUuid(Object value) {
		if (value instanceof UUID uuid) {
			return uuid;
		}
		if (value instanceof byte[] bytes) {
			java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
			return new UUID(buffer.getLong(), buffer.getLong());
		}
		return UUID.fromString(String.valueOf(value));
	}

	@Override
	public List<UUID> classIdsTaughtBy(String organizationId, String teacherSub) {
		return this.entityManager
				.createNativeQuery("select id from teaching_class"
						+ " where partition_key = :partitionKey and teacher_sub = :teacherSub")
				.setParameter("partitionKey", organizationId)
				.setParameter("teacherSub", teacherSub)
				.getResultStream()
				.map(UUID.class::cast)
				.toList();
	}

	@Override
	public List<UUID> classIdsEnrolledBy(String organizationId, String studentSub) {
		return this.entityManager.createNativeQuery("""
				select class_id from class_member
				where partition_key = :partitionKey and student_sub = :studentSub and removed_at is null
				""")
				.setParameter("partitionKey", organizationId)
				.setParameter("studentSub", studentSub)
				.getResultStream()
				.map(UUID.class::cast)
				.toList();
	}

	@Override
	public List<String> activeRoster(String organizationId, UUID classId) {
		return this.entityManager.createNativeQuery("""
				select student_sub from class_member
				where partition_key = :partitionKey and class_id = :classId and removed_at is null
				order by student_sub
				""")
				.setParameter("partitionKey", organizationId)
				.setParameter("classId", classId)
				.getResultStream()
				.map(String.class::cast)
				.toList();
	}

}
