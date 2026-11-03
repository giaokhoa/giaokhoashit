#!/usr/bin/env node
// Bộ cấp phát realm Keycloak từ mẫu cấu hình trong keycloak/templates/.
//
// Việc này (issue #31) cấp đúng MỘT realm duy nhất tên "giaokhoa": mỗi
// trung tâm là một Organization trong realm (Organizations và Admin
// Permissions/FGAP v2 bật từ mẫu), thay cho mô hình realm-mỗi-trung-tâm
// của issue #6. Chạy lần đầu tạo realm từ môi trường trống; chạy lại
// không tạo trùng (kể cả Organization, role, policy và permission
// FGAP), không ghi đè bí mật đang có bằng giá trị rỗng, và khôi phục
// các trường cấu hình bị lệch khỏi mẫu (scalar realm, role, client,
// scope). Realm master giữ nguyên cấu hình mặc định của Keycloak: bộ
// cấp phát chỉ lấy access token admin qua realm master, không sửa gì
// trên đó.
//
// Bí mật không bao giờ nằm trong repo: mẫu chỉ chứa placeholder, giá
// trị thật đi qua biến môi trường lúc chạy (xem keycloak/README.md và
// keycloak/provision.config.example.json cho phần cấu hình không bí
// mật). Script chỉ dùng thư viện chuẩn của Node (fetch, fs).
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
if ("tenants" in config || "platform" in config) {
  console.error(
    'Cấu hình còn khoá "tenants"/"platform": mô hình realm-mỗi-trung-tâm đã bị thay thế bởi một realm ' +
      '"giaokhoa" duy nhất với Organization theo trung tâm. Hãy chuyển cấu hình sang khoá "realm" ' +
      "(xem keycloak/provision.config.example.json).",
  );
  process.exit(2);
}
if (config.realm?.name !== "giaokhoa") {
  console.error(
    'Cấu hình bị từ chối: realm.name phải đúng "giaokhoa" — bộ cấp phát chỉ cấp phát realm giaokhoa duy nhất.',
  );
  process.exit(2);
}
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
    throw new Error(`Thiếu giá trị cho placeholder {{${key}}}`);
  });
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

// Dựng representation của realm giaokhoa từ mẫu: thay placeholder,
// gắn SMTP khi có cấu hình, gắn bí mật client web khi môi trường cấp
// (chỉ dùng ở nhánh tạo mới; nhánh cập nhật xử lý bí mật riêng để
// không ghi đè giá trị đang có).
function buildRepresentation(values) {
  const raw = readFileSync(join(HERE, "templates", "giaokhoa-realm.json"), "utf8");
  const rep = JSON.parse(renderTemplate(raw, values));

  const smtp = smtpServerFromEnv();
  if (smtp) rep.smtpServer = smtp;

  if (process.env.LMS_WEB_SECRET) {
    const web = (rep.clients || []).find((c) => c.clientId === "lms-web");
    if (web) web.secret = process.env.LMS_WEB_SECRET;
  }
  return rep;
}

// ---------------------------------------------------------------------------
// Đối chiếu trên realm đã tồn tại
// ---------------------------------------------------------------------------

// Các trường scalar mà mẫu là nguồn sự thật trên realm đã có. Role và
// client KHÔNG nằm ở đây: chúng có endpoint riêng chuẩn hơn. Mọi tuỳ
// chọn còn lại của Keycloak (thời hạn token, brute force, OTP...) để
// mặc định, không khai trong mẫu nên cũng không hoà giải tại đây.
const MANAGED_SCALAR_KEYS = [
  "displayName",
  "enabled",
  "organizationsEnabled",
  "adminPermissionsEnabled",
  "registrationAllowed",
  "registrationEmailAsUsername",
  "loginWithEmailAllowed",
  "duplicateEmailsAllowed",
  "editUsernameAllowed",
  "resetPasswordAllowed",
  "rememberMe",
  "verifyEmail",
  "defaultDefaultClientScopes",
  "defaultOptionalClientScopes",
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
// access tắt theo mặc định ở mọi realm.
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
// (Full Scope Allowed đã tắt ở mọi client). Scope "organization" do
// Keycloak tự tạo khi realm bật Organizations (kèm mapper membership)
// nên không khai trong mẫu; nó nằm trong bộ gán dưới đây và còn được
// bước assignOrganizationScope gắn riêng sau khi Organization đã có
// (xem giải thích tại bước đó).
const CLIENT_SCOPE_ASSIGNMENTS = {
  "lms-web": {
    default: ["basic", "profile", "email", "roles", "web-origins", "acr", "lms-api-audience", "organization"],
    optional: [],
  },
  "lms-api": { default: ["organization"], optional: [] },
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
};

async function reconcileClientScopeAssignments(realm, clientInternalId, clientId) {
  const plan = CLIENT_SCOPE_ASSIGNMENTS[clientId];
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

async function reconcileClients(rep) {
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
      if (internalId) await reconcileClientScopeAssignments(realm, internalId, client.clientId);
      continue;
    }
    // Cập nhật: phủ các trường của mẫu lên bản hiện tại. Bí mật chỉ ghi
    // khi môi trường cấp giá trị tường minh; thiếu biến thì giữ nguyên
    // bí mật đang có trong Keycloak (không gửi trường secret).
    const desired = { ...existing, ...client, id: existing.id };
    const envSecret = client.clientId === "lms-web" ? process.env.LMS_WEB_SECRET : undefined;
    if (envSecret) desired.secret = envSecret;
    else delete desired.secret;
    const put = await api("PUT", `/admin/realms/${realm}/clients/${existing.id}`, desired);
    if (put.status !== 204) {
      fail(`realm ${realm}: cập nhật client ${client.clientId} trả HTTP ${put.status}`);
      continue;
    }
    await reconcileClientScopeAssignments(realm, existing.id, client.clientId);
  }
  ok(`realm ${realm}: clients khớp mẫu`);
}

// Ánh xạ vai lên client scope "roles": vì Full Scope Allowed tắt ở
// mọi client, token chỉ mang vai khi vai đó được ánh xạ vào một scope
// mà client mang. Scope "roles" (mặc định của client web) được ánh xạ
// đúng tập vai được phép xuất hiện trong token: cả bốn realm role của
// mẫu (ba vai chính của trung tâm và platform-admin). Realm không còn
// client role nào, nên mọi ánh xạ client role hiện hữu đều là ngoài
// mẫu và phải gỡ. Đây là chốt "role scope giới hạn ở mức cần thiết"
// của việc con 3.
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

async function reconcileScopeRoleMappings(rep) {
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

  // Tập đích: cả bốn realm role của mẫu, không client role nào.
  const wantedRealmRoles = rep.roles?.realm || [];
  const wantedClientRoles = {};

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

// ---------------------------------------------------------------------------
// Cấp phát một realm
// ---------------------------------------------------------------------------

async function provisionRealm(rep) {
  const realm = rep.realm;
  console.log(`Realm ${realm}:`);
  const { status } = await api("GET", `/admin/realms/${realm}`);
  if (status === 404) {
    // Không gửi clientScopes trong lượt import tạo realm: khi danh sách scope đi kèm bản import, Keycloak dùng đúng
    // danh sách đó thay cho bộ scope mặc định và không tự tạo scope "organization" của tính năng Organizations. Để
    // trống lúc tạo realm để Keycloak dựng bộ mặc định (gồm "organization" kèm mapper của nó); các scope của mẫu
    // được reconcileClientScopes tạo và đồng bộ ngay sau đó như bình thường.
    const { clientScopes: _templateScopes, ...createRep } = rep;
    const created = await api("POST", "/admin/realms", createRep);
    if (created.status !== 201) {
      fail(
        `tạo realm ${realm} trả HTTP ${created.status}: ${JSON.stringify(created.data)?.slice(0, 300) ?? ""}`,
      );
      return;
    }
    ok(`đã tạo realm ${realm} từ mẫu (kèm role, client, scope)`);
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
  await reconcileClients(rep);
  await reconcileScopeRoleMappings(rep);
}

// ---------------------------------------------------------------------------
// Chính
// ---------------------------------------------------------------------------

async function main() {
  const realmConfig = config.realm;
  const rep = buildRepresentation({
    REALM: realmConfig.name,
    HOST: realmConfig.host,
    MCP_RESOURCE_URL: realmConfig.mcpResourceUrl,
    MCP_REDIRECT_URIS: JSON.stringify(realmConfig.mcpRedirectUris || []),
  });
  const organizations = realmConfig.organizations || [];

  if (dryRun) {
    console.log(
      `DRY-RUN realm ${rep.realm}: ${rep.clients?.length || 0} client, ` +
        `${rep.roles?.realm?.length || 0} realm role, ` +
        `${organizations.length} Organization theo cấu hình`,
    );
    return;
  }

  await provisionRealm(rep);

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
