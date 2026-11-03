import { apiJson } from '#lib/api';
import type { PlayerView } from '../types/player';

async function probe<T>(path: string): Promise<T | null> {
  try {
    return await apiJson<T>(path);
  } catch {
    return null;
  }
}

export function fetchPlayerView(courseId: string, cmId: string) {
  return probe<PlayerView>(`/api/v1/courses/${courseId}/player/${cmId}`);
}

/**
 * "Đánh dấu đã học" cho mục hoàn thành thủ công: trước hết probe completion của chính người dùng để lấy userId
 * (endpoint manual đòi userId trong thân), rồi POST completion/manual với complete=true — đúng hai bước của bản thủ
 * công.
 *
 * @param courseId - Định danh khoá.
 * @param cmId - Định danh module.
 * @throws Error 'no user' khi không lấy được userId từ completion.
 */
export async function markPlayerModuleLearned(
  courseId: string | undefined,
  cmId: string | undefined,
) {
  const mine = await probe<{ course: { userId: string } }>(
    `/api/v1/courses/${courseId}/completion/me`,
  );
  const userId = mine?.course.userId;
  if (!userId) throw new Error('no user');
  await apiJson(`/api/v1/courses/${courseId}/modules/${cmId}/completion/manual`, {
    method: 'POST',
    body: JSON.stringify({ userId, complete: true }),
  });
}
