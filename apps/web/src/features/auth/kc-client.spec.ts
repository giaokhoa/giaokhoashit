import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { TenantIdentity } from './kc-tenant';

/**
 * Kiểm thử hợp đồng của adapter Keycloak phía trình duyệt (issue #38): thư viện `keycloak-js` được mock tại biên,
 * spec ghim đúng cấu hình client công khai, tuỳ chọn khởi tạo theo tài liệu của adapter (check-sso im lặng, PKCE
 * S256, scope organization), phép đọc danh tính từ `tokenParsed`, và lời gọi `/api/me` có làm mới token trước khi
 * gửi Bearer.
 */
const adapterMock = vi.hoisted(() => ({
  config: null as unknown,
  authenticated: true,
  token: 'access-token',
  tokenParsed: {
    sub: 'sub-1',
    preferred_username: 'gv-trung-tam-1',
    name: 'Giáo viên Trung tâm 1',
    email: 'gv.trungtam1@fuckr.net',
    realm_access: { roles: ['tenant-teacher'] },
    organization: ['trung-tam-1'],
  } as Record<string, unknown>,
  init: vi.fn(async () => true),
  updateToken: vi.fn(async () => true),
  login: vi.fn(async () => undefined),
  logout: vi.fn(async () => undefined),
}));

vi.mock('keycloak-js', () => ({
  default: class MockKeycloak {
    constructor(config: unknown) {
      adapterMock.config = config;
      return adapterMock as never;
    }
  },
}));

const IDENTITY: TenantIdentity = {
  sub: 'sub-1',
  preferredUsername: 'gv-trung-tam-1',
  name: 'Giáo viên Trung tâm 1',
  email: 'gv.trungtam1@fuckr.net',
  realmRoles: ['tenant-teacher'],
  organizations: ['trung-tam-1'],
};

beforeEach(() => {
  vi.resetModules();
  adapterMock.config = null;
  adapterMock.init.mockClear();
  adapterMock.updateToken.mockClear();
  adapterMock.login.mockClear();
  adapterMock.logout.mockClear();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('initializeKeycloak', () => {
  it('tạo adapter bằng client công khai lms-web của realm giaokhoa và khởi tạo một lần với check-sso im lặng, PKCE S256, scope organization', async () => {
    const mod = await import('./kc-client');
    await expect(mod.initializeKeycloak()).resolves.toBe(true);
    await expect(mod.initializeKeycloak()).resolves.toBe(true);
    expect(adapterMock.config).toEqual({ url: 'https://auth.fuckr.net', realm: 'giaokhoa', clientId: 'lms-web' });
    expect(adapterMock.init).toHaveBeenCalledTimes(1);
    expect(adapterMock.init).toHaveBeenCalledWith({
      onLoad: 'check-sso',
      silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
      pkceMethod: 'S256',
      scope: 'organization',
    });
  });

  it('đăng nhập và đăng xuất đều quay về /tenant của origin hiện tại', async () => {
    const mod = await import('./kc-client');
    await mod.loginToTenant();
    await mod.logoutFromTenant();
    expect(adapterMock.login).toHaveBeenCalledWith({ redirectUri: `${window.location.origin}/tenant` });
    expect(adapterMock.logout).toHaveBeenCalledWith({ redirectUri: `${window.location.origin}/tenant` });
  });
});

describe('đọc danh tính và gọi server', () => {
  it('ánh xạ tokenParsed thành danh tính đã chuẩn hoá', async () => {
    const mod = await import('./kc-client');
    expect(mod.currentTenantIdentity()).toEqual(IDENTITY);
  });

  it('làm mới token bằng updateToken(30) trước khi gọi /api/me kèm header Authorization Bearer', async () => {
    const fetchMock = vi.fn(
      async () =>
        new Response(JSON.stringify(IDENTITY), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const mod = await import('./kc-client');
    await expect(mod.fetchServerIdentity()).resolves.toEqual(IDENTITY);
    expect(adapterMock.updateToken).toHaveBeenCalledWith(30);
    expect(fetchMock).toHaveBeenCalledWith('/api/me', { headers: { Authorization: 'Bearer access-token' } });
  });
});
