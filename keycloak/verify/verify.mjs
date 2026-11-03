#!/usr/bin/env node
// Bộ kiểm chứng cho việc cấp phát Keycloak của issue #31: MỘT realm
// "giaokhoa" duy nhất, mỗi trung tâm là một Organization trong realm,
// admin của trung tâm được uỷ quyền bằng Admin Permissions (FGAP v2).
//
// Chạy trên một Keycloak 26.8.1 (xem compose.verify.yaml cùng thư
// mục, hoặc bất kỳ instance nào trỏ qua KC_BASE_URL) và kiểm chứng
// bằng hành vi thật trên binary thật, theo đúng thứ tự:
//   0. bộ cấp phát từ chối cấu hình theo mô hình realm-mỗi-trung-tâm;
//   1. cấp phát bằng cấu hình kiểm chứng (2 Organization mẫu);
//   2. cấu trúc realm: Organizations + FGAP bật, đủ role/client/scope,
//      không còn realm theo trung tâm;
//   3. Organization và bộ permission FGAP theo từng trung tâm;
//   4. fixture: user kiểm chứng và membership theo Organization;
//   5. claim "organization" trong token của thành viên;
//   6. ma trận FGAP của admin trung tâm: chỉ quản được Organization
//      của mình, Organization khác bị 403;
//   7. realm master giữ nguyên mặc định của Keycloak;
//   8. chạy lại bộ cấp phát: không trùng Organization/permission,
//      không đổi bí mật của client web.
//
// Mọi kiểm tra in một dòng tiếng Việt ĐẠT/KHÔNG ĐẠT kèm tên rõ ràng;
// tiến trình thoát khác 0 nếu có kiểm tra KHÔNG ĐẠT.

import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const PROVISION = join(HERE, "..", "provision.mjs");
const REALM = "giaokhoa";
// Mật khẩu dùng một lần cho user fixture trên stack kiểm chứng.
const FIXTURE_PASSWORD = "Verify-pass-123!";

const baseUrl = (process.env.KC_BASE_URL || "http://localhost:8180").replace(/\/$/, "");
const adminUser = process.env.KC_ADMIN_USERNAME || "";
const adminPass = process.env.KC_ADMIN_PASSWORD || "";
const configPath = process.env.PROVISION_CONFIG || join(HERE, "provision.config.verify.json");
const config = JSON.parse(readFileSync(configPath, "utf8"));
const orgAliases = (config.realm?.organizations || []).map((o) => o.alias);
const [aliasA, aliasB] = orgAliases;

if (!adminUser || !adminPass) {
  console.error("Thiếu KC_ADMIN_USERNAME / KC_ADMIN_PASSWORD.");
  process.exit(2);
}
if (!aliasA || !aliasB) {
  console.error("Cấu hình kiểm chứng cần đúng 2 Organization mẫu.");
  process.exit(2);
}

let passed = 0;
let failed = 0;
function check(name, condition, detail = "") {
  if (condition) {
    passed += 1;
    console.log(`ĐẠT      ${name}`);
  } else {
    failed += 1;
    console.log(`KHÔNG ĐẠT ${name}${detail ? ` — ${detail}` : ""}`);
  }
}
function section(title) {
  console.log(`\n== ${title} ==`);
}

// ---------------------------------------------------------------------------
// HTTP và Admin REST API (token quản trị qua admin-cli của realm master)
// ---------------------------------------------------------------------------

async function http(method, url, { token, form, json, raw } = {}) {
  const headers = {};
  const init = { method, headers };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (form) {
    headers["Content-Type"] = "application/x-www-form-urlencoded";
    init.body = new URLSearchParams(form).toString();
  } else if (raw !== undefined) {
    headers["Content-Type"] = "application/json";
    init.body = raw;
  } else if (json !== undefined) {
    headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(json);
  }
  const res = await fetch(url, init);
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

let cachedAdminToken = null;
let adminTokenExpiresAt = 0;
async function adminToken() {
  // Token quản trị của realm master chỉ sống ngắn (60 giây trên
  // 26.8.1): xin token mới khi token đã cache sắp hết hạn.
  if (cachedAdminToken && Date.now() < adminTokenExpiresAt - 5_000) return cachedAdminToken;
  const res = await http("POST", `${baseUrl}/realms/master/protocol/openid-connect/token`, {
    form: { client_id: "admin-cli", grant_type: "password", username: adminUser, "password": adminPass },
  });
  if (res.status !== 200) throw new Error(`Lấy token quản trị thất bại: HTTP ${res.status}`);
  cachedAdminToken = res.data.access_token;
  adminTokenExpiresAt = Date.now() + (res.data.expires_in ?? 60) * 1000;
  return cachedAdminToken;
}

async function api(method, path, json) {
  return http(method, `${baseUrl}${path}`, { token: await adminToken(), json });
}

// Gọi Admin API bằng một token cho trước (token của user fixture,
// dùng cho ma trận FGAP) thay vì token quản trị master.
async function apiAs(token, method, path, { json, raw } = {}) {
  return http(method, `${baseUrl}${path}`, { token, json, raw });
}

async function passwordGrant(realm, { clientId, clientSecret, username, scope }) {
  const form = { client_id: clientId, grant_type: "password", username, "password": FIXTURE_PASSWORD };
  if (clientSecret) form.client_secret = clientSecret;
  if (scope) form.scope = scope;
  return http("POST", `${baseUrl}/realms/${realm}/protocol/openid-connect/token`, { form });
}

function decodeJwtPayload(token) {
  return JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString("utf8"));
}

function runProvision(cfgPath) {
  const res = spawnSync("node", [PROVISION, "--config", cfgPath], {
    env: process.env,
    encoding: "utf8",
  });
  return { status: res.status, output: `${res.stdout || ""}${res.stderr || ""}` };
}

// ---------------------------------------------------------------------------
// Tra cứu cấu trúc realm/Organization/FGAP
// ---------------------------------------------------------------------------

async function listOrganizations() {
  const { data } = await api("GET", `/admin/realms/${REALM}/organizations?first=0&max=100`);
  return Array.isArray(data) ? data : [];
}

async function clientInternalId(clientId) {
  const { data } = await api(
    "GET",
    `/admin/realms/${REALM}/clients?clientId=${encodeURIComponent(clientId)}`,
  );
  return Array.isArray(data) ? data[0]?.id : undefined;
}

async function authzBase() {
  const apId = await clientInternalId("admin-permissions");
  return `/admin/realms/${REALM}/clients/${apId}/authz/resource-server`;
}

async function listAuthz(path) {
  const { data } = await api("GET", `${path}?first=0&max=100`);
  return Array.isArray(data) ? data : [];
}

async function fgPermissions() {
  return listAuthz(`${await authzBase()}/permission/scope`);
}

async function permissionDetail(perm) {
  const base = await authzBase();
  const detail = await api("GET", `${base}/permission/scope/${perm.id}`);
  const resources = await api("GET", `${base}/policy/${perm.id}/resources`);
  const scopes = await api("GET", `${base}/policy/${perm.id}/scopes`);
  const policies = await api("GET", `${base}/policy/${perm.id}/associatedPolicies`);
  return {
    resourceType: detail.data?.resourceType,
    resourceNames: (resources.data || []).map((r) => r.name),
    scopeNames: (scopes.data || []).map((s) => s.name),
    policyNames: (policies.data || []).map((p) => p.name),
    policyIds: (policies.data || []).map((p) => p.id),
  };
}

async function clientSecretValue(clientId) {
  const id = await clientInternalId(clientId);
  const { data } = await api("GET", `/admin/realms/${REALM}/clients/${id}/client-secret`);
  return data?.value;
}

// ---------------------------------------------------------------------------
// Fixture qua tài khoản quản trị master
// ---------------------------------------------------------------------------

async function createUser(username) {
  const created = await api("POST", `/admin/realms/${REALM}/users`, {
    username,
    enabled: true,
    // Thiếu firstName/lastName thì Keycloak 26 báo "Account is not
    // fully set up" khi user đăng nhập: fixture bắt buộc có đủ.
    firstName: "Kiểm",
    lastName: "Chứng",
    email: `${username}@example.invalid`,
    emailVerified: true,
    credentials: [{ type: "password", value: FIXTURE_PASSWORD, temporary: false }],
    requiredActions: [],
  });
  if (created.status !== 201) throw new Error(`Tạo user ${username}: HTTP ${created.status}`);
  const { data } = await api(
    "GET",
    `/admin/realms/${REALM}/users?username=${encodeURIComponent(username)}&exact=true`,
  );
  return data[0];
}

async function addMember(orgId, userId) {
  // Admin API nhận body là chính id của user dạng chuỗi JSON.
  return http("POST", `${baseUrl}/admin/realms/${REALM}/organizations/${orgId}/members`, {
    token: await adminToken(),
    raw: JSON.stringify(userId),
  });
}

async function orgMembers(orgId) {
  const { data } = await api("GET", `/admin/realms/${REALM}/organizations/${orgId}/members?first=0&max=100`);
  return Array.isArray(data) ? data : [];
}

async function assignRealmRole(userId, roleName) {
  const { data: role } = await api("GET", `/admin/realms/${REALM}/roles/${roleName}`);
  const res = await api("POST", `/admin/realms/${REALM}/users/${userId}/role-mappings/realm`, [role]);
  if (res.status !== 204) throw new Error(`Gán realm role ${roleName}: HTTP ${res.status}`);
}

async function assignClientRoles(userId, clientId, roleNames) {
  const internalId = await clientInternalId(clientId);
  const { data: roles } = await api("GET", `/admin/realms/${REALM}/clients/${internalId}/roles`);
  const wanted = (roles || []).filter((r) => roleNames.includes(r.name));
  const res = await api(
    "POST",
    `/admin/realms/${REALM}/users/${userId}/role-mappings/clients/${internalId}`,
    wanted,
  );
  if (res.status !== 204) throw new Error(`Gán client role ${roleNames.join(",")}: HTTP ${res.status}`);
  return wanted.length;
}

// ---------------------------------------------------------------------------
// Các pha kiểm chứng
// ---------------------------------------------------------------------------

function phaseRejection() {
  section("Mục 0 — bộ cấp phát từ chối mô hình realm-mỗi-trung-tâm");
  const dir = mkdtempSync(join(tmpdir(), "kc-verify-reject-"));
  const tenantsPath = join(dir, "tenants.json");
  writeFileSync(tenantsPath, JSON.stringify({ ...config, tenants: [{ realm: "tenant-a" }] }));
  const rejectedTenants = runProvision(tenantsPath);
  check(
    "Cấu hình còn khoá tenants bị từ chối với mã thoát 2",
    rejectedTenants.status === 2,
    `mã thoát: ${rejectedTenants.status}`,
  );
  check(
    "Thông báo từ chối nói rõ mô hình realm-mỗi-trung-tâm đã bị thay thế",
    rejectedTenants.output.includes("mô hình realm-mỗi-trung-tâm đã bị thay thế"),
  );
  const wrongNamePath = join(dir, "wrong-name.json");
  writeFileSync(wrongNamePath, JSON.stringify({ ...config, realm: { ...config.realm, name: "tenant-a" } }));
  const rejectedName = runProvision(wrongNamePath);
  check(
    "Cấu hình realm.name khác giaokhoa bị từ chối với mã thoát 2",
    rejectedName.status === 2,
    `mã thoát: ${rejectedName.status}`,
  );
}

function phaseProvisioning() {
  section("Mục 1 — cấp phát bằng cấu hình kiểm chứng");
  const res = runProvision(configPath);
  process.stdout.write(res.output);
  check("Cấp phát lần đầu bằng provision.config.verify.json thoát 0", res.status === 0, `mã thoát: ${res.status}`);
  if (res.status !== 0) throw new Error("Cấp phát thất bại — dừng bộ kiểm chứng");
}

async function phaseRealmStructure() {
  section("Mục 2 — cấu trúc realm giaokhoa");
  const { data: realm } = await api("GET", `/admin/realms/${REALM}`);
  check("Realm giaokhoa bật Organizations (organizationsEnabled=true)", realm?.organizationsEnabled === true);
  check("Realm giaokhoa bật Admin Permissions (adminPermissionsEnabled=true)", realm?.adminPermissionsEnabled === true);

  const { data: roles } = await api("GET", `/admin/realms/${REALM}/roles`);
  const roleNames = (roles || []).map((r) => r.name);
  check(
    "Realm có đủ 4 realm role tenant-admin, tenant-teacher, tenant-member, platform-admin",
    ["tenant-admin", "tenant-teacher", "tenant-member", "platform-admin"].every((n) => roleNames.includes(n)),
    `roles: ${roleNames.join(", ")}`,
  );

  const { data: clients } = await api("GET", `/admin/realms/${REALM}/clients?max=100`);
  const clientIds = (clients || []).map((c) => c.clientId);
  check(
    "Đủ 4 client lms-web, lms-api, lms-mcp, lms-mcp-client",
    ["lms-web", "lms-api", "lms-mcp", "lms-mcp-client"].every((id) => clientIds.includes(id)),
    `clients: ${clientIds.join(", ")}`,
  );

  const { data: scopes } = await api("GET", `/admin/realms/${REALM}/client-scopes`);
  const orgScope = (scopes || []).find((s) => s.name === "organization");
  check(
    "Client scope organization tồn tại kèm mapper oidc-organization-membership-mapper",
    Boolean(orgScope) &&
      (orgScope.protocolMappers || []).some((m) => m.protocolMapper === "oidc-organization-membership-mapper"),
  );
  for (const clientId of ["lms-web", "lms-api"]) {
    const internalId = await clientInternalId(clientId);
    const { data: defaults } = await api(
      "GET",
      `/admin/realms/${REALM}/clients/${internalId}/default-client-scopes`,
    );
    check(
      `Scope organization là scope mặc định của ${clientId}`,
      (defaults || []).some((s) => s.name === "organization"),
    );
  }

  for (const oldRealm of ["tenant-a", "tenant-b"]) {
    const res = await api("GET", `/admin/realms/${oldRealm}`);
    check(`Không còn realm theo trung tâm ${oldRealm} (GET trả 404)`, res.status === 404, `HTTP ${res.status}`);
  }

  const rolesScope = (scopes || []).find((s) => s.name === "roles");
  const { data: mappings } = await api(
    "GET",
    `/admin/realms/${REALM}/client-scopes/${rolesScope.id}/scope-mappings`,
  );
  const mappedRealmRoles = (mappings?.realmMappings || []).map((r) => r.name).sort();
  check(
    "Scope roles ánh xạ đúng 4 realm role của mẫu",
    JSON.stringify(mappedRealmRoles) ===
      JSON.stringify(["platform-admin", "tenant-admin", "tenant-member", "tenant-teacher"]),
    `thực tế: ${mappedRealmRoles.join(", ")}`,
  );
  const mappedClientRoles = Object.values(mappings?.clientMappings || {}).flatMap((e) => e.mappings || []);
  check("Scope roles không ánh xạ client role nào", mappedClientRoles.length === 0);
}

async function phaseOrganizationsAndFgap() {
  section("Mục 3 — Organization và permission FGAP theo trung tâm");
  const orgs = await listOrganizations();
  const aliases = orgs.map((o) => o.alias).sort();
  check(
    `Đúng 2 Organization theo cấu hình (${aliasA}, ${aliasB})`,
    orgs.length === 2 && JSON.stringify(aliases) === JSON.stringify([aliasA, aliasB].sort()),
    `thực tế: ${aliases.join(", ")}`,
  );

  const base = await authzBase();
  const policies = await listAuthz(`${base}/policy`);
  const permissions = await fgPermissions();
  const roleIdByAlias = new Map();

  for (const org of orgs) {
    const roleName = `org-admin-${org.alias}`;
    const { status: roleStatus, data: role } = await api("GET", `/admin/realms/${REALM}/roles/${roleName}`);
    if (role?.id) roleIdByAlias.set(org.alias, role.id);
    check(`Organization ${org.alias}: có realm role ${roleName}`, roleStatus === 200 && role?.name === roleName);

    const policy = policies.find((p) => p.name === roleName && p.type === "role");
    let policyRolesOk = false;
    if (policy) {
      const { data: detail } = await api("GET", `${base}/policy/role/${policy.id}`);
      policyRolesOk = (detail?.roles || []).some((r) => r.id === role?.id && r.required === true);
    }
    check(
      `Organization ${org.alias}: role policy ${roleName} trỏ đúng realm role của nó`,
      Boolean(policy) && policyRolesOk,
    );

    const orgPerm = permissions.find((p) => p.name === `${roleName}-organizations`);
    const orgDetail = orgPerm ? await permissionDetail(orgPerm) : null;
    check(
      `Organization ${org.alias}: permission ${roleName}-organizations có resourceType Organizations`,
      orgDetail?.resourceType === "Organizations",
      `thực tế: ${orgDetail?.resourceType}`,
    );
    check(
      `Organization ${org.alias}: permission ${roleName}-organizations khoá đúng id của Organization đó`,
      JSON.stringify(orgDetail?.resourceNames || []) === JSON.stringify([org.id]),
      `resources: ${(orgDetail?.resourceNames || []).join(", ")}`,
    );
    check(
      `Organization ${org.alias}: permission ${roleName}-organizations có đủ scope view và manage`,
      ["view", "manage"].every((s) => (orgDetail?.scopeNames || []).includes(s)),
      `scopes: ${(orgDetail?.scopeNames || []).join(", ")}`,
    );
    check(
      `Organization ${org.alias}: permission ${roleName}-organizations gắn với role policy ${roleName}`,
      (orgDetail?.policyNames || []).includes(roleName),
    );

  }

  const usersPolicy = policies.find((p) => p.name === "org-admin-users-policy" && p.type === "role");
  let usersPolicyRoles = [];
  if (usersPolicy) {
    const { data: detail } = await api("GET", `${base}/policy/role/${usersPolicy.id}`);
    usersPolicyRoles = detail?.roles || [];
  }
  const expectedRoleIds = orgs.map((o) => roleIdByAlias.get(o.alias)).filter(Boolean).sort();
  const actualRoleIds = usersPolicyRoles.map((r) => r.id).sort();
  check(
    "Role policy org-admin-users-policy chứa đúng 2 role id của org-admin-trung-tam-a và " +
      "org-admin-trung-tam-b, tất cả required: false",
    Boolean(usersPolicy) &&
      JSON.stringify(actualRoleIds) === JSON.stringify(expectedRoleIds) &&
      expectedRoleIds.length === 2 &&
      usersPolicyRoles.every((r) => r.required === false),
    `roles: ${usersPolicyRoles.map((r) => `${r.id}:${r.required}`).join(", ")}`,
  );

  const usersPerms = permissions.filter((p) => p.name === "org-admin-users");
  check(
    "Tồn tại đúng một permission org-admin-users dùng chung toàn realm",
    usersPerms.length === 1,
    `số lượng: ${usersPerms.length}`,
  );
  const usersDetail = usersPerms[0] ? await permissionDetail(usersPerms[0]) : null;
  check(
    "Permission org-admin-users có resourceType Users và scope view, manage",
    usersDetail?.resourceType === "Users" &&
      ["view", "manage"].every((s) => (usersDetail?.scopeNames || []).includes(s)),
    `resourceType: ${usersDetail?.resourceType}, scopes: ${(usersDetail?.scopeNames || []).join(", ")}`,
  );
  check(
    "Permission org-admin-users gắn đúng policy org-admin-users-policy",
    Boolean(usersPolicy) &&
      JSON.stringify(usersDetail?.policyIds || []) === JSON.stringify([usersPolicy.id]),
    `policies: ${(usersDetail?.policyNames || []).join(", ")}`,
  );
  // Permission Users không gắn resource cụ thể nào: Keycloak tự gắn
  // resource mặc định mang tên đúng loại resource ("Users").
  check(
    "Permission org-admin-users không khoá theo user cụ thể (chỉ resource mặc định Users)",
    JSON.stringify(usersDetail?.resourceNames || []) === JSON.stringify(["Users"]),
    `resources: ${(usersDetail?.resourceNames || []).join(", ")}`,
  );
  check(
    "Không tồn tại permission org-admin-trung-tam-a-users hay org-admin-trung-tam-b-users theo từng trung tâm",
    !permissions.some((p) => p.name === `org-admin-${aliasA}-users` || p.name === `org-admin-${aliasB}-users`),
    `permissions: ${permissions.map((p) => p.name).join(", ")}`,
  );
  return new Map(orgs.map((o) => [o.alias, o]));
}

const users = {};

async function phaseFixture(orgByAlias) {
  section("Mục 4 — fixture user và membership theo Organization");
  users.memberA = await createUser("member-a");
  users.memberB = await createUser("member-b");
  users.adminA = await createUser("admin-a");
  check("Tạo được 3 user fixture member-a, member-b, admin-a (có đủ firstName/lastName)", Boolean(users.memberA?.id && users.memberB?.id && users.adminA?.id));

  const orgA = orgByAlias.get(aliasA);
  const orgB = orgByAlias.get(aliasB);
  const adds = [
    await addMember(orgA.id, users.memberA.id),
    await addMember(orgA.id, users.adminA.id),
    await addMember(orgB.id, users.memberB.id),
  ];
  check(
    "Thêm member-a và admin-a vào Organization A, member-b vào Organization B đều trả 201",
    adds.every((r) => r.status === 201),
    `mã trạng thái: ${adds.map((r) => r.status).join(", ")}`,
  );
  const membersA = await orgMembers(orgA.id);
  const membersB = await orgMembers(orgB.id);
  check(
    "Danh sách member của Organization A chứa member-a và admin-a, B chứa member-b",
    membersA.some((m) => m.username === "member-a") &&
      membersA.some((m) => m.username === "admin-a") &&
      membersB.some((m) => m.username === "member-b"),
  );

  await assignRealmRole(users.adminA.id, `org-admin-${aliasA}`);
  const { data: mappings } = await api(
    "GET",
    `/admin/realms/${REALM}/users/${users.adminA.id}/role-mappings/realm`,
  );
  check(
    `admin-a được gán realm role org-admin-${aliasA}`,
    (mappings || []).some((r) => r.name === `org-admin-${aliasA}`),
  );

  // Docs FGAP của Keycloak: admin được uỷ quyền vẫn cần các vai
  // query-* của client realm-management để truy vấn resource qua
  // Admin API; các vai này chỉ mở khả năng truy vấn, không cấp quyền
  // xem/sửa — ranh giới xem/sửa do permission FGAP quyết định.
  const assigned = await assignClientRoles(users.adminA.id, "realm-management", [
    "query-users",
    "query-organizations",
  ]);
  check("admin-a được gán 2 vai truy vấn query-users và query-organizations của realm-management", assigned === 2);
}

async function phaseOrganizationClaim() {
  section("Mục 5 — claim organization trong token của thành viên");
  // lms-web tắt direct access grants theo mẫu; bật tạm trong fixture
  // này để xin token bằng password grant trên môi trường kiểm chứng —
  // đây là thao tác fixture của môi trường verify, không phải cấu
  // hình cấp phát.
  const webId = await clientInternalId("lms-web");
  const { data: web } = await api("GET", `/admin/realms/${REALM}/clients/${webId}`);
  if (!web.directAccessGrantsEnabled) {
    const put = await api("PUT", `/admin/realms/${REALM}/clients/${webId}`, {
      ...web,
      directAccessGrantsEnabled: true,
    });
    if (put.status !== 204) throw new Error(`Bật tạm direct access grants cho lms-web: HTTP ${put.status}`);
    console.log("  --   Fixture verify: đã bật tạm direct access grants trên lms-web để đăng nhập bằng password grant");
  }
  const secret = await clientSecretValue("lms-web");
  let res = await passwordGrant(REALM, { clientId: "lms-web", clientSecret: secret, username: "member-a" });
  if (res.status === 200 && !("organization" in decodeJwtPayload(res.data.access_token))) {
    // Scope organization là scope mặc định của lms-web nên claim phải
    // tự có mặt; chỉ khi bản Keycloak không tự đưa vào mới xin lại
    // với scope tường minh để khẳng định cơ chế mapper.
    console.log("  --   Token đầu chưa mang claim organization — xin lại với scope=organization tường minh");
    res = await passwordGrant(REALM, {
      clientId: "lms-web",
      clientSecret: secret,
      username: "member-a",
      scope: "organization",
    });
  }
  check("member-a đăng nhập bằng password grant qua lms-web thành công", res.status === 200, `HTTP ${res.status}`);
  const claim = res.status === 200 ? decodeJwtPayload(res.data.access_token).organization : undefined;
  const claimText = JSON.stringify(claim || {});
  check(
    `Access token của member-a chứa claim organization gồm ${aliasA}`,
    claim !== undefined && claimText.includes(`"${aliasA}"`),
    `claim: ${claimText}`,
  );
  check(
    `Access token của member-a không chứa ${aliasB} trong claim organization`,
    claim !== undefined && !claimText.includes(`"${aliasB}"`),
    `claim: ${claimText}`,
  );
}

async function phaseFgapMatrix(orgByAlias) {
  section("Mục 6 — ma trận FGAP của admin trung tâm");
  const orgA = orgByAlias.get(aliasA);
  const orgB = orgByAlias.get(aliasB);
  const login = await passwordGrant(REALM, { clientId: "admin-cli", username: "admin-a" });
  if (login.status !== 200) throw new Error(`admin-a lấy token qua admin-cli thất bại: HTTP ${login.status}`);
  const token = login.data.access_token;
  const asAdmin = (method, path, opts) => apiAs(token, method, path, opts);

  const getA = await asAdmin("GET", `/admin/realms/${REALM}/organizations/${orgA.id}`);
  check("FGAP: admin-a GET Organization của mình trả 200", getA.status === 200, `HTTP ${getA.status}`);
  const getB = await asAdmin("GET", `/admin/realms/${REALM}/organizations/${orgB.id}`);
  check("FGAP: admin-a GET Organization khác trả 403", getB.status === 403, `HTTP ${getB.status}`);

  const putA = await asAdmin("PUT", `/admin/realms/${REALM}/organizations/${orgA.id}`, {
    json: { ...getA.data, description: "Cập nhật bởi admin của trung tâm trong kiểm chứng FGAP" },
  });
  check("FGAP: admin-a PUT cập nhật Organization của mình trả 204", putA.status === 204, `HTTP ${putA.status}`);
  const { data: orgBFull } = await api("GET", `/admin/realms/${REALM}/organizations/${orgB.id}`);
  const putB = await asAdmin("PUT", `/admin/realms/${REALM}/organizations/${orgB.id}`, {
    json: { ...orgBFull, description: "Cập nhật trái phép trong kiểm chứng FGAP" },
  });
  check("FGAP: admin-a PUT cập nhật Organization khác trả 403", putB.status === 403, `HTTP ${putB.status}`);

  const membersA = await asAdmin("GET", `/admin/realms/${REALM}/organizations/${orgA.id}/members?first=0&max=100`);
  check("FGAP: admin-a GET members của Organization mình trả 200", membersA.status === 200, `HTTP ${membersA.status}`);
  check(
    "FGAP: danh sách members admin-a thấy chứa member-a",
    Array.isArray(membersA.data) && membersA.data.some((m) => m.username === "member-a"),
  );
  const membersB = await asAdmin("GET", `/admin/realms/${REALM}/organizations/${orgB.id}/members?first=0&max=100`);
  check("FGAP: admin-a GET members của Organization khác trả 403", membersB.status === 403, `HTTP ${membersB.status}`);

  const memberC = await createUser("member-c");
  const addA = await asAdmin("POST", `/admin/realms/${REALM}/organizations/${orgA.id}/members`, {
    raw: JSON.stringify(memberC.id),
  });
  check("FGAP: admin-a thêm member-c vào Organization của mình trả 201", addA.status === 201, `HTTP ${addA.status}`);
  const addB = await asAdmin("POST", `/admin/realms/${REALM}/organizations/${orgB.id}/members`, {
    raw: JSON.stringify(memberC.id),
  });
  check("FGAP: admin-a thêm member-c vào Organization khác trả 403", addB.status === 403, `HTTP ${addB.status}`);
  const removeA = await asAdmin("DELETE", `/admin/realms/${REALM}/organizations/${orgA.id}/members/${memberC.id}`);
  check("FGAP: admin-a xoá member-c khỏi Organization của mình trả 204", removeA.status === 204, `HTTP ${removeA.status}`);
}

async function phaseMasterDefaults() {
  section("Mục 7 — realm master giữ mặc định của Keycloak");
  const { data: master } = await api("GET", "/admin/realms/master");
  check(
    "Realm master dùng browserFlow dựng sẵn browser",
    master?.browserFlow === "browser",
    `browserFlow: ${master?.browserFlow}`,
  );
  const { data: flows } = await api("GET", "/admin/realms/master/authentication/flows");
  check(
    "Realm master không tồn tại flow lms-master-browser",
    !(flows || []).some((f) => f.alias === "lms-master-browser"),
  );
  check(
    "Thuộc tính của realm master không có khoá lms.master.mfa-session-migration",
    !master?.attributes || !("lms.master.mfa-session-migration" in master.attributes),
  );
  const { data: admins } = await api(
    "GET",
    `/admin/realms/master/users?username=${encodeURIComponent(adminUser)}&exact=true`,
  );
  check(
    `User quản trị ${adminUser} ở realm master không bị gắn required action CONFIGURE_TOTP`,
    Boolean(admins?.[0]) && !(admins[0].requiredActions || []).includes("CONFIGURE_TOTP"),
    `requiredActions: ${(admins?.[0]?.requiredActions || []).join(", ")}`,
  );
}

async function phaseIdempotent() {
  section("Mục 8 — chạy lại bộ cấp phát (idempotent)");
  const orgCountBefore = (await listOrganizations()).length;
  const permCountBefore = (await fgPermissions()).length;
  const secretBefore = await clientSecretValue("lms-web");
  const res = runProvision(configPath);
  process.stdout.write(res.output);
  check("Cấp phát chạy lần hai thoát 0", res.status === 0, `mã thoát: ${res.status}`);
  const orgCountAfter = (await listOrganizations()).length;
  check(
    "Chạy lại không tạo thêm Organization",
    orgCountAfter === orgCountBefore && orgCountAfter === 2,
    `trước ${orgCountBefore}, sau ${orgCountAfter}`,
  );
  const permCountAfter = (await fgPermissions()).length;
  // Tổng ở lượt 1 là 3 permission (2 permission -organizations theo trung tâm và 1 permission org-admin-users dùng
  // chung); so bằng số đếm động của lượt 1 thay vì hằng số để khẳng định chạy lại không sinh thêm permission.
  check(
    "Chạy lại không tạo thêm permission FGAP",
    permCountAfter === permCountBefore,
    `trước ${permCountBefore}, sau ${permCountAfter}`,
  );
  const secretAfter = await clientSecretValue("lms-web");
  check(
    "Chạy lại không đổi bí mật của lms-web",
    Boolean(secretBefore) && secretAfter === secretBefore,
  );
}

async function main() {
  phaseRejection();
  phaseProvisioning();
  await phaseRealmStructure();
  const orgByAlias = await phaseOrganizationsAndFgap();
  await phaseFixture(orgByAlias);
  await phaseOrganizationClaim();
  await phaseFgapMatrix(orgByAlias);
  await phaseMasterDefaults();
  await phaseIdempotent();
  console.log(`\nTổng kết: ${passed} đạt, ${failed} không đạt.`);
  if (failed > 0) process.exit(1);
}

main().catch((err) => {
  console.error(`Bộ kiểm chứng dừng vì lỗi: ${err.message}`);
  process.exit(1);
});
