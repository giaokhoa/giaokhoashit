import { describe, expect, it } from 'vitest';
import {
  identityFromTokenClaims,
  isMemberOfTenant,
  roleLabelsOf,
  tenantAliasForHostname,
  tenantDisplayName,
  tenantStanding,
  type TenantIdentity,
} from './kc-tenant';

/**
 * Kiểm thử các quy tắc thuần của trang tenant Keycloak (issue #38):
 * phân giải tenant theo hostname, ánh xạ claim của access token, đánh
 * giá tư cách thành viên/khách theo claim organization, và nhãn vai trò
 * hiển thị.
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

describe('identityFromTokenClaims', () => {
  it('ánh xạ các claim danh tính, vai trò realm và organization dạng mảng của access token', () => {
    expect(
      identityFromTokenClaims({
        sub: 'sub-1',
        preferred_username: 'gv1',
        name: 'Giáo viên Trung tâm 1',
        email: 'gv1@trungtam1.example',
        realm_access: { roles: ['tenant-teacher'] },
        organization: ['trung-tam-1'],
      }),
    ).toEqual(identity({}));
  });

  it('ánh xạ organization dạng object theo alias và bỏ qua claim không đúng hình dạng', () => {
    expect(
      identityFromTokenClaims({
        sub: 'sub-2',
        realm_access: { roles: 'khong-phai-danh-sach' },
        organization: { 'trung-tam-2': { id: 'org-2' } },
      }),
    ).toEqual({
      sub: 'sub-2',
      preferredUsername: null,
      name: null,
      email: null,
      realmRoles: [],
      organizations: ['trung-tam-2'],
    });
  });

  it('trả null khi token chưa có hoặc thiếu claim sub bắt buộc', () => {
    expect(identityFromTokenClaims(undefined)).toBeNull();
    expect(identityFromTokenClaims({ preferred_username: 'gv1' })).toBeNull();
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

describe('tenantStanding', () => {
  it('gán tư cách thành viên khi organization khớp tenant của trang', () => {
    expect(tenantStanding(identity({}), 'trung-tam-1')).toBe('member');
  });

  it('gán tư cách khách khi tài khoản đã đăng nhập thuộc organization khác', () => {
    expect(tenantStanding(identity({}), 'trung-tam-2')).toBe('guest');
  });

  it('gán tư cách khách khi tài khoản đã đăng nhập chưa thuộc organization nào', () => {
    expect(tenantStanding(identity({ organizations: [] }), 'trung-tam-1')).toBe('guest');
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
