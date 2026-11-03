#!/usr/bin/env node
// Bộ cấp phát realm Keycloak từ mẫu cấu hình trong keycloak/templates/.
//
// Việc này (issue #6) đưa realm/client/role/flow của Keycloak 26.8.1
// vào repo dưới dạng mẫu JSON có kiểm chứng + bộ cấp phát chạy lại
// được: chạy lần đầu tạo realm từ môi trường trống; chạy lại không
// tạo trùng, không ghi đè bí mật đang có bằng giá trị rỗng, và khôi
// phục các trường cấu hình bị lệch khỏi mẫu (scalar realm, role,
// client, scope). Flow xác thực là phần nhạy cảm: bộ cấp phát chỉ
// tạo flow khi realm được tạo mới (đi cùng bản import realm) hoặc
// khi flow còn thiếu hẳn; nếu flow đã có mà lệch mẫu, bộ cấp phát
// BÁO LỖI rõ ràng thay vì tự viết lại flow đang chạy.
//
// Bí mật không bao giờ nằm trong repo: mẫu chỉ chứa placeholder,
// giá trị thật đi qua biến môi trường lúc chạy (xem keycloak/README.md
// và keycloak/provision.config.example.json cho phần cấu hình không
// bí mật). Script chỉ dùng thư viện chuẩn của Node (fetch, fs).
//
// Cách chạy:
//   KC_ADMIN_USERNAME=... KC_ADMIN_PASSWORD=... \
//     node keycloak/provision.mjs --config keycloak/provision.config.json
//   node keycloak/provision.mjs --config ... --dry-run   (chỉ dựng và in kế hoạch)

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));

// ---------------------------------------------------------------------------
// Tham số dòng lệnh và môi trường
// ---------------------------------------------------------------------------

const args = process.argv.slice(2);
const configPath = args.includes("--config")
  ? args[args.indexOf("--config") + 1]
  : join(HERE, "provision.config.json");
const dryRun = args.includes("--dry-run");

const config = JSON.parse(readFileSync(configPath, "utf8"));
const baseUrl = (process.env.KC_BASE_URL || config.authBaseUrl || "").replace(/\/$/, "");
if (!baseUrl) {
  console.error("Thiếu KC_BASE_URL (hoặc authBaseUrl trong file cấu hình).");
  process.exit(2);
}
const adminUser = process.env.KC_ADMIN_USERNAME || "";
const adminPass = process.env.KC_ADMIN_PASSWORD || "";
if (!dryRun && (!adminUser || !adminPass)) {
  console.error("Thiếu KC_ADMIN_USERNAME / KC_ADMIN_PASSWORD cho Admin REST API.");
  process.exit(2);
}

// Các placeholder được phép rỗng khi thiếu cấu hình tương ứng: IdP bị
// loại khỏi bản cấp phát thay vì tạo bằng thông tin đăng nhập rỗng.
const OPTIONAL_PLACEHOLDERS = new Set([
  "GOOGLE_CLIENT_ID",
  "GOOGLE_CLIENT_SECRET",
  "FACEBOOK_CLIENT_ID",
  "FACEBOOK_CLIENT_SECRET",
]);

let failures = 0;
function ok(msg) {
  console.log(`  ĐẠT  ${msg}`);
}
function note(msg) {
  console.log(`  --   ${msg}`);
}
function fail(msg) {
  failures += 1;
  console.error(`  LỖI  ${msg}`);
}

// ---------------------------------------------------------------------------
// Admin REST API (token qua admin-cli của realm master)
// ---------------------------------------------------------------------------

let cachedToken = null;
let tokenExpiresAt = 0;

async function adminToken() {
  if (cachedToken && Date.now() < tokenExpiresAt - 30_000) return cachedToken;
  const body = new URLSearchParams({
    client_id: "admin-cli",
    grant_type: "password",
    username: adminUser,
    "password": adminPass,
  });
  const res = await fetch(`${baseUrl}/realms/master/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  if (!res.ok) {
    throw new Error(`Lấy token quản trị thất bại: HTTP ${res.status}`);
  }
  const json = await res.json();
  cachedToken = json.access_token;
  tokenExpiresAt = Date.now() + (json.expires_in ?? 60) * 1000;
  return cachedToken;
}

async function api(method, path, body) {
  const headers = { Authorization: `Bearer ${await adminToken()}` };
  const init = { method, headers };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(body);
  }
  const res = await fetch(`${baseUrl}${path}`, init);
  const text = await res.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }
  return { status: res.status, data };
}

// ---------------------------------------------------------------------------
// Dựng bản biểu diễn realm từ mẫu
// ---------------------------------------------------------------------------

function renderTemplate(templateText, values) {
  return templateText.replace(/\{\{([A-Z0-9_]+)\}\}/g, (match, key) => {
    if (key in values) return values[key];
    if (OPTIONAL_PLACEHOLDERS.has(key)) return "";
    throw new Error(`Thiếu giá trị cho placeholder {{${key}}}`);
  });
}

function realmEnvKey(realm) {
  return realm.toUpperCase().replace(/[^A-Z0-9]+/g, "_");
}

function smtpServerFromEnv() {
  if (!process.env.SMTP_HOST || !process.env.SMTP_FROM) return null;
  return {
    host: process.env.SMTP_HOST,
    port: String(process.env.SMTP_PORT || "587"),
    from: process.env.SMTP_FROM,
    replyTo: "",
    auth: "true",
    user: process.env.SMTP_USER || "",
    "password": process.env.SMTP_PASSWORD || "",
    starttls: process.env.SMTP_STARTTLS === "false" ? "false" : "true",
    ssl: process.env.SMTP_SSL === "true" ? "true" : "false",
  };
}

const IDP_ENV = {
  google: ["GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET"],
  facebook: ["FACEBOOK_CLIENT_ID", "FACEBOOK_CLIENT_SECRET"],
};

function idpConfigured(alias) {
  const [idKey, secretKey] = IDP_ENV[alias] || [];
  return Boolean(idKey && process.env[idKey] && process.env[secretKey]);
}

// Dựng representation cho một realm từ file mẫu: thay placeholder, gắn
// SMTP khi có cấu hình, loại IdP chưa có thông tin đăng nhập, gắn bí
// mật client web khi môi trường cấp (chỉ dùng ở nhánh tạo mới; nhánh
// cập nhật xử lý bí mật riêng để không ghi đè giá trị đang có).
function buildRepresentation(templateFile, values, webClientId, webSecretEnvName) {
  const raw = readFileSync(join(HERE, "templates", templateFile), "utf8");
  const rendered = renderTemplate(raw, values);
  const rep = JSON.parse(rendered);

  const smtp = smtpServerFromEnv();
  if (smtp) rep.smtpServer = smtp;

  if (Array.isArray(rep.identityProviders)) {
    rep.identityProviders = rep.identityProviders.filter((idp) => {
      if (idpConfigured(idp.alias)) return true;
      note(
        `IdP ${idp.alias} của realm ${rep.realm}: chưa có thông tin đăng nhập trong môi trường, bỏ qua khi cấp phát`,
      );
      return false;
    });
  }

  const webSecret = webSecretEnvName ? process.env[webSecretEnvName] : undefined;
  if (webSecret) {
    const web = (rep.clients || []).find((c) => c.clientId === webClientId);
    if (web) web.secret = webSecret;
  }
  return rep;
}

// ---------------------------------------------------------------------------
// Đối chiếu trên realm đã tồn tại
// ---------------------------------------------------------------------------

// Các trường scalar mà mẫu là nguồn sự thật trên realm đã có. Flow,
// role và client KHÔNG nằm ở đây: realm PUT bỏ qua flow (đã kiểm
// chứng trên binary 26.8.1) và role/client có endpoint riêng chuẩn hơn.
const MANAGED_SCALAR_KEYS = [
  "displayName",
  "enabled",
  "sslRequired",
  "registrationAllowed",
  "registrationEmailAsUsername",
  "loginWithEmailAllowed",
  "duplicateEmailsAllowed",
  "editUsernameAllowed",
  "resetPasswordAllowed",
  "rememberMe",
  "verifyEmail",
  "browserFlow",
  "defaultDefaultClientScopes",
  "defaultOptionalClientScopes",
  "accessTokenLifespan",
  "accessTokenLifespanForImplicitFlow",
  "ssoSessionIdleTimeout",
  "ssoSessionMaxLifespan",
  "ssoSessionIdleTimeoutRememberMe",
  "ssoSessionMaxLifespanRememberMe",
  "clientSessionIdleTimeout",
  "clientSessionMaxLifespan",
  "clientOfflineSessionIdleTimeout",
  "clientOfflineSessionMaxLifespan",
  "offlineSessionIdleTimeout",
  "offlineSessionMaxLifespan",
  "accessCodeLifespan",
  "accessCodeLifespanLogin",
  "accessCodeLifespanUserAction",
  "bruteForceProtected",
  "bruteForceStrategy",
  "permanentLockout",
  "failureFactor",
  "waitIncrementSeconds",
  "minimumQuickLoginWaitSeconds",
  "quickLoginCheckMilliSeconds",
  "maxFailureWaitSeconds",
  "maxDeltaTimeSeconds",
  "revokeRefreshToken",
  "refreshTokenMaxReuse",
  "eventsEnabled",
  "eventsListeners",
  "adminEventsEnabled",
  "adminEventsDetailsEnabled",
  "userManagedAccessAllowed",
  "organizationsEnabled",
  "otpPolicyType",
  "otpPolicyAlgorithm",
  "otpPolicyDigits",
  "otpPolicyPeriod",
  "otpPolicyLookAheadWindow",
];

async function reconcileScalars(rep) {
  const realm = rep.realm;
  const { data: current } = await api("GET", `/admin/realms/${realm}`);
  const updated = { ...current };
  let changed = false;
  for (const key of MANAGED_SCALAR_KEYS) {
    if (key in rep && JSON.stringify(rep[key]) !== JSON.stringify(current[key])) {
      updated[key] = rep[key];
      changed = true;
    }
  }
  if ("smtpServer" in rep) {
    updated.smtpServer = rep.smtpServer;
    changed = true;
  }
  if (!changed) {
    ok(`realm ${realm}: các trường scalar đã khớp mẫu`);
    return;
  }
  const { status } = await api("PUT", `/admin/realms/${realm}`, updated);
  if (status === 204) ok(`realm ${realm}: đã đồng bộ các trường scalar theo mẫu`);
  else fail(`realm ${realm}: PUT scalar trả HTTP ${status}`);
}

async function reconcileRequiredActions(rep) {
  const realm = rep.realm;
  if (!Array.isArray(rep.requiredActions)) return;
  const { data: existing } = await api(
    "GET",
    `/admin/realms/${realm}/authentication/required-actions`,
  );
  const byAlias = new Map((existing || []).map((a) => [a.alias, a]));
  for (const action of rep.requiredActions) {
    const found = byAlias.get(action.alias);
    if (!found) {
      const { status } = await api(
        "POST",
        `/admin/realms/${realm}/authentication/required-actions/register`,
        action,
      );
      if (status !== 201) fail(`realm ${realm}: tạo required action ${action.alias} trả HTTP ${status}`);
    } else if (
      found.enabled !== action.enabled ||
      found.defaultAction !== action.defaultAction ||
      found.priority !== action.priority
    ) {
      const { status } = await api(
        "PUT",
        `/admin/realms/${realm}/authentication/required-actions/${action.alias}`,
        { ...found, ...action },
      );
      if (status !== 204) fail(`realm ${realm}: cập nhật required action ${action.alias} trả HTTP ${status}`);
    }
  }
  ok(`realm ${realm}: required actions khớp mẫu`);
}

// Gỡ offline_access khỏi composite default-roles-<realm>: offline
// access tắt theo mặc định ở mọi realm (việc con 7).
async function stripOfflineAccessFromDefaultRoles(realm) {
  const name = `default-roles-${realm}`;
  const { status, data } = await api(
    "GET",
    `/admin/realms/${realm}/roles/${name}/composites`,
  );
  if (status !== 200 || !Array.isArray(data)) return;
  const offline = data.filter((r) => r.name === "offline_access");
  if (offline.length === 0) {
    ok(`realm ${realm}: default-roles không chứa offline_access`);
    return;
  }
  const del = await api("DELETE", `/admin/realms/${realm}/roles/${name}/composites`, offline);
  if (del.status === 204) ok(`realm ${realm}: đã gỡ offline_access khỏi ${name}`);
  else fail(`realm ${realm}: gỡ offline_access khỏi ${name} trả HTTP ${del.status}`);
}

async function reconcileRealmRoles(rep) {
  const realm = rep.realm;
  const wanted = rep.roles?.realm || [];
  for (const role of wanted) {
    const { status, data } = await api("GET", `/admin/realms/${realm}/roles/${role.name}`);
    if (status === 404) {
      const created = await api("POST", `/admin/realms/${realm}/roles`, role);
      if (created.status === 201) ok(`realm ${realm}: đã tạo realm role ${role.name}`);
      else fail(`realm ${realm}: tạo realm role ${role.name} trả HTTP ${created.status}`);
      continue;
    }
    if (status !== 200) {
      fail(`realm ${realm}: đọc realm role ${role.name} trả HTTP ${status}`);
      continue;
    }
    if (data.composite) {
      // Vai chính không được là composite lồng nhau (hợp đồng mục 4):
      // gỡ mọi composite con thay vì xoá role (giữ nguyên gán vai cho
      // người dùng hiện có).
      const comp = await api("GET", `/admin/realms/${realm}/roles/${role.name}/composites`);
      if (comp.status === 200 && Array.isArray(comp.data) && comp.data.length > 0) {
        const del = await api(
          "DELETE",
          `/admin/realms/${realm}/roles/${role.name}/composites`,
          comp.data,
        );
        if (del.status === 204) ok(`realm ${realm}: đã gỡ composite khỏi vai chính ${role.name}`);
        else fail(`realm ${realm}: gỡ composite khỏi ${role.name} trả HTTP ${del.status}`);
      }
    }
  }
  if (wanted.length > 0) ok(`realm ${realm}: bộ vai chính khớp mẫu (không composite lồng)`);
}

async function reconcileClientScopes(rep) {
  const realm = rep.realm;
  const wanted = rep.clientScopes || [];
  const { data: existing } = await api("GET", `/admin/realms/${realm}/client-scopes`);
  const byName = new Map((existing || []).map((s) => [s.name, s]));
  for (const scope of wanted) {
    const found = byName.get(scope.name);
    if (!found) {
      const created = await api("POST", `/admin/realms/${realm}/client-scopes`, scope);
      if (created.status === 201) ok(`realm ${realm}: đã tạo client scope ${scope.name}`);
      else fail(`realm ${realm}: tạo client scope ${scope.name} trả HTTP ${created.status}`);
    } else {
      const put = await api("PUT", `/admin/realms/${realm}/client-scopes/${found.id}`, {
        ...found,
        ...scope,
        id: found.id,
      });
      if (put.status !== 204) fail(`realm ${realm}: cập nhật client scope ${scope.name} trả HTTP ${put.status}`);
    }
  }
  if (wanted.length > 0) ok(`realm ${realm}: client scopes theo mẫu đã đồng bộ`);
}

// Bộ scope gán cho từng client là nguồn sự thật khép kín: thiếu thì
// gắn thêm, thừa thì gỡ — để token không mang scope ngoài ý đồ của mẫu
// (Full Scope Allowed đã tắt ở mọi client).
const CLIENT_SCOPE_ASSIGNMENTS = {
  tenant: {
    "lms-web": {
      default: ["basic", "profile", "email", "roles", "web-origins", "acr", "lms-api-audience"],
      optional: [],
    },
    "lms-api": { default: [], optional: [] },
    "lms-mcp": { default: [], optional: [] },
    "lms-mcp-client": {
      default: ["basic", "lms-mcp-audience"],
      optional: [
        "profile",
        "email",
        "roles",
        "lms.questions.read",
        "lms.questions.draft",
        "lms.questions.write",
        "lms.banks.contribute",
      ],
    },
  },
  platform: {
    "platform-web": {
      default: ["basic", "profile", "email", "roles", "web-origins", "acr", "platform-api-audience"],
      optional: [],
    },
    "platform-api": { default: [], optional: [] },
  },
};

async function reconcileClientScopeAssignments(realm, kind, clientInternalId, clientId) {
  const plan = CLIENT_SCOPE_ASSIGNMENTS[kind]?.[clientId];
  if (!plan) return;
  const { data: allScopes } = await api("GET", `/admin/realms/${realm}/client-scopes`);
  const scopeIdByName = new Map((allScopes || []).map((s) => [s.name, s.id]));
  for (const bucket of ["default", "optional"]) {
    const { data: current } = await api(
      "GET",
      `/admin/realms/${realm}/clients/${clientInternalId}/${bucket}-client-scopes`,
    );
    const currentNames = new Set((current || []).map((s) => s.name));
    const wantedNames = new Set(plan[bucket]);
    for (const name of wantedNames) {
      if (!scopeIdByName.has(name)) {
        fail(`realm ${realm}: client scope ${name} không tồn tại để gán cho ${clientId}`);
        continue;
      }
      if (!currentNames.has(name)) {
        const put = await api(
          "PUT",
          `/admin/realms/${realm}/clients/${clientInternalId}/${bucket}-client-scopes/${scopeIdByName.get(name)}`,
        );
        if (put.status !== 204) fail(`realm ${realm}: gắn scope ${name} (${bucket}) cho ${clientId} trả HTTP ${put.status}`);
      }
    }
    for (const scope of current || []) {
      if (!wantedNames.has(scope.name)) {
        const del = await api(
          "DELETE",
          `/admin/realms/${realm}/clients/${clientInternalId}/${bucket}-client-scopes/${scope.id}`,
        );
        if (del.status !== 204) fail(`realm ${realm}: gỡ scope ${scope.name} (${bucket}) khỏi ${clientId} trả HTTP ${del.status}`);
      }
    }
  }
  ok(`realm ${realm}: bộ scope gán cho client ${clientId} khớp mẫu`);
}

async function reconcileClients(rep, kind, webClientId, webSecretEnvName) {
  const realm = rep.realm;
  for (const client of rep.clients || []) {
    const { data: found } = await api(
      "GET",
      `/admin/realms/${realm}/clients?clientId=${encodeURIComponent(client.clientId)}`,
    );
    const existing = Array.isArray(found) ? found[0] : null;
    if (!existing) {
      const created = await api("POST", `/admin/realms/${realm}/clients`, client);
      if (created.status !== 201) {
        fail(`realm ${realm}: tạo client ${client.clientId} trả HTTP ${created.status}`);
        continue;
      }
      ok(`realm ${realm}: đã tạo client ${client.clientId}`);
      const after = await api(
        "GET",
        `/admin/realms/${realm}/clients?clientId=${encodeURIComponent(client.clientId)}`,
      );
      const internalId = after.data?.[0]?.id;
      if (internalId) await reconcileClientScopeAssignments(realm, kind, internalId, client.clientId);
      continue;
    }
    // Cập nhật: phủ các trường của mẫu lên bản hiện tại. Bí mật chỉ ghi
    // khi môi trường cấp giá trị tường minh; thiếu biến thì giữ nguyên
    // bí mật đang có trong Keycloak (không gửi trường secret).
    const desired = { ...existing, ...client, id: existing.id };
    const envSecret = webSecretEnvName && client.clientId === webClientId
      ? process.env[webSecretEnvName]
      : undefined;
    if (envSecret) desired.secret = envSecret;
    else delete desired.secret;
    const put = await api("PUT", `/admin/realms/${realm}/clients/${existing.id}`, desired);
    if (put.status !== 204) {
      fail(`realm ${realm}: cập nhật client ${client.clientId} trả HTTP ${put.status}`);
      continue;
    }
    await reconcileClientScopeAssignments(realm, kind, existing.id, client.clientId);
  }
  ok(`realm ${realm}: clients khớp mẫu`);
}

async function reconcileClientRoles(rep) {
  const realm = rep.realm;
  const clientRoles = rep.roles?.client || {};
  for (const [clientId, roles] of Object.entries(clientRoles)) {
    const { data: found } = await api(
      "GET",
      `/admin/realms/${realm}/clients?clientId=${encodeURIComponent(clientId)}`,
    );
    const client = Array.isArray(found) ? found[0] : null;
    if (!client) {
      fail(`realm ${realm}: không thấy client ${clientId} để gắn client role`);
      continue;
    }
    const { data: existing } = await api("GET", `/admin/realms/${realm}/clients/${client.id}/roles`);
    const names = new Set((existing || []).map((r) => r.name));
    for (const role of roles) {
      if (names.has(role.name)) continue;
      const created = await api("POST", `/admin/realms/${realm}/clients/${client.id}/roles`, role);
      if (created.status === 201) ok(`realm ${realm}: đã tạo client role ${role.name} trên ${clientId}`);
      else fail(`realm ${realm}: tạo client role ${role.name} trả HTTP ${created.status}`);
    }
  }
}

// Ánh xạ vai lên client scope "roles": vì Full Scope Allowed tắt ở
// mọi client, token chỉ mang vai khi vai đó được ánh xạ vào một scope
// mà client mang. Scope "roles" (mặc định của client web) được ánh xạ
// đúng tập vai được phép xuất hiện trong token: ba vai chính ở realm
// tenant; client role platform-admin ở realm giaokhoa. Đây là chốt
// "role scope giới hạn ở mức cần thiết" của việc con 3.
//
// Tập ánh xạ trên scope "roles" là nguồn sự thật khép kín của mẫu
// (như bộ scope gán cho client): hoà giải cả hai chiều — thiếu thì
// ánh xạ thêm, thừa ngoài mẫu thì gỡ — để một realm đã có sẵn ánh
// xạ lạ không tiếp tục phát vai ngoài tập cho phép vào token khi
// chạy lại bộ cấp phát. Lưu ý định dạng trả về của Keycloak 26.8.1
// (đã kiểm chứng trên binary thật): bản ghi scope-mappings dùng
// trường "realmMappings" và "clientMappings" (khoá theo clientId,
// mỗi mục có mảng "mappings").
// Cấu trúc hợp lệ của bản ghi scope-mappings trên Keycloak
// 26.8.1 (đã kiểm chứng trên binary thật): payload là một object;
// trường realmMappings/clientMappings bị BỎ HẲN khi tập tương
// ứng rỗng (scope chưa có ánh xạ nào thì payload là {}), nên
// "trường vắng mặt" nghĩa là tập rỗng theo đúng thiết kế API.
// Ngược lại, trường có mặt mà sai kiểu (realmMappings không
// phải mảng, clientMappings không phải object, mục con thiếu
// mảng mappings) là payload không đáng tin.
function scopeMappingsShapeOk(m) {
  if (!m || typeof m !== "object" || Array.isArray(m)) return false;
  if (m.realmMappings !== undefined && !Array.isArray(m.realmMappings)) return false;
  if (m.clientMappings !== undefined) {
    if (
      !m.clientMappings ||
      typeof m.clientMappings !== "object" ||
      Array.isArray(m.clientMappings)
    ) {
      return false;
    }
    for (const entry of Object.values(m.clientMappings)) {
      if (!entry || typeof entry !== "object" || !Array.isArray(entry.mappings)) return false;
    }
  }
  return true;
}

async function reconcileScopeRoleMappings(rep, kind) {
  const realm = rep.realm;
  const failuresBefore = failures;
  const scopesRes = await api("GET", `/admin/realms/${realm}/client-scopes`);
  const rolesScope = (Array.isArray(scopesRes.data) ? scopesRes.data : []).find(
    (s) => s.name === "roles",
  );
  if (scopesRes.status !== 200 || !rolesScope) {
    fail(`realm ${realm}: không đọc được client scope roles để ánh xạ vai (HTTP ${scopesRes.status})`);
    return;
  }
  const mappingsRes = await api(
    "GET",
    `/admin/realms/${realm}/client-scopes/${rolesScope.id}/scope-mappings`,
  );
  const mappings = mappingsRes.data;
  // Fail-closed: snapshot ánh xạ là căn cứ duy nhất để biết ánh xạ
  // nào đang thừa mà gỡ. HTTP lỗi hoặc payload sai cấu trúc thì
  // phải dừng và báo lỗi — tuyệt đối không diễn giải thành "không
  // có ánh xạ nào", vì như vậy ánh xạ thừa sẽ lọt qua trong im
  // lặng trong khi bộ cấp phát vẫn báo hoà giải thành công.
  if (mappingsRes.status !== 200 || !scopeMappingsShapeOk(mappings)) {
    fail(
      `realm ${realm}: không đọc được snapshot ánh xạ của scope roles (HTTP ${mappingsRes.status}) — dừng hoà giải thay vì coi như không có ánh xạ`,
    );
    return;
  }

  // Tập đích theo loại realm: tenant chỉ ánh xạ ba vai chính và
  // không ánh xạ client role nào; giaokhoa chỉ ánh xạ các client
  // role của mẫu (platform-admin) và không ánh xạ realm role nào.
  const wantedRealmRoles = kind === "tenant" ? rep.roles?.realm || [] : [];
  const wantedClientRoles = kind === "platform" ? rep.roles?.client || {} : {};

  const currentRealmMappings = mappings.realmMappings || [];
  const wantedRealmNames = new Set(wantedRealmRoles.map((r) => r.name));
  for (const extra of currentRealmMappings) {
    if (wantedRealmNames.has(extra.name)) continue;
    const del = await api(
      "DELETE",
      `/admin/realms/${realm}/client-scopes/${rolesScope.id}/scope-mappings/realm`,
      [extra],
    );
    if (del.status === 204) ok(`realm ${realm}: đã gỡ ánh xạ realm role ${extra.name} ngoài mẫu khỏi scope roles`);
    else fail(`realm ${realm}: gỡ ánh xạ realm role ${extra.name} khỏi scope roles trả HTTP ${del.status}`);
  }
  const haveRealmNames = new Set(currentRealmMappings.map((r) => r.name));
  for (const role of wantedRealmRoles) {
    if (haveRealmNames.has(role.name)) continue;
    const { data: roleRep } = await api("GET", `/admin/realms/${realm}/roles/${role.name}`);
    const posted = await api(
      "POST",
      `/admin/realms/${realm}/client-scopes/${rolesScope.id}/scope-mappings/realm`,
      [roleRep],
    );
    if (posted.status === 204) ok(`realm ${realm}: đã ánh xạ vai ${role.name} vào scope roles`);
    else fail(`realm ${realm}: ánh xạ vai ${role.name} vào scope roles trả HTTP ${posted.status}`);
  }

  const currentClientMappings = mappings.clientMappings || {};
  const clientIds = new Set([
    ...Object.keys(currentClientMappings),
    ...Object.keys(wantedClientRoles),
  ]);
  for (const clientId of clientIds) {
    const internalId = await findClientInternalId(realm, clientId);
    if (!internalId) {
      if ((wantedClientRoles[clientId] || []).length > 0) {
        fail(`realm ${realm}: không thấy client ${clientId} để ánh xạ client role vào scope roles`);
      } else if ((currentClientMappings[clientId]?.mappings || []).length > 0) {
        // Có ánh xạ hiện hữu cần gỡ mà không xác định được client:
        // hoà giải không trọn vẹn, phải báo lỗi thay vì bỏ qua.
        fail(
          `realm ${realm}: scope roles đang mang ánh xạ của client ${clientId} nhưng không tìm thấy client để gỡ ánh xạ ngoài mẫu`,
        );
      }
      continue;
    }
    const current = currentClientMappings[clientId]?.mappings || [];
    const wantedRoles = wantedClientRoles[clientId] || [];
    const wantedNames = new Set(wantedRoles.map((r) => r.name));
    for (const extra of current) {
      if (wantedNames.has(extra.name)) continue;
      const del = await api(
        "DELETE",
        `/admin/realms/${realm}/client-scopes/${rolesScope.id}/scope-mappings/clients/${internalId}`,
        [extra],
      );
      if (del.status === 204) ok(`realm ${realm}: đã gỡ ánh xạ client role ${extra.name} ngoài mẫu của ${clientId} khỏi scope roles`);
      else fail(`realm ${realm}: gỡ ánh xạ client role ${extra.name} của ${clientId} khỏi scope roles trả HTTP ${del.status}`);
    }
    const haveNames = new Set(current.map((r) => r.name));
    for (const role of wantedRoles) {
      if (haveNames.has(role.name)) continue;
      const { data: roleRep } = await api(
        "GET",
        `/admin/realms/${realm}/clients/${internalId}/roles/${role.name}`,
      );
      const posted = await api(
        "POST",
        `/admin/realms/${realm}/client-scopes/${rolesScope.id}/scope-mappings/clients/${internalId}`,
        [roleRep],
      );
      if (posted.status === 204) ok(`realm ${realm}: đã ánh xạ client role ${role.name} vào scope roles`);
      else fail(`realm ${realm}: ánh xạ client role ${role.name} vào scope roles trả HTTP ${posted.status}`);
    }
  }
  // Đọc lại snapshot và so khớp chính xác tập đích trước khi kết
  // luận: chỉ báo khớp mẫu khi tập ánh xạ sau hoà giải bằng đúng
  // tập muốn có và toàn bộ thao tác trong hàm không phát sinh lỗi.
  const afterRes = await api(
    "GET",
    `/admin/realms/${realm}/client-scopes/${rolesScope.id}/scope-mappings`,
  );
  const after = afterRes.data;
  const namesOf = (list) => (list || []).map((r) => r.name).sort();
  const sameNames = (a, b) => JSON.stringify(namesOf(a)) === JSON.stringify(namesOf(b));
  let exact =
    afterRes.status === 200 &&
    scopeMappingsShapeOk(after) &&
    sameNames(after.realmMappings || [], wantedRealmRoles);
  if (exact) {
    const clientKeys = new Set([
      ...Object.keys(wantedClientRoles),
      ...Object.keys(after.clientMappings || {}),
    ]);
    for (const clientId of clientKeys) {
      if (
        !sameNames(
          (after.clientMappings || {})[clientId]?.mappings || [],
          wantedClientRoles[clientId] || [],
        )
      ) {
        exact = false;
        break;
      }
    }
  }
  if (exact && failures === failuresBefore) {
    ok(`realm ${realm}: ánh xạ vai của scope roles khớp mẫu`);
  } else {
    fail(
      `realm ${realm}: ánh xạ vai của scope roles chưa khớp mẫu sau hoà giải (đọc lại HTTP ${afterRes.status})`,
    );
  }
}

async function findClientInternalId(realm, clientId) {
  const { status, data } = await api(
    "GET",
    `/admin/realms/${realm}/clients?clientId=${encodeURIComponent(clientId)}`,
  );
  // Fail-closed: không đọc được danh sách client thì không được
  // diễn giải thành "client không tồn tại", vì caller có thể đang
  // cần ID đó để gỡ một ánh xạ ngoài mẫu. Ném lỗi để dừng lượt
  // cấp phát ngay, thay vì bỏ qua trong im lặng.
  if (status !== 200 || !Array.isArray(data)) {
    throw new Error(
      `realm ${realm}: đọc danh sách client để tìm ${clientId} trả HTTP ${status} — không xác định được trạng thái hiện tại`,
    );
  }
  return data[0]?.id;
}

async function reconcileIdps(rep) {
  const realm = rep.realm;
  for (const idp of rep.identityProviders || []) {
    if (!idpConfigured(idp.alias)) continue; // đã ghi chú ở bước dựng mẫu
    const { status } = await api(
      "GET",
      `/admin/realms/${realm}/identity-provider/instances/${idp.alias}`,
    );
    if (status === 404) {
      const created = await api("POST", `/admin/realms/${realm}/identity-provider/instances`, idp);
      if (created.status === 201) ok(`realm ${realm}: đã tạo IdP ${idp.alias} (callback .../realms/${realm}/broker/${idp.alias}/endpoint)`);
      else fail(`realm ${realm}: tạo IdP ${idp.alias} trả HTTP ${created.status}`);
    } else if (status === 200) {
      const put = await api(
        "PUT",
        `/admin/realms/${realm}/identity-provider/instances/${idp.alias}`,
        idp,
      );
      if (put.status === 204) ok(`realm ${realm}: IdP ${idp.alias} đã đồng bộ theo mẫu`);
      else fail(`realm ${realm}: cập nhật IdP ${idp.alias} trả HTTP ${put.status}`);
    }
  }
}

// Chữ ký của một flow để so lệch: danh sách (authenticator|flowAlias,
// requirement, authenticatorConfig) đã sắp xếp theo priority.
function flowSignature(flow) {
  return (flow.authenticationExecutions || [])
    .map((e) =>
      [e.authenticator || `flow:${e.flowAlias}`, e.requirement, e.authenticatorConfig || ""].join("|"),
    )
    .join(";");
}

async function reconcileFlows(rep) {
  const realm = rep.realm;
  const wanted = rep.authenticationFlows || [];
  if (wanted.length === 0) return;
  const { status, data: exported } = await api(
    "POST",
    `/admin/realms/${realm}/partial-export?exportGroupsAndRoles=false&exportClients=false`,
  );
  if (status !== 200) {
    fail(`realm ${realm}: không xuất được realm để đối chiếu flow (HTTP ${status})`);
    return;
  }
  const existingByAlias = new Map(
    (exported.authenticationFlows || []).map((f) => [f.alias, f]),
  );
  const configByAlias = new Map(
    (exported.authenticatorConfig || []).map((c) => [c.alias, c.config]),
  );
  let drift = 0;
  for (const flow of wanted) {
    const found = existingByAlias.get(flow.alias);
    if (!found) {
      fail(
        `realm ${realm}: flow ${flow.alias} còn thiếu trên realm đã tồn tại — bộ cấp phát không tự viết lại flow; hãy tạo qua Admin Console theo mẫu hoặc dựng lại realm ở môi trường phi production`,
      );
      drift += 1;
      continue;
    }
    if (flowSignature(found) !== flowSignature(flow)) {
      fail(`realm ${realm}: flow ${flow.alias} lệch mẫu (requirement/execution khác) — cần xử lý tay theo mẫu, bộ cấp phát không tự sửa flow đang chạy`);
      drift += 1;
    }
  }
  for (const cfg of rep.authenticatorConfig || []) {
    const foundCfg = configByAlias.get(cfg.alias);
    if (!foundCfg || JSON.stringify(foundCfg) !== JSON.stringify(cfg.config)) {
      fail(`realm ${realm}: authenticator config ${cfg.alias} lệch mẫu`);
      drift += 1;
    }
  }
  if (drift === 0) ok(`realm ${realm}: toàn bộ flow và authenticator config khớp mẫu`);
}

// ---------------------------------------------------------------------------
// Cấp phát một realm
// ---------------------------------------------------------------------------

async function provisionRealm(rep, kind, webClientId, webSecretEnvName) {
  const realm = rep.realm;
  console.log(`Realm ${realm}:`);
  const { status } = await api("GET", `/admin/realms/${realm}`);
  if (status === 404) {
    const created = await api("POST", "/admin/realms", rep);
    if (created.status !== 201) {
      fail(
        `tạo realm ${realm} trả HTTP ${created.status}: ${JSON.stringify(created.data)?.slice(0, 300) ?? ""}`,
      );
      return;
    }
    ok(`đã tạo realm ${realm} từ mẫu (kèm flow, role, client, scope)`);
  } else if (status !== 200) {
    fail(`đọc realm ${realm} trả HTTP ${status}`);
    return;
  } else {
    note(`realm ${realm} đã tồn tại — chuyển sang đối chiếu`);
  }
  await reconcileScalars(rep);
  await reconcileRequiredActions(rep);
  await reconcileRealmRoles(rep);
  await stripOfflineAccessFromDefaultRoles(realm);
  await reconcileClientScopes(rep);
  await reconcileClients(rep, kind, webClientId, webSecretEnvName);
  await reconcileClientRoles(rep);
  await reconcileScopeRoleMappings(rep, kind);
  await reconcileIdps(rep);
  await reconcileFlows(rep);
}

// ---------------------------------------------------------------------------
// Lớp phủ cho realm master — MFA bắt buộc cho đăng nhập tương tác
// ---------------------------------------------------------------------------
//
// Realm master không có mẫu import (nó ra đời cùng bản Keycloak),
// nên lớp phủ này áp bằng Admin REST API thuần:
//
// - Cùng tham số chống brute force và cờ sự kiện như các realm
//   khác.
// - Flow trình duyệt riêng "lms-master-browser" (cùng cấu trúc
//   flow platform-browser đã kiểm chứng ở realm giaokhoa: cookie
//   ALTERNATIVE, subflow form chứa mật khẩu REQUIRED rồi OTP
//   REQUIRED) và đặt làm browserFlow của realm. OTP REQUIRED vô
//   điều kiện nghĩa là mọi đăng nhập trình duyệt vào master đều
//   phải qua yếu tố thứ hai: chưa có credential OTP thì
//   authenticator tự kích hoạt required action khai báo TOTP và
//   đăng nhập không thể hoàn tất trước khi khai báo xong; đã có
//   thì bị hỏi mã OTP. Flow này chỉ tác động đăng nhập tương tác:
//   các lượt cấp token qua endpoint token (password grant của bộ
//   cấp phát qua admin-cli) không đi qua browser flow.
// - Gắn thêm required action CONFIGURE_TOTP cho mọi tài khoản
//   master chưa có credential OTP, trừ đúng một tài khoản: tài
//   khoản đang gọi Admin API (KC_ADMIN_USERNAME). Đây là sự tách
//   bạch theo thiết kế giữa tài khoản tự động hoá (chỉ gọi API,
//   không đăng nhập tương tác) và tài khoản vận hành tương tác
//   (người dùng Admin Console): Keycloak từ chối cấp token cho
//   tài khoản còn required action chưa hoàn tất, nên gắn yêu cầu
//   cho chính tài khoản gọi API sẽ tự khoá đường cấp phát; mọi
//   tài khoản còn lại đều bị bắt buộc MFA mà không cần khai báo
//   thêm biến môi trường nào.
//
// Flow được tạo TỪNG BƯỚC bằng các endpoint chính thức (tạo flow,
// thêm từng execution, đặt requirement bằng PUT từng bản ghi
// execution nguyên trạng đọc từ GET — đã kiểm chứng trên binary
// 26.8.1; gửi cả danh sách executions trong một PUT bị lỗi 400).
// Nếu flow đã tồn tại mà lệch cấu trúc, bộ cấp phát báo lỗi và
// không tự viết lại flow đang chạy, cùng kỷ luật với
// reconcileFlows của các realm có mẫu.

const MASTER_BROWSER_FLOW = "lms-master-browser";
const MASTER_BROWSER_FORMS = "lms-master-browser forms";

// Chữ ký đích của flow: [mức lồng, định danh execution, requirement].
// Execution dạng subflow mang định danh "flow:<alias của subflow>".
const MASTER_FLOW_SIGNATURE = [
  [0, "auth-cookie", "ALTERNATIVE"],
  [0, `flow:${MASTER_BROWSER_FORMS}`, "ALTERNATIVE"],
  [1, "auth-username-password-form", "REQUIRED"],
  [1, "auth-otp-form", "REQUIRED"],
];

function masterFlowKey(e) {
  return e.authenticationFlow ? `flow:${e.displayName}` : e.providerId;
}

function masterFlowElements(execs) {
  return (execs || [])
    .map((e) => `${e.level}|${masterFlowKey(e)}`)
    .sort()
    .join(";");
}

async function ensureMasterBrowserFlow() {
  const execsPath = `/admin/realms/master/authentication/flows/${encodeURIComponent(MASTER_BROWSER_FLOW)}/executions`;
  const first = await api("GET", execsPath);
  if (first.status === 404) {
    const created = await api("POST", "/admin/realms/master/authentication/flows", {
      alias: MASTER_BROWSER_FLOW,
      description:
        "Flow trình duyệt của realm master: mật khẩu và OTP bắt buộc cho mọi đăng nhập tương tác",
      providerId: "basic-flow",
      topLevel: true,
      builtIn: false,
    });
    if (created.status !== 201) {
      fail(`tạo flow ${MASTER_BROWSER_FLOW} cho realm master trả HTTP ${created.status}`);
      return false;
    }
    const steps = [
      [
        `/admin/realms/master/authentication/flows/${encodeURIComponent(MASTER_BROWSER_FLOW)}/executions/execution`,
        { provider: "auth-cookie" },
      ],
      [
        `/admin/realms/master/authentication/flows/${encodeURIComponent(MASTER_BROWSER_FLOW)}/executions/flow`,
        {
          alias: MASTER_BROWSER_FORMS,
          type: "basic-flow",
          description: "Form đăng nhập của realm master: mật khẩu rồi OTP",
        },
      ],
      [
        `/admin/realms/master/authentication/flows/${encodeURIComponent(MASTER_BROWSER_FORMS)}/executions/execution`,
        { provider: "auth-username-password-form" },
      ],
      [
        `/admin/realms/master/authentication/flows/${encodeURIComponent(MASTER_BROWSER_FORMS)}/executions/execution`,
        { provider: "auth-otp-form" },
      ],
    ];
    for (const [path, body] of steps) {
      const res = await api("POST", path, body);
      if (res.status !== 201) {
        fail(`dựng flow ${MASTER_BROWSER_FLOW}: POST ${path} trả HTTP ${res.status}`);
        return false;
      }
    }
    ok(`đã tạo flow ${MASTER_BROWSER_FLOW} cho realm master (mật khẩu + OTP bắt buộc)`);
  } else if (first.status !== 200) {
    fail(`đọc flow ${MASTER_BROWSER_FLOW} của realm master trả HTTP ${first.status}`);
    return false;
  }

  const wantedElements = MASTER_FLOW_SIGNATURE.map(([level, key]) => `${level}|${key}`)
    .sort()
    .join(";");
  let { data: execs } = await api("GET", execsPath);
  if (masterFlowElements(execs) !== wantedElements) {
    fail(
      `flow ${MASTER_BROWSER_FLOW} của realm master lệch mẫu về cấu trúc execution — bộ cấp phát không tự viết lại flow đang chạy; hãy xử lý tay theo keycloak/README.md`,
    );
    return false;
  }
  // Đặt requirement còn lệch: PUT từng bản ghi execution nguyên
  // trạng đọc từ GET, chỉ thay trường requirement.
  const wantedRequirement = new Map(
    MASTER_FLOW_SIGNATURE.map(([level, key, req]) => [`${level}|${key}`, req]),
  );
  for (const e of execs || []) {
    const target = wantedRequirement.get(`${e.level}|${masterFlowKey(e)}`);
    if (!target || e.requirement === target) continue;
    const put = await api(
      "PUT",
      `/admin/realms/master/authentication/flows/${encodeURIComponent(MASTER_BROWSER_FLOW)}/executions`,
      { ...e, requirement: target },
    );
    if (put.status !== 204) {
      fail(`đặt requirement ${target} cho execution ${e.displayName} của flow master trả HTTP ${put.status}`);
      return false;
    }
  }
  const finalRead = await api("GET", execsPath);
  const finalExecs = finalRead.data;
  // Fail-closed: phải đọc lại được executions và đối chiếu cả tập
  // phần tử lẫn requirement của từng phần tử. Mảng rỗng không
  // được coi là đạt — phép every trên mảng rỗng luôn đúng theo
  // tính chất vacuous truth.
  const signatureOk =
    finalRead.status === 200 &&
    Array.isArray(finalExecs) &&
    finalExecs.length > 0 &&
    masterFlowElements(finalExecs) === wantedElements &&
    finalExecs.every(
      (e) => wantedRequirement.get(`${e.level}|${masterFlowKey(e)}`) === e.requirement,
    );
  if (!signatureOk) {
    fail(
      `flow ${MASTER_BROWSER_FLOW} của realm master chưa đạt đúng requirement theo mẫu sau khi đặt (đọc lại HTTP ${finalRead.status})`,
    );
    return false;
  }
  ok(`flow ${MASTER_BROWSER_FLOW} của realm master khớp mẫu (OTP bắt buộc sau mật khẩu)`);
  return true;
}

async function applyMasterOverlay() {
  console.log("Realm master (lớp phủ vận hành):");
  const flowReady = await ensureMasterBrowserFlow();
  const { data: master } = await api("GET", "/admin/realms/master");
  const wanted = {
    bruteForceProtected: true,
    bruteForceStrategy: "LINEAR",
    permanentLockout: false,
    failureFactor: 5,
    waitIncrementSeconds: 60,
    minimumQuickLoginWaitSeconds: 60,
    quickLoginCheckMilliSeconds: 1000,
    maxFailureWaitSeconds: 900,
    maxDeltaTimeSeconds: 43200,
    eventsEnabled: true,
    eventsListeners: ["jboss-logging"],
    adminEventsEnabled: true,
    adminEventsDetailsEnabled: false,
  };
  // Chỉ trỏ browserFlow về flow MFA khi flow đã bảo đảm đúng mẫu,
  // tránh để realm master trỏ vào một flow chưa sẵn sàng.
  if (flowReady) wanted.browserFlow = MASTER_BROWSER_FLOW;
  const updated = { ...master, ...wanted };
  const { status } = await api("PUT", "/admin/realms/master", updated);
  if (status === 204) ok("đã áp tham số brute force, sự kiện và browser flow MFA cho realm master");
  else fail(`PUT realm master trả HTTP ${status}`);

  // Gắn CONFIGURE_TOTP cho mọi tài khoản tương tác chưa có
  // credential OTP, trừ tài khoản đang gọi Admin API (xem khối
  // giải thích ở đầu mục này).
  const { data: users } = await api("GET", "/admin/realms/master/users?first=0&max=1000");
  let covered = 0;
  for (const entry of users || []) {
    if ((entry.username || "").toLowerCase() === adminUser.toLowerCase()) continue;
    const { data: user } = await api("GET", `/admin/realms/master/users/${entry.id}`);
    if (!user) continue;
    const { data: creds } = await api("GET", `/admin/realms/master/users/${user.id}/credentials`);
    if ((creds || []).some((c) => c.type === "otp")) {
      covered += 1;
      continue;
    }
    const actions = new Set(user.requiredActions || []);
    if (actions.has("CONFIGURE_TOTP")) {
      covered += 1;
      continue;
    }
    actions.add("CONFIGURE_TOTP");
    const put = await api("PUT", `/admin/realms/master/users/${user.id}`, {
      ...user,
      requiredActions: [...actions],
    });
    if (put.status === 204) {
      covered += 1;
      ok(`đã gắn yêu cầu khai báo TOTP cho tài khoản master ${user.username}`);
    } else {
      fail(`gắn CONFIGURE_TOTP cho tài khoản master ${user.username} trả HTTP ${put.status}`);
    }
  }
  note(
    `realm master: ${covered} tài khoản tương tác đã nằm trong diện bắt buộc MFA; tài khoản gọi Admin API (${adminUser}) tách riêng cho tự động hoá nên không gắn required action`,
  );
}

// ---------------------------------------------------------------------------
// Chính
// ---------------------------------------------------------------------------

async function main() {
  const jobs = [];
  for (const tenant of config.tenants || []) {
    jobs.push({
      rep: buildRepresentation(
        "tenant-realm.json",
        {
          REALM: tenant.realm,
          TENANT_HOST: tenant.host,
          MCP_RESOURCE_URL: tenant.mcpResourceUrl,
          MCP_REDIRECT_URIS: JSON.stringify(tenant.mcpRedirectUris || []),
          GOOGLE_CLIENT_ID: process.env.GOOGLE_CLIENT_ID || "",
          "GOOGLE_CLIENT_SECRET": process.env.GOOGLE_CLIENT_SECRET || "",
          FACEBOOK_CLIENT_ID: process.env.FACEBOOK_CLIENT_ID || "",
          "FACEBOOK_CLIENT_SECRET": process.env.FACEBOOK_CLIENT_SECRET || "",
        },
        "lms-web",
        `LMS_WEB_SECRET__${realmEnvKey(tenant.realm)}`,
      ),
      kind: "tenant",
      webClientId: "lms-web",
      webSecretEnvName: `LMS_WEB_SECRET__${realmEnvKey(tenant.realm)}`,
    });
  }
  if (config.platform) {
    jobs.push({
      rep: buildRepresentation(
        "platform-realm.json",
        { PLATFORM_HOST: config.platform.host },
        "platform-web",
        "PLATFORM_WEB_SECRET",
      ),
      kind: "platform",
      webClientId: "platform-web",
      webSecretEnvName: "PLATFORM_WEB_SECRET",
    });
  }

  if (dryRun) {
    for (const job of jobs) {
      console.log(
        `DRY-RUN realm ${job.rep.realm}: ${job.rep.clients?.length || 0} client, ` +
          `${job.rep.roles?.realm?.length || 0} realm role, ` +
          `${job.rep.authenticationFlows?.length || 0} flow, ` +
          `${job.rep.identityProviders?.length || 0} IdP sau lọc theo môi trường`,
      );
    }
    console.log("DRY-RUN realm master: lớp phủ brute force + flow trình duyệt bắt OTP + CONFIGURE_TOTP cho mọi tài khoản tương tác trừ tài khoản gọi API");
    return;
  }

  for (const job of jobs) {
    await provisionRealm(job.rep, job.kind, job.webClientId, job.webSecretEnvName);
  }
  await applyMasterOverlay();

  if (failures > 0) {
    console.error(`Cấp phát kết thúc với ${failures} lỗi.`);
    process.exit(1);
  }
  console.log("Cấp phát hoàn tất, không lỗi.");
}

main().catch((err) => {
  console.error(`Cấp phát thất bại: ${err.message}`);
  process.exit(1);
});
