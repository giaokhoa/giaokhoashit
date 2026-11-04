import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import App from '../app/root';
import Shell from '../app/shell';
import { queryClient } from '#lib/query-client';
import { baSessionResponse, isGetSessionUrl } from '#test/ba-session';

/**
 * Test cho cây điều hướng sidebar của bản phát hành 07/11 (issue #79). Cây chỉ còn bề mặt thuộc vòng dạy–học:
 *
 * - 3 mục trực tiếp mọi người dùng đăng nhập đều thấy: "Tổng quan" (/), "Khoá học" (/courses), "Lớp" (/classes).
 * - Cụm ngân hàng cá nhân gác canUseQuestionBank: "Ngân hàng câu hỏi" (link /questions, thu/mở ra mục con "Ngữ
 *   liệu" ở /questions/stimuli) và "Ngân hàng đề" (/exams). Học viên thuần không thấy mục ngân hàng nào.
 * - Mọi bề mặt đã hoãn đều vắng: lịch, tin nhắn, thông báo, phụ huynh và liên kết phụ huynh, nhóm Học tập (huy
 *   hiệu, kế hoạch học tập), ngân hàng ma trận, chương trình, và cả ba nhóm quản trị (Người dùng & quyền, Vai trò
 *   & Quyền, Cài đặt hệ thống) — kể cả với tài khoản quản trị site. Sidebar cũng không còn tự chạy truy vấn nền
 *   nào (số chưa đọc tin nhắn/thông báo, tra cứu liên kết phụ huynh đã gỡ cùng các mục đó).
 *
 * Dựng đúng cây thật (App → Shell → AppSidebar), stub fetch theo vai trò. Trạng thái mở của nhóm lưu ở
 * localStorage `lms:sidebar:open-groups`; nhóm chứa route đang xem luôn tự mở và mục con + nút cha cùng mang
 * trạng thái active. Cookie `sidebar_state=false` → sidebar ở chế độ rail icon (data-collapsible="icon", có
 * SidebarRail).
 */

const ADMIN_ID = '01900000-0000-7000-8000-0000000000a1';
const STUDENT_ID = '01900000-0000-7000-8000-0000000000b2';
const TEACHER_ID = '01900000-0000-7000-8000-0000000000c3';
const SYSTEM_CONTEXT_ID = '01900000-0000-7000-8000-0000000000d4';

function profileOf(id: string, username: string) {
  return {
    id,
    username,
    firstName: 'Tên',
    lastName: 'Mẫu',
    email: `${username}@example.com`,
    suspended: false,
    deleted: false,
    isGuest: false,
    forcePasswordChange: false,
    lastLogin: null,
    createdAt: '2026-01-01T00:00:00.000Z',
    customFields: [],
  };
}

const ADMIN_ACCESS = { userId: ADMIN_ID, isSiteAdmin: true, assignments: [], contexts: [], hasLearnerArtifacts: true };
const STUDENT_ACCESS = { userId: STUDENT_ID, isSiteAdmin: false, assignments: [], contexts: [], hasLearnerArtifacts: true };
const TEACHER_ACCESS = {
  userId: TEACHER_ID,
  isSiteAdmin: false,
  assignments: [],
  contexts: [
    {
      contextId: SYSTEM_CONTEXT_ID,
      contextLevel: 10,
      contextPath: '/1',
      capabilities: ['question:add'],
    },
  ],
  hasLearnerArtifacts: true,
};

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function stubFetch(profile: unknown, access: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (isGetSessionUrl(url)) return baSessionResponse({ id: (profile as { id: string }).id });
      if (url === '/api/v1/users/me') return jsonResponse(profile);
      if (url === '/api/v1/users/me/capabilities') return jsonResponse(access);
      if (url === '/api/v1/courses') return jsonResponse([]);
      if (url === '/api/v1/auth/refresh') return jsonResponse({}, 401);
      return jsonResponse({}, 404);
    }),
  );
}

const PROBE_PATHS = ['courses', 'classes', 'questions', 'questions/stimuli', 'exams'];

/** Tiền tố đích của mọi bề mặt đã hoãn: sidebar của bản phát hành không được chứa link nào trỏ vào chúng. */
const HIDDEN_HREF_PREFIXES = [
  '/calendar',
  '/messages',
  '/notifications',
  '/matrices',
  '/system-bank',
  '/admin',
  '/badges',
  '/learning-plan',
  '/guardian',
  '/curriculum',
  '/content-bank',
  '/reports',
  '/insights',
  '/backup',
  '/privacy',
  '/health',
  '/search',
  '/tokens',
];

function renderShell(initialPath = '/') {
  const router = createMemoryRouter(
    [
      {
        element: <App />,
        children: [
          {
            element: <Shell />,
            children: [
              { index: true, element: <div data-testid="probe-index" /> },
              ...PROBE_PATHS.map((path) => ({
                path,
                element: <div data-testid={`probe-${path.replaceAll('/', '-')}`} />,
              })),
            ],
          },
        ],
      },
    ],
    { initialEntries: [initialPath] },
  );
  return render(<RouterProvider router={router} />);
}

function sidebarRoot(container: HTMLElement): HTMLElement {
  const root = container.querySelector('[data-slot="sidebar"]');
  if (!root) throw new Error('không thấy sidebar');
  return root as HTMLElement;
}

beforeAll(() => {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
  document.cookie = 'sidebar_state=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  queryClient.clear();
});

describe('cây điều hướng sidebar — bản phát hành 07/11', () => {
  it('quản trị site: thấy 3 mục trực tiếp + 2 mục ngân hàng, và không thấy bất kỳ bề mặt đã hoãn nào', async () => {
    stubFetch(profileOf(ADMIN_ID, 'admin'), ADMIN_ACCESS);
    const { container } = renderShell();

    for (const name of ['Tổng quan', 'Khoá học', 'Lớp']) {
      expect(await screen.findByRole('link', { name: new RegExp(name) })).toBeInTheDocument();
    }
    for (const name of ['Ngân hàng câu hỏi', 'Ngân hàng đề']) {
      expect(await screen.findByRole('link', { name })).toBeInTheDocument();
    }
    // Nhóm ngân hàng đóng mặc định: mục con chưa hiện.
    expect(screen.queryByRole('link', { name: 'Ngữ liệu' })).not.toBeInTheDocument();

    for (const name of [
      'Lịch',
      'Tin nhắn',
      'Thông báo',
      'Tìm kiếm',
      'Ngân hàng ma trận',
      'Ngân hàng hệ thống',
      'Phụ huynh',
      'Liên kết phụ huynh',
      'Huy hiệu',
      'Kế hoạch học tập',
      'Người dùng',
      'Trung tâm',
      'Vai trò',
      'Quyền',
      'Cấu hình site',
      'Báo cáo',
      'Sao lưu',
    ]) {
      expect(screen.queryByRole('link', { name: new RegExp(name) })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: new RegExp(name) })).not.toBeInTheDocument();
    }
    const sidebar = sidebarRoot(container);
    for (const anchor of sidebar.querySelectorAll('a[href]')) {
      const href = anchor.getAttribute('href')!;
      expect(HIDDEN_HREF_PREFIXES.some((prefix) => href === prefix || href.startsWith(`${prefix}/`))).toBe(false);
    }
  });

  it('mở thu/mở của "Ngân hàng câu hỏi" thấy mục con "Ngữ liệu" đúng href theo path riêng (chuẩn #221)', async () => {
    stubFetch(profileOf(ADMIN_ID, 'admin'), ADMIN_ACCESS);
    renderShell();

    expect(await screen.findByRole('link', { name: 'Ngân hàng câu hỏi' })).toHaveAttribute('href', '/questions');
    expect(screen.queryByRole('link', { name: 'Ngữ liệu' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Thu/mở Ngân hàng câu hỏi' }));
    expect(await screen.findByRole('link', { name: 'Ngữ liệu' })).toHaveAttribute('href', '/questions/stimuli');
  });

  it('chevron của "Ngân hàng câu hỏi" nằm trong cùng bề mặt nút với nhãn; nhóm chưa có mục con không có nút thu/mở', async () => {
    stubFetch(profileOf(ADMIN_ID, 'admin'), ADMIN_ACCESS);
    renderShell();

    const label = await screen.findByRole('link', { name: 'Ngân hàng câu hỏi' });
    const toggle = screen.getByRole('button', { name: 'Thu/mở Ngân hàng câu hỏi' });
    // Một nút liền: nhãn (link) và chevron (nút thu/mở) chung đúng một sidebar-menu-item — chevron gắn ở cuối cùng bề
    // mặt nút của nhãn, không còn là hộp trigger 32px (h-8 w-8) tách riêng đứng cạnh (chốt sổ quyết định [26/10 23:16],
    // issue #70). Lưu ý: li của nhóm collapse mang data-slot="collapsible" (gốc Collapsible ghi đè data-slot của
    // SidebarMenuItem) nên neo theo data-sidebar="menu-item" mới ổn định.
    const item = label.closest('li[data-sidebar="menu-item"]');
    expect(item).not.toBeNull();
    expect(item!.contains(toggle)).toBe(true);
    expect(toggle.className).not.toMatch(/\bh-8\b/);
    expect(toggle.className).not.toMatch(/\bw-8\b/);
    expect(screen.queryByRole('button', { name: 'Thu/mở Ngân hàng đề' })).not.toBeInTheDocument();
  });

  it('bấm thân "Ngân hàng câu hỏi" điều hướng tới trang /questions', async () => {
    stubFetch(profileOf(ADMIN_ID, 'admin'), ADMIN_ACCESS);
    renderShell();

    await userEvent.click(await screen.findByRole('link', { name: 'Ngân hàng câu hỏi' }));
    expect(await screen.findByTestId('probe-questions')).toBeInTheDocument();
  });

  it('sidebar không còn đường nào tới khu hệ thống — đích /system-bank đã gỡ khỏi bản phát hành', async () => {
    stubFetch(profileOf(ADMIN_ID, 'admin'), ADMIN_ACCESS);
    const { container } = renderShell();

    expect(await screen.findByRole('link', { name: 'Ngân hàng câu hỏi' })).toBeInTheDocument();
    const sidebar = sidebarRoot(container);
    expect(sidebar.querySelector('a[href^="/system-bank"]')).toBeNull();
    expect(within(sidebar).queryByText('Ngân hàng hệ thống')).not.toBeInTheDocument();
  });
});

describe('cây điều hướng sidebar — trạng thái nhóm', () => {
  it('lưu nhóm đang mở vào localStorage và giữ nguyên sau khi dựng lại cây (F5)', async () => {
    stubFetch(profileOf(ADMIN_ID, 'admin'), ADMIN_ACCESS);
    const first = renderShell();

    await userEvent.click(await screen.findByRole('button', { name: 'Thu/mở Ngân hàng câu hỏi' }));
    expect(await screen.findByRole('link', { name: 'Ngữ liệu' })).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('lms:sidebar:open-groups') ?? '[]')).toEqual(['my-questions']);

    first.unmount();
    renderShell();
    expect(await screen.findByRole('link', { name: 'Ngữ liệu' })).toBeInTheDocument();
  });

  it('đang ở /exams: mục top level "Ngân hàng đề" là link active, "Ngân hàng câu hỏi" thì không', async () => {
    stubFetch(profileOf(TEACHER_ID, 'teacher1'), TEACHER_ACCESS);
    renderShell('/exams');

    const child = await screen.findByRole('link', { name: 'Ngân hàng đề' });
    expect(child).toHaveAttribute('href', '/exams');
    expect(child.hasAttribute('data-active')).toBe(true);
    expect(screen.getByRole('link', { name: 'Ngân hàng câu hỏi' }).hasAttribute('data-active')).toBe(false);
  });

  it('đang ở /questions: "Ngân hàng câu hỏi" active và collapse tự mở thấy "Ngữ liệu" chưa active', async () => {
    stubFetch(profileOf(TEACHER_ID, 'teacher1'), TEACHER_ACCESS);
    renderShell('/questions');

    const parent = await screen.findByRole('link', { name: 'Ngân hàng câu hỏi' });
    expect(parent.hasAttribute('data-active')).toBe(true);
    const child = await screen.findByRole('link', { name: 'Ngữ liệu' });
    expect(child).toHaveAttribute('href', '/questions/stimuli');
    expect(child.hasAttribute('data-active')).toBe(false);
  });

  it('đang ở /questions/stimuli: cả mục cha "Ngân hàng câu hỏi" lẫn mục con "Ngữ liệu" cùng active', async () => {
    stubFetch(profileOf(TEACHER_ID, 'teacher1'), TEACHER_ACCESS);
    renderShell('/questions/stimuli');

    const parent = await screen.findByRole('link', { name: 'Ngân hàng câu hỏi' });
    expect(parent.hasAttribute('data-active')).toBe(true);
    const child = await screen.findByRole('link', { name: 'Ngữ liệu' });
    expect(child).toHaveAttribute('href', '/questions/stimuli');
    expect(child.hasAttribute('data-active')).toBe(true);
  });
});

describe('cây điều hướng sidebar — lọc theo quyền', () => {
  it('học viên: chỉ thấy 3 mục trực tiếp, không thấy mục ngân hàng nào', async () => {
    stubFetch(profileOf(STUDENT_ID, 'student1'), STUDENT_ACCESS);
    renderShell();

    expect(await screen.findByRole('link', { name: 'Tổng quan' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Khoá học' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Lớp học' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Ngân hàng câu hỏi' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Ngân hàng đề' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Ngữ liệu' })).not.toBeInTheDocument();
  });

  it('giáo viên có capability câu hỏi: 2 mục ngân hàng ở cấp cao nhất, đúng href path riêng từng khu vực (chuẩn #221)', async () => {
    stubFetch(profileOf(TEACHER_ID, 'teacher1'), TEACHER_ACCESS);
    renderShell();

    expect(await screen.findByRole('link', { name: 'Ngân hàng câu hỏi' })).toHaveAttribute('href', '/questions');
    expect(screen.getByRole('link', { name: 'Ngân hàng đề' })).toHaveAttribute('href', '/exams');
    expect(screen.queryByRole('link', { name: 'Ngữ liệu' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Thu/mở Ngân hàng câu hỏi' }));
    expect(await screen.findByRole('link', { name: 'Ngữ liệu' })).toHaveAttribute('href', '/questions/stimuli');
    expect(screen.queryByRole('link', { name: 'Ngân hàng ma trận' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Câu hỏi hệ thống' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Đề hệ thống' })).not.toBeInTheDocument();
  });
});

describe('cây điều hướng sidebar — chế độ rail icon', () => {
  it('cookie sidebar_state=false → rail icon có SidebarRail và các link trực tiếp vẫn điều hướng được', async () => {
    document.cookie = 'sidebar_state=false; path=/';
    stubFetch(profileOf(ADMIN_ID, 'admin'), ADMIN_ACCESS);
    const { container } = renderShell();

    expect(await screen.findByRole('link', { name: 'Khoá học' })).toBeInTheDocument();
    const root = sidebarRoot(container);
    expect(root).toHaveAttribute('data-state', 'collapsed');
    expect(root).toHaveAttribute('data-collapsible', 'icon');
    expect(container.querySelector('[data-slot="sidebar-rail"]')).not.toBeNull();

    await userEvent.click(screen.getByRole('link', { name: 'Khoá học' }));
    expect(await screen.findByTestId('probe-courses')).toBeInTheDocument();
  });
});
