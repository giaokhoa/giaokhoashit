import { randomUUID } from 'node:crypto';
import { Client } from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { CentreDb } from '../client';
import { buildTestDatabase, dropTestDatabase, withDatabase } from '../test-support/build-test-database';
import { createTestKysely } from '../test-support/create-test-kysely';
import {
  runInTestPlatformContext,
  runInTestTenantContext,
  TestTenantPool,
} from '../test-support/tenant-context';
import { IdentityLinksRepository, isIdentityLinkConflict } from './identity-links';
import { TenantsRepository } from './tenants';

/**
 * Spec tích hợp của repository liên kết danh tính (issue #5).
 *
 * Database trung tâm dựng từ rỗng bằng chính bộ máy migration của
 * package (carrier baseline + migration native của issue này) trên
 * cụm PostgreSQL thật; các ca chạy qua repository production —
 * không mô phỏng handle — và qua pool gắn ngữ cảnh tenant theo
 * đúng hợp đồng GUC/RLS với một role NOSUPERUSER để RLS thật sự
 * có hiệu lực.
 *
 * Các bất biến của hợp đồng xác thực được chốt tại đây:
 * - Khoá liên kết là cặp (issuer, sub): cùng một `sub` ở hai issuer
 *   là hai liên kết khác nhau trỏ về hai user khác nhau; đổi email
 *   của user không đổi liên kết.
 * - Trùng (issuer, sub) và trùng (user, issuer) đều bị chặn ở
 *   database và được nhận diện qua `isIdentityLinkConflict`; lỗi
 *   Postgres khác (FK) không bị nhận diện nhầm.
 * - Tạo liên kết không tạo membership: principal của realm
 *   platform có liên kết mà không bị gán một tenant mặc định nào.
 * - RLS cách ly theo tenant: trong ngữ cảnh của một tenant, liên
 *   kết của tenant khác vô hình (kho chung vẫn tách bạch).
 * - Vai chính của membership: đường ghi qua repository luôn mang
 *   đúng một vai thuộc tập đóng; giá trị ngoài tập bị ràng buộc
 *   CHECK của database chặn.
 */

const adminUrl =
  process.env.LMS_DB_TEST_ADMIN_URL ??
  (process.env.DATABASE_URL ? withDatabase(process.env.DATABASE_URL, 'postgres') : undefined);

const TEST_DB = 'lms_db_issue5_identity';
const TEST_ROLE = 'lms_db_issue5_identity';
const TEST_ROLE_PASSWORD = 'lms_db_issue5_identity';

const ISSUER_A = 'https://keycloak.example.test/realms/trung-tam-a';
const ISSUER_B = 'https://keycloak.example.test/realms/trung-tam-b';
const ISSUER_PLATFORM = 'https://keycloak.example.test/realms/giaokhoa';
const SHARED_SUBJECT = 'f47ac10b-58cc-4372-a567-0e02b2c3d479';

let tenantPool: TestTenantPool;
let db: CentreDb;
let links: IdentityLinksRepository;
let tenantsRepo: TenantsRepository;

let tenantAId = '';
let tenantBId = '';
let platformTenantId = '';
let userAId = '';
let userBId = '';
let platformUserId = '';

/** Bắt lỗi của một thao tác ghi để khẳng định trên chính object lỗi (mã/constraint mà Postgres ném ra). */
async function captureError(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
  } catch (err) {
    return err;
  }
  throw new Error('Thao tác được kỳ vọng ném lỗi nhưng đã chạy thành công');
}

/** Gieo một user qua ngữ cảnh platform (bảng users không có RLS nhưng gieo tập trung một chỗ cho dễ đọc). */
async function makeUser(username: string): Promise<string> {
  return runInTestPlatformContext(async () => {
    const row = await db
      .insertInto('users')
      .values({ username, email: `${username}@example.test`, first_name: 'Người', last_name: 'Dùng' })
      .returning('id')
      .executeTakeFirstOrThrow();
    return row.id;
  });
}

beforeAll(async () => {
  if (!adminUrl) {
    throw new Error('Thiếu kết nối quản trị cho spec liên kết danh tính: đặt LMS_DB_TEST_ADMIN_URL hoặc DATABASE_URL.');
  }
  const adminStoreUrl = await buildTestDatabase({ adminUrl, database: TEST_DB, store: 'centre' });

  const admin = new Client({ connectionString: adminStoreUrl });
  await admin.connect();
  try {
    await admin.query(
      `DO $$ BEGIN
         IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${TEST_ROLE}') THEN
           CREATE ROLE "${TEST_ROLE}" LOGIN NOSUPERUSER PASSWORD '${TEST_ROLE_PASSWORD}';
         END IF;
       END $$;`,
    );
    await admin.query(`ALTER ROLE "${TEST_ROLE}" WITH LOGIN NOSUPERUSER PASSWORD '${TEST_ROLE_PASSWORD}'`);
    await admin.query(`GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO "${TEST_ROLE}"`);
    await admin.query(`GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO "${TEST_ROLE}"`);
  } finally {
    await admin.end();
  }

  const roleUrl = new URL(adminStoreUrl);
  roleUrl.username = TEST_ROLE;
  roleUrl.password = TEST_ROLE_PASSWORD;

  tenantPool = new TestTenantPool({ connectionString: roleUrl.toString() });
  db = createTestKysely(tenantPool).centre;
  links = new IdentityLinksRepository(db);
  tenantsRepo = new TenantsRepository(db);

  await runInTestPlatformContext(async () => {
    const tenantA = await db
      .insertInto('tenants')
      .values({ slug: 'issue5-trung-tam-a', name: 'Trung tâm A', kind: 'center', status: 'active' })
      .returning('id')
      .executeTakeFirstOrThrow();
    tenantAId = tenantA.id;
    const tenantB = await db
      .insertInto('tenants')
      .values({ slug: 'issue5-trung-tam-b', name: 'Trung tâm B', kind: 'center', status: 'active' })
      .returning('id')
      .executeTakeFirstOrThrow();
    tenantBId = tenantB.id;
    const platformTenant = await db
      .insertInto('tenants')
      .values({ slug: 'issue5-trang-chinh', name: 'Trang chính', kind: 'platform', status: 'active' })
      .returning('id')
      .executeTakeFirstOrThrow();
    platformTenantId = platformTenant.id;
  });

  userAId = await makeUser('issue5-user-a');
  userBId = await makeUser('issue5-user-b');
  platformUserId = await makeUser('issue5-platform-admin');
}, 180_000);

afterAll(async () => {
  if (tenantPool) await tenantPool.end().catch(() => undefined);
  if (adminUrl) await dropTestDatabase(adminUrl, TEST_DB).catch(() => undefined);
});

describe('liên kết danh tính theo cặp (issuer, sub)', () => {
  it('cùng một sub ở hai issuer là hai liên kết khác nhau, mỗi liên kết trỏ về đúng user của realm đó', async () => {
    const linkA = await runInTestTenantContext(tenantAId, () =>
      links.createLink({ tenantId: tenantAId, userId: userAId, issuer: ISSUER_A, subject: SHARED_SUBJECT }));
    const linkB = await runInTestTenantContext(tenantBId, () =>
      links.createLink({ tenantId: tenantBId, userId: userBId, issuer: ISSUER_B, subject: SHARED_SUBJECT }));
    expect(linkA.id).not.toBe(linkB.id);
    expect(linkA.status).toBe('active');
    expect(linkA.version).toBe(1);

    const resolvedA = await runInTestTenantContext(tenantAId, () => links.findByIssuerAndSubject(ISSUER_A, SHARED_SUBJECT));
    expect(resolvedA?.userId).toBe(userAId);
    const resolvedB = await runInTestTenantContext(tenantBId, () => links.findByIssuerAndSubject(ISSUER_B, SHARED_SUBJECT));
    expect(resolvedB?.userId).toBe(userBId);
    // Tra chéo realm trong cùng một kho: sub của realm B không tồn
    // tại dưới issuer của realm A — cặp khoá gồm cả hai thành phần.
    const cross = await runInTestPlatformContext(() => links.findByIssuerAndSubject(ISSUER_A, 'sub-chi-co-o-realm-b'));
    expect(cross).toBeUndefined();
  });

  it('đổi email của user không đổi liên kết — email không phải khoá liên kết', async () => {
    const before = await runInTestTenantContext(tenantAId, () => links.findByIssuerAndSubject(ISSUER_A, SHARED_SUBJECT));
    expect(before).toBeDefined();
    await runInTestPlatformContext(async () => {
      await db
        .updateTable('users')
        .set({ email: 'email-moi-hoan-toan@example.test' })
        .where('id', '=', userAId)
        .execute();
    });
    const after = await runInTestTenantContext(tenantAId, () => links.findByIssuerAndSubject(ISSUER_A, SHARED_SUBJECT));
    expect(after?.id).toBe(before!.id);
    expect(after?.userId).toBe(userAId);
  });

  it('trùng cặp (issuer, sub) bị chặn và được nhận diện là xung đột liên kết', async () => {
    const err = await captureError(runInTestTenantContext(tenantAId, () =>
      links.createLink({ tenantId: tenantAId, userId: userBId, issuer: ISSUER_A, subject: SHARED_SUBJECT })));
    expect(isIdentityLinkConflict(err)).toBe(true);
  });

  it('một user không có hai liên kết cho cùng một issuer dù subject khác nhau', async () => {
    const err = await captureError(runInTestTenantContext(tenantAId, () =>
      links.createLink({ tenantId: tenantAId, userId: userAId, issuer: ISSUER_A, subject: 'mot-sub-khac' })));
    expect(isIdentityLinkConflict(err)).toBe(true);
  });

  it('lỗi Postgres khác (vi phạm khoá ngoại user) không bị nhận diện nhầm là xung đột liên kết', async () => {
    const err = await captureError(runInTestPlatformContext(() =>
      links.createLink({
        tenantId: tenantAId,
        userId: '01923456-789a-7def-8abc-0123456789ab',
        issuer: ISSUER_A,
        subject: 'sub-cua-user-khong-ton-tai',
      })));
    expect(isIdentityLinkConflict(err)).toBe(false);
  });

  it('liên kết trỏ tới tenant không tồn tại bị khoá ngoại tenant chặn, không nhận diện nhầm là xung đột liên kết', async () => {
    const missingTenantId = randomUUID();
    const err = await captureError(runInTestTenantContext(missingTenantId, () =>
      links.createLink({
        tenantId: missingTenantId,
        userId: userAId,
        issuer: 'https://keycloak.example.test/realms/khong-ton-tai',
        subject: 'sub-cua-tenant-khong-ton-tai',
      })));
    expect((err as { code?: string }).code).toBe('23503');
    expect(isIdentityLinkConflict(err)).toBe(false);
  });

  it('tạo liên kết cho principal platform không sinh membership và không gán tenant mặc định nào', async () => {
    const link = await runInTestPlatformContext(() =>
      links.createLink({ tenantId: platformTenantId, userId: platformUserId, issuer: ISSUER_PLATFORM, subject: 'sub-platform-admin' }));
    expect(link.userId).toBe(platformUserId);
    const memberships = await runInTestPlatformContext(() => tenantsRepo.listMembershipsForUser(platformUserId));
    expect(memberships).toEqual([]);
  });

  it('RLS: liên kết của tenant khác vô hình trong ngữ cảnh của một tenant, hiện rõ trong ngữ cảnh platform', async () => {
    const seenFromA = await runInTestTenantContext(tenantAId, () => links.listForUser(userBId));
    expect(seenFromA).toEqual([]);
    const resolvedFromA = await runInTestTenantContext(tenantAId, () => links.findByIssuerAndSubject(ISSUER_B, SHARED_SUBJECT));
    expect(resolvedFromA).toBeUndefined();
    const seenFromB = await runInTestTenantContext(tenantBId, () => links.listForUser(userBId));
    expect(seenFromB.map((l) => l.id)).toHaveLength(1);
    const seenFromPlatform = await runInTestPlatformContext(() => links.listForUser(userBId));
    expect(seenFromPlatform.map((l) => l.id)).toEqual(seenFromB.map((l) => l.id));
  });

  it('thu hồi liên kết: trạng thái về revoked và version tăng một bậc', async () => {
    const created = await runInTestTenantContext(tenantAId, () =>
      links.createLink({ tenantId: tenantAId, userId: userAId, issuer: ISSUER_PLATFORM, subject: 'sub-se-bi-thu-hoi' }));
    // userA có liên kết ở issuer thứ hai (platform) — liên kết
    // theo từng issuer độc lập, thu hồi một liên kết không chạm
    // liên kết còn lại.
    const revoked = await runInTestTenantContext(tenantAId, () => links.revokeLink(created.id));
    expect(revoked?.status).toBe('revoked');
    expect(revoked?.version).toBe(created.version + 1);
    const stillActive = await runInTestTenantContext(tenantAId, () => links.findByIssuerAndSubject(ISSUER_A, SHARED_SUBJECT));
    expect(stillActive?.status).toBe('active');
    const missing = await runInTestTenantContext(tenantAId, () =>
      links.revokeLink('01923456-789a-7def-8abc-0123456789ab'));
    expect(missing).toBeUndefined();
  });
});

describe('vai chính của membership ở tầng dữ liệu', () => {
  it('đường ghi qua repository luôn mang đúng một vai chính thuộc tập đóng', async () => {
    const membership = await runInTestPlatformContext(() =>
      tenantsRepo.insertMembership({ tenantId: tenantAId, userId: userAId, status: 'active', primaryRole: 'teacher' }));
    expect(membership.primaryRole).toBe('teacher');
    expect(membership.version).toBe(1);
    const reread = await runInTestPlatformContext(() => tenantsRepo.getMembership(tenantAId, userAId));
    expect(reread?.primaryRole).toBe('teacher');
  });

  it('giá trị vai chính ngoài tập đóng bị ràng buộc CHECK của database chặn', async () => {
    const err = await captureError(runInTestPlatformContext(async () => {
      await db
        .insertInto('tenant_memberships')
        .values({ tenant_id: tenantBId, user_id: userBId, status: 'active', primary_role: 'superadmin' })
        .execute();
    }));
    expect((err as { code?: string }).code).toBe('23514');
  });

  it('membership active thiếu vai chính bị ràng buộc CHECK chặn, còn membership chưa active thiếu vai vẫn ghi được để chờ lấp ở lần kích hoạt lại', async () => {
    const activeErr = await captureError(runInTestPlatformContext(async () => {
      await db
        .insertInto('tenant_memberships')
        .values({ tenant_id: tenantBId, user_id: userBId, status: 'active', primary_role: null })
        .execute();
    }));
    expect((activeErr as { code?: string }).code).toBe('23514');
    const suspended = await runInTestPlatformContext(async () => {
      const rows = await db
        .insertInto('tenant_memberships')
        .values({ tenant_id: tenantAId, user_id: platformUserId, status: 'suspended', primary_role: null })
        .returning(['id'])
        .execute();
      return rows[0]!;
    });
    expect(suspended.id).toBeDefined();
    const reread = await runInTestPlatformContext(() => tenantsRepo.getMembership(tenantAId, platformUserId));
    expect(reread?.status).toBe('suspended');
    expect(reread?.primaryRole).toBeNull();
    await runInTestPlatformContext(async () => {
      await db.deleteFrom('tenant_memberships').where('id', '=', suspended.id).execute();
    });
  });
});
