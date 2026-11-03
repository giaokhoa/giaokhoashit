import { describe, expect, it } from 'vitest';
import {
  isMemberOfTenant,
  roleLabelsOf,
  tenantAliasForHostname,
  tenantDisplayName,
  type TenantIdentity,
} from './kc-tenant';

/**
 * Kiểm thử các quy tắc thuần của trang tenant Keycloak (issue #38):
 * phân giải tenant theo hostname, đánh giá tư cách thành viên theo
 * claim organization, và nhãn vai trò hiển thị.
 */

function identity(overrides: Partial<TenantIdentity>): TenantIdentity {
  return {
    sub: 'sub-1',
    preferredUsername: 'gv1',
    name: 'Giáo viên Trung tâm 1',
    email: 'gv1@trungtam1.example',
    realmRoles: ['tenant-teacher'],
    organizations: ['trung-tam-1'],
    ...overrides,
  };
}

describe('tenantAliasForHostname', () => {
  it('trả alias cho hai hostname tenant nghiệm thu', () => {
    expect(tenantAliasForHostname('trung-tam-1.fuckr.net')).toBe('trung-tam-1');
    expect(tenantAliasForHostname('trung-tam-2.fuckr.net')).toBe('trung-tam-2');
  });

  it('trả null cho hostname ngoài hai tenant nghiệm thu', () => {
    expect(tenantAliasForHostname('giaokhoa.fuckr.net')).toBeNull();
    expect(tenantAliasForHostname('localhost')).toBeNull();
  });
});

describe('tenantDisplayName', () => {
  it('đổi alias trung-tam-N thành tên hiển thị Trung tâm N', () => {
    expect(tenantDisplayName('trung-tam-1')).toBe('Trung tâm 1');
    expect(tenantDisplayName('trung-tam-2')).toBe('Trung tâm 2');
  });
});

describe('isMemberOfTenant', () => {
  it('đúng khi claim organization chứa tenant của trang', () => {
    expect(isMemberOfTenant(identity({}), 'trung-tam-1')).toBe(true);
  });

  it('sai khi tài khoản thuộc organization khác với tenant của trang', () => {
    expect(isMemberOfTenant(identity({}), 'trung-tam-2')).toBe(false);
  });

  it('sai khi tài khoản không thuộc organization nào', () => {
    expect(isMemberOfTenant(identity({ organizations: [] }), 'trung-tam-1')).toBe(false);
  });
});

describe('roleLabelsOf', () => {
  it('đổi vai trò nghiệp vụ sang nhãn tiếng Việt', () => {
    expect(roleLabelsOf(identity({ realmRoles: ['tenant-teacher'] }))).toEqual(['Giáo viên']);
    expect(roleLabelsOf(identity({ realmRoles: ['tenant-member'] }))).toEqual(['Học sinh']);
  });

  it('lọc bỏ các vai trò mặc định Keycloak tự gắn cho mọi tài khoản', () => {
    const roles = roleLabelsOf(
      identity({ realmRoles: ['default-roles-giaokhoa', 'offline_access', 'uma_authorization', 'tenant-member'] }),
    );
    expect(roles).toEqual(['Học sinh']);
  });
});
