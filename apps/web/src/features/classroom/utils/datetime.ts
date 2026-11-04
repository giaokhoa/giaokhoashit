/**
 * Tiện ích thời gian của feature classroom (issue #102).
 *
 * Hai chiều chuyển đổi mà các màn hình cần: hiển thị mốc thời gian ISO của server theo giờ địa phương của người
 * dùng, và chuyển giá trị của ô nhập `datetime-local` (giờ địa phương không múi giờ) sang chuỗi ISO có múi giờ
 * để gửi lên server khi tạo lần giao — server lưu cửa sổ giao bài theo `OffsetDateTime` nên chuỗi gửi lên bắt
 * buộc mang offset; lấy offset từ chính trình duyệt của người dùng là nguồn đúng duy nhất ở tầng này.
 */

/**
 * Định dạng một mốc thời gian ISO của server để hiển thị.
 *
 * @param value - Chuỗi ISO của server, hoặc `null` khi mốc chưa có (ví dụ lượt chưa nộp).
 * @returns Chuỗi ngày giờ theo locale `vi-VN`, hoặc dấu gạch ngang khi giá trị rỗng/không đọc được.
 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString('vi-VN');
}

/**
 * Chuyển giá trị của ô nhập `datetime-local` sang chuỗi ISO có offset múi giờ địa phương.
 *
 * @param localValue - Giá trị dạng `YYYY-MM-DDTHH:mm` của ô nhập.
 * @returns Chuỗi ISO đầy đủ (kiểu `Date.toISOString()`), hoặc `null` khi giá trị rỗng/không hợp lệ.
 */
export function localInputToIso(localValue: string): string | null {
  if (!localValue) return null;
  const date = new Date(localValue);
  if (Number.isNaN(date.getTime())) return null;
  return date.toISOString();
}
