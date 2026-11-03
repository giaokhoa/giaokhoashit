import type { GradeItem } from '@lms/contracts';

export type VneduColumnKind = 'none' | 'regular' | 'midterm' | 'final';

/**
 * Mục điểm đủ điều kiện làm cột thành phần vnEdu: mục của một bài
 * (nhập tay hoặc hoạt động), điểm số dạng value — khớp đúng điều kiện
 * phía API kiểm khi xuất. Từ issue #224, bài còn phải ĐÃ CÔNG BỐ.
 */
export function isVneduExportableItem(item: GradeItem): boolean {
  return (item.itemType === 'manual' || item.itemType === 'mod') && (item.gradeType ?? 'value') === 'value';
}

/**
 * Gợi ý loại cột vnEdu từ tên mục điểm. Đây CHỈ là mặc định trên giao diện
 * để giáo viên đỡ phải chọn lại từ đầu — giáo viên xác nhận trước khi xuất
 * và phía API không hề đoán theo tên.
 */
export function guessVneduColumnKind(item: GradeItem): VneduColumnKind {
  const name = item.itemName.toLowerCase();
  if (/giữa\s*k[ỳì]|giua\s*ky|midterm|\bgk\b/.test(name)) return 'midterm';
  if (/cuối\s*k[ỳì]|cuoi\s*ky|học\s*k[ỳì]|hoc\s*ky|\bck\b|final/.test(name)) return 'final';
  return 'regular';
}

export function scoreLabel(value: number, locale: string): string {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(value);
}
