import { queryOptions, useQuery } from '@tanstack/react-query';
import { queryKeys as sharedQueryKeys } from '#lib/query-keys';
import { fetchDashboardOverview, fetchNotificationUnreadCount } from '../api/dashboard';
import { queryKeys } from './keys';

export function dashboardOverviewQueryOptions() {
  return queryOptions({
    queryKey: queryKeys.dashboardOverview,
    queryFn: () => fetchDashboardOverview(),
  });
}

export function useDashboardOverview() {
  return useQuery(dashboardOverviewQueryOptions());
}

/**
 * Tuỳ chọn truy vấn số thông báo chưa đọc cho thẻ thống kê của học sinh (GET /notifications/unread-count): dùng CHUNG
 * khoá `notificationUnreadCount` với chuông/sidebar — một dữ kiện chỉ có một khoá để stream SSE và các đột biến đọc/xoá
 * cập nhật đúng mục cache mà thẻ này đọc. Loader SL-1 của route `/` cũng nạp trước đúng khoá này để dehydrate cho cả
 * hai bề mặt.
 */
export function dashboardUnreadCountQueryOptions() {
  return queryOptions({
    queryKey: sharedQueryKeys.notificationUnreadCount,
    queryFn: () => fetchNotificationUnreadCount(),
  });
}
