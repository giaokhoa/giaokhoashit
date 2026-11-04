package com.giaokhoa.server.access;

import java.util.UUID;

/**
 * Các điểm kiểm phụ trợ cho luồng dạy–học mà lõi quyền cung cấp dạng hợp đồng để các feat lớp học, phân công dạy,
 * ghi danh và bài làm gọi khi quyết quyền trên dữ kiện của chúng. Lượt lõi này chỉ chốt ngữ nghĩa của hợp đồng,
 * chưa cài đặt: phần cài đặt thuộc về feat sở hữu dữ kiện tương ứng và phải đọc dữ kiện thật của feat đó tại thời
 * điểm gọi, không suy ra từ dữ kiện của lõi quyền.
 */
public interface LearningAccessChecks {

	/**
	 * Giáo viên có đang được phân công dạy lớp này tại trung tâm này hay không. Chỉ phân công dạy còn hiệu lực mới
	 * tính; vai quản trị viên của trung tâm tự nó không làm người giữ vai thành giáo viên của lớp.
	 */
	boolean isAssignedToTeach(String teacherSub, String organizationId, UUID classId);

	/**
	 * Học viên có đang ghi danh hiệu lực vào lớp này tại trung tâm này hay không. Ghi danh đã rút, đã hết hạn hoặc
	 * thuộc trung tâm khác không tính; tư cách thành viên của trung tâm tự nó không thay thế được ghi danh.
	 */
	boolean isEnrolled(String studentSub, String organizationId, UUID classId);

	/**
	 * Bài làm có thuộc về học viên này hay không, theo chính bản ghi của bài làm. Không suy ra từ việc học viên
	 * nhìn thấy lớp hay đề chứa bài làm đó.
	 */
	boolean ownsAttempt(String studentSub, UUID attemptId);

}
