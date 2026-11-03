import type { DashboardOverviewView } from '@lms/contracts';
import { apiJson } from '#lib/api';

/**
 * Đọc khối tổng hợp DASH1 (GET /dashboard/overview). Endpoint đồng
 * thời cập nhật mốc xem trước phía server khi tính xong danh sách
 * "mới".
 */
export function fetchDashboardOverview() {
  return apiJson<DashboardOverviewView>('/api/v1/dashboard/overview?days=7');
}

export function fetchNotificationUnreadCount() {
  return apiJson<{ unread: number }>('/api/v1/notifications/unread-count');
}
