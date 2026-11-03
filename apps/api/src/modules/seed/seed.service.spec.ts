import { describe, expect, it, vi } from 'vitest';
import { SeedService } from './seed.service';

describe('SeedService platform operator migration cleanup', () => {
  it('giữ operator đã cấu hình trong siteadmins và dọn membership tenant-zero legacy mà không xoá username', async () => {
    const admin = { id: 'admin-id', username: 'admin' };
    const operator = { id: 'operator-id', username: 'platform-operator' };
    const zero = { id: 'tenant-zero', slug: 'trang-chinh', name: 'Trang chính', plan: 'free' };
    const school = { id: 'tenant-school', slug: 'truong-mac-dinh', name: 'Trường mặc định', plan: 'free' };

    const roles = {
      seedDefaults: vi.fn(async () => undefined),
      getRoleByShortname: vi.fn(async () => undefined),
    };
    const contexts = {};
    const evaluator = {
      listSiteAdmins: vi.fn(async () => ['admin-id']),
      setSiteAdmins: vi.fn(async () => undefined),
    };
    const users = {
      ensureGuestUser: vi.fn(async () => undefined),
      getByIdOrUndefined: vi.fn(async () => admin),
      getByUsername: vi.fn(async (username: string) => username === operator.username ? operator : undefined),
    };
    const config = {
      get: vi.fn((key: string) => ({
        ADMIN_USERNAME: 'admin',
        PLATFORM_ADMIN_USERNAME: operator.username,
      })[key]),
    };
    const tenants = {
      ensurePlatformTenant: vi.fn(async () => zero),
      ensureDefaultSchoolTenant: vi.fn(async () => school),
      ensureActiveMembership: vi.fn(async () => undefined),
      removeMembership: vi.fn(async () => undefined),
    };
    const siteConfig = {
      getValue: vi.fn(async () => 'Trường mặc định'),
    };
    const controlPlane = { isConfigured: false };

    const service = new SeedService(
      roles as never,
      contexts as never,
      evaluator as never,
      users as never,
      config as never,
      tenants as never,
      siteConfig as never,
      controlPlane as never,
    );

    await service.onApplicationBootstrap();

    expect(users.getByUsername).toHaveBeenCalledWith('platform-operator');
    expect(evaluator.setSiteAdmins).toHaveBeenCalledWith(['admin-id', 'operator-id']);
    expect(tenants.removeMembership).toHaveBeenCalledWith('tenant-zero', 'operator-id');
    expect(tenants.ensureActiveMembership).toHaveBeenCalledTimes(2);
    expect(tenants.ensureActiveMembership).not.toHaveBeenCalledWith('tenant-zero', 'operator-id', 'admin');
  });
});
