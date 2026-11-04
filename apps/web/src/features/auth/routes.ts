import { type RouteConfig, relative } from '@react-router/dev/routes';

const { route } = relative(import.meta.dirname);

export default [
  route('login', 'routes/login.tsx'),
  route('oauth/callback', 'routes/oauth-callback.tsx'),
] satisfies RouteConfig;

/**
 * Các trang nằm NGOÀI shell layout được gộp ở tầng app. Hiện chỉ còn route tenant Keycloak (issue #38): trang
 * tenant là mặt nghiệm thu độc lập với phiên Better Auth của app — đăng nhập, callback và đăng xuất do adapter
 * `keycloak-js` xử lý ngay tại `/tenant`, nên không còn route phía server riêng cho các bước đó. Hai trang của
 * luồng uỷ quyền MCP (`mcp-login`, `mcp-consent`) thuộc tính năng đã hoãn sau bản phát hành 07/11 nên không còn
 * được đăng ký ở đây (issue #79); module của chúng còn trong feature cho tới khi tính năng được mở lại.
 */
export const standaloneRoutes = [route('tenant', 'routes/tenant.tsx')] satisfies RouteConfig;
