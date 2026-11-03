import { createServer } from 'node:http';
import { createHash, createPublicKey, randomBytes, verify as verifySignature } from 'node:crypto';

const PORT = Number(process.env.PORT || 3000);
const ISSUER = (process.env.OIDC_ISSUER || 'https://auth.fuckr.net/realms/giaokhoa').replace(/\/$/, '');
const CLIENT_ID = 'lms-web';
const PUBLIC_SCHEME = process.env.TENANT_DEMO_PUBLIC_SCHEME || 'https';
const ALLOWED_HOSTS = new Set(
  (process.env.TENANT_DEMO_ALLOWED_HOSTS || 'trung-tam-1.fuckr.net,trung-tam-2.fuckr.net')
    .split(',')
    .map((host) => host.trim().toLowerCase())
    .filter(Boolean),
);

const FLOW_COOKIE = 'tenant_demo_flow';
const SESSION_COOKIE = 'tenant_demo_session';
const FLOW_TTL_MS = 10 * 60 * 1000;
const SESSION_TTL_MS = 8 * 60 * 60 * 1000;

// Phiên và trạng thái luồng đăng nhập chỉ sống trong bộ nhớ tiến trình:
// ứng dụng demo không lưu trữ dữ kiện, khởi động lại là mất hết phiên.
const flows = new Map();
const sessions = new Map();

let discoveryPromise = null;
function discover() {
  if (!discoveryPromise) {
    discoveryPromise = fetch(`${ISSUER}/.well-known/openid-configuration`)
      .then(async (res) => {
        if (!res.ok) throw new Error(`discovery failed: ${res.status}`);
        const doc = await res.json();
        // OIDC Discovery yêu cầu issuer trong tài liệu khám phá khớp
        // tuyệt đối với issuer đã dùng để lấy tài liệu, nếu không các
        // endpoint lấy được không đáng tin.
        if (doc.issuer !== ISSUER) throw new Error('issuer mismatch in discovery document');
        return doc;
      })
      .catch((err) => {
        discoveryPromise = null;
        throw err;
      });
  }
  return discoveryPromise;
}

let jwksCache = null;
async function getSigningKey(jwksUri, kid) {
  if (!jwksCache) {
    const res = await fetch(jwksUri);
    if (!res.ok) throw new Error(`jwks failed: ${res.status}`);
    jwksCache = await res.json();
  }
  const jwk = jwksCache.keys.find((key) => key.kid === kid);
  if (!jwk) throw new Error('signing key not found');
  return createPublicKey({ key: jwk, format: 'jwk' });
}

// Kiểm chứng chữ ký RS256 qua JWKS của issuer, rồi iss/exp — dùng chung
// cho ID token và access token. Thiếu bước này thì claim lấy từ token
// không có gì đảm bảo là do Keycloak phát hành cho đúng lần đăng nhập.
async function verifySignedToken(token, endpoints) {
  const parts = token.split('.');
  if (parts.length !== 3) throw new Error('malformed token');
  const header = JSON.parse(Buffer.from(parts[0], 'base64url').toString('utf8'));
  const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
  if (header.alg !== 'RS256') throw new Error('unexpected token alg');
  const key = await getSigningKey(endpoints.jwks_uri, header.kid);
  const signed = Buffer.from(`${parts[0]}.${parts[1]}`);
  const signature = Buffer.from(parts[2], 'base64url');
  if (!verifySignature('RSA-SHA256', signed, key, signature)) throw new Error('token signature invalid');
  if (payload.iss !== ISSUER) throw new Error('token issuer mismatch');
  if (payload.exp * 1000 <= Date.now()) throw new Error('token expired');
  return payload;
}

// ID token kiểm thêm aud và nonce theo OIDC Core: aud phải là client này
// và nonce phải khớp giá trị đã gửi ở bước authorize của đúng lượt này.
async function verifyIdToken(idToken, endpoints, expectedNonce) {
  const payload = await verifySignedToken(idToken, endpoints);
  const audiences = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
  if (!audiences.includes(CLIENT_ID)) throw new Error('id token audience mismatch');
  if (payload.nonce !== expectedNonce) throw new Error('id token nonce mismatch');
  return payload;
}

function randomToken(bytes) {
  return randomBytes(bytes).toString('base64url');
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

function parseCookies(req) {
  const cookies = {};
  for (const part of (req.headers.cookie || '').split(';')) {
    const idx = part.indexOf('=');
    if (idx > 0) cookies[part.slice(0, idx).trim()] = part.slice(idx + 1).trim();
  }
  return cookies;
}

function cookieHeader(name, value, maxAgeSeconds) {
  const attrs = [`${name}=${value}`, 'HttpOnly', 'Path=/', 'SameSite=Lax', `Max-Age=${maxAgeSeconds}`];
  // Cookie Secure chỉ bật khi địa chỉ công khai là https; lượt chạy
  // local qua http mà gắn Secure thì trình duyệt sẽ không gửi lại cookie.
  if (PUBLIC_SCHEME === 'https') attrs.push('Secure');
  return attrs.join('; ');
}

function requestHost(req) {
  return (req.headers.host || '').toLowerCase();
}

function publicBaseUrl(req) {
  return `${PUBLIC_SCHEME}://${requestHost(req)}`;
}

function pageShell(title, inner) {
  return `<!doctype html>
<html lang="vi">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)}</title>
<style>
  body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
         background: #fff; font-family: system-ui, sans-serif; color: #111; }
  .card { width: min(420px, calc(100vw - 48px)); border: 1px solid #e5e5e5; border-radius: 12px;
          padding: 32px; box-shadow: 0 1px 3px rgba(0,0,0,.06); }
  h1 { font-size: 22px; margin: 0 0 8px; }
  p { color: #555; line-height: 1.5; }
  .btn { display: block; text-align: center; background: #111; color: #fff; text-decoration: none;
         padding: 12px 16px; border-radius: 8px; font-weight: 600; margin-top: 20px; }
  .btn.secondary { background: #fff; color: #111; border: 1px solid #d4d4d4; }
  table { width: 100%; border-collapse: collapse; margin-top: 12px; }
  td { padding: 8px 0; border-bottom: 1px solid #eee; vertical-align: top; }
  td:first-child { color: #666; width: 40%; }
  .notice { background: #fafafa; border: 1px solid #eee; border-radius: 8px; padding: 10px 12px; }
</style>
</head>
<body>
<main class="card">${inner}</main>
</body>
</html>`;
}

function renderHome(req, res) {
  const host = escapeHtml(requestHost(req));
  const inner = `
    <h1>Giáo Khoa — điểm nghiệm thu tenant</h1>
    <p>Hostname đang truy cập: <strong>${host}</strong></p>
    <p>Đăng nhập bằng tài khoản Keycloak để xem thông tin của bạn và tenant bạn thuộc về.</p>
    <a class="btn" href="/login">Đăng nhập</a>`;
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(pageShell('Giáo Khoa — điểm nghiệm thu tenant', inner));
}

// Nhãn hiển thị cho các realm role của hệ thống; role ngoài danh sách giữ nguyên tên gốc.
const ROLE_LABELS = {
  'tenant-admin': 'Quản trị viên trung tâm',
  'tenant-teacher': 'Giáo viên',
  'tenant-member': 'Học sinh',
  'platform-admin': 'Quản trị viên nền tảng',
};

// Tenant của trang đang mở chỉ là ngữ cảnh nơi đang đứng: nhãn đầu của hostname (trung-tam-2.fuckr.net ->
// trung-tam-2). Danh tính người dùng không suy từ đây mà gắn với Organization gốc của tài khoản.
function pageTenantFromHost(host) {
  return host.split(':')[0].split('.')[0];
}

// Vai trò chỉ tồn tại trong organization mà tài khoản là thành viên, nên chỉ trình bày vai trò khi trang đang mở
// thuộc đúng organization đó, dạng "Giáo viên — thuộc trung-tam-2".
function roleLinesFor(user, organization) {
  const labels = user.roles.map((role) => ROLE_LABELS[role] || role);
  if (labels.length === 0) return [`Thuộc ${organization}`];
  return labels.map((label) => `${label} — thuộc ${organization}`);
}

function renderProfile(req, res, session) {
  const user = session.user;
  const pageTenant = pageTenantFromHost(requestHost(req));
  const row = (label, value) => `<tr><td>${escapeHtml(label)}</td><td>${escapeHtml(value)}</td></tr>`;
  const isMember = user.organizations.includes(pageTenant);
  let notice = '';
  let roleRow = '';
  if (isMember) {
    const lines = roleLinesFor(user, pageTenant);
    roleRow = `<tr><td>Vai trò</td><td>${lines.map((line) => escapeHtml(line)).join('<br>')}</td></tr>`;
  } else {
    // Không phải thành viên của tenant đang mở thì tài khoản không có tư cách hay quyền gì tại đây; trang khẳng
    // định điều đó kèm thông tin cơ bản bên dưới để đối chiếu, thay vì trình bày như khách có vai trò ở trang này.
    const home = user.organizations.length > 0 ? user.organizations.join(', ') : 'không thuộc trung tâm nào';
    notice = `<p class="notice">Tài khoản thuộc ${escapeHtml(home)} — không thuộc ${escapeHtml(
      pageTenant,
    )} và không có quyền tại ${escapeHtml(pageTenant)}.</p>`;
  }
  const inner = `
    <h1>Thông tin người đăng nhập</h1>
    <p>Bạn đang ở trang của tenant <strong>${escapeHtml(pageTenant)}</strong>.</p>
    ${notice}
    <table>
      ${row('Tenant của trang đang mở', pageTenant)}
      ${row('Họ tên', user.name)}
      ${row('Tên đăng nhập', user.username)}
      ${row('Email', user.email)}
      ${roleRow}
    </table>
    <a class="btn secondary" href="/logout">Đăng xuất</a>`;
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(pageShell('Thông tin người đăng nhập', inner));
}

function renderError(res, status, message) {
  const inner = `
    <h1>Đã xảy ra lỗi</h1>
    <p>${escapeHtml(message)}</p>
    <a class="btn secondary" href="/">Về trang chủ</a>`;
  res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(pageShell('Đã xảy ra lỗi', inner));
}

function redirect(res, location, extraHeaders = {}) {
  res.writeHead(302, { Location: location, ...extraHeaders });
  res.end();
}

async function handleLogin(req, res) {
  const host = requestHost(req);
  if (!ALLOWED_HOSTS.has(host)) {
    return renderError(res, 400, 'Hostname không thuộc danh sách cho phép của điểm nghiệm thu.');
  }
  const endpoints = await discover();
  const redirectUri = `${publicBaseUrl(req)}/callback`;
  const state = randomToken(32);
  const nonce = randomToken(32);
  // PKCE (RFC 7636): verifier đủ dài từ bộ ký tự unreserved, challenge
  // là SHA-256 của verifier mã hoá base64url không đệm.
  const codeVerifier = randomToken(48);
  const codeChallenge = createHash('sha256').update(codeVerifier, 'ascii').digest('base64url');
  const flowId = randomToken(24);
  flows.set(flowId, { state, nonce, codeVerifier, redirectUri, host, createdAt: Date.now() });

  const authorizeUrl = new URL(endpoints.authorization_endpoint);
  authorizeUrl.searchParams.set('client_id', CLIENT_ID);
  authorizeUrl.searchParams.set('response_type', 'code');
  authorizeUrl.searchParams.set('redirect_uri', redirectUri);
  // Scope organization phải xin tường minh: Keycloak 26.8.1 không nhận scope này làm Default client scope (gán
  // qua Admin API vẫn thành công nhưng scope nằm ở nhóm Optional), không xin thì claim organization không về.
  authorizeUrl.searchParams.set('scope', 'openid organization');
  authorizeUrl.searchParams.set('state', state);
  authorizeUrl.searchParams.set('nonce', nonce);
  authorizeUrl.searchParams.set('code_challenge', codeChallenge);
  authorizeUrl.searchParams.set('code_challenge_method', 'S256');
  redirect(res, authorizeUrl.toString(), { 'Set-Cookie': cookieHeader(FLOW_COOKIE, flowId, FLOW_TTL_MS / 1000) });
}

async function handleCallback(req, res, url) {
  const cookies = parseCookies(req);
  const flow = flows.get(cookies[FLOW_COOKIE]);
  if (cookies[FLOW_COOKIE]) flows.delete(cookies[FLOW_COOKIE]);
  const clearFlowCookie = cookieHeader(FLOW_COOKIE, '', 0);

  if (url.searchParams.get('error')) {
    return renderError(res, 400, `Keycloak báo lỗi đăng nhập: ${url.searchParams.get('error')}`);
  }
  if (!flow || Date.now() - flow.createdAt > FLOW_TTL_MS) {
    return renderError(res, 400, 'Phiên đăng nhập đã hết hạn hoặc không hợp lệ, hãy thử lại.');
  }
  const code = url.searchParams.get('code');
  if (!code || url.searchParams.get('state') !== flow.state) {
    return renderError(res, 400, 'Tham số callback không khớp với lượt đăng nhập đã khởi tạo.');
  }

  const endpoints = await discover();
  const tokenRes = await fetch(endpoints.token_endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: CLIENT_ID,
      code,
      redirect_uri: flow.redirectUri,
      code_verifier: flow.codeVerifier,
    }),
  });
  if (!tokenRes.ok) return renderError(res, 502, 'Đổi mã đăng nhập lấy token không thành công.');
  const tokens = await tokenRes.json();

  const idTokenClaims = await verifyIdToken(tokens.id_token, endpoints, flow.nonce);
  const accessTokenClaims = await verifySignedToken(tokens.access_token, endpoints);

  const userinfoRes = await fetch(endpoints.userinfo_endpoint, {
    headers: { Authorization: `Bearer ${tokens.access_token}` },
  });
  if (!userinfoRes.ok) return renderError(res, 502, 'Lấy thông tin người dùng từ Keycloak không thành công.');
  const userinfo = await userinfoRes.json();
  if (userinfo.sub !== idTokenClaims.sub) {
    return renderError(res, 502, 'Thông tin người dùng không khớp với ID token.');
  }

  // Nguồn claim theo thứ tự userinfo -> ID token -> access token (cả hai
  // token đều đã kiểm chứng chữ ký ở trên): scope `roles` có sẵn của
  // Keycloak chỉ đưa realm_access vào access token (mapper của nó không
  // bật cộng vào ID token hay userinfo), còn claim organization có mặt ở
  // cả ID token và userinfo khi scope organization được xin tường minh.
  const rolesClaim = userinfo.realm_access || idTokenClaims.realm_access || accessTokenClaims.realm_access || {};
  const defaultRoles = new Set(['offline_access', 'uma_authorization']);
  const roles = (rolesClaim.roles || []).filter(
    (role) => !defaultRoles.has(role) && !role.startsWith('default-roles-'),
  );
  const organizationClaim = userinfo.organization || idTokenClaims.organization || {};
  // Claim organization có hai dạng tuỳ cách Keycloak phát hành: mảng các alias, hoặc map alias -> thông tin
  // của tổ chức (id, groups khi mapper bật). Cả hai đều chỉ cần danh sách alias.
  const organizations = Array.isArray(organizationClaim) ? organizationClaim : Object.keys(organizationClaim);

  const sessionId = randomToken(32);
  sessions.set(sessionId, {
    createdAt: Date.now(),
    idToken: tokens.id_token,
    host: flow.host,
    user: {
      name: userinfo.name || idTokenClaims.name || '—',
      username: userinfo.preferred_username || idTokenClaims.preferred_username || '—',
      email: userinfo.email || '—',
      roles,
      organizations,
    },
  });
  redirect(res, '/', {
    'Set-Cookie': [clearFlowCookie, cookieHeader(SESSION_COOKIE, sessionId, SESSION_TTL_MS / 1000)],
  });
}

async function handleLogout(req, res) {
  const cookies = parseCookies(req);
  const session = sessions.get(cookies[SESSION_COOKIE]);
  if (cookies[SESSION_COOKIE]) sessions.delete(cookies[SESSION_COOKIE]);
  const clearSessionCookie = cookieHeader(SESSION_COOKIE, '', 0);
  if (!session) return redirect(res, '/', { 'Set-Cookie': clearSessionCookie });

  const endpoints = await discover();
  if (!endpoints.end_session_endpoint) return redirect(res, '/', { 'Set-Cookie': clearSessionCookie });
  const logoutUrl = new URL(endpoints.end_session_endpoint);
  logoutUrl.searchParams.set('id_token_hint', session.idToken);
  logoutUrl.searchParams.set('post_logout_redirect_uri', `${PUBLIC_SCHEME}://${session.host}/`);
  redirect(res, logoutUrl.toString(), { 'Set-Cookie': clearSessionCookie });
}

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://internal');
    if (req.method !== 'GET') return renderError(res, 405, 'Phương thức không được hỗ trợ.');
    if (url.pathname === '/') {
      const cookies = parseCookies(req);
      const session = sessions.get(cookies[SESSION_COOKIE]);
      if (session && Date.now() - session.createdAt <= SESSION_TTL_MS) {
        return renderProfile(req, res, session);
      }
      if (session) sessions.delete(cookies[SESSION_COOKIE]);
      return renderHome(req, res);
    }
    if (url.pathname === '/login') return await handleLogin(req, res);
    if (url.pathname === '/callback') return await handleCallback(req, res, url);
    if (url.pathname === '/logout') return await handleLogout(req, res);
    if (url.pathname === '/favicon.ico') {
      res.writeHead(204);
      return res.end();
    }
    return renderError(res, 404, 'Không tìm thấy trang.');
  } catch (err) {
    console.error('request failed:', err.message);
    return renderError(res, 500, 'Máy chủ gặp lỗi khi xử lý yêu cầu.');
  }
});

server.listen(PORT, () => {
  console.log(`tenant-demo listening on port ${PORT}, issuer ${ISSUER}`);
});
