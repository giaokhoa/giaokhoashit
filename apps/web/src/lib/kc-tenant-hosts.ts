/**
 * Ánh xạ hostname → tenant Keycloak cho lát nghiệm thu issue #38.
 *
 * Hai hostname `trung-tam-1.fuckr.net` và `trung-tam-2.fuckr.net` là
 * hai tenant nghiệm thu: mỗi hostname tương ứng đúng một Organization
 * trong realm `giaokhoa` của Keycloak, alias của Organization dùng
 * làm định danh tenant ở mọi tầng (claim `organization` trong token
 * cũng chứa alias). Hostname khác không thuộc lát này và giữ hành vi
 * hiện tại của app.
 *
 * Module thuần ở tầng dùng chung vì hai feature cùng cần: feature
 * auth (trang tenant, kiểm tra tư cách) và feature dashboard (loader
 * trang gốc chuyển hướng theo Host). Giá trị trả về là alias của
 * Organization, không phải tên hiển thị.
 */
const TENANT_HOSTNAME_ALIASES: Readonly<Record<string, string>> = {
  'trung-tam-1.fuckr.net': 'trung-tam-1',
  'trung-tam-2.fuckr.net': 'trung-tam-2',
};

/**
 * Suy alias tenant nghiệm thu từ hostname của yêu cầu.
 *
 * @param hostname - Hostname đã tách khỏi cổng (ví dụ `trung-tam-1.fuckr.net`).
 * @returns Alias Organization của tenant, hoặc `null` khi hostname không thuộc lát nghiệm thu.
 */
export function tenantAliasForHostname(hostname: string): string | null {
  return TENANT_HOSTNAME_ALIASES[hostname.toLowerCase()] ?? null;
}
