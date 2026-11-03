/**
 * Repository phía TRUNG TÂM của kho ngân hàng hệ thống trên Kysely — hạng mục nhân rộng G10 của chiến dịch chuyển tầng
 * dữ liệu (issue #650), chuyển từ repository bộ máy cũ phía `apps/api` theo khuôn pilot kho control plane (issue #640).
 *
 * Vai trò: các bảng vệ tinh nằm trong kho nghiệp vụ của trung tâm — bản ghi nhập bản chụp (`system_question_imports`),
 * vết nhận sự kiện (`system_bank_event_receipts`), bản cài gói YCCĐ (`outcome_package_installs`) — cộng các tra cứu và
 * upsert cần cho đường nhập bản chụp và đường cài gói (slug trung tâm, môn học, chương trình, YCCĐ theo id giữ nguyên).
 *
 * Handle là handle Kysely ĐỊNH TUYẾN THEO KHÔNG GIAN của kho trung tâm (`SpaceDatabaseService.routedKysely` phía ứng
 * dụng): trong ngữ cảnh request của trung tâm, handle trỏ đúng kho của trung tâm đó (kho chung hoặc kho riêng sau di
 * trú), nên service nhập/cài không tự chọn kho. Handle này tách hẳn handle kho ngân hàng riêng của `system-bank.ts` —
 * hai kho không có transaction chung, repository này không bao giờ chạm database kho ngân hàng.
 *
 * Khuôn hình dạng dữ liệu ở biên repository (như pilot): bên trong dùng tên bảng/cột snake_case của interface schema
 * sinh bằng codegen; bên ngoài trả/nhận hình dạng dòng camelCase như phía tiêu thụ đang dùng (danh sách cột tường minh
 * có bí danh cho cả select lẫn returning, kiểu dòng công khai khai ngay trong file này — không import từ file schema bộ
 * máy cũ vốn bị xoá ở hạng mục gỡ bộ máy cũ); giá trị ghi vào cột jsonb đi qua helper serialize tường minh + `::jsonb`;
 * mọi giá trị where là tham số bắt buộc. Các phương thức ghi bản chụp nhận handle giao dịch tuỳ chọn do bên gọi truyền
 * tường minh (khuôn `tx ?? handle gốc`) — một transaction chỉ chứa một ORM, cấm giao dịch lai.
 */
import { sql, type RawBuilder } from 'kysely';
import type { CentreDb, CentreTx } from '../client';
import type { LearningOutcomeDoc } from '../jsonb-types';

export type SbCentreTx = CentreTx;
export type SbCentreDb = CentreDb | CentreTx;

export interface SystemQuestionImportRow {
  /** Câu hỏi ở kho trung tâm (bản chụp) — cũng là khoá chính của dòng truy vết. */
  questionId: string;
  sourceQuestionId: string;
  sourceVersionId: string;
  sourceVersionNumber: number;
  packageChecksum: string;
  importedVersionNumber: number;
  importedAt: Date;
  importedBy: string | null;
  /** pristine = nguyên trạng chưa sửa; derived = đã có version do trung tâm tạo thêm. */
  kind: string;
}

export interface SystemBankEventReceiptRow {
  id: string;
  eventId: string;
  centreSlug: string;
  receivedAt: Date;
}

export interface OutcomePackageInstallRow {
  id: string;
  /** Định danh phiên bản gói đã cài (khớp `system_outcome_packages.version` ở kho ngân hàng). */
  packageVersion: string;
  /** Phiên bản gói trước lần cài này, null ở lần cài đầu tiên. */
  previousVersion: string | null;
  /** all | slug môn học. */
  scope: string;
  installedAt: Date;
  installedBy: string | null;
}

export interface SystemBankSubjectRow {
  id: string;
  contextId: string;
  name: string;
  slug: string;
  position: number;
  createdAt: Date;
  updatedAt: Date;
}

export interface SystemBankCurriculumRow {
  id: string;
  subjectId: string;
  grade: number;
  createdAt: Date;
  updatedAt: Date;
}

/**
 * Bọc một giá trị jsonb thành biểu thức tham số JSON tường minh (serialize + ép kiểu `::jsonb`) — khuôn duy nhất cho
 * đường ghi jsonb của repository này: driver `pg` hiểu MẢNG ở cấp cao nhất là mảng PostgreSQL chứ không phải tài liệu
 * JSON (phát hiện của pilot kho control plane).
 */
function jsonbColumn<T>(value: unknown): RawBuilder<T> {
  return sql<T>`${JSON.stringify(value)}::jsonb`;
}

const SYSTEM_QUESTION_IMPORT_COLUMNS = [
  'question_id as questionId',
  'source_question_id as sourceQuestionId',
  'source_version_id as sourceVersionId',
  'source_version_number as sourceVersionNumber',
  'package_checksum as packageChecksum',
  'imported_version_number as importedVersionNumber',
  'imported_at as importedAt',
  'imported_by as importedBy',
  'kind',
] as const;

const SYSTEM_BANK_EVENT_RECEIPT_COLUMNS = [
  'id',
  'event_id as eventId',
  'centre_slug as centreSlug',
  'received_at as receivedAt',
] as const;

const OUTCOME_PACKAGE_INSTALL_COLUMNS = [
  'id',
  'package_version as packageVersion',
  'previous_version as previousVersion',
  'scope',
  'installed_at as installedAt',
  'installed_by as installedBy',
] as const;

const SUBJECT_COLUMNS = [
  'id',
  'context_id as contextId',
  'name',
  'slug',
  'position',
  'created_at as createdAt',
  'updated_at as updatedAt',
] as const;

const CURRICULUM_COLUMNS = [
  'id',
  'subject_id as subjectId',
  'grade',
  'created_at as createdAt',
  'updated_at as updatedAt',
] as const;

/**
 * Repository phía trung tâm của kho ngân hàng hệ thống trên Kysely: giữ handle định tuyến theo không gian của kho trung
 * tâm do tầng ứng dụng cấp lúc lắp ráp; mọi truy vấn chạy trên handle đó hoặc trên handle giao dịch bên gọi truyền vào.
 */
export class SystemBankCentreRepository {
  constructor(private readonly db: CentreDb) {}

  /**
   * Slug của tenant theo id (null khi không có) — dùng suy slug trung tâm cho dòng importer khi request không mang slug
   * kho riêng.
   */
  async resolveTenantSlug(tenantId: string): Promise<string | null> {
    const rows = await this.db
      .selectFrom('tenants')
      .select(['slug'])
      .where('id', '=', tenantId)
      .limit(1)
      .execute();
    return rows[0]?.slug ?? null;
  }

  async outcomeExists(outcomeId: string): Promise<boolean> {
    const rows = await this.db
      .selectFrom('learning_outcomes')
      .select(['id'])
      .where('id', '=', outcomeId)
      .limit(1)
      .execute();
    return rows.length > 0;
  }

  async insertImportRow(
    input: {
      questionId: string;
      sourceQuestionId: string;
      sourceVersionId: string;
      sourceVersionNumber: number;
      packageChecksum: string;
      importedVersionNumber: number;
      importedBy: string | null;
      kind: 'pristine' | 'derived';
    },
    tx?: SbCentreDb,
  ): Promise<SystemQuestionImportRow> {
    const handle = tx ?? this.db;
    const [row] = await handle
      .insertInto('system_question_imports')
      .values({
        question_id: input.questionId,
        source_question_id: input.sourceQuestionId,
        source_version_id: input.sourceVersionId,
        source_version_number: input.sourceVersionNumber,
        package_checksum: input.packageChecksum,
        imported_version_number: input.importedVersionNumber,
        imported_by: input.importedBy,
        kind: input.kind,
      })
      .returning(SYSTEM_QUESTION_IMPORT_COLUMNS)
      .execute();
    if (!row) throw new Error('system bank: failed to insert question import row');
    return row;
  }

  async getImportRow(questionId: string, tx?: SbCentreDb): Promise<SystemQuestionImportRow | null> {
    const handle = tx ?? this.db;
    const row = await handle
      .selectFrom('system_question_imports')
      .select(SYSTEM_QUESTION_IMPORT_COLUMNS)
      .where('question_id', '=', questionId)
      .limit(1)
      .executeTakeFirst();
    return row ?? null;
  }

  /**
   * Chuyển một bản nhập nguyên trạng thành bản phái sinh (khi câu trung tâm bị sửa): chỉ đổi cờ `kind`, không chạm các
   * trường truy vết khác — phiên bản nguyên trạng vẫn đọc lại được theo `importedVersionNumber`.
   */
  async flipImportToDerived(questionId: string, tx?: SbCentreDb): Promise<void> {
    const handle = tx ?? this.db;
    await handle
      .updateTable('system_question_imports')
      .set({ kind: 'derived' })
      .where('question_id', '=', questionId)
      .where('kind', '=', 'pristine')
      .execute();
  }

  /**
   * Ghi vết đã nhận một sự kiện kho ngân hàng (idempotent theo cặp sự kiện + slug trung tâm): insert-bỏ-qua-khi-trùng,
   * nếu cặp đã có thì đọc lại dòng hiện hữu.
   */
  async insertEventReceipt(input: {
    eventId: string;
    centreSlug: string;
  }): Promise<SystemBankEventReceiptRow> {
    const [row] = await this.db
      .insertInto('system_bank_event_receipts')
      .values({ event_id: input.eventId, centre_slug: input.centreSlug })
      .onConflict((oc) => oc.columns(['event_id', 'centre_slug']).doNothing())
      .returning(SYSTEM_BANK_EVENT_RECEIPT_COLUMNS)
      .execute();
    if (row) return row;
    const existing = await this.db
      .selectFrom('system_bank_event_receipts')
      .select(SYSTEM_BANK_EVENT_RECEIPT_COLUMNS)
      .where('event_id', '=', input.eventId)
      .where('centre_slug', '=', input.centreSlug)
      .limit(1)
      .executeTakeFirst();
    if (!existing) throw new Error('system bank: failed to record event receipt');
    return existing;
  }

  async listEventReceiptEventIds(centreSlug: string): Promise<string[]> {
    const rows = await this.db
      .selectFrom('system_bank_event_receipts')
      .select(['event_id as eventId'])
      .where('centre_slug', '=', centreSlug)
      .execute();
    return rows.map((row) => row.eventId);
  }

  /**
   * Đọc bản cài gói YCCĐ mới nhất của trung tâm (null khi chưa cài gói nào) — dùng suy phiên bản trước khi ghi vết cài
   * mới.
   */
  async getOutcomePackageInstall(): Promise<OutcomePackageInstallRow | null> {
    const row = await this.db
      .selectFrom('outcome_package_installs')
      .select(OUTCOME_PACKAGE_INSTALL_COLUMNS)
      .orderBy('installed_at', 'desc')
      .limit(1)
      .executeTakeFirst();
    return row ?? null;
  }

  async findSubjectBySlug(slug: string): Promise<SystemBankSubjectRow | null> {
    const row = await this.db
      .selectFrom('subjects')
      .select(SUBJECT_COLUMNS)
      .where('slug', '=', slug)
      .limit(1)
      .executeTakeFirst();
    return row ?? null;
  }

  async findCurriculumBySubjectGrade(subjectId: string, grade: number): Promise<SystemBankCurriculumRow | null> {
    const row = await this.db
      .selectFrom('curricula')
      .select(CURRICULUM_COLUMNS)
      .where('subject_id', '=', subjectId)
      .where('grade', '=', grade)
      .limit(1)
      .executeTakeFirst();
    return row ?? null;
  }

  async upsertCurriculum(input: { id: string; subjectId: string; grade: number }): Promise<void> {
    await this.db
      .insertInto('curricula')
      .values({ id: input.id, subject_id: input.subjectId, grade: input.grade })
      .onConflict((oc) =>
        oc.column('id').doUpdateSet({ subject_id: input.subjectId, grade: input.grade }),
      )
      .execute();
  }

  /** Upsert một node cây chương trình theo id giữ nguyên (gọi theo thứ tự cha trước). */
  async upsertCurriculumNode(input: {
    id: string;
    curriculumId: string;
    parentId: string | null;
    title: string;
    kind: string;
    position: number;
    durationPeriods: number | null;
    durationPercent: number | null;
  }): Promise<void> {
    await this.db
      .insertInto('curriculum_nodes')
      .values({
        id: input.id,
        curriculum_id: input.curriculumId,
        parent_id: input.parentId,
        title: input.title,
        kind: input.kind,
        position: input.position,
        duration_periods: input.durationPeriods,
        duration_percent: input.durationPercent,
      })
      .onConflict((oc) =>
        oc.column('id').doUpdateSet({
          curriculum_id: input.curriculumId,
          parent_id: input.parentId,
          title: input.title,
          kind: input.kind,
          position: input.position,
          duration_periods: input.durationPeriods,
          duration_percent: input.durationPercent,
        }),
      )
      .execute();
  }

  /**
   * Upsert một YCCĐ theo id giữ nguyên (bản cài gói): cập nhật nội dung tại chỗ — các gắn chuẩn cũ trỏ theo id nên
   * không bị sửa ngược. `tenantId` không nằm trong gói và không được chạm: dòng cài luôn là YCCĐ chuẩn platform
   * (tenantId NULL); dòng derived của trung tâm có id khác nên không bao giờ va vào upsert này.
   */
  async upsertLearningOutcome(input: {
    id: string;
    nodeId: string;
    position: number;
    content: unknown;
    sourceKind: string;
    sourceRef: string | null;
  }): Promise<void> {
    await this.db
      .insertInto('learning_outcomes')
      .values({
        id: input.id,
        node_id: input.nodeId,
        position: input.position,
        content: jsonbColumn<LearningOutcomeDoc>(input.content),
        source_kind: input.sourceKind,
        source_ref: input.sourceRef,
      })
      .onConflict((oc) =>
        oc.column('id').doUpdateSet({
          node_id: input.nodeId,
          position: input.position,
          content: jsonbColumn<LearningOutcomeDoc>(input.content),
          source_kind: input.sourceKind,
          source_ref: input.sourceRef,
        }),
      )
      .execute();
  }

  async recordOutcomePackageInstall(input: {
    packageVersion: string;
    previousVersion: string | null;
    scope: string;
    installedBy: string | null;
  }): Promise<OutcomePackageInstallRow> {
    const [row] = await this.db
      .insertInto('outcome_package_installs')
      .values({
        package_version: input.packageVersion,
        previous_version: input.previousVersion,
        scope: input.scope,
        installed_by: input.installedBy,
      })
      .onConflict((oc) =>
        oc.column('package_version').doUpdateSet({
          previous_version: input.previousVersion,
          scope: input.scope,
          installed_by: input.installedBy,
          installed_at: new Date(),
        }),
      )
      .returning(OUTCOME_PACKAGE_INSTALL_COLUMNS)
      .execute();
    if (!row) throw new Error('system bank: failed to record outcome package install');
    return row;
  }
}
