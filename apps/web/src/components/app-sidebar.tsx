import * as React from 'react';
import {
  IconBook,
  IconInnerShadowTop,
  IconLayoutDashboard,
  IconNotebook,
  IconQuestionMark,
  IconUsers,
} from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { NavMain, type NavEntry } from '#components/nav-main';
import { NavUser } from '#components/nav-user';
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarRail,
} from '@lms/ui/components/sidebar';
import { usePermissions } from '#lib/permissions';
import '#config/i18n';

const OPEN_GROUPS_STORAGE_KEY = 'lms:sidebar:open-groups';

/**
 * Đọc lười danh sách nhóm đang mở đã lưu ở lần render đầu tiên, theo đúng mẫu `readSidebarDefaultOpen` của shell: giá
 * trị hỏng/không phải mảng chuỗi thì coi như chưa lưu gì. Nhóm chứa route đang xem luôn mở bất kể giá trị đã lưu — quy
 * tắc đó nằm ở NavMain, không ở đây.
 */
function readStoredOpenGroups(): string[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = window.localStorage.getItem(OPEN_GROUPS_STORAGE_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((id): id is string => typeof id === 'string') : [];
  } catch {
    return [];
  }
}

/**
 * Sidebar điều hướng của khu vực đã đăng nhập, LỌC THEO QUYỀN THẬT của người đang đăng nhập (hồ sơ UI/UX §4 — cây nhóm
 * thay cho 3 nhóm phẳng của khung dashboard-01 trước đây). Cây của bản phát hành 07/11 (issue #79) chỉ còn bề mặt
 * thuộc vòng dạy–học:
 *
 * - 3 mục trực tiếp (Tổng quan, Khoá học, Lớp): các trang mọi người dùng đăng nhập đều tự phục vụ được.
 * - Cụm ngân hàng cá nhân, chỉ hiện khi API cho phép dùng ngân hàng câu hỏi (canUseQuestionBank) — học viên thuần
 *   không nhìn thấy: "Ngân hàng câu hỏi" (thân mục dẫn tới /questions, thu/mở ra mục con "Ngữ liệu" ở
 *   /questions/stimuli) và "Ngân hàng đề" (/exams) — thân mục dẫn thẳng tới trang danh sách. Cấu trúc 3 mục ngân
 *   hàng ở cấp cao nhất của bản cũ còn mục "Ngân hàng ma trận" (/matrices): ma trận thuộc tính năng đã hoãn nên
 *   mục này đã gỡ, và tab "Hệ thống" trong từng màn danh sách ngân hàng cũng đã gỡ vì khu ngân hàng hệ thống
 *   (/system-bank/*) không thuộc bản phát hành.
 * - Mọi bề mặt đã hoãn đều vắng khỏi cây này và route của chúng không còn được đăng ký (xem `src/app/routes.ts`):
 *   lịch, tin nhắn, thông báo, phụ huynh và liên kết phụ huynh, nhóm Học tập (huy hiệu, kế hoạch học tập), chương
 *   trình, và toàn bộ các nhóm quản trị (người dùng và quyền, vai trò và quyền, hệ thống và dữ kiện). Vì vậy
 *   sidebar không còn tự chạy truy vấn nền nào — các truy vấn đếm số chưa đọc của tin nhắn/thông báo và tra cứu
 *   liên kết phụ huynh từng gắn với các mục đó đã gỡ cùng mục; phần tử không có quyền thì KHÔNG render — không
 *   render rồi disable, không để link dẫn tới trang 403.
 * - Thu sidebar là thu về rail icon (`collapsible="icon"` + SidebarRail) thay vì mất hẳn điều hướng như `offcanvas`
 *   trước đây; trạng thái thu/mở của từng nhóm lưu ở localStorage, trạng thái sidebar toàn cục vẫn do cookie
 *   `sidebar_state` của SidebarProvider giữ.
 */
export function AppSidebar({ ...props }: React.ComponentProps<typeof Sidebar>) {
  const { t } = useTranslation(['app', 'dashboard']);
  const perms = usePermissions();

  const [openGroups, setOpenGroups] = React.useState<string[]>(readStoredOpenGroups);
  const handleOpenGroupChange = React.useCallback((groupId: string, open: boolean) => {
    setOpenGroups((prev) => {
      const next = open ? [...new Set([...prev, groupId])] : prev.filter((id) => id !== groupId);
      try {
        window.localStorage.setItem(OPEN_GROUPS_STORAGE_KEY, JSON.stringify(next));
      } catch {
        // localStorage có thể bị chặn (chế độ riêng tư…) — trạng thái vẫn sống trong bộ nhớ cho phiên hiện tại, chỉ
        // không bền qua F5.
      }
      return next;
    });
  }, []);

  const entries: NavEntry[] = [
    { title: t('dashboard:itemDashboard'), url: '/', icon: IconLayoutDashboard },
    { title: t('app:navCourses'), url: '/courses', icon: IconBook },
    { title: t('app:navClasses'), url: '/classes', icon: IconUsers },
    ...(perms.canUseQuestionBank
      ? [
          {
            groupId: 'my-questions',
            title: t('app:navMyQuestions'),
            icon: IconQuestionMark,
            url: '/questions',
            toggleLabel: t('app:navExpandGroup', { name: t('app:navMyQuestions') }),
            children: [
              { title: t('app:navStimuli'), url: '/questions/stimuli', icon: IconBook },
            ],
          },
          {
            groupId: 'my-papers',
            title: t('app:navMyPapers'),
            icon: IconNotebook,
            url: '/exams',
            toggleLabel: t('app:navExpandGroup', { name: t('app:navMyPapers') }),
            children: [],
          },
        ]
      : []),
  ];

  return (
    <Sidebar collapsible="icon" {...props}>
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton render={<Link to="/" />} className="data-[slot=sidebar-menu-button]:p-1.5!">
              <IconInnerShadowTop className="size-5!" />
              <span className="text-base font-semibold">Giáo khoa</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>
      <SidebarContent>
        <NavMain items={entries} openGroups={openGroups} onOpenGroupChange={handleOpenGroupChange} />
      </SidebarContent>
      <SidebarFooter>
        <NavUser />
      </SidebarFooter>
      <SidebarRail />
    </Sidebar>
  );
}
