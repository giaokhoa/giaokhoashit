package com.giaokhoa.server.organization;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

/**
 * Hai phép kiểm dùng chung của khối tổ chức dạy học — nơi duy nhất định nghĩa chúng để khối giao đề/làm bài và
 * khối chấm điểm dùng lại nguyên trạng: "được phân công dạy lớp X" và "được ghi danh vào lớp X". Cả hai đều đọc
 * dữ kiện hiện hành trong cơ sở dữ liệu tại thời điểm kiểm, không suy ra từ token.
 */
@Component
public class TeachingAccessChecks {

	private final TeachingClassRepository classes;

	private final ClassMemberRepository members;

	private final GuestPartitionScope guestScope;

	private final JdbcTemplate jdbc;

	public TeachingAccessChecks(TeachingClassRepository classes, ClassMemberRepository members,
			GuestPartitionScope guestScope, JdbcTemplate jdbc) {
		this.classes = classes;
		this.members = members;
		this.guestScope = guestScope;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public boolean isAssignedTeacher(UUID classId) {
		return this.classes.findById(classId).map(this::isAssignedTeacher).orElse(false);
	}

	public boolean isAssignedTeacher(TeachingClass teachingClass) {
		TenantContext context = TenantContexts.current();
		// Điều kiện "đang ở chế độ trung tâm của đúng trung tâm sở hữu lớp" là phần khiến giáo viên đã bị gỡ tư
		// cách thành viên mất quyền dạy ngay dù teacher_sub vẫn ghi tên họ: khung tenancy khi đó chỉ còn mở chế độ
		// khách cho họ, và chế độ khách không bao giờ thỏa phép kiểm này.
		return context != null && !context.guest() && context.organizationId() != null
				&& context.organizationId().equals(teachingClass.getPartitionKey())
				&& context.sub().equals(teachingClass.getTeacherSub());
	}

	@Transactional(readOnly = true)
	public boolean isEnrolled(UUID classId) {
		TenantContext context = TenantContexts.current();
		if (context == null) {
			return false;
		}
		if (!context.guest()) {
			return context.organizationId() != null
					&& this.members.existsByClassIdAndStudentSubAndRemovedAtIsNull(classId, context.sub());
		}
		// Khách không có khoá phân vùng trong ngữ cảnh: đối chiếu ghi danh bằng SQL thuần trong phạm vi phân vùng
		// đã ghim về trung tâm của trang.
		UUID organizationId = this.guestScope.pinPartitionForPage(context.organizationAlias());
		Integer count = this.jdbc.queryForObject("""
				select count(*) from class_member
				where partition_key = ? and class_id = ? and student_sub = ? and removed_at is null
				""", Integer.class, organizationId.toString(), classId, context.sub());
		return count != null && count > 0;
	}

}
