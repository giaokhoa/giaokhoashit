/**
 * Bộ khớp đường proxy của SSR topology C (prod-server.mjs).
 *
 * Các đường thuộc về tiến trình API nội bộ, SSR chỉ chuyển tiếp:
 *   - `/api` và mọi subpath: REST API (`/api/v1/...`). Riêng các đường do `apps/server` (Spring Boot) phục vụ —
 *     `/api/me`, họ `/api/tenancy-check/...` và các họ đường nghiệp vụ của bản phát hành 07/11 (khóa/lớp, ngân
 *     hàng câu hỏi, đề, lần giao, lượt làm): vẫn là đường proxy nhưng đi upstream server riêng — xem
 *     `isServerProxyPath` ở dưới.
 *   - `/socket.io/...`: realtime (HTTP polling + đường hầm upgrade).
 *   - `/mcp` và mọi subpath: endpoint MCP của issue #110 (MCP-0 mount NGOÀI `/api/v1` theo thiết kế — Streamable HTTP
 *     stateless).
 *   - `/.well-known/oauth-protected-resource` và mọi subpath: metadata PRM (RFC 9728) của MCP, gồm cả biến thể
 *     `/.well-known/oauth-protected-resource/mcp`.
 *   - `/.well-known/oauth-authorization-server` và mọi subpath: metadata AS (RFC 8414) của MCP AS (issue #7) — gồm dạng
 *     gốc, dạng chèn-đường-dẫn (`/.well-known/oauth-authorization-server/api/v1/auth`) và dạng hậu tố qua
 *     `/api/v1/auth` (đường `/api` đã khớp sẵn).
 *
 * Mọi đường còn lại thuộc về SSR/asset tĩnh. Khớp trên pathname (đã bỏ query string): bản cũ khớp thẳng trên req.url
 * nên ca `/mcp?...` sẽ lọt exact-match; đồng thời exact + tiền tố có dấu `/` bảo đảm các đường gần giống (`/mcpfoo`,
 * `/apixyz`) KHÔNG bị proxy nuốt.
 */

const PROXY_EXACT = new Set([
  '/api',
  '/mcp',
  '/.well-known/oauth-protected-resource',
  '/.well-known/oauth-authorization-server',
]);

const PROXY_PREFIXES = [
  '/api/',
  '/socket.io/',
  '/mcp/',
  '/.well-known/oauth-protected-resource/',
  '/.well-known/oauth-authorization-server/',
];

export function isProxyPath(url) {
  const path = url.split('?')[0];
  return PROXY_EXACT.has(path) || PROXY_PREFIXES.some((prefix) => path.startsWith(prefix));
}

const SERVER_PROXY_EXACT = new Set([
  '/api/me',
  '/api/courses',
  '/api/classes',
  '/api/question-banks',
  '/api/questions',
  '/api/exams',
  '/api/assignments',
]);

const SERVER_PROXY_PREFIXES = [
  '/api/tenancy-check/',
  '/api/classes/',
  '/api/question-banks/',
  '/api/questions/',
  '/api/exams/',
  '/api/assignments/',
  '/api/attempts/',
];

/**
 * Đường proxy đi upstream `apps/server` thay vì upstream API chung: endpoint danh tính `/api/me` (issue #38), các
 * endpoint kiểm chứng khung tenancy `/api/tenancy-check/...` (issue #57), và các họ đường nghiệp vụ của bản phát
 * hành 07/11 (issue #102): khóa/lớp và thành viên lớp, ngân hàng câu hỏi và câu hỏi, đề, lần giao và lượt làm —
 * `apps/server` là nơi duy nhất phục vụ chúng, service API (NestJS) chỉ có các đường `/api/v1/...` tương ứng ở hệ
 * cũ. prod-server phải xét luật này TRƯỚC khi rơi về upstream `/api` chung, vì các đường này cũng khớp tiền tố
 * `/api/` mà service API không phục vụ. Khớp trên pathname (đã bỏ query string) như `isProxyPath`: các đường là
 * tập hợp có subpath thì khớp exact cho chính nó cộng tiền tố có dấu `/` cuối cho subpath; `/api/courses` và
 * `/api/attempts` chỉ có một phía của cặp đó vì phía còn lại không tồn tại trên server (khóa không có subpath,
 * lượt làm không có đường gốc). Đường gần giống (`/api/classesfoo`, `/api/attempts` trơ) không bị nuốt.
 */
export function isServerProxyPath(url) {
  const path = url.split('?')[0];
  return SERVER_PROXY_EXACT.has(path) || SERVER_PROXY_PREFIXES.some((prefix) => path.startsWith(prefix));
}
