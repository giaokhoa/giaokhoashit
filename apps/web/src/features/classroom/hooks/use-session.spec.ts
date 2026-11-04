/**
 * Spec của phép rút vai tại trang từ claim của token (issue #102).
 *
 * Hành vi được kiểm chứng là bất biến của mô hình quyền: vai của trung tâm chỉ đọc ở
 * `organization[<alias>].realm_access.roles` của đúng trung tâm của trang; vai realm ở tầng trên cùng không bao
 * giờ thành vai của trung tâm (chỉ `platform-admin` là vai toàn cục được cộng thêm); claim ở dạng mảng alias
 * không mang vai.
 */
import { describe, expect, it } from 'vitest';
import { pageRolesOf, roleLabelsOf } from './use-session';

describe('pageRolesOf (vai tại trang đọc từ claim organization)', () => {
  it('đọc vai ở organization[<alias>].realm_access.roles của đúng trung tâm của trang', () => {
    const tokenParsed = {
      organization: {
        'trung-tam-1': { id: 'org-1', realm_access: { roles: ['tenant-teacher'] } },
        'trung-tam-2': { id: 'org-2', realm_access: { roles: ['tenant-admin'] } },
      },
    };
    expect(pageRolesOf(tokenParsed, 'trung-tam-1', [])).toEqual(['tenant-teacher']);
    expect(pageRolesOf(tokenParsed, 'trung-tam-2', [])).toEqual(['tenant-admin']);
  });

  it('không lấy vai realm ở tầng trên cùng làm vai của trung tâm', () => {
    const tokenParsed = {
      realm_access: { roles: ['tenant-admin', 'tenant-teacher'] },
      organization: { 'trung-tam-1': { id: 'org-1' } },
    };
    expect(pageRolesOf(tokenParsed, 'trung-tam-1', ['tenant-admin'])).toEqual([]);
  });

  it('claim organization dạng mảng alias không mang vai theo trung tâm', () => {
    const tokenParsed = { organization: ['trung-tam-1'] };
    expect(pageRolesOf(tokenParsed, 'trung-tam-1', [])).toEqual([]);
  });

  it('platform-admin trong vai realm được cộng thêm ở mọi trang', () => {
    const tokenParsed = { organization: { 'trung-tam-1': { realm_access: { roles: ['tenant-member'] } } } };
    expect(pageRolesOf(tokenParsed, 'trung-tam-1', ['platform-admin', 'tenant-teacher'])).toEqual([
      'tenant-member',
      'platform-admin',
    ]);
  });

  it('chưa đăng nhập hoặc ở trang nền tảng thì không có vai theo trung tâm', () => {
    expect(pageRolesOf(undefined, 'trung-tam-1', [])).toEqual([]);
    expect(pageRolesOf({ organization: {} }, null, [])).toEqual([]);
  });
});

describe('roleLabelsOf (nhãn hiển thị của vai)', () => {
  it('ánh xạ các vai đã biết sang nhãn tiếng Việt và bỏ qua vai mặc định của Keycloak', () => {
    expect(roleLabelsOf(['tenant-teacher', 'default-roles-giaokhoa', 'platform-admin'])).toEqual([
      'Giáo viên',
      'Quản trị nền tảng',
    ]);
  });
});
