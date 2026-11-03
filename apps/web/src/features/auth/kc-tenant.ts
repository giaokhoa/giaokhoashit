/**
 * Quy tắc tenant cho luồng đăng nhập Keycloak của hai tenant nghiệm thu
 * (issue #38).
 *
 * File này giữ phần THUẦN phía client: hình dạng danh tính đã chuẩn hoá của trang tenant, phép ánh xạ claim từ
 * access token do Keycloak JavaScript adapter giữ trong bộ nhớ, và phép đánh giá tư cách của người đăng nhập đối
 * với tenant của trang đang mở. Riêng ánh xạ hostname → tenant nằm ở tầng dùng chung (`#lib/kc-tenant-hosts`) vì
 * feature dashboard cũng cần nó ở loader trang gốc; file này xuất lại hàm đó để các module của feature auth dùng
 * một chỗ.
 *
 * Mô hình danh tính (theo chương Organizations của Keycloak): một realm duy nhất, trung tâm là Organization trong
 * realm; hostname CHỈ cho biết người dùng đang đứng ở trang của tenant nào — tư cách thành viên chỉ tồn tại trong
 * Organization mà tài khoản là thành viên, thể hiện qua claim `organization` trong access token. Vì vậy phép đánh
 * giá ở đây đối chiếu đúng danh sách organization của danh tính với tenant của trang: khớp thì là thành viên của
 * tenant đó; không khớp thì là khách của tenant đó. Tư cách khách chỉ phục vụ hiển thị tại trang này và không biến
 * tài khoản thành thành viên của Organization.
 */

export { tenantAliasForHostname } from '#lib/kc-tenant-hosts';

/**
 * Danh tính người đăng nhập đã chuẩn hoá cho trang tenant: `sub`, `preferred_username`, `name`, `email`,
 * `realm_access.roles` và các alias trong claim `organization` của access token. Cùng hình dạng này cũng là đáp
 * ứng của `GET /api/me` phía `apps/server`, nên component chỉ làm việc với một kiểu danh tính duy nhất bất kể dữ
 * kiện đến từ `tokenParsed` của adapter hay từ server đã kiểm chứng JWT.
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
 * Phần claim của access token mà trang tenant cần đọc. Các trường được giữ ở dạng `unknown` ở biên vì
 * `tokenParsed` là dữ kiện do Keycloak cấp cho trình duyệt; phép ánh xạ dưới đây chỉ nhận chuỗi và danh sách chuỗi
 * đúng hình dạng, không suy diễn từ dữ kiện lạ.
 */
export interface KeycloakTokenClaims {
  sub?: unknown;
  preferred_username?: unknown;
  name?: unknown;
  email?: unknown;
  realm_access?: unknown;
  organization?: unknown;
  [claim: string]: unknown;
}

/**
 * Đọc một claim chuỗi không rỗng.
 *
 * @param value - Giá trị claim thô.
 * @returns Chuỗi hợp lệ, hoặc `null` khi claim vắng hoặc không phải chuỗi không rỗng.
 */
function stringClaim(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}

/**
 * Chuẩn hoá một giá trị claim thành danh sách chuỗi: mảng giữ các phần tử chuỗi; object lấy các khoá của nó. Dạng
 * object là hình dạng thứ hai của claim `organization` khi mapper của client scope trả thêm thông tin organization
 * theo alias.
 *
 * @param value - Giá trị claim thô.
 * @returns Danh sách chuỗi đã lọc; rỗng khi giá trị không thuộc hai hình dạng được hỗ trợ.
 */
function stringListClaim(value: unknown): string[] {
  if (Array.isArray(value)) return value.filter((item): item is string => typeof item === 'string');
  if (value && typeof value === 'object') return Object.keys(value);
  return [];
}

/**
 * Ánh xạ claim của access token thành danh tính đã chuẩn hoá của trang tenant.
 *
 * @param claims - `tokenParsed` của adapter, hoặc `undefined` khi chưa có token.
 * @returns Danh tính khi claim `sub` hợp lệ; `null` khi chưa đăng nhập hoặc token thiếu định danh bắt buộc.
 */
export function identityFromTokenClaims(claims: KeycloakTokenClaims | undefined): TenantIdentity | null {
  if (!claims || typeof claims.sub !== 'string' || claims.sub.length === 0) return null;
  const realmAccess =
    claims.realm_access && typeof claims.realm_access === 'object'
      ? (claims.realm_access as { roles?: unknown })
      : null;
  return {
    sub: claims.sub,
    preferredUsername: stringClaim(claims.preferred_username),
    name: stringClaim(claims.name),
    email: stringClaim(claims.email),
    realmRoles: stringListClaim(realmAccess?.roles),
    organizations: stringListClaim(claims.organization),
  };
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

/** Tư cách hiển thị của một tài khoản đã đăng nhập tại tenant của trang đang mở. */
export type TenantStanding = 'member' | 'guest';

/**
 * Suy tư cách hiển thị tại tenant của trang: thành viên khi claim `organization` chứa tenant đó; mọi tài khoản đã
 * đăng nhập còn lại là khách của tenant đó. Phép suy này không kiểm tra role và không tạo membership trong
 * Keycloak — nó chỉ chọn mặt hiển thị đúng ngữ cảnh trang.
 *
 * @param identity - Danh tính đã chuẩn hoá từ access token.
 * @param tenantAlias - Alias của tenant của trang đang mở.
 * @returns `member` cho thành viên thật, `guest` cho tài khoản đã đăng nhập nhưng không thuộc tenant của trang.
 */
export function tenantStanding(identity: TenantIdentity, tenantAlias: string): TenantStanding {
  return isMemberOfTenant(identity, tenantAlias) ? 'member' : 'guest';
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
