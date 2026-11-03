import { type RouteConfig, relative } from '@react-router/dev/routes';

const { route } = relative(import.meta.dirname);

export default [
  route('login', 'routes/login.tsx'),
  route('oauth/callback', 'routes/oauth-callback.tsx'),
] satisfies RouteConfig;

/**
 * Hai trang mỏng của luồng uỷ quyền MCP (lát MCP-1, issue #163): nằm NGOÀI shell layout — chúng là
 * loginPage/consentPage mà máy chủ OAuth chuyển trình duyệt tới giữa luồng authorize, phải hiển thị trần (không khung
 * app, không bị guard của shell đá về /login). File gốc ở tầng app gộp khối này ngoài layout.
 */
export const standaloneRoutes = [
  route('mcp-login', 'routes/mcp-login.tsx'),
  route('mcp-consent', 'routes/mcp-consent.tsx'),
] satisfies RouteConfig;
