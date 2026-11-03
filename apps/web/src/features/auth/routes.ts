import { type RouteConfig, relative } from '@react-router/dev/routes';

const { route } = relative(import.meta.dirname);

/**
 * Khu route của feature auth: đăng nhập và callback OAuth nằm trong shell; hai trang uỷ quyền MCP nằm ngoài shell (khối `standaloneRoutes`).
 */
export default [
  route('login', 'routes/login.tsx'),
  route('oauth/callback', 'routes/oauth-callback.tsx'),
] satisfies RouteConfig;

/**
 * Hai trang mỏng của luồng uỷ quyền MCP (lát MCP-1, issue #163):
 * nằm NGOÀI shell layout — chúng là loginPage/consentPage mà máy
 * chủ OAuth chuyển trình duyệt tới giữa luồng authorize, phải hiển
 * thị trần (không khung app, không bị guard của shell đá về /login).
 * File gốc ở tầng app gộp khối này ngoài layout.
 */
export const standaloneRoutes = [
  route('mcp-login', 'routes/mcp-login.tsx'),
  route('mcp-consent', 'routes/mcp-consent.tsx'),
  // Nhóm route tenant Keycloak (issue #38): cũng nằm ngoài shell —
  // trang tenant là mặt nghiệm thu độc lập với phiên Better Auth của
  // app (phiên ở đây là phiên phía web của luồng OIDC với Keycloak);
  // ba route login/callback/logout xử lý toàn bộ ở loader và luôn kết
  // thúc bằng chuyển hướng (component chỉ là mặt dự phòng).
  route('tenant', 'routes/tenant.tsx'),
  route('tenant/login', 'routes/tenant-login.tsx'),
  route('tenant/callback', 'routes/tenant-callback.tsx'),
  route('tenant/logout', 'routes/tenant-logout.tsx'),
] satisfies RouteConfig;
