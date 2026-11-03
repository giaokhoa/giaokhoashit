/**
 * Quy tắc tenant cho luồng đăng nhập Keycloak của hai tenant nghiệm thu
 * (issue #38).
 *
 * File này giữ phần THUẦN, dùng được cả phía server (loader) lẫn phía
 * client (component): ánh xạ hostname → tenant, hình dạng danh tính mà
 * endpoint `GET /api/me` của `apps/server` trả về, và phép đánh giá tư
 * cách của người đăng nhập đối với tenant của trang đang mở.
 *
 * Mô hình danh tính (theo chương Organizations của Keycloak): một realm
 * duy nhất, trung tâm là Organization trong realm; hostname CHỈ cho biết
 * người dùng đang đứng ở trang của tenant nào — tư cách và quyền của tài
 * khoản chỉ tồn tại trong Organization mà tài khoản là thành viên, thể
 * hiện qua claim `organization` trong access token. Vì vậy phép đánh giá
 * ở đây đối chiếu đúng danh sách organization của danh tính với tenant
 * của trang: khớp thì là thành viên của tenant đó, lệch thì tài khoản
 * không thuộc và không có quyền tại tenant của trang.
 */

/**
 * Hai hostname tenant nghiệm thu → alias Organization tương ứng trong
 * realm `giaokhoa`. Hostname ngoài bảng này không phải trang tenant:
 * trang gốc giữ hành vi hiện tại của app.
 */
const TENANT_ALIAS_BY_HOSTNAME: Readonly<Record<string, string>> = {
  'trung-tam-1.fuckr.net': 'trung-tam-1',
  'trung-tam-2.fuckr.net': 'trung-tam-2',
};

/**
 * Danh tính người đăng nhập do `apps/server` trả về từ `GET /api/me`:
 * ánh xạ thẳng các claim của access token đã được server kiểm chứng
 * (sub, preferred_username, name, email, realm_access.roles và các alias
 * trong claim organization). Web không tự giải mã token — mọi quyết
 * định hiển thị theo tư cách đều dựa trên đáp ứng này.
 */
export interface TenantIdentity {
  sub: string;
  preferredUsername: string | null;
  name: string | null;
  email: string | null;
  realmRoles: string[];
  organizations: string[];
}

/**
 * Phân giải tenant của trang từ hostname của request.
 *
 * @param hostname - Hostname đã tách cổng (ví dụ `trung-tam-1.fuckr.net`).
 * @returns Alias của tenant khi hostname thuộc hai tenant nghiệm thu;
 *   `null` với mọi hostname khác.
 */
export function tenantAliasForHostname(hostname: string): string | null {
  return TENANT_ALIAS_BY_HOSTNAME[hostname.toLowerCase()] ?? null;
}

/**
 * Tên hiển thị của tenant từ alias: `trung-tam-1` → `Trung tâm 1`.
 *
 * @param alias - Alias của Organization (định danh kỹ thuật trong realm).
 * @returns Tên hiển thị cho người dùng; alias lạ giữ nguyên văn.
 */
export function tenantDisplayName(alias: string): string {
  const match = /^trung-tam-(\d+)$/.exec(alias);
  return match ? `Trung tâm ${match[1]}` : alias;
}

/**
 * Người đăng nhập có là thành viên của tenant của trang hay không: đúng
 * khi claim `organization` (qua đáp ứng của `apps/server`) chứa alias
 * của tenant đó. Đây là điều kiện duy nhất để gán vai trò của tenant
 * cho tài khoản trên trang.
 *
 * @param identity - Danh tính từ `apps/server`.
 * @param tenantAlias - Alias của tenant của trang đang mở.
 * @returns `true` khi tài khoản là thành viên của tenant của trang.
 */
export function isMemberOfTenant(identity: TenantIdentity, tenantAlias: string): boolean {
  return identity.organizations.includes(tenantAlias);
}

/**
 * Vai trò mặc định Keycloak tự gắn cho mọi tài khoản (gói
 * `default-roles-<realm>`, `offline_access`, `uma_authorization`): có
 * trong `realm_access.roles` của mọi access token nhưng không phải vai
 * trò nghiệp vụ — tầng hiển thị lọc bỏ để chỉ còn vai trò thật của
 * người dùng trong tenant.
 */
function isDefaultRealmRole(role: string): boolean {
  return role === 'offline_access' || role === 'uma_authorization' || role.startsWith('default-roles-');
}

/** Nhãn hiển thị tiếng Việt cho các vai trò realm nghiệp vụ của LMS. */
const ROLE_LABELS: Readonly<Record<string, string>> = {
  'tenant-admin': 'Quản trị trung tâm',
  'tenant-teacher': 'Giáo viên',
  'tenant-member': 'Học sinh',
  'platform-admin': 'Quản trị nền tảng',
};

/**
 * Danh sách vai trò nghiệp vụ của danh tính để hiển thị: đã lọc vai trò
 * mặc định của Keycloak và đổi sang nhãn tiếng Việt; vai trò chưa có
 * nhãn giữ nguyên tên kỹ thuật để không giấu thông tin.
 *
 * @param identity - Danh tính từ `apps/server`.
 * @returns Các nhãn vai trò theo thứ tự trong token.
 */
export function roleLabelsOf(identity: TenantIdentity): string[] {
  return identity.realmRoles.filter((role) => !isDefaultRealmRole(role)).map((role) => ROLE_LABELS[role] ?? role);
}
