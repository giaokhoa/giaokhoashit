import { ensureTenantContext, markContextDirty } from './contexts';
import {
  BASELINE_TENANT_MEMBERSHIP_COLUMNS,
  TENANT_MEMBERSHIP_COLUMNS,
  type EnrolDatabase,
  type TenantMembershipRow,
  type TenantPrimaryRole,
} from './types';

/**
 * Lỗi có phải "cột chưa tồn tại" của Postgres (mã 42703) hay không —
 * dấu hiệu kho đang ở schema baseline, trước migration cộng
 * `primary_role`/`version` của issue #5.
 */
function isMissingColumnError(err: unknown): boolean {
  const code = (err as { code?: unknown } | null)?.code;
  if (code === '42703') return true;
  const message = err instanceof Error ? err.message : '';
  return /column "(primary_role|version)" does not exist/.test(message);
}

/** Khoá tạm trong tenant_config để mang vai dự định qua cửa sổ baseline → migration. */
const PENDING_PRIMARY_ROLE_PREFIX = '__migration_tenant_membership_primary_role__:';

/**
 * Ghi vai chính tạm vào bảng đã tồn tại trên schema baseline.
 * Migration issue #5 đọc khoá này trước backfill mặc định member
 * rồi xoá nó; RLS của tenant_config giữ dữ liệu trong tenant sở hữu.
 *
 * @param db - Tay cầm Kysely của host.
 * @param tenantId - Tenant sở hữu membership.
 * @param userId - Người dùng của membership.
 * @param primaryRole - Vai dự định cần bảo toàn.
 */
async function stageBaselinePrimaryRole(
  db: EnrolDatabase,
  tenantId: string,
  userId: string,
  primaryRole: TenantPrimaryRole,
): Promise<void> {
  await db
    .insertInto('tenant_config')
    .values({
      tenant_id: tenantId,
      key: PENDING_PRIMARY_ROLE_PREFIX + userId,
      value: JSON.stringify(primaryRole),
    })
    .onConflict((oc) => oc.columns(['tenant_id', 'key']).doUpdateSet({
      value: JSON.stringify(primaryRole),
      updated_at: new Date(),
    }))
    .execute();
}

/** Chuẩn hoá một dòng membership đọc từ kho baseline thành hình dạng đầy đủ. */
function normalizeMembershipRow(row: Record<string, unknown>): TenantMembershipRow {
  return {
    id: row.id as string,
    tenantId: row.tenantId as string,
    userId: row.userId as string,
    status: row.status as string,
    primaryRole: (row.primaryRole as string | null | undefined) ?? null,
    version: (row.version as number | undefined) ?? 1,
    joinedAt: row.joinedAt as Date,
    endedAt: (row.endedAt as Date | null | undefined) ?? null,
  };
}

/**
 * Đảm bảo membership active của một người dùng trong một tenant ở
 * tầng dữ liệu (dùng chung cho funnel ghi danh và bề mặt membership
 * của host): ghi danh vào khoá của tenant nào thì thuộc tenant đó —
 * membership active được bảo đảm ngay tại funnel ghi danh để cổng
 * membership của phép đánh giá quyền cho qua các vai ghi danh vừa cấp.
 *
 * Flow: đọc membership; nếu đã active và nơi gọi không truyền vai
 * tường minh (hoặc vai hiện tại đã đúng) thì trả nguyên dòng. Nếu
 * nơi gọi truyền vai tường minh khác vai hiện tại, cập nhật vai và
 * đánh dấu dirty context. Membership chưa active được kích hoạt,
 * giữ vai hiện tại khi nơi gọi không chỉ định vai mới; membership
 * chưa có thì chèn mới với vai mặc định member.
 *
 * Tương thích tiền di trú: khi schema baseline chưa có primary_role,
 * helper ghi vai dự định vào tenant_config bằng khoá tạm theo
 * (tenant_id, user_id), sau đó ghi membership theo schema baseline.
 * Migration issue #5 đọc khoá tạm này trước backfill member mặc định
 * và xoá nó. Vì vậy vai teacher/admin không bị biến thành member
 * trong cửa sổ deploy mã trước migration. Bảng tenant_config đã có
 * trên baseline và giữ RLS tenant_isolation như membership.
 *
 * Ngữ cảnh RLS: hàm này kế thừa ngữ cảnh của nơi gọi — các bề mặt
 * gọi nó (funnel ghi danh, tạo tenant/user, bootstrap seed) tự bọc
 * ngữ cảnh phù hợp ở tầng của chúng, như hành vi hiện hành.
 *
 * @param db - Tay cầm Kysely của host.
 * @param tenantId - Tenant đích.
 * @param userId - Người dùng cần thuộc tenant.
 * @param primaryRole - Vai chính tường minh nếu nơi gọi cần tạo mới,
 *   kích hoạt hoặc sửa vai hiện tại; vắng mặt thì mặc định member khi
 *   tạo/lấp dòng thiếu vai và giữ vai đã có.
 * @returns Dòng membership ở trạng thái active.
 */
export async function ensureActiveTenantMembership(
  db: EnrolDatabase,
  tenantId: string,
  userId: string,
  primaryRole?: TenantPrimaryRole,
): Promise<TenantMembershipRow> {
  let existing: TenantMembershipRow | undefined;
  try {
    existing = await db
      .selectFrom('tenant_memberships')
      .select(TENANT_MEMBERSHIP_COLUMNS)
      .where((eb) => eb.and([
        eb('tenant_memberships.tenant_id', '=', tenantId),
        eb('tenant_memberships.user_id', '=', userId),
      ]))
      .executeTakeFirst();
  } catch (err) {
    if (!isMissingColumnError(err)) throw err;
    const baseline = await db
      .selectFrom('tenant_memberships')
      .select(BASELINE_TENANT_MEMBERSHIP_COLUMNS)
      .where((eb) => eb.and([
        eb('tenant_memberships.tenant_id', '=', tenantId),
        eb('tenant_memberships.user_id', '=', userId),
      ]))
      .executeTakeFirst();
    existing = baseline ? normalizeMembershipRow(baseline as unknown as Record<string, unknown>) : undefined;
  }

  const requestedRole = primaryRole ?? 'member';
  if (
    existing?.status === 'active'
    && existing.primaryRole !== null
    && (primaryRole === undefined || existing.primaryRole === primaryRole)
  ) {
    return existing;
  }

  let row: TenantMembershipRow;
  if (existing) {
    const effectivePrimaryRole = primaryRole ?? (existing.primaryRole as TenantPrimaryRole | null) ?? 'member';
    const wasActive = existing.status === 'active';
    const roleChanged = existing.primaryRole !== effectivePrimaryRole;
    try {
      await db
        .updateTable('tenant_memberships')
        .set({ status: 'active', ended_at: null, primary_role: effectivePrimaryRole })
        .where('id', '=', existing.id)
        .execute();
    } catch (err) {
      if (!isMissingColumnError(err)) throw err;
      // Ghi vai vào bảng có sẵn trước khi cập nhật membership baseline;
      // migration sẽ chuyển giá trị này sang cột primary_role.
      await stageBaselinePrimaryRole(db, tenantId, userId, effectivePrimaryRole);
      await db
        .updateTable('tenant_memberships')
        .set({ status: 'active', ended_at: null })
        .where('id', '=', existing.id)
        .execute();
      row = { ...existing, status: 'active', endedAt: null, primaryRole: effectivePrimaryRole };
      if (!wasActive || roleChanged) {
        const tenantCtx = await ensureTenantContext(db, tenantId);
        await markContextDirty(db, tenantCtx.id);
      }
      return row;
    }
    row = { ...existing, status: 'active', endedAt: null, primaryRole: effectivePrimaryRole };
    if (!wasActive || roleChanged) {
      const tenantCtx = await ensureTenantContext(db, tenantId);
      await markContextDirty(db, tenantCtx.id);
    }
    return row;
  }

  try {
    row = await db
      .insertInto('tenant_memberships')
      .values({ tenant_id: tenantId, user_id: userId, status: 'active', primary_role: requestedRole })
      .returning(TENANT_MEMBERSHIP_COLUMNS)
      .executeTakeFirstOrThrow();
  } catch (err) {
    if (!isMissingColumnError(err)) throw err;
    // Ghi vai tạm trước membership: nếu không ghi được staging thì
    // không để lại một membership active sẽ bị backfill thành member.
    await stageBaselinePrimaryRole(db, tenantId, userId, requestedRole);
    const baseline = await db
      .insertInto('tenant_memberships')
      .values({ tenant_id: tenantId, user_id: userId, status: 'active' })
      .returning(BASELINE_TENANT_MEMBERSHIP_COLUMNS)
      .executeTakeFirstOrThrow();
    row = { ...normalizeMembershipRow(baseline as unknown as Record<string, unknown>), primaryRole: requestedRole };
  }

  const tenantCtx = await ensureTenantContext(db, tenantId);
  await markContextDirty(db, tenantCtx.id);
  return row;
}
