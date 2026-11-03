/**
 * Repository của cụm HỌC LIỆU trên Kysely — chuyển đổi từ repository bộ máy cũ của module content phía `apps/api`
 * (issue #646, nhóm G6 của chiến dịch chuyển tầng dữ liệu sang Kysely).
 *
 * Vai trò: mọi truy cập tới các bảng học liệu của kho trung tâm (pages — trang đọc mod_page, resources — tài nguyên
 * mod_resource, course_last_access — dấu vết mở học gần nhất, course_module_progress — tiến độ xem theo mục,
 * learning_records — hồ sơ hoàn thành) tập trung ở đây. Repository không chứa luật nghiệp vụ — tính "percent chỉ tăng",
 * cổng truy cập và tổng hợp tiến độ nằm ở tầng service phía ứng dụng. Repository này không mở transaction nào (mọi
 * phương thức chạy trên handle gốc), đúng bề mặt của repository cũ.
 *
 * Khuôn hình dạng dữ liệu ở biên repository (theo playbook chuyển đổi của package, đúc từ pilot kho control plane):
 * - Bên trong repository, truy vấn viết trên interface schema sinh bằng codegen: tên bảng/cột giữ nguyên snake_case của
 *   database (riêng bảng pages/resources, cột khoá ngoại tên là `course` trong database nên hình dạng camelCase phía
 *   tiêu thụ cũng giữ nguyên tên `course`).
 * - Bên ngoài, repository trả/nhận các hình dạng dòng camelCase như phía tiêu thụ đang dùng (khớp
 *   `$inferSelect`/`$inferInsert` của repository cũ trước chuyển đổi): cột được chọn bằng danh sách tường minh có bí
 *   danh, giá trị ghi được ánh xạ từng trường ở các hàm `*InsertValues`/`*UpdateSet` trong file này.
 * - Giá trị `undefined` bị LOẠI khỏi values/set trước khi gọi builder (quy tắc T-4 đã chốt ở pilot); mọi giá trị where
 *   là tham số bắt buộc.
 * - `onConflict` theo khoá phức hợp: last-access neo cặp (user, course), module-progress neo cặp (user, cm),
 *   learning-records `doNothing()` trên cặp (user, cm) — lần hoàn thành đầu tiên là bản ghi duy nhất.
 * - Hai đường đọc giữ nguyên hành vi lọc tại tầng JS của repository cũ: `getLastAccess` đọc theo user rồi khớp course
 *   trong bộ nhớ, `getModuleProgress` đọc theo cm rồi đối chiếu user trong bộ nhớ — không đổi thành điều kiện SQL để
 *   hành vi (và thứ tự dòng đầu tiên) giữ nguyên.
 */
import type { InsertObject, UpdateObject } from 'kysely';
import type { CentreDb, CentreDB } from '../client';

export interface PageRow {
  id: string;
  course: string;
  cmId: string | null;
  name: string;
  content: string;
  createdAt: Date;
  updatedAt: Date;
}

export interface NewPage {
  id?: string;
  course: string;
  cmId?: string | null;
  name: string;
  content?: string;
  createdAt?: Date;
  updatedAt?: Date;
}

export interface ResourceRow {
  id: string;
  course: string;
  cmId: string | null;
  name: string;
  kind: string;
  fileId: string | null;
  url: string | null;
  transcript: string | null;
  createdAt: Date;
  updatedAt: Date;
}

export interface NewResource {
  id?: string;
  course: string;
  cmId?: string | null;
  name: string;
  kind?: string;
  fileId?: string | null;
  url?: string | null;
  transcript?: string | null;
  createdAt?: Date;
  updatedAt?: Date;
}

export interface LastAccessRow {
  id: string;
  userId: string;
  courseId: string;
  cmId: string;
  accessedAt: Date;
}

export interface ModuleProgressRow {
  id: string;
  userId: string;
  courseId: string;
  cmId: string;
  percent: number;
  positionSeconds: number | null;
  page: number | null;
  updatedAt: Date;
}

export interface LearningRecordRow {
  id: string;
  userId: string;
  courseId: string;
  cmId: string;
  cmName: string;
  component: string;
  completedAt: Date;
}

export interface NewLearningRecord {
  id?: string;
  userId: string;
  courseId: string;
  cmId: string;
  cmName?: string;
  component?: string;
  completedAt?: Date;
}

const PAGE_COLUMNS = [
  'id',
  'course',
  'cm_id as cmId',
  'name',
  'content',
  'created_at as createdAt',
  'updated_at as updatedAt',
] as const;

const RESOURCE_COLUMNS = [
  'id',
  'course',
  'cm_id as cmId',
  'name',
  'kind',
  'file_id as fileId',
  'url',
  'transcript',
  'created_at as createdAt',
  'updated_at as updatedAt',
] as const;

const LAST_ACCESS_COLUMNS = [
  'id',
  'user_id as userId',
  'course_id as courseId',
  'cm_id as cmId',
  'accessed_at as accessedAt',
] as const;

const MODULE_PROGRESS_COLUMNS = [
  'id',
  'user_id as userId',
  'course_id as courseId',
  'cm_id as cmId',
  'percent',
  'position_seconds as positionSeconds',
  'page',
  'updated_at as updatedAt',
] as const;

const LEARNING_RECORD_COLUMNS = [
  'id',
  'user_id as userId',
  'course_id as courseId',
  'cm_id as cmId',
  'cm_name as cmName',
  'component',
  'completed_at as completedAt',
] as const;

function pageInsertValues(values: NewPage): InsertObject<CentreDB, 'pages'> {
  const row: InsertObject<CentreDB, 'pages'> = {
    course: values.course,
    name: values.name,
  };
  if (values.id !== undefined) row.id = values.id;
  if (values.cmId !== undefined) row.cm_id = values.cmId;
  if (values.content !== undefined) row.content = values.content;
  if (values.createdAt !== undefined) row.created_at = values.createdAt;
  if (values.updatedAt !== undefined) row.updated_at = values.updatedAt;
  return row;
}

function pageUpdateSet(patch: Partial<NewPage>): UpdateObject<CentreDB, 'pages'> {
  const set: UpdateObject<CentreDB, 'pages'> = {};
  if (patch.id !== undefined) set.id = patch.id;
  if (patch.course !== undefined) set.course = patch.course;
  if (patch.cmId !== undefined) set.cm_id = patch.cmId;
  if (patch.name !== undefined) set.name = patch.name;
  if (patch.content !== undefined) set.content = patch.content;
  if (patch.createdAt !== undefined) set.created_at = patch.createdAt;
  if (patch.updatedAt !== undefined) set.updated_at = patch.updatedAt;
  return set;
}

function resourceInsertValues(values: NewResource): InsertObject<CentreDB, 'resources'> {
  const row: InsertObject<CentreDB, 'resources'> = {
    course: values.course,
    name: values.name,
  };
  if (values.id !== undefined) row.id = values.id;
  if (values.cmId !== undefined) row.cm_id = values.cmId;
  if (values.kind !== undefined) row.kind = values.kind;
  if (values.fileId !== undefined) row.file_id = values.fileId;
  if (values.url !== undefined) row.url = values.url;
  if (values.transcript !== undefined) row.transcript = values.transcript;
  if (values.createdAt !== undefined) row.created_at = values.createdAt;
  if (values.updatedAt !== undefined) row.updated_at = values.updatedAt;
  return row;
}

function resourceUpdateSet(patch: Partial<NewResource>): UpdateObject<CentreDB, 'resources'> {
  const set: UpdateObject<CentreDB, 'resources'> = {};
  if (patch.id !== undefined) set.id = patch.id;
  if (patch.course !== undefined) set.course = patch.course;
  if (patch.cmId !== undefined) set.cm_id = patch.cmId;
  if (patch.name !== undefined) set.name = patch.name;
  if (patch.kind !== undefined) set.kind = patch.kind;
  if (patch.fileId !== undefined) set.file_id = patch.fileId;
  if (patch.url !== undefined) set.url = patch.url;
  if (patch.transcript !== undefined) set.transcript = patch.transcript;
  if (patch.createdAt !== undefined) set.created_at = patch.createdAt;
  if (patch.updatedAt !== undefined) set.updated_at = patch.updatedAt;
  return set;
}

function learningRecordInsertValues(values: NewLearningRecord): InsertObject<CentreDB, 'learning_records'> {
  const row: InsertObject<CentreDB, 'learning_records'> = {
    user_id: values.userId,
    course_id: values.courseId,
    cm_id: values.cmId,
  };
  if (values.id !== undefined) row.id = values.id;
  if (values.cmName !== undefined) row.cm_name = values.cmName;
  if (values.component !== undefined) row.component = values.component;
  if (values.completedAt !== undefined) row.completed_at = values.completedAt;
  return row;
}

export class ContentRepository {
  constructor(private readonly handle: CentreDb) {}

  private db(): CentreDb {
    return this.handle;
  }

  get dbHandle(): CentreDb {
    return this.db();
  }

  async insertPage(v: NewPage): Promise<PageRow> {
    const rows = await this.db()
      .insertInto('pages')
      .values(pageInsertValues(v))
      .returning(PAGE_COLUMNS)
      .execute();
    return rows[0]!;
  }

  async getPage(id: string): Promise<PageRow | undefined> {
    const row = await this.db()
      .selectFrom('pages')
      .select(PAGE_COLUMNS)
      .where('id', '=', id)
      .executeTakeFirst();
    return row;
  }

  async listPages(courseId: string): Promise<PageRow[]> {
    return this.db()
      .selectFrom('pages')
      .select(PAGE_COLUMNS)
      .where('course', '=', courseId)
      .orderBy('id', 'asc')
      .execute();
  }

  async updatePage(id: string, v: Partial<NewPage>): Promise<void> {
    await this.db()
      .updateTable('pages')
      .set({ ...pageUpdateSet(v), updated_at: new Date() })
      .where('id', '=', id)
      .execute();
  }

  async deletePage(id: string): Promise<void> {
    await this.db().deleteFrom('pages').where('id', '=', id).execute();
  }

  async deletePageByCm(cmId: string): Promise<void> {
    await this.db().deleteFrom('pages').where('cm_id', '=', cmId).execute();
  }

  async insertResource(v: NewResource): Promise<ResourceRow> {
    const rows = await this.db()
      .insertInto('resources')
      .values(resourceInsertValues(v))
      .returning(RESOURCE_COLUMNS)
      .execute();
    return rows[0]!;
  }

  async getResource(id: string): Promise<ResourceRow | undefined> {
    const row = await this.db()
      .selectFrom('resources')
      .select(RESOURCE_COLUMNS)
      .where('id', '=', id)
      .executeTakeFirst();
    return row;
  }

  async listResources(courseId: string): Promise<ResourceRow[]> {
    return this.db()
      .selectFrom('resources')
      .select(RESOURCE_COLUMNS)
      .where('course', '=', courseId)
      .orderBy('id', 'asc')
      .execute();
  }

  async updateResource(id: string, v: Partial<NewResource>): Promise<void> {
    await this.db()
      .updateTable('resources')
      .set({ ...resourceUpdateSet(v), updated_at: new Date() })
      .where('id', '=', id)
      .execute();
  }

  async deleteResource(id: string): Promise<void> {
    await this.db().deleteFrom('resources').where('id', '=', id).execute();
  }

  async deleteResourceByCm(cmId: string): Promise<void> {
    await this.db().deleteFrom('resources').where('cm_id', '=', cmId).execute();
  }

  async upsertLastAccess(userId: string, courseId: string, cmId: string): Promise<LastAccessRow> {
    const rows = await this.db()
      .insertInto('course_last_access')
      .values({ user_id: userId, course_id: courseId, cm_id: cmId, accessed_at: new Date() })
      .onConflict((oc) =>
        oc.columns(['user_id', 'course_id']).doUpdateSet({ cm_id: cmId, accessed_at: new Date() }),
      )
      .returning(LAST_ACCESS_COLUMNS)
      .execute();
    return rows[0]!;
  }

  /** Đọc dấu vết mở học gần nhất của một học sinh trong một khoá (khớp course trong bộ nhớ như repository cũ). */
  async getLastAccess(userId: string, courseId: string): Promise<LastAccessRow | undefined> {
    const rows = await this.db()
      .selectFrom('course_last_access')
      .select(LAST_ACCESS_COLUMNS)
      .where('user_id', '=', userId)
      .execute();
    return rows.find((r) => r.courseId === courseId);
  }

  /**
   * Ghi tiến độ xem một mục: percent chỉ TĂNG (giữ giá trị cao nhất đã đạt — ôn lại không bị phạt), vị trí dở dang lấy
   * theo lần báo mới nhất.
   */
  async upsertModuleProgress(
    userId: string,
    courseId: string,
    cmId: string,
    patch: { percent?: number; positionSeconds?: number; page?: number },
  ): Promise<ModuleProgressRow> {
    const existing = await this.getModuleProgress(userId, cmId);
    const percent = Math.max(existing?.percent ?? 0, patch.percent ?? 0);
    const positionSeconds = patch.positionSeconds ?? existing?.positionSeconds ?? null;
    const page = patch.page ?? existing?.page ?? null;
    const rows = await this.db()
      .insertInto('course_module_progress')
      .values({
        user_id: userId,
        course_id: courseId,
        cm_id: cmId,
        percent,
        position_seconds: positionSeconds,
        page,
        updated_at: new Date(),
      })
      .onConflict((oc) =>
        oc.columns(['user_id', 'cm_id']).doUpdateSet({
          percent,
          position_seconds: positionSeconds,
          page,
          updated_at: new Date(),
        }),
      )
      .returning(MODULE_PROGRESS_COLUMNS)
      .execute();
    return rows[0]!;
  }

  async getModuleProgress(userId: string, cmId: string): Promise<ModuleProgressRow | undefined> {
    const row = await this.db()
      .selectFrom('course_module_progress')
      .select(MODULE_PROGRESS_COLUMNS)
      .where('cm_id', '=', cmId)
      .executeTakeFirst();
    return row && row.userId === userId ? row : undefined;
  }

  /**
   * Ghi bản ghi hồ sơ hoàn thành (chụp tên mục tại thời điểm hoàn thành). Idempotent: lần hoàn thành đầu tiên của mỗi
   * (HS, mục) là bản ghi duy nhất — các lần hoàn thành sau không ghi đè.
   */
  async insertLearningRecord(v: NewLearningRecord): Promise<void> {
    await this.db()
      .insertInto('learning_records')
      .values(learningRecordInsertValues(v))
      .onConflict((oc) => oc.columns(['user_id', 'cm_id']).doNothing())
      .execute();
  }

  async listLearningRecords(courseId: string): Promise<LearningRecordRow[]> {
    return this.db()
      .selectFrom('learning_records')
      .select(LEARNING_RECORD_COLUMNS)
      .where('course_id', '=', courseId)
      .execute();
  }
}
