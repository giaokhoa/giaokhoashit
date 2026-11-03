/**
 * Luồng OIDC với Keycloak phía SERVER của feature auth (issue #38) —
 * module `.server` nên không bao giờ vào bản build phía client.
 *
 * Vai trò: giữ mọi bước bàn giao với Keycloak và với `apps/server` mà
 * trình duyệt không được thấy token thô:
 *
 *   1. Discovery: đọc tài liệu `/.well-known/openid-configuration` của
 *      issuer (cấu hình qua biến môi trường `KEYCLOAK_ISSUER`), đối
 *      chiếu trường `issuer` trong tài liệu phải khớp chính xác issuer
 *      đã cấu hình rồi mới lấy các endpoint authorize/token/end-session
 *      từ đó — không ghim đường dẫn endpoint trong code.
 *   2. PKCE: sinh verifier/challenge S256 và state/nonce cho mỗi lượt
 *      bắt đầu đăng nhập; bộ ba này đi vòng qua trình duyệt trong một
 *      cookie HttpOnly ngắn hạn (`__Host-lms_kc_oidc`) để callback kiểm lại.
 *   3. Phiên phía web: sau khi đổi code lấy token ở kênh server-to-server,
 *      access token và ID token nằm trong kho phiên ở bộ nhớ tiến trình
 *      SSR, trình duyệt chỉ giữ một mã phiên ngẫu nhiên trong cookie
 *      HttpOnly (`__Host-lms_kc_session`). Token không ghi log và không đưa ra
 *      trang ở bất kỳ dạng nào.
 *   4. Danh tính: gọi `GET /api/me` của `apps/server` (biến môi trường
 *      `LMS_SERVER_INTERNAL_URL`) kèm access token; server kiểm chứng
 *      JWT và trả claim — web không tự giải mã token để quyết định.
 *
 * Kho phiên ở bộ nhớ là đủ cho lát nghiệm thu này (một tiến trình SSR);
 * mất phiên khi tiến trình khởi động lại chỉ buộc người dùng đăng nhập
 * lại qua phiên SSO còn sống của Keycloak.
 */
import { createHash, randomBytes } from 'node:crypto';
import type { TenantIdentity } from './kc-tenant';

/** Tên cookie giữ mã phiên đăng nhập phía web (tiền tố `__Host-`, xem `buildCookie`). */
export const KC_SESSION_COOKIE = '__Host-lms_kc_session';

/** Tên cookie ngắn hạn giữ bộ ba PKCE/state/nonce giữa login và callback (tiền tố `__Host-`). */
const KC_OIDC_COOKIE = '__Host-lms_kc_oidc';

/** Tuổi của cookie chờ callback: đủ cho một lượt đăng nhập bị chậm ở trang Keycloak. */
const OIDC_COOKIE_MAX_AGE_SECONDS = 600;

/**
 * Issuer của realm `giaokhoa`: mặc định là địa chỉ Keycloak của môi
 * trường dev; môi trường khác (và lượt kiểm chứng local) ghi đè bằng
 * biến môi trường `KEYCLOAK_ISSUER`.
 *
 * @returns Issuer không có dấu gạch chéo cuối.
 */
function keycloakIssuer(): string {
  return (process.env.KEYCLOAK_ISSUER ?? 'https://auth.fuckr.net/realms/giaokhoa').replace(/\/+$/, '');
}

/**
 * Client công khai của luồng đăng nhập trên trình duyệt (biến môi
 * trường `KEYCLOAK_CLIENT_ID`, mặc định `lms-web`).
 *
 * @returns Client id dùng cho authorize/token/logout.
 */
export function keycloakClientId(): string {
  return process.env.KEYCLOAK_CLIENT_ID ?? 'lms-web';
}

/**
 * Địa chỉ nội bộ của `apps/server` — cùng khuôn `LMS_API_INTERNAL_URL`
 * của `apps/api`: web gọi qua mạng nội bộ, trình duyệt không qua đây.
 *
 * @returns Origin nội bộ của `apps/server`, không có gạch chéo cuối.
 */
function serverInternalBase(): string {
  return (process.env.LMS_SERVER_INTERNAL_URL ?? 'http://127.0.0.1:8081').replace(/\/+$/, '');
}

/** Các endpoint OIDC cần cho luồng, trích từ tài liệu discovery của issuer. */
interface OidcDiscovery {
  issuer: string;
  authorizationEndpoint: string;
  tokenEndpoint: string;
  endSessionEndpoint: string | null;
}

let discoveryCache: Promise<OidcDiscovery> | null = null;

/**
 * Lấy (và nhớ cho cả tiến trình) các endpoint OIDC của issuer. Lỗi mạng
 * hay đáp ứng thiếu trường thì bỏ cache để lượt gọi sau thử lại.
 *
 * Trường `issuer` trong tài liệu phải khớp chính xác issuer đã cấu
 * hình (OpenID Connect Discovery §4): metadata mang issuer khác nghĩa
 * là tài liệu không thuộc OP đã cấu hình, và dùng các endpoint trong
 * đó sẽ gửi authorization code tới một đích không được uỷ quyền — đáp
 * ứng như vậy bị từ chối và không được nhớ vào cache.
 *
 * @returns Các endpoint authorize/token/end-session của issuer.
 */
export function getOidcDiscovery(): Promise<OidcDiscovery> {
  discoveryCache ??= (async () => {
    const res = await fetch(`${keycloakIssuer()}/.well-known/openid-configuration`);
    if (!res.ok) throw new Error(`OIDC discovery failed: ${res.status}`);
    const doc = (await res.json()) as Record<string, unknown>;
    if (typeof doc.issuer !== 'string' || doc.issuer !== keycloakIssuer()) {
      throw new Error('OIDC discovery document issuer does not match the configured issuer');
    }
    if (typeof doc.authorization_endpoint !== 'string' || typeof doc.token_endpoint !== 'string') {
      throw new Error('OIDC discovery document is missing endpoints');
    }
    return {
      issuer: doc.issuer,
      authorizationEndpoint: doc.authorization_endpoint,
      tokenEndpoint: doc.token_endpoint,
      endSessionEndpoint: typeof doc.end_session_endpoint === 'string' ? doc.end_session_endpoint : null,
    };
  })();
  discoveryCache.catch(() => {
    discoveryCache = null;
  });
  return discoveryCache;
}

/**
 * Origin công khai của app cho request hiện tại — nguồn của mọi
 * redirect_uri gửi cho Keycloak. Sau reverse proxy (Cloudflare Tunnel ở
 * môi trường triển khai), giao thức thật nằm ở header `X-Forwarded-Proto`
 * và host ở header `Host`; chạy trực tiếp thì lấy từ chính URL request.
 *
 * @param request - Request đang phục vụ.
 * @returns Origin dạng `scheme://host[:port]`, không có gạch chéo cuối.
 */
export function publicOrigin(request: Request): string {
  const url = new URL(request.url);
  const forwardedProto = request.headers.get('x-forwarded-proto')?.split(',')[0]?.trim();
  const proto = forwardedProto || url.protocol.replace(':', '');
  const host = request.headers.get('host') ?? url.host;
  return `${proto}://${host}`;
}

/** Sinh một giá trị ngẫu nhiên an toàn dạng base64url (state, nonce, mã phiên). */
function randomBase64Url(bytes: number): string {
  return randomBytes(bytes).toString('base64url');
}

/** Cặp PKCE của một lượt đăng nhập: verifier giữ ở cookie, challenge gửi cho Keycloak. */
export interface PkcePair {
  verifier: string;
  challenge: string;
}

/**
 * Sinh cặp PKCE S256 theo RFC 7636: verifier ngẫu nhiên 32 byte,
 * challenge là SHA-256 của verifier mã hoá base64url.
 *
 * @returns Cặp verifier/challenge của lượt đăng nhập.
 */
export function createPkcePair(): PkcePair {
  const verifier = randomBase64Url(32);
  const challenge = createHash('sha256').update(verifier).digest('base64url');
  return { verifier, challenge };
}

/** Bộ ba chống giả mạo của một lượt đăng nhập, giữ ở cookie chờ callback. */
export interface PendingLogin {
  verifier: string;
  state: string;
  nonce: string;
}

/**
 * Sinh bộ ba PKCE/state/nonce mới cho một lượt bắt đầu đăng nhập.
 *
 * @returns Bộ ba cần lưu vào cookie chờ và nhúng vào URL authorize.
 */
export function createPendingLogin(): PendingLogin & PkcePair {
  const { verifier, challenge } = createPkcePair();
  return { verifier, challenge, state: randomBase64Url(24), nonce: randomBase64Url(24) };
}

/**
 * Dựng header Set-Cookie thô cho các cookie của luồng này: tên mang tiền
 * tố `__Host-`, luôn `Secure`, `Path=/`, HttpOnly và SameSite=Lax (lượt
 * quay về từ Keycloak là điều hướng GET cấp cao nên Lax vẫn cho cookie
 * đi kèm), không thuộc tính `Domain`.
 *
 * Tiền tố `__Host-` (RFC 6265bis §4.1.3.2) là cơ chế trình duyệt dành
 * cho đúng kênh cookie này: trình duyệt chỉ nhận cookie `__Host-` khi
 * nó có `Secure`, `Path=/` và không có `Domain`, nên một subdomain anh
 * em không thể cấy cookie cùng tên theo domain cha (cookie-tossing) để
 * thay bộ ba chống giả mạo hay mã phiên của lượt đăng nhập — việc kênh
 * cookie tự nó không bị thay thế là điều kiện để `state` được coi là
 * ràng buộc an toàn với trình duyệt theo RFC 9700 §4.7.1. Hệ quả: luồng
 * này chỉ chạy trên https, đúng mọi môi trường triển khai của app.
 *
 * @param name - Tên cookie.
 * @param value - Giá trị cookie (đã an toàn cho header).
 * @param maxAgeSeconds - Tuổi tối đa tính bằng giây; 0 nghĩa là xoá cookie.
 * @returns Giá trị header Set-Cookie hoàn chỉnh.
 */
function buildCookie(name: string, value: string, maxAgeSeconds: number): string {
  return `${name}=${value}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${maxAgeSeconds}; Secure`;
}

/**
 * Đọc một cookie từ header Cookie của request. Tên cookie xuất hiện
 * hơn một lần thì coi như không có: hai cookie cùng tên nghĩa là một
 * cookie do nguồn khác đặt (ví dụ theo domain cha từ subdomain anh em)
 * đang sống song song với cookie `__Host-` của app, và RFC 6265 §4.2.2
 * đã cảnh báo server không được trông vào thứ tự xuất hiện của chúng —
 * không giá trị nào trong hai giá trị là đáng tin.
 *
 * @param request - Request đang phục vụ.
 * @param name - Tên cookie cần đọc.
 * @returns Giá trị cookie, hoặc `null` khi không có hoặc tên bị trùng.
 */
function readCookie(request: Request, name: string): string | null {
  const header = request.headers.get('cookie');
  if (!header) return null;
  let found: string | null = null;
  for (const part of header.split(';')) {
    const eq = part.indexOf('=');
    if (eq < 0) continue;
    if (part.slice(0, eq).trim() !== name) continue;
    if (found !== null) return null;
    found = part.slice(eq + 1).trim();
  }
  return found;
}

/**
 * Gói bộ ba chờ callback vào cookie `__Host-lms_kc_oidc`: JSON mã hoá base64url
 * — đây là dữ kiện chống giả mạo của chính lượt đăng nhập, không phải
 * token, và cookie HttpOnly nên trang không đọc được.
 *
 * @param pending - Bộ ba PKCE/state/nonce vừa sinh.
 * @returns Header Set-Cookie cho cookie chờ.
 */
export function pendingLoginCookie(pending: PendingLogin): string {
  const value = Buffer.from(JSON.stringify(pending), 'utf8').toString('base64url');
  return buildCookie(KC_OIDC_COOKIE, value, OIDC_COOKIE_MAX_AGE_SECONDS);
}

/**
 * Đọc lại bộ ba chờ callback từ cookie của request callback.
 *
 * @param request - Request callback từ Keycloak quay về.
 * @returns Bộ ba đã lưu, hoặc `null` khi cookie vắng/hỏng.
 */
export function readPendingLogin(request: Request): PendingLogin | null {
  const raw = readCookie(request, KC_OIDC_COOKIE);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(Buffer.from(raw, 'base64url').toString('utf8')) as PendingLogin;
    if (!parsed.verifier || !parsed.state || !parsed.nonce) return null;
    return parsed;
  } catch {
    return null;
  }
}

/**
 * Header xoá cookie chờ callback (dùng ở mọi lối ra của callback để bộ
 * ba không sống qua một lượt đăng nhập).
 *
 * @returns Header Set-Cookie với Max-Age=0.
 */
export function clearPendingLoginCookie(): string {
  return buildCookie(KC_OIDC_COOKIE, '', 0);
}

/** Một phiên đăng nhập phía web: token nằm ở server, kèm mốc hết hạn của access token. */
interface KcSession {
  accessToken: string;
  idToken: string;
  expiresAt: number;
}

/** Kho phiên trong bộ nhớ tiến trình SSR, khoá là mã phiên ngẫu nhiên trong cookie. */
const sessions = new Map<string, KcSession>();

/**
 * Lập phiên mới cho một bộ token vừa đổi được và dựng cookie phiên.
 *
 * @param tokens - Access token, ID token và tuổi access token (giây).
 * @returns Header Set-Cookie mang mã phiên mới.
 */
export function createSessionCookie(tokens: {
  accessToken: string;
  idToken: string;
  expiresInSeconds: number;
}): string {
  const sessionId = randomBase64Url(32);
  sessions.set(sessionId, {
    accessToken: tokens.accessToken,
    idToken: tokens.idToken,
    expiresAt: Date.now() + tokens.expiresInSeconds * 1000,
  });
  return buildCookie(KC_SESSION_COOKIE, sessionId, tokens.expiresInSeconds);
}

/**
 * Tra phiên của request: hết hạn theo access token thì xoá khỏi kho và
 * coi như chưa đăng nhập (người dùng đăng nhập lại qua phiên SSO của
 * Keycloak nếu còn sống).
 *
 * @param request - Request đang phục vụ.
 * @returns Phiên còn hiệu lực, hoặc `null`.
 */
export function readSession(request: Request): KcSession | null {
  const sessionId = readCookie(request, KC_SESSION_COOKIE);
  if (!sessionId) return null;
  const session = sessions.get(sessionId);
  if (!session) return null;
  if (session.expiresAt <= Date.now()) {
    sessions.delete(sessionId);
    return null;
  }
  return session;
}

/**
 * Huỷ phiên của request (đăng xuất) và dựng cookie xoá phiên.
 *
 * @param request - Request đang phục vụ.
 * @returns Header Set-Cookie xoá cookie phiên.
 */
export function destroySessionCookie(request: Request): string {
  const sessionId = readCookie(request, KC_SESSION_COOKIE);
  if (sessionId) sessions.delete(sessionId);
  return buildCookie(KC_SESSION_COOKIE, '', 0);
}

/** Đáp ứng của token endpoint sau khi đổi authorization code. */
export interface TokenResponse {
  accessToken: string;
  idToken: string;
  expiresInSeconds: number;
}

/**
 * Đổi authorization code lấy token tại token endpoint (client công khai
 * `lms-web`, không secret; bằng chứng sở hữu là PKCE verifier).
 *
 * @param input - Code từ callback, verifier đã lưu, và redirect_uri phải
 *   trùng nguyên văn giá trị đã gửi ở bước authorize.
 * @returns Bộ token và tuổi access token; ném lỗi khi Keycloak từ chối.
 */
export async function exchangeCode(input: {
  code: string;
  verifier: string;
  redirectUri: string;
}): Promise<TokenResponse> {
  const discovery = await getOidcDiscovery();
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: keycloakClientId(),
    code: input.code,
    redirect_uri: input.redirectUri,
    code_verifier: input.verifier,
  });
  const res = await fetch(discovery.tokenEndpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!res.ok) throw new Error(`Token exchange failed: ${res.status}`);
  const doc = (await res.json()) as Record<string, unknown>;
  if (typeof doc.access_token !== 'string' || typeof doc.id_token !== 'string') {
    throw new Error('Token response is missing tokens');
  }
  return {
    accessToken: doc.access_token,
    idToken: doc.id_token,
    expiresInSeconds: typeof doc.expires_in === 'number' ? doc.expires_in : 300,
  };
}

/**
 * Kiểm các claim bắt buộc của ID token theo OIDC Core §3.1.3.7 trước
 * khi lập phiên: `iss` khớp chính xác issuer đã cấu hình (quy tắc 2),
 * `aud` chứa client của luồng này (quy tắc 3), `exp` còn hạn (quy tắc
 * 9) và `nonce` khớp giá trị đã sinh ở bước authorize (quy tắc 11).
 *
 * Chữ ký không được kiểm ở đây: token này vừa nhận trực tiếp từ token
 * endpoint của issuer đã cấu hình qua kênh server-to-server có TLS,
 * và quy tắc 6 của cùng mục cho phép việc kiểm thực máy chủ qua TLS
 * thay cho kiểm chữ ký trong authorization code flow. Access token đi
 * kèm vẫn bị `apps/server` kiểm chứng chữ ký ở mỗi lượt lấy danh tính
 * — đó là token khác, không thay được các kiểm tra claim ở đây.
 *
 * @param idToken - ID token vừa nhận từ token endpoint.
 * @param expected - Issuer đã cấu hình, client id của luồng và nonce đã lưu trong cookie chờ.
 * @returns `true` khi mọi claim bắt buộc đều đạt.
 */
export function validateIdToken(
  idToken: string,
  expected: { issuer: string; clientId: string; nonce: string },
): boolean {
  const parts = idToken.split('.');
  if (parts.length !== 3) return false;
  try {
    const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8')) as Record<string, unknown>;
    if (payload.iss !== expected.issuer) return false;
    const audience = payload.aud;
    const audienceMatches =
      audience === expected.clientId || (Array.isArray(audience) && audience.includes(expected.clientId));
    if (!audienceMatches) return false;
    if (typeof payload.exp !== 'number' || payload.exp * 1000 <= Date.now()) return false;
    return payload.nonce === expected.nonce;
  } catch {
    return false;
  }
}

/**
 * Lấy danh tính của phiên hiện tại qua `GET /api/me` của `apps/server`.
 * Server là nơi kiểm chứng JWT (chữ ký, issuer, thời hạn) — đáp ứng 401
 * hay lỗi mạng đều suy biến thành `null` (trang hiển thị trạng thái chưa
 * đăng nhập thay vì sập).
 *
 * @param accessToken - Access token lấy từ kho phiên phía web.
 * @returns Danh tính đã ánh xạ từ claim, hoặc `null`.
 */
export async function fetchIdentity(accessToken: string): Promise<TenantIdentity | null> {
  try {
    const res = await fetch(`${serverInternalBase()}/api/me`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (!res.ok) return null;
    return (await res.json()) as TenantIdentity;
  } catch {
    return null;
  }
}
