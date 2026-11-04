/**
 * Khoá cache React Query của feature classroom (issue #102).
 *
 * Mọi khoá của feature bắt đầu bằng đoạn `classroom` để việc vô hiệu hoá theo họ (sau các mutation) không chạm
 * cache của feature khác. Khoá chi tiết mang định danh của thực thể để các màn hình đọc cùng một dữ kiện dùng
 * chung một mục cache — đặc biệt các câu hỏi và đề vừa tạo ở một màn hình phải thấy được ở màn hình soạn đề/giao
 * bài ngay trong cùng phiên, vì server chưa có endpoint danh sách cho câu hỏi và đề nên phiên làm việc dựa vào
 * cache này cộng với đọc theo định danh.
 */
export const classroomKeys = {
  /** Danh tính của người gọi (`GET /api/me`). */
  me: ['classroom', 'me'] as const,
  /** Danh sách khóa của trung tâm. */
  courses: ['classroom', 'courses'] as const,
  /** Danh sách lớp của trung tâm. */
  classes: ['classroom', 'classes'] as const,
  /**
   * Danh sách thành viên của một lớp.
   *
   * @param classId - Định danh lớp.
   * @returns Khoá cache của danh sách thành viên lớp đó.
   */
  members: (classId: string) => ['classroom', 'classes', classId, 'members'] as const,
  /** Danh sách ngân hàng cá nhân. */
  banks: ['classroom', 'question-banks'] as const,
  /**
   * Một câu hỏi cùng các phiên bản của nó.
   *
   * @param questionId - Định danh câu hỏi.
   * @returns Khoá cache của câu hỏi đó.
   */
  question: (questionId: string) => ['classroom', 'questions', questionId] as const,
  /**
   * Các liên kết tệp đính kèm của một phiên bản câu.
   *
   * @param questionId - Định danh câu hỏi.
   * @param versionId - Định danh phiên bản.
   * @returns Khoá cache của danh sách tệp đính kèm của phiên bản đó.
   */
  attachments: (questionId: string, versionId: string) =>
    ['classroom', 'questions', questionId, 'versions', versionId, 'attachments'] as const,
  /**
   * Một đề cùng phiên bản đang đọc.
   *
   * @param examId - Định danh đề.
   * @returns Khoá cache của đề đó.
   */
  exam: (examId: string) => ['classroom', 'exams', examId] as const,
  /** Danh sách lần giao nhìn thấy theo vai. */
  assignments: ['classroom', 'assignments'] as const,
  /**
   * Bảng bài nộp của một lần giao.
   *
   * @param assignmentId - Định danh lần giao.
   * @returns Khoá cache của bảng bài nộp.
   */
  submissions: (assignmentId: string) => ['classroom', 'assignments', assignmentId, 'submissions'] as const,
  /**
   * Hình chiếu kết quả của một lượt làm cho học viên.
   *
   * @param attemptId - Định danh lượt làm.
   * @returns Khoá cache của hình chiếu kết quả.
   */
  attemptResult: (attemptId: string) => ['classroom', 'attempts', attemptId, 'result'] as const,
  /**
   * Hình chiếu chấm của một lượt làm cho giáo viên phụ trách.
   *
   * @param attemptId - Định danh lượt làm.
   * @returns Khoá cache của hình chiếu chấm.
   */
  teacherAttempt: (attemptId: string) => ['classroom', 'attempts', attemptId, 'teacher'] as const,
  /**
   * Danh sách tệp bài làm của một lượt làm.
   *
   * @param attemptId - Định danh lượt làm.
   * @returns Khoá cache của danh sách tệp bài làm của lượt đó.
   */
  submissionFiles: (attemptId: string) => ['classroom', 'attempts', attemptId, 'files'] as const,
};
