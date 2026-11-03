import { cleanup, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { TenantIdentity } from '../kc-tenant';
import TenantPage from './tenant';

/**
 * Kiểm thử hiển thị sau đăng nhập của trang tenant Keycloak (issue #38): adapter được mock tại biên, trang phải
 * hiện đúng tên tài khoản và đúng trung tâm theo ngữ cảnh hostname — thành viên thấy vai trò của mình, tài khoản
 * thuộc trung tâm khác thấy tư cách khách kèm trung tâm thật của tài khoản.
 */
const keycloakMock = vi.hoisted(() => ({
  authenticated: true,
  identity: null as TenantIdentity | null,
}));

vi.mock('../kc-client', () => ({
  initializeKeycloak: vi.fn(async () => keycloakMock.authenticated),
  currentTenantIdentity: vi.fn(() => keycloakMock.identity),
  fetchServerIdentity: vi.fn(async () => null),
  loginToTenant: vi.fn(),
  logoutFromTenant: vi.fn(),
}));

/**
 * Dựng danh tính đã chuẩn hoá từ token cho một trường hợp hiển thị.
 *
 * @param overrides - Phần danh tính riêng của trường hợp.
 * @returns Danh tính đầy đủ để trang render.
 */
function identity(overrides: Partial<TenantIdentity>): TenantIdentity {
  return {
    sub: 'sub-1',
    preferredUsername: 'gv-trung-tam-1',
    name: 'Giáo viên Trung tâm 1',
    email: 'gv.trungtam1@fuckr.net',
    realmRoles: ['tenant-teacher'],
    organizations: ['trung-tam-1'],
    ...overrides,
  };
}

/**
 * Render trang tenant tại alias cho trước với danh tính mock hiện tại.
 *
 * @param tenantAlias - Alias của trung tâm theo ngữ cảnh trang.
 * @returns Kết quả render của Testing Library.
 */
function renderTenantPage(tenantAlias: string) {
  const router = createMemoryRouter([{ path: '/tenant', loader: () => ({ tenantAlias }), Component: TenantPage }], {
    initialEntries: ['/tenant'],
  });
  return render(<RouterProvider router={router} />);
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  keycloakMock.authenticated = true;
  keycloakMock.identity = null;
});

describe('Trang tenant sau đăng nhập bằng Keycloak adapter', () => {
  it('hiện đúng tên và vai trò giáo viên tại Trung tâm 1', async () => {
    keycloakMock.identity = identity({});
    renderTenantPage('trung-tam-1');

    expect(await screen.findByText('Giáo viên Trung tâm 1')).toBeInTheDocument();
    expect(screen.getByText('Trung tâm 1', { selector: '[data-slot="card-title"]' })).toBeInTheDocument();
    expect(screen.getByText(/thành viên của Trung tâm 1/)).toBeInTheDocument();
    expect(screen.getByText(/Vai trò tại Trung tâm 1:/)).toBeInTheDocument();
    expect(screen.getByText('Giáo viên')).toBeInTheDocument();
  });

  it('hiện đúng tên và vai trò học viên tại Trung tâm 1', async () => {
    keycloakMock.identity = identity({
      sub: 'sub-2',
      preferredUsername: 'hs-trung-tam-1',
      name: 'Học viên Trung tâm 1',
      email: null,
      realmRoles: ['tenant-member'],
    });
    renderTenantPage('trung-tam-1');

    expect(await screen.findByText('Học viên Trung tâm 1')).toBeInTheDocument();
    expect(screen.getByText(/Vai trò tại Trung tâm 1:/)).toBeInTheDocument();
    expect(screen.getByText('Học sinh')).toBeInTheDocument();
  });

  it('hiện đúng tên và vai trò giáo viên tại Trung tâm 2', async () => {
    keycloakMock.identity = identity({
      sub: 'sub-3',
      preferredUsername: 'gv-trung-tam-2',
      name: 'Giáo viên Trung tâm 2',
      email: 'gv.trungtam2@fuckr.net',
      organizations: ['trung-tam-2'],
    });
    renderTenantPage('trung-tam-2');

    expect(await screen.findByText('Giáo viên Trung tâm 2')).toBeInTheDocument();
    expect(screen.getByText('Trung tâm 2', { selector: '[data-slot="card-title"]' })).toBeInTheDocument();
    expect(screen.getByText(/thành viên của Trung tâm 2/)).toBeInTheDocument();
    expect(screen.getByText(/Vai trò tại Trung tâm 2:/)).toBeInTheDocument();
    expect(screen.getByText('Giáo viên')).toBeInTheDocument();
  });

  it('hiện đúng tên và vai trò học viên tại Trung tâm 2', async () => {
    keycloakMock.identity = identity({
      sub: 'sub-4',
      preferredUsername: 'hs-trung-tam-2',
      name: 'Học viên Trung tâm 2',
      email: null,
      realmRoles: ['tenant-member'],
      organizations: ['trung-tam-2'],
    });
    renderTenantPage('trung-tam-2');

    expect(await screen.findByText('Học viên Trung tâm 2')).toBeInTheDocument();
    expect(screen.getByText(/Vai trò tại Trung tâm 2:/)).toBeInTheDocument();
    expect(screen.getByText('Học sinh')).toBeInTheDocument();
  });

  it('hiện tài khoản Trung tâm 1 là khách tại trang Trung tâm 2, kèm trung tâm thật của tài khoản', async () => {
    keycloakMock.identity = identity({});
    renderTenantPage('trung-tam-2');

    expect(await screen.findByText('Giáo viên Trung tâm 1')).toBeInTheDocument();
    expect(screen.getByText(/khách của Trung tâm 2/)).toBeInTheDocument();
    expect(screen.getByText('Tài khoản thuộc Trung tâm 1.')).toBeInTheDocument();
    expect(screen.queryByText(/thành viên của Trung tâm 2/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Vai trò tại Trung tâm 2/)).not.toBeInTheDocument();
  });
});
