import { Suspense, lazy } from 'react';
import type {
  Course,
  CourseActivityGrid,
  DashboardOverviewView,
  MyAccessView,
  MyActivityGrades,
} from '@lms/contracts';
import { redirect } from 'react-router';
import { useAuth } from '#lib/auth';
import { tenantAliasForHostname } from '#lib/kc-tenant-hosts';
import { HomePage } from '../components/home';
import { DashboardSkeleton } from '../components/dashboard-skeleton';
import { queryKeys } from '#lib/query-keys';
import { queryKeys as dashboardQueryKeys } from '../queries/keys';
import { buildSsrLoaderData, serverProbe, serverQueryFn, type SsrLoaderData } from '#lib/ssr-data';
import { hasCourseAssignment } from '#lib/permissions';
import { CourseGradeData } from '#hooks/use-courses-grade-data';

/**
 * Loader phía server cho route `/` (tầng SL-1 của #7).
 *
 * Hai mặt của route theo phiên:
 *   - CHƯA đăng nhập (không cookie phiên Better Auth): loader không gọi API nghiệp vụ nào, trả dehydrated state rỗng —
 *     mặt công khai giữ nguyên và là bản được prerender lúc build.
 *   - ĐÃ đăng nhập: prefetch bằng cookie của chính request (xem `lib/ssr-data.ts`) rồi dehydrate cho root.tsx hydrate:
 *     lượt 1 gồm hai query gốc của PermissionsProvider (ảnh chụp quyền `capabilities` + danh sách khoá `courses` — có
 *     chúng thì provider phân loại vai ngay từ lần render đầu, dashboard hết hiện khung giữ chỗ chờ phân loại) và
 *     query tổng hợp `dashboardOverview` của các khối còn hiện trên trang; lượt 2 — chỉ khi lượt 1 có đủ ảnh chụp
 *     quyền + danh sách khoá — prefetch dữ liệu điểm theo khoá (`courseAccess`) cho đúng tập khoá người dùng THUỘC
 *     về (dạy ∪ học, phân loại bằng chính các hàm thuần của `lib/permissions`). Các prefetch của những khối thuộc
 *     tính năng đã ẩn khỏi bản phát hành 07/11 (huy hiệu của tôi, số thông báo chưa đọc, sự kiện lịch 30 ngày, ba
 *     query của khối quản trị) đã gỡ cùng các khối đó ở issue #79 — loader không gọi nền các đích của tính năng hoãn
 *     nữa.
 *
 * Phần CHƯA lên loader (khai rõ): pref thẻ khoá (key `courseCards`) — dữ liệu của query đó là một `Map` do
 * `fetchCourseCardPrefs` dựng phía client, không serialize qua dehydrate được (sẽ thành object rỗng và làm hỏng lời gọi
 * `.get()` của các khối); khối GV/HS vẫn fetch query này phía client như trước SL-1.
 */
export async function loader({ request }: { request: Request }): Promise<SsrLoaderData> {
  // Hai hostname tenant nghiệm thu Keycloak (issue #38): trang gốc
  // của các host này là trang tenant, không phải mặt công khai/tổng
  // quan của app — chuyển thẳng sang route tenant trước mọi prefetch.
  if (tenantAliasForHostname(new URL(request.url).hostname)) {
    throw redirect('/tenant');
  }
  return buildSsrLoaderData(request, async (queryClient, req) => {
    await Promise.all([
      queryClient.prefetchQuery({
        queryKey: queryKeys.capabilities,
        queryFn: serverQueryFn<MyAccessView>(req, '/api/v1/users/me/capabilities'),
      }),
      queryClient.prefetchQuery({
        queryKey: queryKeys.courses,
        queryFn: serverQueryFn<Course[]>(req, '/api/v1/courses'),
      }),
      queryClient.prefetchQuery({
        queryKey: dashboardQueryKeys.dashboardOverview,
        queryFn: serverQueryFn<DashboardOverviewView>(req, '/api/v1/dashboard/overview?days=7'),
      }),
    ]);

    // Lượt 2 cần kết quả lượt 1: thiếu ảnh chụp quyền hoặc danh sách khoá (API lỗi phía server) thì dừng — phía client
    // tự phân loại và tải như trước, loader không đoán tập khoá thay nó.
    const access = queryClient.getQueryData<MyAccessView>(queryKeys.capabilities);
    const courses = queryClient.getQueryData<Course[]>(queryKeys.courses);
    if (!access || !courses) return;

    const memberCourses = courses.filter((course) => hasCourseAssignment(access, course));
    // Dữ liệu điểm theo khoá: cùng hình dạng + cùng ngữ nghĩa dò (endpoint lỗi độc lập thì phần đó null) với
    // `fetchCourseGradeData` phía client — chỉ khác kênh gọi (cookie của request qua API nội bộ).
    const gradePrefetches = memberCourses.map((course) =>
      queryClient.prefetchQuery({
        queryKey: queryKeys.courseAccess(course.id),
        queryFn: async (): Promise<CourseGradeData> => {
          const [report, gradebook] = await Promise.all([
            serverProbe<CourseActivityGrid>(req, `/api/v1/courses/${course.id}/gradebook/report`),
            serverProbe<MyActivityGrades>(req, `/api/v1/courses/${course.id}/gradebook`),
          ]);
          return { report, gradebook };
        },
      }),
    );

    await Promise.all(gradePrefetches);
  });
}

const DashboardPage = lazy(() => import('../components/dashboard').then((m) => ({ default: m.DashboardPage })));

export default function HomeIndexRoute() {
  const { user } = useAuth();
  if (!user) return <HomePage />;
  return (
    <Suspense fallback={<DashboardSkeleton />}>
      <DashboardPage />
    </Suspense>
  );
}

// Biên lỗi + tiêu đề theo route (cụm khung SSR #146, từ rà soát #133).
export { RouteErrorBoundary as ErrorBoundary } from '#components/route-error-boundary';
export function meta() {
  return [{ title: 'Giáo khoa' }];
}
