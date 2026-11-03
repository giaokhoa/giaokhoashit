import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { queryClient } from '#lib/query-client';
import { queryKeys as sharedQueryKeys } from '#lib/query-keys';
import {
  createCourseCategory,
  deleteCourseCategory,
  fetchCourseCategoryTree,
  fetchCourseMyProgress,
  fetchCourses,
  renameCourseCategory,
} from '../api/courses';
import { queryKeys } from './keys';

export function categoryTreeQueryOptions() {
  return queryOptions({
    queryKey: sharedQueryKeys.categoryTree,
    queryFn: () => fetchCourseCategoryTree(),
  });
}

export function coursesListQueryOptions() {
  return queryOptions({
    queryKey: sharedQueryKeys.courses,
    queryFn: () => fetchCourses(),
  });
}

export function courseMyProgressQueryOptions(courseId: string) {
  return queryOptions({
    queryKey: queryKeys.courseMyProgress(courseId),
    queryFn: async () => {
      try {
        return await fetchCourseMyProgress(courseId);
      } catch {
        return { percent: null };
      }
    },
  });
}

function invalidateCatalog(client: ReturnType<typeof useQueryClient>) {
  void client.invalidateQueries({ queryKey: sharedQueryKeys.categoryTree });
  void client.invalidateQueries({ queryKey: sharedQueryKeys.courses });
}

/**
 * Đột biến tạo danh mục (POST /courses/categories): `parent` chỉ gửi
 * khi chọn một danh mục cha thật — ở cấp cao nhất thì bỏ qua hẳn
 * trường này thay vì gửi giá trị không phải UUID.
 */
export function useCreateCourseCategory() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; parent?: string }) => createCourseCategory(body),
    onSuccess: () => invalidateCatalog(client),
  });
}

export function useRenameCourseCategory() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { categoryId: string; name: string }) => renameCourseCategory(input),
    onSuccess: () => invalidateCatalog(client),
  });
}

export function useDeleteCourseCategory() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (categoryId: string) => deleteCourseCategory(categoryId),
    onSuccess: () => invalidateCatalog(client),
  });
}

export async function coursesPageClientLoader() {
  await Promise.allSettled([
    queryClient.ensureQueryData(categoryTreeQueryOptions()),
    queryClient.ensureQueryData(coursesListQueryOptions()),
  ]);
  return null;
}
