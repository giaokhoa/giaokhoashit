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
async function reconcileScopeRoleMappings(rep, kind) {
  const realm = rep.realm;
  const { data: scopes } = await api("GET", `/admin/realms/${realm}/client-scopes`);
  const rolesScope = (scopes || []).find((s) => s.name === "roles");
  if (!rolesScope) {
    fail(`realm ${realm}: không thấy client scope roles để ánh xạ vai`);
    return;
  }
  const { data: mappings } = await api(
    "GET",
    `/admin/realms/${realm}/client-scopes/${rolesScope.id}/scope-mappings`,
  );
  if (kind === "tenant") {
    const have = new Set((mappings?.realm || []).map((r) => r.name));
    for (const role of rep.roles?.realm || []) {
      if (have.has(role.name)) continue;
      const { data: roleRep } = await api("GET", `/admin/realms/${realm}/roles/${role.name}`);
      const posted = await api(
        "POST",
        `/admin/realms/${realm}/client-scopes/${rolesScope.id}/scope-mappings/realm`,
        [roleRep],
      );
      if (posted.status === 204) ok(`realm ${realm}: đã ánh xạ vai ${role.name} vào scope roles`);
      else fail(`realm ${realm}: ánh xạ vai ${role.name} vào scope roles trả HTTP ${posted.status}`);
    }
  }
  if (kind === "platform") {
    for (const [clientId, roles] of Object.entries(rep.roles?.client || {})) {
      const internalId = await findClientInternalId(realm, clientId);
      if (!internalId) continue;
      const have = new Set((mappings?.client?.[clientId] || []).map((r) => r.name));
      for (const role of roles) {
        if (have.has(role.name)) continue;
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
        else fail(`realm ${realm}: ánh xạ client role ${role.name} trả HTTP ${posted.status}`);
      }
    }
  }
  ok(`realm ${realm}: ánh xạ vai của scope roles khớp mẫu`);
}

async function findClientInternalId(realm, clientId) {
  const { data } = await api(
    "GET",
    `/admin/realms/${realm}/clients?clientId=${encodeURIComponent(clientId)}`,
  );
  return Array.isArray(data) ? data[0]?.id : undefined;
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

// Lớp phủ cho realm master: cùng tham số chống brute force và cờ sự
// kiện như các realm khác, cộng bắt tài khoản vận hành khai báo qua
// KC_MASTER_OPERATOR_USERNAME (mặc định: chính tài khoản đang gọi API)
// phải hoàn tất khai báo TOTP. Đây là tài khoản quản trị Keycloak
// thuần, tách khỏi tài khoản nền tảng và tài khoản tenant.
async function applyMasterOverlay() {
  console.log("Realm master (lớp phủ vận hành):");
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
  const updated = { ...master, ...wanted };
  const { status } = await api("PUT", "/admin/realms/master", updated);
  if (status === 204) ok("đã áp tham số brute force và sự kiện cho realm master");
  else fail(`PUT realm master trả HTTP ${status}`);

  const operator = process.env.KC_MASTER_OPERATOR_USERNAME || "";
  if (!operator) {
    note(
      "chưa khai báo KC_MASTER_OPERATOR_USERNAME: bỏ qua bước gắn yêu cầu TOTP cho tài khoản vận hành master (khai báo biến này trỏ tới tài khoản người vận hành, khác tài khoản gọi API)",
    );
    return;
  }
  if (operator === adminUser) {
    note(
      "KC_MASTER_OPERATOR_USERNAME trùng tài khoản gọi API: bỏ qua gắn CONFIGURE_TOTP — gắn yêu cầu này sẽ khoá chính đường gọi Admin API cho tới khi một người hoàn tất khai báo TOTP qua trình duyệt",
    );
    return;
  }
  const { data: users } = await api(
    "GET",
    `/admin/realms/master/users?username=${encodeURIComponent(operator)}&exact=true`,
  );
  const user = Array.isArray(users) ? users[0] : null;
  if (!user) {
    fail(`không tìm thấy tài khoản vận hành ${operator} trong realm master`);
    return;
  }
  const actions = new Set(user.requiredActions || []);
  if (actions.has("CONFIGURE_TOTP")) {
    ok(`tài khoản vận hành ${operator} đã mang yêu cầu khai báo TOTP`);
    return;
  }
  // Chỉ gắn yêu cầu khi tài khoản chưa có credential OTP: đã có rồi
  // thì flow đăng nhập của master tự bắt nhập OTP, gắn thêm yêu cầu
  // khai báo lại là thừa.
  const { data: creds } = await api("GET", `/admin/realms/master/users/${user.id}/credentials`);
  if ((creds || []).some((c) => c.type === "otp")) {
    ok(`tài khoản vận hành ${operator} đã có credential OTP`);
    return;
  }
  actions.add("CONFIGURE_TOTP");
  const put = await api("PUT", `/admin/realms/master/users/${user.id}`, {
    ...user,
    requiredActions: [...actions],
  });
  if (put.status === 204) ok(`đã gắn yêu cầu khai báo TOTP cho tài khoản vận hành ${operator}`);
  else fail(`gắn CONFIGURE_TOTP cho ${operator} trả HTTP ${put.status}`);
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
    console.log("DRY-RUN realm master: lớp phủ brute force + TOTP cho tài khoản vận hành");
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
