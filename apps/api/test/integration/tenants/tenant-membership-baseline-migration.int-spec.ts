import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createCentreKysely, type CentreDb } from '@lms/db';
import { Client, Pool } from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ensureActiveTenantMembership } from '@lms/enrol';

const DATABASE_ADMIN_URL = new URL(
  process.env.DATABASE_URL ?? 'postgres://postgres:postgres@localhost:5432/lms_test_api',
);
DATABASE_ADMIN_URL.pathname = '/postgres';
const ADMIN_URL = process.env.LMS_DB_TEST_ADMIN_URL ?? DATABASE_ADMIN_URL.toString();
const SCRATCH_DB = 'lms_test_enrol_role_bridge_pr25';

function databaseUrl(database: string): string {
  const url = new URL(ADMIN_URL);
  url.pathname = `/${database}`;
  return url.toString();
}

async function clientFor(url: string): Promise<Client> {
  const client = new Client({ connectionString: url });
  await client.connect();
  return client;
}

describe('vai membership qua cửa sổ schema baseline → migration issue #5', () => {
  let db: CentreDb | undefined;
  let teacherUserId = '';
  let legacyUserId = '';
  let tenantId = '';

  beforeAll(async () => {
    const admin = await clientFor(ADMIN_URL);
    try {
      await admin.query(`DROP DATABASE IF EXISTS "${SCRATCH_DB}" WITH (FORCE)`);
      await admin.query(`CREATE DATABASE "${SCRATCH_DB}"`);
    } finally {
      await admin.end();
    }

    const scratchUrl = databaseUrl(SCRATCH_DB);
    const scratch = await clientFor(scratchUrl);
    try {
      const migrations = join(process.cwd(), '..', '..', 'packages', 'db', 'migrations', 'centre');
      await scratch.query(readFileSync(join(migrations, '20261025163710_baseline', 'migration.sql'), 'utf8'));
      const tenant = await scratch.query<{ id: string }>(
        `INSERT INTO tenants (slug, name, kind, status)
         VALUES ('role-bridge', 'Role bridge', 'school', 'active') RETURNING id`,
      );
      tenantId = tenant.rows[0]!.id;
      const teacher = await scratch.query<{ id: string }>(
        `INSERT INTO users (username, email, first_name, last_name)
         VALUES ('role-bridge.teacher', 'role-bridge.teacher@example.test', 'Teacher', 'Bridge') RETURNING id`,
      );
      teacherUserId = teacher.rows[0]!.id;
      const legacy = await scratch.query<{ id: string }>(
        `INSERT INTO users (username, email, first_name, last_name)
         VALUES ('role-bridge.legacy', 'role-bridge.legacy@example.test', 'Legacy', 'Bridge') RETURNING id`,
      );
      legacyUserId = legacy.rows[0]!.id;
      await scratch.query(
        `INSERT INTO tenant_memberships (tenant_id, user_id, status)
         VALUES ($1, $2, 'active')`,
        [tenantId, legacyUserId],
      );
    } finally {
      await scratch.end();
    }

    const pool = new Pool({ connectionString: scratchUrl });
    db = createCentreKysely(pool);
  }, 60_000);

  afterAll(async () => {
    await db?.destroy().catch(() => undefined);
    const admin = await clientFor(ADMIN_URL);
    try {
      await admin.query(`DROP DATABASE IF EXISTS "${SCRATCH_DB}" WITH (FORCE)`);
    } finally {
      await admin.end();
    }
  }, 60_000);

  it('vai teacher được staging trên baseline, qua migration vẫn là teacher; vai explicit sửa được member bị backfill cũ', async () => {
    const beforeMigration = await ensureActiveTenantMembership(
      db!,
      tenantId,
      teacherUserId,
      'teacher',
    );
    expect(beforeMigration.status).toBe('active');
    expect(beforeMigration.primaryRole).toBe('teacher');

    const staged = await db!
      .selectFrom('tenant_config')
      .select('value')
      .where('tenant_id', '=', tenantId)
      .where('key', '=', '__migration_tenant_membership_primary_role__:' + teacherUserId)
      .executeTakeFirst();
    expect(staged?.value).toBe('teacher');

    const migrationPath = join(
      process.cwd(),
      '..',
      '..',
      'packages',
      'db',
      'migrations',
      'centre',
      '20261103050557_identity_links_and_primary_role',
      'migration.sql',
    );
    const migration = await clientFor(databaseUrl(SCRATCH_DB));
    try {
      await migration.query(readFileSync(migrationPath, 'utf8'));
    } finally {
      await migration.end();
    }

    const rolesAfterMigration = await db!
      .selectFrom('tenant_memberships')
      .select(['user_id', 'primary_role'])
      .where('tenant_id', '=', tenantId)
      .execute();
    const roleByUser = new Map(rolesAfterMigration.map((row) => [row.user_id, row.primary_role]));
    expect(roleByUser.get(teacherUserId)).toBe('teacher');
    expect(roleByUser.get(legacyUserId)).toBe('member');

    const pending = await db!
      .selectFrom('tenant_config')
      .select('key')
      .where('tenant_id', '=', tenantId)
      .where('key', 'like', '__migration_tenant_membership_primary_role__:%')
      .execute();
    expect(pending).toHaveLength(0);

    const corrected = await ensureActiveTenantMembership(
      db!,
      tenantId,
      legacyUserId,
      'teacher',
    );
    expect(corrected.primaryRole).toBe('teacher');
    const reread = await db!
      .selectFrom('tenant_memberships')
      .select('primary_role')
      .where('tenant_id', '=', tenantId)
      .where('user_id', '=', legacyUserId)
      .executeTakeFirstOrThrow();
    expect(reread.primary_role).toBe('teacher');
  });
});
