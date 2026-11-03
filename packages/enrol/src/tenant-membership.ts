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
 * Đảm bảo membership `active` của một người dùng trong một tenant ở
 * tầng dữ liệu (dùng chung cho funnel ghi danh và bề mặt membership
 * của host): ghi danh vào khoá của tenant nào thì thuộc tenant đó —
 * membership active được bảo đảm ngay tại funnel ghi danh để cổng
 * membership của phép đánh giá quyền cho qua các vai ghi danh vừa
 * cấp.
 *
 * Flow: membership đã `active` và đã có vai chính thì trả nguyên
 * dòng hiện hữu (no-op); membership đã `active` nhưng thiếu vai
 * chính (dòng lịch sử trước migration cộng cột, hoặc dòng tạo trước
 * khi ràng buộc cấm active thiếu vai có hiệu lực) thì được LẤP vai
 * truyền vào rồi mới trả — không được trả nguyên một membership
 * active thiếu vai. Membership tồn tại ở trạng thái khác thì kích
 * hoạt lại (status về `active`, xoá mốc kết thúc, lấp vai nếu thiếu);
 * chưa có thì chèn mới ở trạng thái `active`. Cả ba đường ghi đều
 * kết thúc bằng việc đảm bảo context tenant tồn tại và đánh dấu
 * dirty context đó (membership là dữ kiện cổng của phép đánh giá
 * quyền trong cây của tenant).
 *
 * Tương thích tiền di trú (vòng sửa PR #25): ứng dụng phải boot và
 * phục vụ được trên kho đang ở schema baseline (fleet áp migration
 * sau khi mã mới đã chạy — xem spec tiền di trú issue #587). Vì vậy
 * mọi lượt đọc/ghi ở đây thử danh sách cột đầy đủ trước, và chỉ khi
 * database báo thiếu cột mới rơi về danh sách baseline (đọc/ghi
 * không kèm `primary_role`/`version`); vai chính khi đó chưa có nơi
 * chứa và được lấp ở lần migration/kích hoạt lại sau khi kho đã
 * migrate — không được để đường ghi chết chỉ vì kho chưa migrate.
 *
 * Vai chính (issue #5): membership đang hoạt động luôn mang đúng
 * một vai chính. Đường ghi danh tạo/kích hoạt membership với vai
 * `member` theo mặc định — ghi danh làm người dùng thành thành
 * viên của tenant; nơi gọi biết vai khác (bề mặt quản trị thêm
 * thành viên) truyền tường minh qua tham số `primaryRole`. Kích
 * hoạt lại một dòng ĐÃ có vai chính thì GIỮ NGUYÊN vai đó: ghi
 * danh không bao giờ hạ vai của quản trị viên/giáo viên xuống
 * `member`; chỉ dòng lịch sử chưa có vai (trước migration cộng
 * cột) mới được lấp bằng vai truyền vào khi kích hoạt lại.
 *
 * Ngữ cảnh RLS: hàm này kế thừa ngữ cảnh của nơi gọi — các bề mặt
 * gọi nó (funnel ghi danh, tạo tenant/user, bootstrap seed) tự bọc
 * ngữ cảnh phù hợp ở tầng của chúng, như hành vi hiện hành.
 *
 * @param db - tay cầm Kysely của host.
 * @param tenantId - tenant đích.
 * @param userId - người dùng cần thuộc tenant.
 * @param primaryRole - vai chính khi phải tạo mới (hoặc lấp chỗ
 *   trống của dòng lịch sử); mặc định `member` theo ngữ nghĩa ghi danh.
 * @returns dòng membership ở trạng thái `active`.
 */
export async function ensureActiveTenantMembership(
  db: EnrolDatabase,
  tenantId: string,
  userId: string,
  primaryRole: TenantPrimaryRole = 'member',
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
  if (existing?.status === 'active' && existing.primaryRole !== null) return existing;
  let row: TenantMembershipRow;
  if (existing) {
    // Giữ nguyên vai chính đã có; chỉ lấp khi dòng lịch sử chưa có vai
    // — kể cả khi dòng đã active (trường hợp lấp vai nói trên).
    const effectivePrimaryRole = existing.primaryRole ?? primaryRole;
    const wasActive = existing.status === 'active';
    try {
      await db
        .updateTable('tenant_memberships')
        .set({ status: 'active', ended_at: null, primary_role: effectivePrimaryRole })
        .where('id', '=', existing.id)
        .execute();
    } catch (err) {
      if (!isMissingColumnError(err)) throw err;
      // Kho baseline chưa có cột vai chính: chỉ đổi trạng thái, vai
      // sẽ được lấp sau khi migration chạy.
      await db
        .updateTable('tenant_memberships')
        .set({ status: 'active', ended_at: null })
        .where('id', '=', existing.id)
        .execute();
      row = { ...existing, status: 'active', endedAt: null };
      if (!wasActive) {
        const tenantCtx = await ensureTenantContext(db, tenantId);
        await markContextDirty(db, tenantCtx.id);
      }
      return row;
    }
    row = { ...existing, status: 'active', endedAt: null, primaryRole: effectivePrimaryRole };
  } else {
    try {
      row = await db
        .insertInto('tenant_memberships')
        .values({ tenant_id: tenantId, user_id: userId, status: 'active', primary_role: primaryRole })
        .returning(TENANT_MEMBERSHIP_COLUMNS)
        .executeTakeFirstOrThrow();
    } catch (err) {
      if (!isMissingColumnError(err)) throw err;
      const baseline = await db
        .insertInto('tenant_memberships')
        .values({ tenant_id: tenantId, user_id: userId, status: 'active' })
        .returning(BASELINE_TENANT_MEMBERSHIP_COLUMNS)
        .executeTakeFirstOrThrow();
      row = normalizeMembershipRow(baseline as unknown as Record<string, unknown>);
    }
  }
  const tenantCtx = await ensureTenantContext(db, tenantId);
  await markContextDirty(db, tenantCtx.id);
  return row;
}
