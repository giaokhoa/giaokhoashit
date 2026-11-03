
export interface NotificationItemView {
  id: string;
  subject: string;
  fullMessage: string;
  smallMessage: string;
  eventType: string;
  contextUrl: string | null;
  contextUrlName: string | null;
  timeRead: string | null;
  createdAt: string;
}

export type NotificationGroupFilter = 'learning' | 'people' | 'system';

export interface NotificationGroupView {
  eventType: string;
  component: string;
  group: NotificationGroupFilter;
  contextUrl: string | null;
  day: string;
  count: number;
  unreadCount: number;
  latest: {
    id: string;
    subject: string;
    smallMessage: string;
    contextUrlName: string | null;
    createdAt: string;
    timeRead: string | null;
  };
}

export interface NotificationGroupsPage {
  items: NotificationGroupView[];
  nextCursor: string | null;
}

export type EmailFrequency = 'immediate' | 'daily' | 'off';

/**
 * Một dòng tuỳ chọn v2 đúng như API trả: MỘT dòng cho mỗi provider. Nhãn + mô tả vi/en đến từ registry ở API (nguồn sự
 * thật duy nhất) — web chọn theo ngôn ngữ giao diện, không tự chế bảng nhãn. `emailFrequency` null = provider không có
 * kênh email; `emailAvailable` false = kênh email chưa gửi thật — trang hiện ghi chú trung thực, cài đặt vẫn lưu và áp
 * dụng khi mở. `siteEnabled` false = admin đã tắt loại này ở cấp site (NB-7): trang Cài đặt thông báo hiển thị khoá
 * "Admin đã tắt ở cấp site" và vô hiệu hoá điều khiển của dòng đó, vì server không phát loại này bất kể tuỳ chọn cá
 * nhân. Kênh push không có ở đây: ẩn hẳn cho tới khi có app native.
 */
export interface NotificationPreferenceView {
  provider: string;
  component: string;
  group: NotificationGroupFilter;
  label: { vi: string; en: string };
  description: { vi: string; en: string };
  teacherOnly: boolean;
  mandatoryInApp: boolean;
  popupEnabled: boolean;
  emailFrequency: EmailFrequency | null;
  emailAvailable: boolean;
  siteEnabled: boolean;
}

export interface PreferencePatch {
  popupEnabled?: boolean;
  emailFrequency?: EmailFrequency;
}

/**
 * Chế độ ghi đè thông báo theo khoá (NB-4, issue #174): 'all' = nhận như mặc định toàn cục; 'mute' = tắt tiếng khoá đó
 * (thông báo thường của khoá không được sinh ở kênh nào; điểm mới + sắp hết hạn ghi danh vẫn tới theo chính sách P3 đã
 * chốt).
 */
export type NotificationCourseOverrideMode = 'all' | 'mute';

export interface NotificationCourseOverrideView {
  courseId: string;
  courseName: string;
  mode: NotificationCourseOverrideMode;
}
