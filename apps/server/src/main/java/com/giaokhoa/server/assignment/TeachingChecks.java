package com.giaokhoa.server.assignment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Điểm nối tới khối tổ chức dạy học (lớp, giáo viên phụ trách, ghi danh): hai phép kiểm dùng chung mà mọi khối
 * sau tiêu thụ nguyên trạng — "được phân công dạy lớp X" và "được ghi danh vào lớp X" — cộng các đường đọc lớp
 * và danh sách lớp mà bề mặt giao/làm bài cần. Chữ ký hai phép kiểm bám đúng giao diện kiểm tra học tập mà lõi
 * quyền đã công bố cho khối này (sub và Organization truyền tường minh, đọc dữ kiện hiện hành tại thời điểm
 * quyết định, không suy ra từ token). Khi khối tổ chức dạy học hợp nhất, tầng dịch vụ chuyển sang tiêu thụ
 * trực tiếp thành phần kiểm tra dùng chung của khối đó thay cho điểm nối này.
 */
public interface TeachingChecks {

	boolean isAssignedToTeach(String teacherSub, String organizationId, UUID classId);

	boolean isEnrolled(String studentSub, String organizationId, UUID classId);

	Optional<ClassView> findClass(String organizationId, UUID classId);

	List<UUID> classIdsTaughtBy(String organizationId, String teacherSub);

	List<UUID> classIdsEnrolledBy(String organizationId, String studentSub);

	List<String> activeRoster(String organizationId, UUID classId);

	record ClassView(UUID id, String teacherSub) {
	}

}
