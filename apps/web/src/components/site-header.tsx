import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router';

import { Separator } from '@lms/ui/components/separator';
import { SidebarTrigger } from '@lms/ui/components/sidebar';
import '#config/i18n';

const TITLE_KEYS: { prefix: string; key: string; ns: 'app' | 'dashboard' }[] = [
  { prefix: '/courses', key: 'navCourses', ns: 'app' },
  { prefix: '/questions', key: 'navQuestions', ns: 'app' },
  { prefix: '/exams', key: 'navMyPapers', ns: 'app' },
  { prefix: '/question-usages', key: 'navQuestions', ns: 'app' },
  { prefix: '/profile', key: 'navProfile', ns: 'app' },
  { prefix: '/settings', key: 'navSettings', ns: 'app' },
];

/**
 * Header trên cùng của khu vực đã đăng nhập (khung block dashboard-01). Bản cũ có bố cục BA VÙNG (tiêu đề — ô tìm
 * kiếm toàn cục ở giữa — chuông thông báo và bảng lệnh ở phải); bản phát hành 07/11 (issue #79) chỉ còn VÙNG TIÊU
 * ĐỀ: nút thu/mở sidebar + tiêu đề trang hiện tại (tra catalog i18n theo route bằng TITLE_KEYS ở trên, chỉ còn các
 * tiền tố thuộc bề mặt phát hành). Ô tìm kiếm toàn cục, bảng lệnh Cmd+K và chuông thông báo thuộc các tính năng đã
 * hoãn (tìm kiếm toàn hệ thống, thông báo) nên đã gỡ cùng route đích của chúng — header không còn tự gọi API nào.
 *
 * Site chỉ dành cho người Việt nên không có bộ chọn ngôn ngữ trên giao diện — i18n chỉ quản lý chuỗi tập trung, locale
 * cố định tiếng Việt.
 */
export function SiteHeader() {
  const { t } = useTranslation(['app', 'dashboard']);
  const { pathname } = useLocation();
  const match =
    pathname === '/'
      ? { key: 'itemDashboard', ns: 'dashboard' as const }
      : TITLE_KEYS.find((entry) => pathname.startsWith(entry.prefix));
  return (
    <header className="flex h-(--header-height) shrink-0 items-center gap-2 border-b transition-all ease-linear group-has-data-[collapsible=icon]/sidebar-wrapper:h-(--header-height)">
      <div className="flex w-full items-center gap-2 pr-4 pl-3 lg:px-6">
        {/* Vùng tiêu đề: nút mở sidebar + tiêu đề trang. min-w-0 + truncate để tiêu đề dài tự cắt thay vì tràn hàng. */}
        <div className="flex min-w-0 items-center gap-1 lg:gap-2">
          <SidebarTrigger />
          <Separator orientation="vertical" className="mx-2 data-[orientation=vertical]:h-4" />
          <h1 className="truncate text-base font-medium">{match ? t(`${match.ns}:${match.key}`) : 'Giáo khoa'}</h1>
        </div>
      </div>
    </header>
  );
}
