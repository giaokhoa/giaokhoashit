import { ensureTenantContext, markContextDirty } from './contexts';
import {
  TENANT_MEMBERSHIP_COLUMNS,
  type EnrolDatabase,
  type TenantMembershipRow,
  type TenantPrimaryRole,
} from './types';

/**
 * Đảm bảo membership `active` của một người dùng trong một tenant ở
 * tầng dữ liệu (dùng chung cho funnel ghi danh và bề mặt membership
 * của host): ghi danh vào khoá của tenant nào thì thuộc tenant đó —
 * membership active được bảo đảm ngay tại funnel ghi danh để cổng
 * membership của phép đánh giá quyền cho qua các vai ghi danh vừa
 * cấp.
 *
 * Flow: membership đã `active` thì trả nguyên dòng hiện hữu (no-op);
 * membership tồn tại ở trạng thái khác thì kích hoạt lại (status về
 * `active`, xoá mốc kết thúc); chưa có thì chèn mới ở trạng thái
 * `active`. Cả hai đường ghi đều kết thúc bằng việc đảm bảo context
 * tenant tồn tại và đánh dấu dirty context đó (membership là dữ
 * kiện cổng của phép đánh giá quyền trong cây của tenant).
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
  const existing = await db
    .selectFrom('tenant_memberships')
    .select(TENANT_MEMBERSHIP_COLUMNS)
    .where((eb) => eb.and([
      eb('tenant_memberships.tenant_id', '=', tenantId),
      eb('tenant_memberships.user_id', '=', userId),
    ]))
    .executeTakeFirst();
  if (existing?.status === 'active') return existing;
  let row: TenantMembershipRow;
  if (existing) {
    // Giữ nguyên vai chính đã có; chỉ lấp khi dòng lịch sử chưa có vai.
    const effectivePrimaryRole = existing.primaryRole ?? primaryRole;
    await db
      .updateTable('tenant_memberships')
      .set({ status: 'active', ended_at: null, primary_role: effectivePrimaryRole })
      .where('id', '=', existing.id)
      .execute();
    row = { ...existing, status: 'active', endedAt: null, primaryRole: effectivePrimaryRole };
  } else {
    row = await db
      .insertInto('tenant_memberships')
      .values({ tenant_id: tenantId, user_id: userId, status: 'active', primary_role: primaryRole })
      .returning(TENANT_MEMBERSHIP_COLUMNS)
      .executeTakeFirstOrThrow();
  }
  const tenantCtx = await ensureTenantContext(db, tenantId);
  await markContextDirty(db, tenantCtx.id);
  return row;
}
