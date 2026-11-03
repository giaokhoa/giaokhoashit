/**
 * Repository LIÊN KẾT DANH TÍNH Keycloak trên Kysely (issue #5).
 *
 * Vai trò: mọi truy cập tới bảng `identity_links` của kho trung tâm
 * tập trung ở đây. Bảng giữ liên kết danh tính của hợp đồng xác
 * thực: cặp `(issuer, sub)` → user cục bộ của kho. Luồng danh tính
 * chuẩn mà bảng này phục vụ:
 *
 *   Host đã xác minh → tenant → issuer cố định đã đăng ký trong sổ
 *   control plane → token kiểm theo issuer đó → tra liên kết bằng
 *   đúng cặp (issuer, subject) → principal nghiệp vụ là user cục bộ
 *   mà liên kết trỏ về.
 *
 * Bất biến dữ liệu (chốt ở schema, repository chỉ giữ đúng khuôn):
 * - Khoá liên kết là cặp (issuer, subject) — KHÔNG dùng email,
 *   username hay `sub` đơn độc ở bất kỳ đường tra cứu nào của
 *   repository này. Đổi email của user không đổi liên kết; cùng một
 *   `sub` ở hai issuer khác nhau là hai liên kết khác nhau.
 * - Một cặp (issuer, subject) trỏ về đúng một user (ràng buộc duy
 *   nhất `identity_links_issuer_subject_unique`) và một user có tối
 *   đa một liên kết cho mỗi issuer (ràng buộc duy nhất
 *   `identity_links_user_issuer_unique`).
 * - Liên kết KHÔNG mang theo membership: tạo liên kết cho một
 *   principal (kể cả principal của realm platform `giaokhoa`) không
 *   tạo và không suy ra bất kỳ `tenant_memberships` nào — tư cách
 *   thành viên của tenant là dữ kiện riêng, cấp qua đường membership.
 * - Bảng đứng trong khuôn RLS `tenant_isolation` fail-closed theo
 *   `tenant_id` như `tenant_memberships`: repository chạy qua handle
 *   được định tuyến và KẾ THỪA ngữ cảnh tenant/platform mà tầng gọi
 *   đã thiết lập (GUC), không tự suy ngữ cảnh và không tự mở pool.
 *
 * Repository không chứa luật nghiệp vụ: quyết định tạo/thu hồi liên
 * kết khi nào, và ánh xạ lỗi xung đột sang mã lỗi HTTP, nằm ở tầng
 * service phía ứng dụng; lỗi vi phạm duy nhất của Postgres ném
 * thẳng ra để tầng đó nhận diện qua {@link isIdentityLinkConflict}.
 */
import type { Insertable, Updateable } from 'kysely';
import type { CentreDb, CentreDB } from '../client';

/** Một dòng liên kết danh tính (hình dạng camelCase phía tiêu thụ dùng). */
export interface IdentityLinkRow {
  id: string;
  tenantId: string;
  userId: string;
  /** Issuer OIDC cố định đã đăng ký của không gian sở hữu kho (URL của realm). */
  issuer: string;
  /** Claim `sub` của danh tính tại issuer đó. */
  subject: string;
  /** active | revoked. */
  status: string;
  /** Phiên bản đồng bộ của liên kết — tăng mỗi lần thu hồi để các lượt đồng bộ sau nhận ra thay đổi. */
  version: number;
  createdAt: Date;
  updatedAt: Date;
}

/** Dữ kiện tạo một liên kết danh tính mới. */
export interface NewIdentityLink {
  id?: string;
  tenantId: string;
  userId: string;
  issuer: string;
  subject: string;
  /** active | revoked; vắng mặt = mặc định `active` của database. */
  status?: string;
  createdAt?: Date;
  updatedAt?: Date;
}

/** Tên ràng buộc duy nhất trên cặp (issuer, subject) của bảng `identity_links`. */
export const IDENTITY_LINK_ISSUER_SUBJECT_UNIQUE = 'identity_links_issuer_subject_unique';
/** Tên ràng buộc duy nhất trên cặp (user_id, issuer) của bảng `identity_links`. */
export const IDENTITY_LINK_USER_ISSUER_UNIQUE = 'identity_links_user_issuer_unique';

/**
 * Lỗi có phải va chạm của một trong hai ràng buộc duy nhất của
 * bảng `identity_links` hay không — cùng khuôn nhận diện lỗi của
 * pilot control plane: mã 23505 cộng tên ràng buộc khớp (trên
 * trường `constraint` hoặc trong `message`), duyệt chuỗi `cause`
 * để chịu được các lớp bọc lỗi phía trên.
 *
 * @param err - Lỗi bắt được từ thao tác ghi liên kết.
 * @returns true khi lỗi là va chạm liên kết (trùng (issuer, sub)
 *   hoặc trùng (user, issuer)).
 */
export function isIdentityLinkConflict(err: unknown): boolean {
  let e: unknown = err;
  while (e !== null && typeof e === 'object') {
    const candidate = e as { code?: unknown; constraint?: unknown; message?: unknown };
    if (candidate.code === '23505'
      && (candidate.constraint === IDENTITY_LINK_ISSUER_SUBJECT_UNIQUE
        || candidate.constraint === IDENTITY_LINK_USER_ISSUER_UNIQUE
        || (typeof candidate.message === 'string'
          && (candidate.message.includes(IDENTITY_LINK_ISSUER_SUBJECT_UNIQUE)
            || candidate.message.includes(IDENTITY_LINK_USER_ISSUER_UNIQUE))))) {
      return true;
    }
    e = (e as { cause?: unknown }).cause;
  }
  return false;
}

/** Danh sách cột tường minh có bí danh camelCase cho bảng liên kết danh tính (dùng cho cả select lẫn returning). */
const IDENTITY_LINK_COLUMNS = [
  'id',
  'tenant_id as tenantId',
  'user_id as userId',
  'issuer',
  'subject',
  'status',
  'version',
  'created_at as createdAt',
  'updated_at as updatedAt',
] as const;

/**
 * Ánh xạ dữ kiện tạo liên kết (camelCase) sang values insert
 * (snake_case), LOẠI mọi trường mang `undefined` (quy tắc T-4):
 * trường vắng mặt để default của database quyết định.
 */
function identityLinkInsertValues(values: NewIdentityLink): Insertable<CentreDB['identity_links']> {
  const row: Insertable<CentreDB['identity_links']> = {
    tenant_id: values.tenantId,
    user_id: values.userId,
    issuer: values.issuer,
    subject: values.subject,
  };
  if (values.id !== undefined) row.id = values.id;
  if (values.status !== undefined) row.status = values.status;
  if (values.createdAt !== undefined) row.created_at = values.createdAt;
  if (values.updatedAt !== undefined) row.updated_at = values.updatedAt;
  return row;
}

/**
 * Kho dữ liệu liên kết danh tính của kho trung tâm (issue #5).
 * Thuần dữ liệu — repository nhận handle đã định tuyến, không đọc
 * ngữ cảnh ứng dụng và không tự mở pool.
 */
export class IdentityLinksRepository {
  constructor(private readonly handle: CentreDb) {}

  /**
   * Tạo một liên kết danh tính mới. Trùng (issuer, subject) hoặc
   * trùng (user, issuer) thì lỗi vi phạm duy nhất của Postgres ném
   * thẳng ra (tầng gọi nhận diện bằng {@link isIdentityLinkConflict}).
   * `tenant_id` phải là tenant đã tồn tại trong kho (khoá ngoại tới
   * `tenants.id`): liên kết trỏ tới tenant không tồn tại bị chặn ở
   * database, và liên kết của principal platform vẫn chỉ là một dòng
   * liên kết — thao tác này CHỈ ghi bảng liên kết, không tạo
   * membership nào.
   */
  async createLink(values: NewIdentityLink): Promise<IdentityLinkRow> {
    const rows = await this.handle
      .insertInto('identity_links')
      .values(identityLinkInsertValues(values))
      .returning(IDENTITY_LINK_COLUMNS)
      .execute();
    return rows[0]!;
  }

  /**
   * Tra liên kết bằng đúng cặp (issuer, subject) — đường phân giải
   * danh tính duy nhất của hợp đồng: token đã kiểm theo issuer đã
   * đăng ký cho ra `sub`, cặp này cho ra user cục bộ. Không tồn tại
   * đường tra bằng email hay bằng `sub` đơn độc.
   */
  async findByIssuerAndSubject(issuer: string, subject: string): Promise<IdentityLinkRow | undefined> {
    return this.handle
      .selectFrom('identity_links')
      .select(IDENTITY_LINK_COLUMNS)
      .where('issuer', '=', issuer)
      .where('subject', '=', subject)
      .limit(1)
      .executeTakeFirst();
  }

  /** Liên kết của một user tại một issuer (tối đa một theo ràng buộc duy nhất của bảng). */
  async findForUserAndIssuer(userId: string, issuer: string): Promise<IdentityLinkRow | undefined> {
    return this.handle
      .selectFrom('identity_links')
      .select(IDENTITY_LINK_COLUMNS)
      .where('user_id', '=', userId)
      .where('issuer', '=', issuer)
      .limit(1)
      .executeTakeFirst();
  }

  /** Mọi liên kết của một user trong kho, theo thời điểm tạo tăng dần. */
  async listForUser(userId: string): Promise<IdentityLinkRow[]> {
    return this.handle
      .selectFrom('identity_links')
      .select(IDENTITY_LINK_COLUMNS)
      .where('user_id', '=', userId)
      .orderBy('created_at', 'asc')
      .execute();
  }

  /**
   * Thu hồi một liên kết: trạng thái về `revoked`, `version` tăng
   * một bậc và mốc `updated_at` đóng lại — vết mà việc đồng bộ
   * danh tính ở các issue sau dựa vào để ngừng phân giải principal
   * cho cặp (issuer, sub) này. `version` được tăng ở tầng
   * repository bằng lượt đọc ngay trước lượt ghi (cột mang kiểu
   * sinh `Generated<number>` nên biểu thức SQL không lọt qua kiểu
   * `Updateable`); version là mốc đồng bộ chứ không phải khoá
   * chống đua, nên cửa sổ đọc-ghi này không phá bất biến nào. Trả
   * về dòng sau thu hồi, hoặc `undefined` khi liên kết không tồn
   * tại trong ngữ cảnh hiện tại.
   */
  async revokeLink(id: string): Promise<IdentityLinkRow | undefined> {
    const existing = await this.handle
      .selectFrom('identity_links')
      .select(IDENTITY_LINK_COLUMNS)
      .where('id', '=', id)
      .limit(1)
      .executeTakeFirst();
    if (!existing) return undefined;
    const set: Updateable<CentreDB['identity_links']> = {
      status: 'revoked',
      version: existing.version + 1,
      updated_at: new Date(),
    };
    const rows = await this.handle
      .updateTable('identity_links')
      .set(set)
      .where('id', '=', id)
      .returning(IDENTITY_LINK_COLUMNS)
      .execute();
    return rows[0];
  }
}
