
/**
 * Một dòng lịch sử thay đổi điểm như API trả về (bảng
 * `grade_grades_history` phía grades service): `action` 1 = tạo mới,
 * 2 = cập nhật, 3 = xoá theo quy ước của Moodle.
 */
export interface GradeHistoryView {
  id: string;
  action: number;
  itemId: string;
  userId: string;
  rawGrade: number | null;
  finalGrade: number | null;
  source: string | null;
  updatedAt: string;
  loggedUser: string | null;
}
