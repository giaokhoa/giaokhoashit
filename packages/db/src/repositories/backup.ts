/**
 * Repository của cụm BACKUP trên Kysely — chuyển đổi bộ máy cũ → Kysely thuộc nhóm G8 của chiến dịch chuyển tầng dữ
 * liệu (issue #648).
 *
 * Vai trò: mọi truy cập tới ba bảng backup_jobs, backup_job_steps và backup_id_mappings của kho trung tâm tập trung ở
 * đây. Phần dữ liệu khoá học mà job sao lưu/khôi phục (contributor) nằm ở tầng service phía ứng dụng, chạy trên chính
 * handle mà repository này phơi ra qua `dbHandle`. Repository không chứa luật nghiệp vụ — cổng quyền, chế độ khôi phục
 * và ánh xạ id nằm ở service.
 *
 * Khuôn hình dạng dữ liệu ở biên repository (theo khuôn pilot kho control plane): bên trong dùng tên bảng/cột
 * snake_case của schema codegen; bên ngoài nhận/trả hình dạng camelCase qua danh sách cột có bí danh tường minh và các
 * hàm ánh xạ `*InsertValues`/`*UpdateSet` loại `undefined` từng trường (luật T-4). Các cột jsonb (`archive`, `detail`)
 * khi ghi đi qua `jsonbValue` (serialize tường minh + `::jsonb`) vì `archive` là một OBJECT lớn và `detail` cũng là
 * object — một khuôn duy nhất cho mọi giá trị jsonb, không phụ thuộc nhánh xử lý của driver.
 */
import { sql, type Insertable, type InsertObject, type RawBuilder, type UpdateObject } from 'kysely';
import type { CentreDb, CentreTx, CentreDB } from '../client';
import type { JsonValue } from '../types/centre-db';

export type BackupDb = CentreDb | CentreTx;

export interface JobRow {
  id: string;
  type: string;
  scope: string;
  courseId: string | null;
  targetCourseId: string | null;
  mode: string;
  status: string;
  anonymize: boolean;
  userId: string;
  /** Toàn bộ bản sao lưu (jsonb) — chỉ có ở job backup đã hoàn tất. */
  archive: unknown | null;
  error: string | null;
  createdAt: Date;
  timeFinished: Date | null;
}

export interface StepRow {
  id: string;
  jobId: string;
  name: string;
  status: string;
  detail: unknown | null;
  createdAt: Date;
}

export interface MappingRow {
  id: string;
  jobId: string;
  itemType: string;
  oldId: string;
  newId: string;
}

export interface NewJob {
  id?: string;
  type: string;
  scope?: string;
  courseId?: string | null;
  targetCourseId?: string | null;
  mode?: string;
  status?: string;
  anonymize?: boolean;
  userId: string;
  archive?: unknown | null;
  error?: string | null;
  createdAt?: Date;
  timeFinished?: Date | null;
}

export interface NewStep {
  id?: string;
  jobId: string;
  name: string;
  status?: string;
  detail?: unknown | null;
  createdAt?: Date;
}

export interface NewMapping {
  id?: string;
  jobId: string;
  itemType: string;
  oldId: string;
  newId: string;
}

/**
 * Bọc một giá trị jsonb thành biểu thức tham số JSON tường minh (serialize + `::jsonb`) — xem lý do ở phần đầu file.
 */
function jsonbValue(value: unknown): RawBuilder<JsonValue> {
  return sql<JsonValue>`${JSON.stringify(value)}::jsonb`;
}

const JOB_COLUMNS = [
  'id',
  'type',
  'scope',
  'course_id as courseId',
  'target_course_id as targetCourseId',
  'mode',
  'status',
  'anonymize',
  'user_id as userId',
  'archive',
  'error',
  'created_at as createdAt',
  'time_finished as timeFinished',
] as const;

const STEP_COLUMNS = [
  'id',
  'job_id as jobId',
  'name',
  'status',
  'detail',
  'created_at as createdAt',
] as const;

const MAPPING_COLUMNS = [
  'id',
  'job_id as jobId',
  'item_type as itemType',
  'old_id as oldId',
  'new_id as newId',
] as const;

function jobInsertValues(values: NewJob): InsertObject<CentreDB, 'backup_jobs'> {
  const row: InsertObject<CentreDB, 'backup_jobs'> = {
    type: values.type,
    user_id: values.userId,
  };
  if (values.id !== undefined) row.id = values.id;
  if (values.scope !== undefined) row.scope = values.scope;
  if (values.courseId !== undefined) row.course_id = values.courseId;
  if (values.targetCourseId !== undefined) row.target_course_id = values.targetCourseId;
  if (values.mode !== undefined) row.mode = values.mode;
  if (values.status !== undefined) row.status = values.status;
  if (values.anonymize !== undefined) row.anonymize = values.anonymize;
  if (values.archive !== undefined) row.archive = jsonbValue(values.archive);
  if (values.error !== undefined) row.error = values.error;
  if (values.createdAt !== undefined) row.created_at = values.createdAt;
  if (values.timeFinished !== undefined) row.time_finished = values.timeFinished;
  return row;
}

function jobUpdateSet(patch: Partial<NewJob>): UpdateObject<CentreDB, 'backup_jobs'> {
  const set: UpdateObject<CentreDB, 'backup_jobs'> = {};
  if (patch.id !== undefined) set.id = patch.id;
  if (patch.type !== undefined) set.type = patch.type;
  if (patch.scope !== undefined) set.scope = patch.scope;
  if (patch.courseId !== undefined) set.course_id = patch.courseId;
  if (patch.targetCourseId !== undefined) set.target_course_id = patch.targetCourseId;
  if (patch.mode !== undefined) set.mode = patch.mode;
  if (patch.status !== undefined) set.status = patch.status;
  if (patch.anonymize !== undefined) set.anonymize = patch.anonymize;
  if (patch.userId !== undefined) set.user_id = patch.userId;
  if (patch.archive !== undefined) set.archive = jsonbValue(patch.archive);
  if (patch.error !== undefined) set.error = patch.error;
  if (patch.createdAt !== undefined) set.created_at = patch.createdAt;
  if (patch.timeFinished !== undefined) set.time_finished = patch.timeFinished;
  return set;
}

function stepInsertValues(values: NewStep): InsertObject<CentreDB, 'backup_job_steps'> {
  const row: InsertObject<CentreDB, 'backup_job_steps'> = {
    job_id: values.jobId,
    name: values.name,
  };
  if (values.id !== undefined) row.id = values.id;
  if (values.status !== undefined) row.status = values.status;
  if (values.detail !== undefined) row.detail = jsonbValue(values.detail);
  if (values.createdAt !== undefined) row.created_at = values.createdAt;
  return row;
}

function stepUpdateSet(patch: Partial<NewStep>): UpdateObject<CentreDB, 'backup_job_steps'> {
  const set: UpdateObject<CentreDB, 'backup_job_steps'> = {};
  if (patch.id !== undefined) set.id = patch.id;
  if (patch.jobId !== undefined) set.job_id = patch.jobId;
  if (patch.name !== undefined) set.name = patch.name;
  if (patch.status !== undefined) set.status = patch.status;
  if (patch.detail !== undefined) set.detail = jsonbValue(patch.detail);
  if (patch.createdAt !== undefined) set.created_at = patch.createdAt;
  return set;
}

function mappingInsertValues(values: NewMapping): Insertable<CentreDB['backup_id_mappings']> {
  const row: Insertable<CentreDB['backup_id_mappings']> = {
    job_id: values.jobId,
    item_type: values.itemType,
    old_id: values.oldId,
    new_id: values.newId,
  };
  if (values.id !== undefined) row.id = values.id;
  return row;
}

export class BackupRepository {
  constructor(private readonly handle: CentreDb) {}

  get dbHandle(): CentreDb {
    return this.handle;
  }

  async insertJob(v: NewJob, db: BackupDb = this.handle): Promise<JobRow> {
    const rows = await db
      .insertInto('backup_jobs')
      .values(jobInsertValues(v))
      .returning(JOB_COLUMNS)
      .execute();
    return rows[0]!;
  }

  async getJob(id: string, db: BackupDb = this.handle): Promise<JobRow | undefined> {
    return db
      .selectFrom('backup_jobs')
      .select(JOB_COLUMNS)
      .where('id', '=', id)
      .executeTakeFirst();
  }

  async listJobs(db: BackupDb = this.handle): Promise<JobRow[]> {
    return db.selectFrom('backup_jobs').select(JOB_COLUMNS).execute();
  }

  async updateJob(id: string, v: Partial<NewJob>, db: BackupDb = this.handle): Promise<void> {
    await db
      .updateTable('backup_jobs')
      .set(jobUpdateSet(v))
      .where('id', '=', id)
      .execute();
  }

  async insertStep(v: NewStep, db: BackupDb = this.handle): Promise<StepRow> {
    const rows = await db
      .insertInto('backup_job_steps')
      .values(stepInsertValues(v))
      .returning(STEP_COLUMNS)
      .execute();
    return rows[0]!;
  }

  async listSteps(jobId: string, db: BackupDb = this.handle): Promise<StepRow[]> {
    return db
      .selectFrom('backup_job_steps')
      .select(STEP_COLUMNS)
      .where('job_id', '=', jobId)
      .execute();
  }

  async updateStep(id: string, v: Partial<NewStep>, db: BackupDb = this.handle): Promise<void> {
    await db
      .updateTable('backup_job_steps')
      .set(stepUpdateSet(v))
      .where('id', '=', id)
      .execute();
  }

  /**
   * Ghi một ánh xạ id; trùng bộ ba (job, loại, id cũ) thì cập nhật id mới theo dữ kiện (upsert trên ràng buộc unique
   * của bảng).
   */
  async insertMapping(v: NewMapping, db: BackupDb = this.handle): Promise<MappingRow> {
    const rows = await db
      .insertInto('backup_id_mappings')
      .values(mappingInsertValues(v))
      .onConflict((oc) => oc
        .columns(['job_id', 'item_type', 'old_id'])
        .doUpdateSet({ new_id: v.newId }))
      .returning(MAPPING_COLUMNS)
      .execute();
    return rows[0]!;
  }

  async listMappings(jobId: string, db: BackupDb = this.handle): Promise<MappingRow[]> {
    return db
      .selectFrom('backup_id_mappings')
      .select(MAPPING_COLUMNS)
      .where('job_id', '=', jobId)
      .execute();
  }

  async getMapping(jobId: string, itemType: string, oldId: string, db: BackupDb = this.handle): Promise<MappingRow | undefined> {
    return db
      .selectFrom('backup_id_mappings')
      .select(MAPPING_COLUMNS)
      .where('job_id', '=', jobId)
      .where('item_type', '=', itemType)
      .where('old_id', '=', oldId)
      .executeTakeFirst();
  }
}
