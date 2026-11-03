/**
 * Repository của cụm TENANT trên Kysely — chuyển đổi bộ máy cũ →
 * Kysely của chiến dịch chuyển tầng dữ liệu (issue #641, nhóm G1).
 *
 * Vai trò: mọi truy cập tới ba bảng `tenants`, `tenant_memberships`
 * và `tenant_config` của kho trung tâm tập trung ở đây. Thuần dữ
 * liệu — mọi quyết định quyền nằm ở TenantsService /
 * PermissionEvaluator phía ứng dụng; repository chỉ nhận handle
 * Kysely do tầng ứng dụng truyền vào.
 *
 * Khuôn hình dạng dữ liệu ở biên repository — cùng khuôn pilot kho
 * control plane (xem `control-plane.ts` và playbook trong
 * `AGENTS.md` của package):
 * - Bên trong, truy vấn viết trên interface schema sinh bằng
 *   codegen: tên bảng/cột giữ nguyên snake_case của database.
 * - Bên ngoài, repository trả/nhận hình dạng dòng camelCase như
 *   phía tiêu thụ đang dùng: cột được chọn bằng danh sách tường
 *   minh có bí danh (`tenant_id as tenantId`), giá trị ghi được
 *   ánh xạ từng trường từ camelCase sang snake_case ở các hàm
 *   `*InsertValues`/`*UpdateSet` trong file này.
 * - Giá trị `undefined` bị LOẠI khỏi values/set trước khi gọi
 *   builder (quy tắc T-4 của pilot): trường vắng mặt phải để
 *   default của database quyết định; mọi giá trị where đều là
 *   tham số bắt buộc.
 * - Giá trị jsonb (`tenant_config.value`) ghi qua helper
 *   serialize + ép kiểu `::jsonb` (mẫu `jsonbValue` của pilot):
 *   driver `pg` hiểu một MẢNG ở cấp cao nhất là mảng PostgreSQL
 *   chứ không phải JSON, nên serialize tường minh cho mọi hình
 *   dạng giá trị; giá trị `null` giữ nguyên là SQL NULL như đường
 *   bộ máy cũ trước đây.
 * - Repository không import từ `apps/*`, không đọc ALS/ngữ cảnh
 *   ứng dụng, không tự mở pool: handle do tầng ứng dụng dựng (điểm
 *   lắp ráp là facade định tuyến theo không gian `routedKysely`)
 *   rồi truyền vào; ngữ cảnh trung tâm đi theo chính kết nối của
 *   handle đó.
 */
import { sql, type Insertable, type RawBuilder, type Updateable } from 'kysely';
import type { CentreDb, CentreDB } from '../client';
import type { Json } from '../types/centre-db';

/** Một dòng tenant (hình dạng camelCase phía tiêu thụ dùng). */
export interface TenantRow {
  id: string;
  slug: string;
  name: string;
  kind: string;
  status: string;
  plan: string | null;
  createdAt: Date;
  updatedAt: Date;
}

/**
 * Vai chính duy nhất của một membership (issue #5): tập đóng
 * `admin | teacher | member`, ánh xạ 1-1 sang realm role của
 * Keycloak tương ứng là `tenant-admin | tenant-teacher |
 * tenant-member`. Vai chính là hình chiếu do API sở hữu trong giai
 * đoạn 1 của hợp đồng xác thực — độc lập với các gán vai theo
 * context (`role_assignments`), vốn quyết quyền chi tiết tại cây
 * context chứ không định danh loại thành viên.
 */
export type TenantPrimaryRole = 'admin' | 'teacher' | 'member';

/** Một dòng membership của tenant. */
export interface TenantMembershipRow {
  id: string;
  tenantId: string;
  userId: string;
  status: string;
  /**
   * Vai chính của membership (issue #5) — giá trị thuộc tập đóng
   * {@link TenantPrimaryRole}, chốt ở database bằng ràng buộc
   * CHECK. Mọi đường ghi được hỗ trợ đều bắt buộc ghi trường này
   * khi tạo/kích hoạt membership; `null` chỉ còn ở các dòng lịch
   * sử có trước migration cộng cột. Kiểu giữ `string` như mọi cột
   * trạng thái khác của package; tập đóng được ép ở hợp đồng ghi
   * {@link NewTenantMembership} và ở ràng buộc của bảng.
   */
  primaryRole: string | null;
  /** Phiên bản đồng bộ của dòng membership — tăng khi thu hồi/đồng bộ danh tính ở các issue sau. */
  version: number;
  joinedAt: Date;
  endedAt: Date | null;
}

/** Một dòng cấu hình theo tenant; `value` là jsonb tự do nên giữ kiểu `unknown` như đường cũ trước đây. */
export interface TenantConfigRow {
  id: string;
  tenantId: string;
  key: string;
  value: unknown;
  updatedAt: Date;
}

/** Dữ kiện tạo tenant mới (các trường có default ở database được phép vắng mặt). */
export interface NewTenant {
  id?: string;
  slug: string;
  name: string;
  kind: string;
  status?: string;
  plan?: string | null;
  createdAt?: Date;
  updatedAt?: Date;
}

/** Dữ kiện tạo membership mới. */
export interface NewTenantMembership {
  id?: string;
  tenantId: string;
  userId: string;
  status?: string;
  /**
   * Vai chính của membership — BẮT BUỘC ở hợp đồng ghi (issue #5):
   * không đường ghi được hỗ trợ nào tạo membership mà thiếu vai
   * chính, và một cột đơn khiến "hơn một vai chính" không thể biểu
   * diễn. Tập giá trị đóng còn được chốt ở database bằng ràng buộc
   * CHECK trên cột `primary_role`.
   */
  primaryRole: TenantPrimaryRole;
  joinedAt?: Date;
  endedAt?: Date | null;
}

/** Slug của tenant-zero — không gian Trang chính (giáo viên/học sinh tự do). */
export const PLATFORM_TENANT_SLUG = 'trang-chinh';
/** Slug của tenant trường mặc định do review-seed dựng cho câu chuyện demo. */
export const DEFAULT_SCHOOL_TENANT_SLUG = 'truong-mac-dinh';

/**
 * Bọc một giá trị jsonb thành biểu thức tham số JSON tường minh —
 * cùng mẫu `jsonbValue` của pilot kho control plane: serialize
 * tường minh + ép kiểu `::jsonb` cho MỌI giá trị jsonb khi ghi
 * (object lẫn mảng) để một khuôn duy nhất, không phụ thuộc nhánh
 * xử lý riêng của driver theo hình dạng giá trị. Kiểu trả về gắn
 * theo kiểu `Json` của cột đích để builder chấp nhận biểu thức ở
 * vị trí giá trị của cột jsonb.
 */
function jsonbValue(value: unknown): RawBuilder<Json> {
  return sql<Json>`${JSON.stringify(value as Json)}::jsonb`;
}

/** Danh sách cột tường minh có bí danh camelCase cho bảng tenant (dùng cho cả select lẫn returning). */
const TENANT_COLUMNS = [
  'id',
  'slug',
  'name',
  'kind',
  'status',
  'plan',
  'created_at as createdAt',
  'updated_at as updatedAt',
] as const;

/** Danh sách cột tường minh có bí danh camelCase cho bảng membership. */
const TENANT_MEMBERSHIP_COLUMNS = [
  'id',
  'tenant_id as tenantId',
  'user_id as userId',
  'status',
  'primary_role as primaryRole',
  'version',
  'joined_at as joinedAt',
  'ended_at as endedAt',
] as const;

/**
 * Danh sách cột membership ở schema baseline — trước migration cộng
 * `primary_role`/`version` của issue #5. Ứng dụng phải phục vụ được
 * kho đang ở baseline trong cửa sổ triển khai (fleet áp migration
 * sau khi mã mới đã chạy — xem spec tiền di trú issue #587): mọi
 * đường đọc/ghi membership dưới đây thử danh sách đầy đủ trước, và
 * chỉ khi database báo thiếu cột mới rơi về danh sách này. Dòng đọc
 * về từ baseline được chuẩn hoá thành hình dạng đầy đủ (vai chính
 * `null`, phiên bản `1`) để phía tiêu thụ không phân biệt hai
 * schema; sau khi migration chạy, các dòng đó được lấp vai ở chính
 * migration (active) hoặc ở lần kích hoạt lại (chưa active).
 */
const BASELINE_TENANT_MEMBERSHIP_COLUMNS = [
  'id',
  'tenant_id as tenantId',
  'user_id as userId',
  'status',
  'joined_at as joinedAt',
  'ended_at as endedAt',
] as const;

/** Lỗi có phải "cột chưa tồn tại" của Postgres (mã 42703) hay không — dấu hiệu kho đang ở schema baseline. */
function isMissingColumnError(err: unknown): boolean {
  const code = (err as { code?: unknown } | null)?.code;
  if (code === '42703') return true;
  const message = err instanceof Error ? err.message : '';
  return /column "(primary_role|version)" does not exist/.test(message);
}

/** Chuẩn hoá một dòng membership đọc từ kho baseline thành hình dạng đầy đủ của repository. */
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

/** Danh sách cột tường minh có bí danh camelCase cho bảng cấu hình tenant. */
const TENANT_CONFIG_COLUMNS = [
  'id',
  'tenant_id as tenantId',
  'key',
  'value',
  'updated_at as updatedAt',
] as const;

/**
 * Ánh xạ dữ kiện tạo tenant (camelCase) sang values insert
 * (snake_case), LOẠI mọi trường mang `undefined` (quy tắc T-4).
 */
function tenantInsertValues(values: NewTenant): Insertable<CentreDB['tenants']> {
  const row: Insertable<CentreDB['tenants']> = {
    slug: values.slug,
    name: values.name,
    kind: values.kind,
  };
  if (values.id !== undefined) row.id = values.id;
  if (values.status !== undefined) row.status = values.status;
  if (values.plan !== undefined) row.plan = values.plan;
  if (values.createdAt !== undefined) row.created_at = values.createdAt;
  if (values.updatedAt !== undefined) row.updated_at = values.updatedAt;
  return row;
}

/**
 * Ánh xạ một patch tenant (camelCase, lấy theo hình dạng dòng đầy
 * đủ) sang set update (snake_case), LOẠI mọi trường `undefined` —
 * patch chỉ chứa trường nào thì chỉ trường đó đổi (quy tắc T-4).
 * Mốc `updated_at` do repository tự đóng dấu ở điểm gọi.
 */
function tenantUpdateSet(patch: Partial<TenantRow>): Updateable<CentreDB['tenants']> {
  const set: Updateable<CentreDB['tenants']> = {};
  if (patch.slug !== undefined) set.slug = patch.slug;
  if (patch.name !== undefined) set.name = patch.name;
  if (patch.kind !== undefined) set.kind = patch.kind;
  if (patch.status !== undefined) set.status = patch.status;
  if (patch.plan !== undefined) set.plan = patch.plan;
  if (patch.createdAt !== undefined) set.created_at = patch.createdAt;
  return set;
}

/** Ánh xạ dữ kiện tạo membership (camelCase) sang values insert (snake_case), loại `undefined` như mọi đường ghi khác. `primary_role` là trường bắt buộc của hợp đồng ghi nên luôn được ghi tường minh. */
function membershipInsertValues(values: NewTenantMembership): Insertable<CentreDB['tenant_memberships']> {
  const row: Insertable<CentreDB['tenant_memberships']> = {
    tenant_id: values.tenantId,
    user_id: values.userId,
    primary_role: values.primaryRole,
  };
  if (values.id !== undefined) row.id = values.id;
  if (values.status !== undefined) row.status = values.status;
  if (values.joinedAt !== undefined) row.joined_at = values.joinedAt;
  if (values.endedAt !== undefined) row.ended_at = values.endedAt;
  return row;
}

/**
 * Ánh xạ một patch membership (camelCase) sang set update
 * (snake_case), LOẠI mọi trường `undefined` (quy tắc T-4). Bảng
 * membership không có mốc `updated_at` nên repository không đóng
 * dấu mốc nào ở đường cập nhật này — đúng hành vi đường bộ máy cũ
 * trước đây.
 */
function membershipUpdateSet(patch: Partial<TenantMembershipRow>): Updateable<CentreDB['tenant_memberships']> {
  const set: Updateable<CentreDB['tenant_memberships']> = {};
  if (patch.tenantId !== undefined) set.tenant_id = patch.tenantId;
  if (patch.userId !== undefined) set.user_id = patch.userId;
  if (patch.status !== undefined) set.status = patch.status;
  if (patch.primaryRole !== undefined) set.primary_role = patch.primaryRole;
  if (patch.version !== undefined) set.version = patch.version;
  if (patch.joinedAt !== undefined) set.joined_at = patch.joinedAt;
  if (patch.endedAt !== undefined) set.ended_at = patch.endedAt;
  return set;
}

/**
 * Kho dữ liệu tenant: vòng đời tenant, membership và cấu hình theo tenant.
 * Thuần dữ liệu — mọi quyết định quyền nằm ở TenantsService /
 * PermissionEvaluator.
 */
export class TenantsRepository {
  constructor(private readonly handle: CentreDb) {}

  /**
   * Handle gốc của repository (handle Kysely được định tuyến theo
   * không gian). Tầng service còn đúng một điểm ghép cần handle
   * thô — uỷ thác bảo đảm membership cho tầng dùng chung
   * `@lms/enrol` — và điểm đó KHÔNG lấy handle từ đây nữa: tầng
   * dùng chung đó vẫn chạy trên bộ máy cũ cho tới hạng mục chuyển
   * đổi của chính nó, nên service tự inject handle bộ máy cũ cho
   * riêng điểm uỷ thác đó (xem kiểm kê giao dịch trong thân PR
   * của issue #641). Getter này giữ cho các đường đọc/ghi Kysely
   * cần handle gốc trong tương lai của chính cụm tenant.
   */
  get dbHandle(): CentreDb { return this.handle; }

  async insertTenant(v: NewTenant): Promise<TenantRow> {
    const rows = await this.handle
      .insertInto('tenants')
      .values(tenantInsertValues(v))
      .returning(TENANT_COLUMNS)
      .execute();
    return rows[0]!;
  }

  async getTenant(id: string): Promise<TenantRow | undefined> {
    return this.handle
      .selectFrom('tenants')
      .select(TENANT_COLUMNS)
      .where('id', '=', id)
      .executeTakeFirst();
  }

  async getTenantBySlug(slug: string): Promise<TenantRow | undefined> {
    return this.handle
      .selectFrom('tenants')
      .select(TENANT_COLUMNS)
      .where('slug', '=', slug)
      .executeTakeFirst();
  }

  /** Bản tenant-zero đã tra được, giữ trong singleton của repository. */
  private platformTenantCache: TenantRow | undefined;

  /**
   * Tenant-zero (tenant nền tảng) cho TenantGuard: được gieo một lần
   * lúc bootstrap và không đổi danh tính (id/slug/kind) trong vòng
   * đời tiến trình, nên tra một lần rồi giữ lại trong singleton này
   * — bỏ một truy vấn khỏi MỌI request. Chỉ cache khi ĐÃ tra thấy:
   * trước bootstrap seed (tenant-zero chưa tồn tại) mọi lần gọi vẫn
   * xuống cơ sở dữ liệu để request đầu tiên sau khi gieo thấy ngay.
   * Các thay đổi trạng thái/cấu hình của tenant-zero không đi qua
   * bản cache này (guard chỉ dùng `id` của nó làm tenant hiệu lực
   * mặc định); đường đọc cần trạng thái tươi dùng `getTenantBySlug` /
   * `getTenant` như cũ.
   */
  async getPlatformTenant(): Promise<TenantRow | undefined> {
    if (!this.platformTenantCache) {
      this.platformTenantCache = await this.getTenantBySlug(PLATFORM_TENANT_SLUG);
    }
    return this.platformTenantCache;
  }

  async listTenants(): Promise<TenantRow[]> {
    return this.handle
      .selectFrom('tenants')
      .select(TENANT_COLUMNS)
      .orderBy('created_at', 'asc')
      .execute();
  }

  async updateTenant(id: string, patch: Partial<TenantRow>): Promise<void> {
    await this.handle
      .updateTable('tenants')
      .set({ ...tenantUpdateSet(patch), updated_at: new Date() })
      .where('id', '=', id)
      .execute();
  }

  async insertMembership(v: NewTenantMembership): Promise<TenantMembershipRow> {
    try {
      const rows = await this.handle
        .insertInto('tenant_memberships')
        .values(membershipInsertValues(v))
        .returning(TENANT_MEMBERSHIP_COLUMNS)
        .execute();
      return rows[0]!;
    } catch (err) {
      if (!isMissingColumnError(err)) throw err;
      // Kho baseline chưa có cột vai chính/phiên bản: ghi không kèm
      // hai cột đó, vai chính sẽ được lấp ở lần migration/kích hoạt
      // lại — không được để đường ghi chết chỉ vì kho chưa migrate.
      const values = membershipInsertValues(v);
      const { primary_role: _ignored, ...baselineValues } = values as Record<string, unknown>;
      void _ignored;
      const rows = await this.handle
        .insertInto('tenant_memberships')
        .values(baselineValues as Insertable<CentreDB['tenant_memberships']>)
        .returning(BASELINE_TENANT_MEMBERSHIP_COLUMNS)
        .execute();
      return normalizeMembershipRow(rows[0]! as unknown as Record<string, unknown>);
    }
  }

  async getMembership(tenantId: string, userId: string): Promise<TenantMembershipRow | undefined> {
    try {
      return await this.handle
        .selectFrom('tenant_memberships')
        .select(TENANT_MEMBERSHIP_COLUMNS)
        .where('tenant_id', '=', tenantId)
        .where('user_id', '=', userId)
        .executeTakeFirst();
    } catch (err) {
      if (!isMissingColumnError(err)) throw err;
      const row = await this.handle
        .selectFrom('tenant_memberships')
        .select(BASELINE_TENANT_MEMBERSHIP_COLUMNS)
        .where('tenant_id', '=', tenantId)
        .where('user_id', '=', userId)
        .executeTakeFirst();
      return row ? normalizeMembershipRow(row as unknown as Record<string, unknown>) : undefined;
    }
  }

  /** Xoá membership legacy của một user trong tenant đích (dùng cho dọn dữ liệu seed platform). */
  async deleteMembership(tenantId: string, userId: string): Promise<void> {
    await this.handle
      .deleteFrom('tenant_memberships')
      .where('tenant_id', '=', tenantId)
      .where('user_id', '=', userId)
      .execute();
  }

  async listMembershipsForUser(userId: string): Promise<TenantMembershipRow[]> {
    try {
      return await this.handle
        .selectFrom('tenant_memberships')
        .select(TENANT_MEMBERSHIP_COLUMNS)
        .where('user_id', '=', userId)
        .orderBy('joined_at', 'asc')
        .execute();
    } catch (err) {
      if (!isMissingColumnError(err)) throw err;
      const rows = await this.handle
        .selectFrom('tenant_memberships')
        .select(BASELINE_TENANT_MEMBERSHIP_COLUMNS)
        .where('user_id', '=', userId)
        .orderBy('joined_at', 'asc')
        .execute();
      return rows.map((row) => normalizeMembershipRow(row as unknown as Record<string, unknown>));
    }
  }

  async listMembershipsForTenant(tenantId: string): Promise<TenantMembershipRow[]> {
    try {
      return await this.handle
        .selectFrom('tenant_memberships')
        .select(TENANT_MEMBERSHIP_COLUMNS)
        .where('tenant_id', '=', tenantId)
        .orderBy('joined_at', 'asc')
        .execute();
    } catch (err) {
      if (!isMissingColumnError(err)) throw err;
      const rows = await this.handle
        .selectFrom('tenant_memberships')
        .select(BASELINE_TENANT_MEMBERSHIP_COLUMNS)
        .where('tenant_id', '=', tenantId)
        .orderBy('joined_at', 'asc')
        .execute();
      return rows.map((row) => normalizeMembershipRow(row as unknown as Record<string, unknown>));
    }
  }

  async updateMembership(id: string, patch: Partial<TenantMembershipRow>): Promise<void> {
    try {
      await this.handle
        .updateTable('tenant_memberships')
        .set(membershipUpdateSet(patch))
        .where('id', '=', id)
        .execute();
    } catch (err) {
      if (!isMissingColumnError(err)) throw err;
      // Kho baseline: bỏ hai trường mới khỏi lệnh cập nhật; nếu patch
      // chỉ gồm hai trường đó thì không còn gì để ghi — đó đúng là
      // ngữ nghĩa của cửa sổ tiền di trú (vai/phiên bản chưa có nơi
      // chứa, sẽ được lấp sau khi migration chạy).
      const set = membershipUpdateSet(patch);
      const { primary_role: _role, version: _version, ...baselineSet } = set as Record<string, unknown>;
      void _role;
      void _version;
      if (Object.keys(baselineSet).length === 0) return;
      await this.handle
        .updateTable('tenant_memberships')
        .set(baselineSet as Updateable<CentreDB['tenant_memberships']>)
        .where('id', '=', id)
        .execute();
    }
  }

  async getConfigValue(tenantId: string, key: string): Promise<TenantConfigRow | undefined> {
    const row = await this.handle
      .selectFrom('tenant_config')
      .select(TENANT_CONFIG_COLUMNS)
      .where('tenant_id', '=', tenantId)
      .where('key', '=', key)
      .executeTakeFirst();
    return row as TenantConfigRow | undefined;
  }

  async listConfigForTenant(tenantId: string): Promise<TenantConfigRow[]> {
    const rows = await this.handle
      .selectFrom('tenant_config')
      .select(TENANT_CONFIG_COLUMNS)
      .where('tenant_id', '=', tenantId)
      .execute();
    return rows as TenantConfigRow[];
  }

  async upsertConfigValue(tenantId: string, key: string, value: unknown): Promise<void> {
    const stored = value === null || value === undefined ? null : jsonbValue(value);
    await this.handle
      .insertInto('tenant_config')
      .values({ tenant_id: tenantId, key, value: stored })
      .onConflict((oc) => oc.columns(['tenant_id', 'key']).doUpdateSet({
        value: stored,
        updated_at: new Date(),
      }))
      .execute();
  }

  async deleteConfigValue(tenantId: string, key: string): Promise<void> {
    await this.handle
      .deleteFrom('tenant_config')
      .where('tenant_id', '=', tenantId)
      .where('key', '=', key)
      .execute();
  }
}
