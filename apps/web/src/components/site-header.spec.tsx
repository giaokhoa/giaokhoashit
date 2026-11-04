import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, Outlet, RouterProvider, useLocation } from 'react-router';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { SidebarProvider } from '@lms/ui/components/sidebar';
import { TestProviders } from '#test/providers';
import { SiteHeader } from '#components/site-header';

/**
 * Test cho header của bản phát hành 07/11 (issue #79). Header chỉ còn vùng tiêu đề (nút thu/mở sidebar + tiêu đề
 * trang theo route):
 *
 * - Tiêu đề tra theo tiền tố path trong catalog i18n: `/` là "Tổng quan", họ `/courses` là "Khoá học"; path không
 *   có trong catalog thì rơi về "LMS".
 * - Ô tìm kiếm toàn cục ở giữa header, chuông thông báo và bảng lệnh Cmd+K thuộc các tính năng đã hoãn (tìm kiếm
 *   toàn hệ thống, thông báo) nên đã gỡ: không còn ô nhập nào trong header, không còn nút chuông, và Ctrl+K không
 *   mở bề mặt nào. Header tự nó không gọi API nào khi hiển thị.
 *
 * Dựng SiteHeader thật trong SidebarProvider + router nhớ giống shell thật.
 */

function CurrentPath() {
  const location = useLocation();
  return <p data-testid="current-path">{`${location.pathname}${location.search}`}</p>;
}

function Layout() {
  return (
    <SidebarProvider>
      <SiteHeader />
      <CurrentPath />
      <Outlet />
    </SidebarProvider>
  );
}

function renderHeader(initialPath = '/') {
  const router = createMemoryRouter(
    [
      {
        element: <Layout />,
        children: [
          { index: true, element: <p>Trang chủ thử</p> },
          { path: 'courses', element: <p>Trang khoá học thử</p> },
          { path: 'other', element: <p>Trang lạ thử</p> },
        ],
      },
    ],
    { initialEntries: [initialPath] },
  );
  render(
    <TestProviders>
      <RouterProvider router={router} />
    </TestProviders>,
  );
  return router;
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
});

describe('site-header — bản phát hành 07/11', () => {
  it('ở trang chủ: tiêu đề là "Tổng quan" và header không còn ô tìm kiếm, chuông thông báo hay bảng lệnh', async () => {
    renderHeader('/');
    expect(screen.getByRole('heading', { name: 'Tổng quan' })).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('search')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /thông báo/i })).not.toBeInTheDocument();
    const user = userEvent.setup();
    await user.keyboard('{Control>}k{/Control}');
    expect(screen.queryByTestId('command-palette')).not.toBeInTheDocument();
  });

  it('ở họ /courses: tiêu đề là "Khoá học"', () => {
    renderHeader('/courses');
    expect(screen.getByRole('heading', { name: 'Khoá học' })).toBeInTheDocument();
  });

  it('ở path lạ: tiêu đề rơi về "LMS"', () => {
    renderHeader('/other');
    expect(screen.getByRole('heading', { name: 'LMS' })).toBeInTheDocument();
  });
});
