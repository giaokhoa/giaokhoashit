import { apiJson } from '#lib/api';
import type { CourseGroup, ProgressReport } from '../types/progress-report';

async function probe<T>(path: string): Promise<T | null> {
  try {
    return await apiJson<T>(path);
  } catch {
    return null;
  }
}

/**
 * Đọc báo cáo tiến độ (GET /courses/:id/progress/report, lọc theo
 * nhóm khi có); lỗi tải → null (trang hiểu là không có quyền), giữ
 * nguyên ngữ nghĩa probe thủ công trước đây.
 */
export function fetchProgressReport(courseId: string, groupId: string) {
  return probe<ProgressReport>(
    `/api/v1/courses/${courseId}/progress/report${groupId ? `?groupId=${groupId}` : ''}`,
  );
}

export function fetchProgressReportGroups(courseId: string) {
  return probe<CourseGroup[]>(`/api/v1/courses/${courseId}/groups`);
}
