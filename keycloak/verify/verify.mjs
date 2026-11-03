#!/usr/bin/env node
// Harness kiểm chứng cho mẫu realm Keycloak của issue #6.
//
// Chạy trên một Keycloak 26.8.1 SẠCH (xem compose.verify.yaml cùng
// thư mục, hoặc bất kỳ instance nào trỏ qua KC_BASE_URL) và kiểm
// chứng bằng hành vi thật trên binary thật:
//   - cấp phát 2 realm tenant + realm giaokhoa từ môi trường trống
//     bằng chính keycloak/provision.mjs, chạy lại để chứng minh
//     không trùng lặp và không mất bí mật (tiêu chí nghiệm thu 1);
//   - token thật qua authorization code + PKCE: issuer, audience,
//     role, scope đúng hợp đồng; ID token không mang audience của
//     API; redirect/PKCE/resource sai bị từ chối (nghiệm thu 2);
//   - bằng chứng đăng nhập trình duyệt ở mức HTTP cho MFA chưa khai
//     báo, broker (chỉ mức cấu hình — đăng nhập nhà cung cấp thật
//     cần thông tin đăng nhập kiểm chứng thật, không có ở đây),
//     SSO và brute force (nghiệm thu 3);
//   - refresh token rotation + phát hiện tái dùng, thời lượng phiên
//     theo cấu hình, đổi khoá ký, và diễn tập khôi phục mẫu sau khi
//     cấu hình bị sửa lệch (nghiệm thu 4).
//
// Mọi kiểm tra in một dòng tiếng Việt ĐẠT/KHÔNG ĐẠT/BỎ QUA kèm mô tả
// hành vi; tiến trình thoát khác 0 nếu có kiểm tra KHÔNG ĐẠT. Bí mật
// kiểm chứng sinh ngẫu nhiên trong bộ nhớ của tiến trình, không ghi
// ra file và không in ra log.

import { execFileSync } from "node:child_process";
import { createHash, createHmac, randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const PROVISION = join(HERE, "..", "provision.mjs");

const baseUrl = (process.env.KC_BASE_URL || "http://localhost:8180").replace(/\/$/, "");
const adminUser = process.env.KC_ADMIN_USERNAME || "";
const adminPass = process.env.KC_ADMIN_PASSWORD || "";
const configPath =
  process.env.PROVISION_CONFIG || join(HERE, "..", "provision.config.json");
const config = JSON.parse(readFileSync(configPath, "utf8"));

if (!adminUser || !adminPass) {
  console.error("Thiếu KC_ADMIN_USERNAME / KC_ADMIN_PASSWORD.");
  process.exit(2);
}

const tenants = config.tenants || [];
const tenantA = tenants[0];
const tenantB = tenants[1];
const platform = config.platform;
if (!tenantA) {
  console.error("File cấu hình cấp phát cần ít nhất một tenant.");
  process.exit(2);
}

function realmEnvKey(realm) {
  return realm.toUpperCase().replace(/[^A-Z0-9]+/g, "_");
}
function webSecret(realm) {
  return process.env[`LMS_WEB_SECRET__${realmEnvKey(realm)}`] || "";
}

let passed = 0;
let failed = 0;
let skipped = 0;
function check(name, condition, detail = "") {
  if (condition) {
    passed += 1;
    console.log(`ĐẠT      ${name}`);
  } else {
    failed += 1;
    console.log(`KHÔNG ĐẠT ${name}${detail ? ` — ${detail}` : ""}`);
  }
}
function skip(name, reason) {
  skipped += 1;
  console.log(`BỎ QUA   ${name} — ${reason}`);
}
function section(title) {
  console.log(`\n== ${title} ==`);
}

const testPassword = () => randomBytes(12).toString("base64url") + "aA1!";

// ---------------------------------------------------------------------------
// HTTP có cookie jar + lái form HTML (mô phỏng trình duyệt ở mức HTTP)
// ---------------------------------------------------------------------------

function newJar() {
  return new Map();
}

function jarHeader(jar) {
  return [...jar.entries()].map(([k, v]) => `${k}=${v}`).join("; ");
}

function absorbCookies(jar, res) {
  const setCookies = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  for (const line of setCookies) {
    const pair = line.split(";")[0];
    const idx = pair.indexOf("=");
    if (idx > 0) jar.set(pair.slice(0, idx).trim(), pair.slice(idx + 1).trim());
  }
}

async function http(jar, method, url, { form, json, headers = {} } = {}) {
  const init = { method, redirect: "manual", headers: { ...headers } };
  if (jar) init.headers.Cookie = jarHeader(jar);
  if (form) {
    init.headers["Content-Type"] = "application/x-www-form-urlencoded";
    init.body = new URLSearchParams(form).toString();
  } else if (json !== undefined) {
    init.headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(json);
  }
  const res = await fetch(url, init);
  if (jar) absorbCookies(jar, res);
  const text = await res.text();
  return { status: res.status, headers: res.headers, text };
}

// Trích form đầu tiên trong HTML: action + mọi input có name.
function parseForm(html) {
  const formMatch = html.match(/<form[^>]*action="([^"]+)"[^>]*>/i);
  if (!formMatch) return null;
  const action = formMatch[1].replace(/&amp;/g, "&");
  const fields = {};
  const inputRe = /<input[^>]*>/gi;
  let m;
  while ((m = inputRe.exec(html)) !== null) {
    const tag = m[0];
    const name = tag.match(/name="([^"]+)"/)?.[1];
    if (!name) continue;
    fields[name] = tag.match(/value="([^"]*)"/)?.[1] ?? "";
  }
  return { action, fields };
}

function classifyPage(html) {
  if (/totpSecret|totp-secret/i.test(html)) return "otp-setup";
  if (/name="otp"/i.test(html)) return "otp";
  if (/name="password"/i.test(html)) return "password";
  if (/temporarily disabled|account is disabled/i.test(html)) return "disabled";
  return "other";
}

// TOTP (RFC 6238, HMAC-SHA1, 30 giây, 6 số) — chỉ dùng cho tài khoản
// kiểm chứng do harness tạo, để hoàn tất khai báo MFA như người dùng thật.
function base32Decode(text) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  const clean = text.replace(/[^A-Z2-7]/gi, "").toUpperCase();
  let bits = "";
  for (const ch of clean) bits += alphabet.indexOf(ch).toString(2).padStart(5, "0");
  const bytes = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) {
    bytes.push(parseInt(bits.slice(i, i + 8), 2));
  }
  return Buffer.from(bytes);
}

function totp(secretBase32, atMs = Date.now()) {
  const counter = Math.floor(atMs / 1000 / 30);
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(counter));
  const digest = createHmac("sha1", base32Decode(secretBase32)).update(msg).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const code =
    ((digest[offset] & 0x7f) << 24) |
    ((digest[offset + 1] & 0xff) << 16) |
    ((digest[offset + 2] & 0xff) << 8) |
    (digest[offset + 3] & 0xff);
  return String(code % 1_000_000).padStart(6, "0");
}

// Keycloak từ chối mã TOTP đã được dùng trong cùng cửa sổ 30 giây
// cho cùng một credential. Harness ghi lại cửa sổ đã nộp cho từng
// bí mật kiểm chứng và chờ sang cửa sổ kế tiếp khi cần nộp lại —
// chỉ là nhịp của bộ kiểm chứng, không liên quan hành vi của mẫu.
const usedOtpWindows = new Map();

function noteOtpUsed(secretBase32, atMs = Date.now()) {
  const window = Math.floor(atMs / 30000);
  if (!usedOtpWindows.has(secretBase32)) usedOtpWindows.set(secretBase32, new Set());
  usedOtpWindows.get(secretBase32).add(window);
}

async function freshTotp(secretBase32) {
  for (;;) {
    const now = Date.now();
    const window = Math.floor(now / 30000);
    if (!usedOtpWindows.get(secretBase32)?.has(window)) {
      noteOtpUsed(secretBase32, now);
      return totp(secretBase32, now);
    }
    await new Promise((resolve) => setTimeout(resolve, (window + 1) * 30000 - now + 1500));
  }
}

function extractTotpSecret(html) {
  // Chỉ lấy secret dạng đọc được ở chế độ thủ công (base32). Trường ẩn
  // totpSecret trên trang quét QR là dạng mã hoá của Keycloak 26,
  // không dùng trực tiếp làm khoá TOTP được.
  return (
    html.match(/id="kc-totp-secret-key"[^>]*>([A-Z2-7 ]+)/)?.[1] ||
    html.match(/([A-Z2-7]{4}(?: [A-Z2-7]{4}){2,})/)?.[1] ||
    html.match(/>([A-Z2-7]{16,})</)?.[1] ||
    null
  );
}

// Xử lý trang khai báo TOTP: mở liên kết "Unable to scan?" (mode=manual)
// để lấy secret dạng đọc được, rồi trả về form cần nộp kèm mã TOTP.
async function handleOtpSetup(jar, page, otpState) {
  let form = page.form;
  let secret = extractTotpSecret(page.html);
  const manualLink = page.html.match(/href="([^"]*mode=manual[^"]*)"/)?.[1]?.replace(/&amp;/g, "&");
  if (manualLink) {
    const res = await http(jar, "GET", new URL(manualLink, page.url).toString());
    const manualSecret = extractTotpSecret(res.text);
    if (manualSecret) {
      secret = manualSecret;
      form = parseForm(res.text) || form;
    }
  }
  if (!secret || !form) return null;
  otpState.secret = secret.replace(/ /g, "");
  const setupCode = totp(otpState.secret);
  noteOtpUsed(otpState.secret);
  return {
    action: form.action,
    fields: { ...form.fields, totp: setupCode, userLabel: "harness" },
  };
}

// ---------------------------------------------------------------------------
// JWT (chỉ đọc claim để đối chiếu; chữ ký được Keycloak tự kiểm chứng
// qua các endpoint userinfo/introspection khi harness gọi)
// ---------------------------------------------------------------------------

function decodeJwt(token) {
  const [h, p] = token.split(".");
  const dec = (s) => JSON.parse(Buffer.from(s, "base64url").toString("utf8"));
  return { header: dec(h), payload: dec(p) };
}

function pkce() {
  const verifier = randomBytes(48).toString("base64url");
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  return { verifier, challenge };
}

// ---------------------------------------------------------------------------
// Admin REST API
// ---------------------------------------------------------------------------

let cachedAdminToken = null;
async function adminToken() {
  if (cachedAdminToken) return cachedAdminToken;
  const res = await http(null, "POST", `${baseUrl}/realms/master/protocol/openid-connect/token`, {
    form: {
      client_id: "admin-cli",
      grant_type: "password",
      username: adminUser,
      "password": adminPass,
    },
  });
  if (res.status !== 200) throw new Error(`Lấy token quản trị thất bại: HTTP ${res.status}`);
  cachedAdminToken = JSON.parse(res.text).access_token;
  return cachedAdminToken;
}

async function api(method, path, json) {
  const res = await http(null, method, `${baseUrl}${path}`, {
    json,
    headers: { Authorization: `Bearer ${await adminToken()}` },
  });
  let data = null;
  if (res.text) {
    try {
      data = JSON.parse(res.text);
    } catch {
      data = res.text;
    }
  }
  return { status: res.status, data };
}

async function createUser(realm, username, password) {
  _passwords.set(username, password);
  const created = await api("POST", `/admin/realms/${realm}/users`, {
    username,
    enabled: true,
    email: `${username}@example.invalid`,
    emailVerified: true,
    firstName: "Kiểm",
    lastName: "Chứng",
    credentials: [{ type: "password", value: password, temporary: false }],
    requiredActions: [],
  });
  if (created.status !== 201) throw new Error(`Tạo user ${username} ở ${realm}: HTTP ${created.status}`);
  const { data } = await api(
    "GET",
    `/admin/realms/${realm}/users?username=${encodeURIComponent(username)}&exact=true`,
  );
  return data[0];
}

async function assignRealmRole(realm, userId, roleName) {
  const { data: role } = await api("GET", `/admin/realms/${realm}/roles/${roleName}`);
  const res = await api("POST", `/admin/realms/${realm}/users/${userId}/role-mappings/realm`, [role]);
  if (res.status !== 204) throw new Error(`Gán vai ${roleName}: HTTP ${res.status}`);
}

async function assignClientRole(realm, userId, clientId, roleName) {
  const { data: clients } = await api(
    "GET",
    `/admin/realms/${realm}/clients?clientId=${encodeURIComponent(clientId)}`,
  );
  const client = clients[0];
  const { data: role } = await api(
    "GET",
    `/admin/realms/${realm}/clients/${client.id}/roles/${roleName}`,
  );
  const res = await api(
    "POST",
    `/admin/realms/${realm}/users/${userId}/role-mappings/clients/${client.id}`,
    [role],
  );
  if (res.status !== 204) throw new Error(`Gán client role ${roleName}: HTTP ${res.status}`);
}

async function clientInternalId(realm, clientId) {
  const { data } = await api(
    "GET",
    `/admin/realms/${realm}/clients?clientId=${encodeURIComponent(clientId)}`,
  );
  return data[0]?.id;
}

async function clientSecretValue(realm, clientId) {
  const id = await clientInternalId(realm, clientId);
  const { data } = await api("GET", `/admin/realms/${realm}/clients/${id}/client-secret`);
  return data?.value;
}

function runProvision() {
  execFileSync("node", [PROVISION, "--config", configPath], {
    env: process.env,
    stdio: "pipe",
  });
}

// ---------------------------------------------------------------------------
// Lái luồng authorization code + PKCE qua form đăng nhập HTML
// ---------------------------------------------------------------------------

function authorizeUrl(realm, { clientId, redirectUri, challenge, acrValues, idpHint, prompt, resource }) {
  const u = new URL(`${baseUrl}/realms/${realm}/protocol/openid-connect/auth`);
  u.searchParams.set("client_id", clientId);
  u.searchParams.set("redirect_uri", redirectUri);
  u.searchParams.set("response_type", "code");
  u.searchParams.set("scope", "openid");
  u.searchParams.set("state", randomBytes(8).toString("hex"));
  u.searchParams.set("nonce", randomBytes(8).toString("hex"));
  if (challenge) {
    u.searchParams.set("code_challenge", challenge);
    u.searchParams.set("code_challenge_method", "S256");
  }
  if (acrValues) u.searchParams.set("acr_values", acrValues);
  if (idpHint) u.searchParams.set("kc_idp_hint", idpHint);
  if (prompt) u.searchParams.set("prompt", prompt);
  if (resource) u.searchParams.set("resource", resource);
  return u.toString();
}

// Đi theo chuỗi redirect/form cho tới khi về redirect_uri (mang code
// hoặc error) hoặc gặp trang cần thao tác. `onPage` quyết định thao
// tác theo loại trang và trả về fields để nộp, hoặc null để dừng.
async function driveFlow(jar, startUrl, redirectUri, onPage, maxSteps = 14) {
  let url = startUrl;
  let method = "GET";
  let form = null;
  for (let step = 0; step < maxSteps; step += 1) {
    const res = await http(jar, method, url, { form });
    method = "GET";
    form = null;
    const location = res.headers.get("location");
    if (res.status >= 300 && res.status < 400 && location) {
      const next = new URL(location, url).toString();
      if (next.startsWith(redirectUri)) {
        const parsed = new URL(next);
        return {
          outcome: "redirect",
          code: parsed.searchParams.get("code"),
          error: parsed.searchParams.get("error"),
          location: next,
        };
      }
      url = next;
      continue;
    }
    if (res.status === 200) {
      const kind = classifyPage(res.text);
      const parsed = parseForm(res.text);
      const decision = await onPage({ kind, html: res.text, form: parsed, url });
      if (!decision) return { outcome: "page", kind, html: res.text, url };
      url = new URL(decision.action, url).toString();
      form = decision.fields;
      method = "POST";
      continue;
    }
    return { outcome: "http-error", status: res.status, html: res.text, url };
  }
  return { outcome: "too-many-steps", url };
}

// onPage chuẩn: nộp mật khẩu ở trang password; ở trang OTP thì nộp mã
// từ `otpState` ({secret}) — trang khai báo TOTP được xử lý riêng vì
// cần lấy secret từ chính trang đó.
function standardOnPage({ username, password, otpState }) {
  return async ({ kind, form }) => {
    if (!form) return null;
    if (kind === "password") {
      return { action: form.action, fields: { ...form.fields, username, password } };
    }
    if (kind === "otp") {
      if (!otpState?.secret) return null;
      return {
        action: form.action,
        fields: { ...form.fields, otp: await freshTotp(otpState.secret) },
      };
    }
    return null;
  };
}

async function exchangeCode(realm, { clientId, secret, code, redirectUri, verifier, resource }) {
  const form = {
    grant_type: "authorization_code",
    client_id: clientId,
    code,
    redirect_uri: redirectUri,
  };
  if (secret) form.client_secret = secret;
  if (verifier) form.code_verifier = verifier;
  if (resource) form.resource = resource;
  const res = await http(null, "POST", `${baseUrl}/realms/${realm}/protocol/openid-connect/token`, {
    form,
  });
  let data = null;
  try {
    data = JSON.parse(res.text);
  } catch {
    /* giữ nguyên null */
  }
  return { status: res.status, data };
}

async function refresh(realm, { clientId, secret, refreshToken }) {
  const res = await http(null, "POST", `${baseUrl}/realms/${realm}/protocol/openid-connect/token`, {
    form: {
      grant_type: "refresh_token",
      client_id: clientId,
      client_secret: secret,
      refresh_token: refreshToken,
    },
  });
  let data = null;
  try {
    data = JSON.parse(res.text);
  } catch {
    /* giữ nguyên null */
  }
  return { status: res.status, data };
}

// Đăng nhập đầy đủ một user qua lms-web của realm tenant; otpState
// được điền secret khi user phải khai báo TOTP giữa chừng.
async function loginTenantUser(realm, tenant, username, password, { acrValues, otpState: givenOtpState } = {}) {
  const jar = newJar();
  const { verifier, challenge } = pkce();
  const redirectUri = `https://${tenant.host}/api/auth/callback/keycloak`;
  const otpState = givenOtpState || { secret: null };
  const baseOnPage = standardOnPage({ username, password, otpState });
  const onPage = async (page) => {
    if (page.kind === "otp-setup") return handleOtpSetup(jar, page, otpState);
    return baseOnPage(page);
  };
  const result = await driveFlow(
    jar,
    authorizeUrl(realm, { clientId: "lms-web", redirectUri, challenge, acrValues }),
    redirectUri,
    onPage,
  );
  return { result, jar, verifier, redirectUri, otpState };
}

// ---------------------------------------------------------------------------
// Các pha kiểm chứng
// ---------------------------------------------------------------------------

const users = {};

async function phaseProvisioning() {
  section("Nghiệm thu 1 — cấp phát từ môi trường trống và chạy lại không trùng, không mất bí mật");
  let firstRunOk = true;
  try {
    runProvision();
  } catch {
    firstRunOk = false;
  }
  check("Cấp phát lần đầu trên Keycloak trống chạy thành công (2 tenant + giaokhoa + master)", firstRunOk);
  if (!firstRunOk) throw new Error("Cấp phát lần đầu thất bại — dừng harness");

  const { data: realms } = await api("GET", "/admin/realms");
  const realmNames = realms.map((r) => r.realm).sort();
  const expected = [tenantA.realm, ...(tenantB ? [tenantB.realm] : []), platform.realm, "master"].sort();
  check(
    "Số realm đúng kỳ vọng sau cấp phát lần đầu",
    JSON.stringify(realmNames) === JSON.stringify(expected),
    `thực tế: ${realmNames.join(", ")}`,
  );

  // Tạo người dùng kiểm chứng và ghi lại các giá trị phải bền qua lần chạy lại.
  users.teacher = await createUser(tenantA.realm, "gv-kiem-chung", testPassword());
  await assignRealmRole(tenantA.realm, users.teacher.id, "tenant-teacher");
  users.member = await createUser(tenantA.realm, "hv-kiem-chung", testPassword());
  await assignRealmRole(tenantA.realm, users.member.id, "tenant-member");
  users.admin = await createUser(tenantA.realm, "qtv-kiem-chung", testPassword());
  await assignRealmRole(tenantA.realm, users.admin.id, "tenant-admin");
  users.memberB = tenantB
    ? await createUser(tenantB.realm, "hv-tenant-b", testPassword())
    : null;
  if (users.memberB) await assignRealmRole(tenantB.realm, users.memberB.id, "tenant-member");
  users.platformAdmin = await createUser(platform.realm, "qtv-nen-tang", testPassword());
  await assignClientRole(platform.realm, users.platformAdmin.id, "platform-api", "platform-admin");

  const memberSubBefore = users.member.id;
  const webSecretBefore = await clientSecretValue(tenantA.realm, "lms-web");
  const { data: clientsBefore } = await api("GET", `/admin/realms/${tenantA.realm}/clients`);
  check("Bí mật của lms-web đọc được sau lần cấp phát đầu (để đối chiếu lần chạy lại)", Boolean(webSecretBefore));

  let secondRunOk = true;
  try {
    runProvision();
  } catch {
    secondRunOk = false;
  }
  check("Cấp phát chạy lần hai thành công (không lỗi)", secondRunOk);

  const { data: realmsAfter } = await api("GET", "/admin/realms");
  check(
    "Chạy lại không tạo realm trùng",
    realmsAfter.length === realms.length,
    `trước ${realms.length}, sau ${realmsAfter.length}`,
  );
  const { data: clientsAfter } = await api("GET", `/admin/realms/${tenantA.realm}/clients`);
  check(
    "Chạy lại không tạo client trùng trong realm tenant",
    clientsAfter.length === clientsBefore.length,
    `trước ${clientsBefore.length}, sau ${clientsAfter.length}`,
  );
  const webSecretAfter = await clientSecretValue(tenantA.realm, "lms-web");
  check("Chạy lại không ghi đè hay làm mất bí mật của lms-web", webSecretAfter === webSecretBefore);
  const { data: memberAfter } = await api("GET", `/admin/realms/${tenantA.realm}/users/${memberSubBefore}`);
  check("Danh tính người dùng (sub) bền qua lần chạy lại", memberAfter?.id === memberSubBefore);

  const { data: roles } = await api("GET", `/admin/realms/${tenantA.realm}/roles`);
  const primaries = roles.filter((r) => ["tenant-admin", "tenant-teacher", "tenant-member"].includes(r.name));
  check(
    "Ba vai chính tồn tại và không vai nào là composite",
    primaries.length === 3 && primaries.every((r) => r.composite === false),
  );
  const { data: defaultComposites } = await api(
    "GET",
    `/admin/realms/${tenantA.realm}/roles/default-roles-${tenantA.realm}/composites`,
  );
  const defaultNames = (defaultComposites || []).map((r) => r.name);
  check(
    "default-roles không chứa offline_access và không chứa vai chính nào (không gán ngầm hai vai chính)",
    !defaultNames.includes("offline_access") &&
      !defaultNames.some((n) => ["tenant-admin", "tenant-teacher", "tenant-member"].includes(n)),
    `default-roles gồm: ${defaultNames.join(", ")}`,
  );
}

async function phaseTokens() {
  section("Nghiệm thu 2 — token thật đúng issuer/audience/role/scope; sai redirect/PKCE/resource bị từ chối");
  const realm = tenantA.realm;
  const secret = webSecret(realm);

  // Member đăng nhập không qua OTP.
  const memberLogin = await loginTenantUser(realm, tenantA, "hv-kiem-chung", pw("hv-kiem-chung"));
  _memberJar = memberLogin.jar;
  check(
    "Thành viên đăng nhập qua form mà không bị đòi OTP",
    memberLogin.result.outcome === "redirect" && Boolean(memberLogin.result.code),
    `kết quả: ${memberLogin.result.outcome}/${memberLogin.result.kind || ""}`,
  );
  const memberTokens = await exchangeCode(realm, {
    clientId: "lms-web",
    secret,
    code: memberLogin.result.code,
    redirectUri: memberLogin.redirectUri,
    verifier: memberLogin.verifier,
  });
  check("Đổi code của thành viên lấy token thành công", memberTokens.status === 200, `HTTP ${memberTokens.status}`);
  const access = decodeJwt(memberTokens.data.access_token);
  const idToken = decodeJwt(memberTokens.data.id_token);
  check("Issuer của access token là realm tenant đúng", access.payload.iss === `${baseUrl}/realms/${realm}`);
  check(
    "Access token mang audience lms-api",
    (Array.isArray(access.payload.aud) ? access.payload.aud : [access.payload.aud]).includes("lms-api"),
    `aud: ${JSON.stringify(access.payload.aud)}`,
  );
  check(
    "ID token chỉ mang audience lms-web, không bị gắn audience của API",
    idToken.payload.aud === "lms-web",
    `aud ID token: ${JSON.stringify(idToken.payload.aud)}`,
  );
  const memberRoles = access.payload.realm_access?.roles || [];
  check(
    "Access token của thành viên chỉ mang vai chính tenant-member",
    memberRoles.includes("tenant-member") &&
      !memberRoles.includes("tenant-teacher") &&
      !memberRoles.includes("tenant-admin"),
    `roles: ${memberRoles.join(", ")}`,
  );
  check(
    "Access token không mang claim trạng thái khoá học/lớp/con (không claim nghiệp vụ trong token)",
    !("course" in access.payload) && !("classId" in access.payload) && !("children" in access.payload),
  );
  check(
    "Tuổi thọ access token của tenant là 5 phút theo mẫu",
    access.payload.exp - access.payload.iat === 300,
    `exp-iat: ${access.payload.exp - access.payload.iat}`,
  );
  check(
    "Scope của token không liệt kê scope audience (include.in.token.scope tắt)",
    !(memberTokens.data.scope || "").split(" ").includes("lms-api-audience"),
    `scope: ${memberTokens.data.scope}`,
  );
  users.memberTokens = memberTokens.data;

  // Redirect URI sai bị từ chối ngay ở endpoint authorize.
  const { challenge } = pkce();
  const badRedirect = await http(
    newJar(),
    "GET",
    authorizeUrl(realm, {
      clientId: "lms-web",
      redirectUri: "https://evil.example/callback",
      challenge,
    }),
  );
  check(
    "Redirect URI chưa đăng ký bị từ chối (không cấp code)",
    badRedirect.status === 400 && /redirect/i.test(badRedirect.text),
    `HTTP ${badRedirect.status}`,
  );

  // PKCE sai bị từ chối ở endpoint token.
  const jar2 = newJar();
  const pk = pkce();
  const redirectUri = `https://${tenantA.host}/api/auth/callback/keycloak`;
  const flow = await driveFlow(
    jar2,
    authorizeUrl(realm, { clientId: "lms-web", redirectUri, challenge: pk.challenge }),
    redirectUri,
    standardOnPage({ username: "hv-kiem-chung", password: pw("hv-kiem-chung"), otpState: { secret: null } }),
  );
  const wrongVerifier = await exchangeCode(realm, {
    clientId: "lms-web",
    secret,
    code: flow.code,
    redirectUri,
    verifier: "verifier-sai-hoan-toan-0123456789-abcdefghijklmnop",
  });
  check(
    "Code verifier PKCE sai bị endpoint token từ chối",
    wrongVerifier.status === 400,
    `HTTP ${wrongVerifier.status}`,
  );

  // Resource indicators (RFC 8707, tính năng thử nghiệm của 26.8.1)
  // qua client MCP đăng ký trước (lms-mcp-client): resource phải khai
  // ở cả endpoint authorize lẫn endpoint token (Keycloak đối chiếu
  // với "original request"); resource lạ bị từ chối, resource khớp
  // resource_url trên client lms-mcp thì aud của token chỉ còn đúng
  // giá trị resource đó.
  const mcpRedirectUri = (tenantA.mcpRedirectUris || [])[0];
  async function mcpFlow(resource) {
    const jar = newJar();
    const pk = pkce();
    const flow = await driveFlow(
      jar,
      authorizeUrl(realm, {
        clientId: "lms-mcp-client",
        redirectUri: mcpRedirectUri,
        challenge: pk.challenge,
        resource,
      }),
      mcpRedirectUri,
      standardOnPage({ username: "hv-kiem-chung", password: pw("hv-kiem-chung"), otpState: { secret: null } }),
    );
    return { flow, pk };
  }
  const wrongAttempt = await mcpFlow("http://evil.example/mcp");
  let wrongRejected = wrongAttempt.flow.outcome !== "redirect" || !wrongAttempt.flow.code;
  if (!wrongRejected) {
    const wrongResource = await exchangeCode(realm, {
      clientId: "lms-mcp-client",
      code: wrongAttempt.flow.code,
      redirectUri: mcpRedirectUri,
      verifier: wrongAttempt.pk.verifier,
      resource: "http://evil.example/mcp",
    });
    wrongRejected = wrongResource.status === 400;
  }
  check(
    "Resource indicator không khớp resource_url nào đã đăng ký bị từ chối (invalid_target)",
    wrongRejected,
    `flow: ${wrongAttempt.flow.outcome}`,
  );
  const rightAttempt = await mcpFlow(tenantA.mcpResourceUrl);
  const rightResource = await exchangeCode(realm, {
    clientId: "lms-mcp-client",
    code: rightAttempt.flow.code,
    redirectUri: mcpRedirectUri,
    verifier: rightAttempt.pk.verifier,
    resource: tenantA.mcpResourceUrl,
  });
  const audRight = rightResource.data?.access_token
    ? decodeJwt(rightResource.data.access_token).payload.aud
    : null;
  check(
    "Resource indicator khớp resource_url trên lms-mcp: aud của token chỉ còn đúng resource đó",
    rightResource.status === 200 &&
      JSON.stringify(Array.isArray(audRight) ? audRight : [audRight]) ===
        JSON.stringify([tenantA.mcpResourceUrl]),
    `HTTP ${rightResource.status}, aud: ${JSON.stringify(audRight)}`,
  );
}

async function phaseMfa() {
  section("Nghiệm thu 3 — MFA theo vai, step-up LoA và SSO (bằng chứng đăng nhập ở mức HTTP)");
  const realm = tenantA.realm;

  // Giáo viên chưa khai báo MFA: đăng nhập bị chặn ở bước khai báo TOTP,
  // hoàn tất khai báo thì mới vào được.
  const teacherLogin = await loginTenantUser(realm, tenantA, "gv-kiem-chung", pw("gv-kiem-chung"));
  check(
    "Giáo viên chưa khai báo MFA bị buộc khai báo TOTP rồi mới đăng nhập xong",
    teacherLogin.result.outcome === "redirect" &&
      Boolean(teacherLogin.result.code) &&
      Boolean(teacherLogin.otpState.secret),
    `kết quả: ${teacherLogin.result.outcome}/${teacherLogin.result.kind || ""}, đã thấy trang khai báo: ${Boolean(teacherLogin.otpState.secret)}`,
  );
  users.teacherOtpSecret = teacherLogin.otpState.secret;

  const teacherTokens = await exchangeCode(realm, {
    clientId: "lms-web",
    secret: webSecret(realm),
    code: teacherLogin.result.code,
    redirectUri: teacherLogin.redirectUri,
    verifier: teacherLogin.verifier,
  });
  const teacherRoles = teacherTokens.data?.access_token
    ? decodeJwt(teacherTokens.data.access_token).payload.realm_access?.roles || []
    : [];
  check(
    "Token của giáo viên sau MFA mang vai tenant-teacher",
    teacherRoles.includes("tenant-teacher") && !teacherRoles.includes("tenant-admin"),
    `roles: ${teacherRoles.join(", ")}`,
  );

  // Lần đăng nhập sau của giáo viên: mật khẩu xong phải qua bước OTP.
  const jar = newJar();
  const pk = pkce();
  let sawOtp = false;
  const onPage = async (page) => {
    if (page.kind === "otp") {
      sawOtp = true;
      return {
        action: page.form.action,
        fields: { ...page.form.fields, otp: await freshTotp(users.teacherOtpSecret) },
      };
    }
    if (page.kind === "password") {
      return {
        action: page.form.action,
        fields: { ...page.form.fields, username: "gv-kiem-chung", password: pw("gv-kiem-chung") },
      };
    }
    return null;
  };
  const second = await driveFlow(
    jar,
    authorizeUrl(realm, { clientId: "lms-web", redirectUri: teacherLogin.redirectUri, challenge: pk.challenge }),
    teacherLogin.redirectUri,
    onPage,
  );
  check(
    "Giáo viên đã khai báo MFA: đăng nhập lần sau bắt buộc nhập OTP",
    sawOtp && second.outcome === "redirect" && Boolean(second.code),
  );

  // Nâng vai giữa chừng: user đang là member đăng nhập không OTP; sau
  // khi được gán vai teacher thì lần đăng nhập kế tiếp bị bắt MFA.
  const promoted = await createUser(realm, "hv-nang-vai", testPassword());
  await assignRealmRole(realm, promoted.id, "tenant-member");
  const promotedPassword = pw("hv-nang-vai");
  const beforePromotion = await loginTenantUser(realm, tenantA, "hv-nang-vai", promotedPassword);
  check(
    "User trước khi nâng vai đăng nhập không cần OTP",
    beforePromotion.result.outcome === "redirect" && !beforePromotion.otpState.secret,
  );
  await assignRealmRole(realm, promoted.id, "tenant-teacher");
  const afterPromotion = await loginTenantUser(realm, tenantA, "hv-nang-vai", promotedPassword);
  check(
    "Sau khi nâng lên vai teacher, đăng nhập bị bắt khai báo/nhập MFA",
    afterPromotion.result.outcome === "redirect" && Boolean(afterPromotion.otpState.secret),
  );

  // Step-up LoA 2 cho member đã có sẵn credential OTP (member tự khai
  // báo qua required action): đăng nhập thường không OTP, yêu cầu
  // acr loa2 thì bị bắt OTP và token mang acr loa2.
  const stepper = await createUser(realm, "hv-stepup", testPassword());
  await assignRealmRole(realm, stepper.id, "tenant-member");
  await api("PUT", `/admin/realms/${realm}/users/${stepper.id}`, {
    ...stepper,
    requiredActions: ["CONFIGURE_TOTP"],
  });
  const enroll = await loginTenantUser(realm, tenantA, "hv-stepup", pw("hv-stepup"));
  check(
    "Member được gắn yêu cầu khai báo TOTP thì hoàn tất khai báo khi đăng nhập",
    enroll.result.outcome === "redirect" && Boolean(enroll.otpState.secret),
  );
  const stepLogin = await loginTenantUser(realm, tenantA, "hv-stepup", pw("hv-stepup"), {
    acrValues: "loa2",
    otpState: enroll.otpState,
  });
  const stepTokens = await exchangeCode(realm, {
    clientId: "lms-web",
    secret: webSecret(realm),
    code: stepLogin.result.code,
    redirectUri: stepLogin.redirectUri,
    verifier: stepLogin.verifier,
  });
  const stepAcr = stepTokens.data?.access_token
    ? decodeJwt(stepTokens.data.access_token).payload.acr
    : null;
  check(
    "Step-up: yêu cầu acr loa2 bắt OTP và token đạt acr loa2",
    stepLogin.result.outcome === "redirect" && stepAcr === "loa2",
    `acr: ${stepAcr}`,
  );

  // SSO: cùng phiên trình duyệt, yêu cầu authorize thứ hai không hỏi lại mật khẩu.
  const jarSso = memberLoginJar();
  const pkSso = pkce();
  let askedPassword = false;
  const ssoResult = await driveFlow(
    jarSso,
    authorizeUrl(realm, { clientId: "lms-web", redirectUri: teacherLogin.redirectUri, challenge: pkSso.challenge }),
    teacherLogin.redirectUri,
    async (page) => {
      if (page.kind === "password") askedPassword = true;
      return null;
    },
  );
  check(
    "SSO: authorize lần hai trong cùng phiên không hỏi lại mật khẩu và cấp code ngay",
    !askedPassword && ssoResult.outcome === "redirect" && Boolean(ssoResult.code),
  );
}

// Tái dùng đúng cookie jar của lần đăng nhập member ở pha token.
let _memberJar = null;
function memberLoginJar() {
  return _memberJar;
}

async function phaseBruteForce() {
  section("Nghiệm thu 3 — chống brute force: 5 lần sai thì tài khoản bị khoá tạm");
  const realm = tenantA.realm;
  const victim = await createUser(realm, "hv-bi-khoa", testPassword());
  await assignRealmRole(realm, victim.id, "tenant-member");
  const redirectUri = `https://${tenantA.host}/api/auth/callback/keycloak`;

  let blocked = false;
  for (let attempt = 1; attempt <= 6; attempt += 1) {
    const jar = newJar();
    const { challenge } = pkce();
    const password = attempt <= 5 ? `sai-mat-khau-${attempt}` : pw("hv-bi-khoa");
    const result = await driveFlow(
      jar,
      authorizeUrl(realm, { clientId: "lms-web", redirectUri, challenge }),
      redirectUri,
      standardOnPage({ username: "hv-bi-khoa", password, otpState: { secret: null } }),
      6,
    );
    if (attempt === 6) {
      blocked = !(result.outcome === "redirect" && result.code);
    }
  }
  check(
    "Sau 5 lần sai mật khẩu, lần thứ 6 dùng đúng mật khẩu vẫn bị chặn (khoá tạm theo mẫu)",
    blocked,
  );
}

async function phaseRefreshRotation() {
  section("Nghiệm thu 4 — refresh token rotation và phát hiện tái dùng");
  const realm = tenantA.realm;
  const secret = webSecret(realm);
  const first = users.memberTokens;
  check("Có refresh token từ lần đăng nhập của thành viên để kiểm chứng", Boolean(first?.refresh_token));
  if (!first?.refresh_token) return;

  const rotated = await refresh(realm, { clientId: "lms-web", secret, refreshToken: first.refresh_token });
  check(
    "Refresh hợp lệ cấp cặp token mới và refresh token được xoay (khác token cũ)",
    rotated.status === 200 && rotated.data.refresh_token && rotated.data.refresh_token !== first.refresh_token,
    `HTTP ${rotated.status}`,
  );
  const reuse = await refresh(realm, { clientId: "lms-web", secret, refreshToken: first.refresh_token });
  check(
    "Tái dùng refresh token cũ bị từ chối",
    reuse.status === 400,
    `HTTP ${reuse.status}`,
  );
  const afterReuse = await refresh(realm, {
    clientId: "lms-web",
    secret,
    refreshToken: rotated.data.refresh_token,
  });
  check(
    "Sau khi phát hiện tái dùng, cả phiên bị thu hồi (refresh token mới nhất cũng bị từ chối)",
    afterReuse.status === 400,
    `HTTP ${afterReuse.status}`,
  );
}

async function phaseBrokerConfig() {
  section("Nghiệm thu 3 — broker: cấu hình IdP và callback (không tuyên bố đăng nhập nhà cung cấp thật)");
  const realm = tenantA.realm;
  if (!process.env.GOOGLE_CLIENT_ID || !process.env.GOOGLE_CLIENT_SECRET) {
    skip("IdP google/facebook được tạo với callback và flow hậu broker đúng mẫu", "môi trường chưa cấp thông tin đăng nhập IdP kiểm chứng");
    return;
  }
  for (const alias of ["google", "facebook"]) {
    const { status, data } = await api("GET", `/admin/realms/${realm}/identity-provider/instances/${alias}`);
    check(
      `IdP ${alias}: tồn tại, tắt lưu token ngoài, gắn flow hậu broker MFA và flow first-broker mặc định`,
      status === 200 &&
        data.storeToken === false &&
        data.postBrokerLoginFlowAlias === "lms-post-broker-mfa" &&
        data.firstBrokerLoginFlowAlias === "first broker login",
      `HTTP ${status}`,
    );
  }
  // Bắt đầu đăng nhập qua google: Keycloak phải 302 sang Google với
  // redirect_uri là endpoint broker của đúng realm — bằng chứng
  // callback khớp mà không cần tài khoản Google kiểm chứng thật.
  const jar = newJar();
  const { challenge } = pkce();
  const redirectUri = `https://${tenantA.host}/api/auth/callback/keycloak`;
  let res = await http(
    jar,
    "GET",
    authorizeUrl(realm, { clientId: "lms-web", redirectUri, challenge, idpHint: "google" }),
  );
  // Keycloak trả trước một hop nội bộ tới endpoint broker của chính
  // nó; đi tiếp cho tới khi Location trỏ ra ngoài (tới Google).
  let location = res.headers.get("location") || "";
  for (let hop = 0; hop < 5 && location.startsWith(baseUrl); hop += 1) {
    res = await http(jar, "GET", location);
    location = res.headers.get("location") || "";
  }
  let googleUrl = null;
  try {
    googleUrl = new URL(location);
  } catch {
    /* giữ null */
  }
  check(
    "Bắt đầu broker google: Keycloak chuyển sang Google với redirect_uri là endpoint broker của realm",
    res.status >= 300 &&
      res.status < 400 &&
      googleUrl?.host === "accounts.google.com" &&
      googleUrl?.searchParams.get("redirect_uri") === `${baseUrl}/realms/${realm}/broker/google/endpoint`,
    `HTTP ${res.status}, location: ${location.slice(0, 140)}`,
  );
}

async function phasePlatform() {
  section("Realm giaokhoa — platform-admin là client role của platform-api, MFA bắt buộc cho mọi tài khoản");
  const realm = platform.realm;
  const redirectUri = `https://${platform.host}/api/auth/callback/keycloak`;
  const jar = newJar();
  const { verifier, challenge } = pkce();
  const otpState = { secret: null };
  const onPage = async (page) => {
    if (page.kind === "otp-setup") return handleOtpSetup(jar, page, otpState);
    if (page.kind === "password") {
      return {
        action: page.form.action,
        fields: { ...page.form.fields, username: "qtv-nen-tang", password: pw("qtv-nen-tang") },
      };
    }
    if (page.kind === "otp" && otpState.secret) {
      return { action: page.form.action, fields: { ...page.form.fields, otp: await freshTotp(otpState.secret) } };
    }
    return null;
  };
  const result = await driveFlow(
    jar,
    authorizeUrl(realm, { clientId: "platform-web", redirectUri, challenge }),
    redirectUri,
    onPage,
  );
  check(
    "Tài khoản nền tảng chưa có MFA bị buộc khai báo TOTP (flow platform bắt OTP vô điều kiện)",
    result.outcome === "redirect" && Boolean(result.code) && Boolean(otpState.secret),
    `kết quả: ${result.outcome}/${result.kind || ""}`,
  );
  const tokens = await exchangeCode(realm, {
    clientId: "platform-web",
    secret: process.env.PLATFORM_WEB_SECRET || "",
    code: result.code,
    redirectUri,
    verifier,
  });
  const access = tokens.data?.access_token ? decodeJwt(tokens.data.access_token) : null;
  const aud = access ? (Array.isArray(access.payload.aud) ? access.payload.aud : [access.payload.aud]) : [];
  const clientRoles = access?.payload.resource_access?.["platform-api"]?.roles || [];
  const realmRoles = access?.payload.realm_access?.roles || [];
  check(
    "Token nền tảng: audience platform-api và client role platform-admin",
    aud.includes("platform-api") && clientRoles.includes("platform-admin"),
    `aud: ${aud.join(",")}; client roles: ${clientRoles.join(",")}`,
  );
  check(
    "Realm giaokhoa không phát vai tenant nào trong token nền tảng",
    !realmRoles.some((r) => r.startsWith("tenant-")),
    `realm roles: ${realmRoles.join(", ")}`,
  );
}

async function phaseLifetimesAndAudit() {
  section("Nghiệm thu 4 — thời lượng phiên, refresh và kiểm toán theo cấu hình mẫu");
  const { data: tenant } = await api("GET", `/admin/realms/${tenantA.realm}`);
  check(
    "Tenant: access token 5 phút, SSO idle/max 60 phút/12 giờ, client session idle/max 30 phút/8 giờ",
    tenant.accessTokenLifespan === 300 &&
      tenant.ssoSessionIdleTimeout === 3600 &&
      tenant.ssoSessionMaxLifespan === 43200 &&
      tenant.clientSessionIdleTimeout === 1800 &&
      tenant.clientSessionMaxLifespan === 28800,
    JSON.stringify({
      a: tenant.accessTokenLifespan,
      b: tenant.ssoSessionIdleTimeout,
      c: tenant.ssoSessionMaxLifespan,
      d: tenant.clientSessionIdleTimeout,
      e: tenant.clientSessionMaxLifespan,
    }),
  );
  const { data: plat } = await api("GET", `/admin/realms/${platform.realm}`);
  check(
    "Realm nền tảng: access token 2 phút, SSO idle/max 15 phút/8 giờ, client session idle/max 10 phút/4 giờ",
    plat.accessTokenLifespan === 120 &&
      plat.ssoSessionIdleTimeout === 900 &&
      plat.ssoSessionMaxLifespan === 28800 &&
      plat.clientSessionIdleTimeout === 600 &&
      plat.clientSessionMaxLifespan === 14400,
  );
  check(
    "Cả hai realm: refresh token rotation bật, tái dùng tối đa 0, Remember Me tắt, brute force 5 lần chờ tăng 60 giây trần 15 phút",
    [tenant, plat].every(
      (r) =>
        r.revokeRefreshToken === true &&
        r.refreshTokenMaxReuse === 0 &&
        r.rememberMe === false &&
        r.bruteForceProtected === true &&
        r.failureFactor === 5 &&
        r.waitIncrementSeconds === 60 &&
        r.maxFailureWaitSeconds === 900,
    ),
  );
  // Kiểm toán không lộ bí mật: sự kiện quản trị của realm tenant không
  // được chứa giá trị bí mật của client trong bất kỳ trường nào.
  const secretValue = await clientSecretValue(tenantA.realm, "lms-web");
  const { data: events } = await api(
    "GET",
    `/admin/realms/${tenantA.realm}/admin-events?max=100`,
  );
  check(
    "Sự kiện kiểm toán bật và không chứa giá trị bí mật của client",
    Array.isArray(events) &&
      events.length > 0 &&
      secretValue &&
      !JSON.stringify(events).includes(secretValue),
    `số sự kiện: ${Array.isArray(events) ? events.length : "n/a"}`,
  );
  check(
    "Chi tiết sự kiện quản trị tắt (adminEventsDetailsEnabled=false) để representation không lọt vào log kiểm toán",
    tenant.adminEventsDetailsEnabled === false,
  );
}

async function phaseKeyRotation() {
  section("Nghiệm thu 4 — đổi khoá ký: token mới ký bằng khoá mới, token cũ vẫn kiểm chứng được");
  const realm = tenantA.realm;
  const certsBefore = await http(null, "GET", `${baseUrl}/realms/${realm}/protocol/openid-connect/certs`);
  const kidsBefore = new Set(JSON.parse(certsBefore.text).keys.map((k) => k.kid));
  const preLogin = await loginTenantUser(realm, tenantA, "hv-kiem-chung", pw("hv-kiem-chung"));
  const preTokens = await exchangeCode(realm, {
    clientId: "lms-web",
    secret: webSecret(realm),
    code: preLogin.result.code,
    redirectUri: preLogin.redirectUri,
    verifier: preLogin.verifier,
  });
  const oldToken = preTokens.data?.access_token;
  const created = await api("POST", `/admin/realms/${realm}/components`, {
    name: "rsa-kiem-chung",
    providerId: "rsa-generated",
    providerType: "org.keycloak.keys.KeyProvider",
    config: { keySize: ["2048"], priority: ["200"] },
  });
  check("Tạo được KeyProvider RSA mới với độ ưu tiên cao hơn", created.status === 201, `HTTP ${created.status}`);
  if (created.status !== 201) return;

  let newKid = null;
  for (let i = 0; i < 10 && !newKid; i += 1) {
    const certs = await http(null, "GET", `${baseUrl}/realms/${realm}/protocol/openid-connect/certs`);
    const fresh = JSON.parse(certs.text).keys.filter((k) => !kidsBefore.has(k.kid) && k.use === "sig");
    if (fresh.length > 0) newKid = fresh[0].kid;
    else await new Promise((r) => setTimeout(r, 1000));
  }
  check("JWKS công bố kid mới sau khi đổi khoá", Boolean(newKid));

  const login = await loginTenantUser(realm, tenantA, "hv-kiem-chung", pw("hv-kiem-chung"));
  const tokens = await exchangeCode(realm, {
    clientId: "lms-web",
    secret: webSecret(realm),
    code: login.result.code,
    redirectUri: login.redirectUri,
    verifier: login.verifier,
  });
  const kidOfNewToken = tokens.data?.access_token ? decodeJwt(tokens.data.access_token).header.kid : null;
  check(
    "Token cấp sau khi đổi khoá được ký bằng khoá mới",
    kidOfNewToken === newKid,
    `kid token: ${kidOfNewToken}, kid mới: ${newKid}`,
  );
  const userinfo = await http(null, "GET", `${baseUrl}/realms/${realm}/protocol/openid-connect/userinfo`, {
    headers: { Authorization: `Bearer ${oldToken}` },
  });
  check(
    "Token ký bằng khoá cũ vẫn kiểm chứng được qua userinfo (khoá cũ còn công bố ở JWKS)",
    userinfo.status === 200,
    `HTTP ${userinfo.status}`,
  );
}

async function phaseCrossTenant() {
  section("Cách ly tenant — token của tenant này không dùng được ở realm tenant khác");
  if (!tenantB || !users.memberB) {
    skip("Token tenant-b bị realm tenant-a từ chối", "file cấu hình chỉ có một tenant");
    return;
  }
  const loginB = await loginTenantUser(tenantB.realm, tenantB, "hv-tenant-b", pw("hv-tenant-b"));
  const tokensB = await exchangeCode(tenantB.realm, {
    clientId: "lms-web",
    secret: webSecret(tenantB.realm),
    code: loginB.result.code,
    redirectUri: loginB.redirectUri,
    verifier: loginB.verifier,
  });
  check(
    "Token của tenant-b mang issuer của tenant-b",
    tokensB.data?.access_token &&
      decodeJwt(tokensB.data.access_token).payload.iss === `${baseUrl}/realms/${tenantB.realm}`,
  );
  const cross = await http(
    null,
    "GET",
    `${baseUrl}/realms/${tenantA.realm}/protocol/openid-connect/userinfo`,
    { headers: { Authorization: `Bearer ${tokensB.data.access_token}` } },
  );
  check(
    "Token của tenant-b bị userinfo của tenant-a từ chối",
    cross.status === 401,
    `HTTP ${cross.status}`,
  );
}

async function phaseRollback() {
  section("Nghiệm thu 4 — diễn tập khôi phục: sửa lệch cấu hình rồi chạy lại mẫu để khôi phục");
  const realm = tenantA.realm;
  const { data: current } = await api("GET", `/admin/realms/${realm}`);
  await api("PUT", `/admin/realms/${realm}`, { ...current, accessTokenLifespan: 9999 });
  const webId = await clientInternalId(realm, "lms-web");
  const { data: webRep } = await api("GET", `/admin/realms/${realm}/clients/${webId}`);
  await api("PUT", `/admin/realms/${realm}/clients/${webId}`, {
    ...webRep,
    redirectUris: ["https://evil.example/callback"],
  });
  const mcpClientId = await clientInternalId(realm, "lms-mcp-client");
  const { data: optionalScopes } = await api(
    "GET",
    `/admin/realms/${realm}/clients/${mcpClientId}/optional-client-scopes`,
  );
  const banksScope = (optionalScopes || []).find((s) => s.name === "lms.banks.contribute");
  if (banksScope) {
    await api(
      "DELETE",
      `/admin/realms/${realm}/clients/${mcpClientId}/optional-client-scopes/${banksScope.id}`,
    );
  }
  const { data: perturbed } = await api("GET", `/admin/realms/${realm}`);
  check(
    "Đã sửa lệch thành công để diễn tập (accessTokenLifespan=9999, redirect URI lạ, mất scope banks)",
    perturbed.accessTokenLifespan === 9999,
  );

  const secretBefore = await clientSecretValue(realm, "lms-web");
  let restoreOk = true;
  try {
    runProvision();
  } catch {
    restoreOk = false;
  }
  check("Chạy lại bộ cấp phát để khôi phục không lỗi", restoreOk);
  const { data: restored } = await api("GET", `/admin/realms/${realm}`);
  const { data: webAfter } = await api("GET", `/admin/realms/${realm}/clients/${webId}`);
  const { data: optionalAfter } = await api(
    "GET",
    `/admin/realms/${realm}/clients/${mcpClientId}/optional-client-scopes`,
  );
  const secretAfter = await clientSecretValue(realm, "lms-web");
  check(
    "Khôi phục đúng mẫu: tuổi thọ token, redirect URI và scope banks trở lại; bí mật và người dùng không đổi",
    restored.accessTokenLifespan === 300 &&
      JSON.stringify(webAfter.redirectUris) ===
        JSON.stringify([`https://${tenantA.host}/api/auth/callback/keycloak`]) &&
      (optionalAfter || []).some((s) => s.name === "lms.banks.contribute") &&
      secretAfter === secretBefore,
  );
}

// Mật khẩu kiểm chứng giữ trong bộ nhớ theo username (không in ra log).
const _passwords = new Map();
function pw(username) {
  return _passwords.get(username);
}

async function main() {
  console.log(`Kiểm chứng mẫu realm Keycloak tại ${baseUrl}`);
  console.log(`File cấu hình cấp phát: ${configPath}`);
  await phaseProvisioning();
  await phaseTokens();
  await phaseMfa();
  await phaseBruteForce();
  await phaseRefreshRotation();
  await phaseBrokerConfig();
  if (platform) await phasePlatform();
  await phaseLifetimesAndAudit();
  await phaseKeyRotation();
  await phaseCrossTenant();
  await phaseRollback();
  console.log(`\nTổng kết: ${passed} ĐẠT, ${failed} KHÔNG ĐẠT, ${skipped} BỎ QUA`);
  if (failed > 0) process.exit(1);
}

main().catch((err) => {
  console.error(`Harness dừng vì lỗi: ${err.message}`);
  console.log(`\nTổng kết tới lúc dừng: ${passed} ĐẠT, ${failed} KHÔNG ĐẠT, ${skipped} BỎ QUA`);
  process.exit(1);
});
