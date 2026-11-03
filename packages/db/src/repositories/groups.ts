import type { CentreDb, CentreTx } from '../client';

/**
 * Kho dữ liệu nhóm trên Kysely (issue #645, nhóm G5 của chiến dịch chuyển tầng dữ liệu sang Kysely): TOÀN BỘ bề mặt của
 * `GroupsRepository` cũ ở `apps/api` được chuyển nguyên vẹn sang đây — group, grouping, thành viên nhóm và cohort (nhóm
 * hệ thống/danh mục).
 *
 * Khuôn truy vấn theo đúng pilot kho control plane (issue #640):
 * - Cột đọc khai tường minh kèm bí danh camelCase dùng chung cho select và returning — KHÔNG bật CamelCasePlugin.
 * - Ghi qua hàm ánh xạ Insert/Update loại `undefined` tường minh từng trường (thử nghiệm T-4).
 * - Khoá chính `uuidv7()` do database sinh (phụ lục U): insert bỏ qua `id` và lấy id qua `returning`.
 * - Ba điểm `onConflictDoNothing` của bản cũ trước chuyển đổi (thêm thành viên nhóm, đưa nhóm vào grouping, thêm thành
 *   viên cohort) dùng `onConflict … doNothing()` tương đương; riêng `addCohortMember` suy luận "đã chèn thật hay chưa"
 *   từ độ dài của `returning` như bản cũ.
 *
 * `removeCohortMember` nhận handle tuỳ chọn (mặc định handle thường): đường gỡ thành viên TRONG transaction của bên gọi
 * (settle hoàn tiền — điểm nối C-8/C, đã đóng ở #656) truyền transaction của bên gọi vào để việc gỡ và ghi sổ hoàn
 * commit cùng nhau.
 *
 * Bề mặt kiểu dòng giữ đúng hình dạng `$inferSelect` của repository cũ để phía tiêu thụ trong module groups không đổi
 * hợp đồng.
 */

export type GroupsDb = CentreDb | CentreTx;
export type GroupsTx = CentreTx;

export interface GroupRow {
  id: string;
  courseId: string;
  name: string;
  idNumber: string;
  description: string;
  enrolmentKey: string | null;
  createdAt: Date;
  updatedAt: Date;
}

export interface GroupMemberRow {
  id: string;
  groupId: string;
  userId: string;
  timeAdded: Date;
  component: string;
  itemId: string | null;
}

export interface GroupingRow {
  id: string;
  courseId: string;
  name: string;
  idNumber: string;
  description: string;
  createdAt: Date;
  updatedAt: Date;
}

export interface CohortRow {
  id: string;
  tenantId: string;
  name: string;
  idNumber: string;
  description: string;
  contextId: string;
  component: string;
  visible: boolean;
  createdAt: Date;
  updatedAt: Date;
}

export interface CohortMemberRow {
  cohortId: string;
  userId: string;
  timeAdded: Date;
}

export interface NewGroup {
  id?: string;
  courseId: string;
  name: string;
  idNumber?: string;
  description?: string;
  enrolmentKey?: string | null;
}

export interface NewGrouping {
  id?: string;
  courseId: string;
  name: string;
  idNumber?: string;
  description?: string;
}

export interface NewCohort {
  id?: string;
  tenantId: string;
  name: string;
  idNumber?: string;
  description?: string;
  contextId: string;
  component?: string;
  visible?: boolean;
}

const GROUP_COLUMNS = [
  'id',
  'course_id as courseId',
  'name',
  'id_number as idNumber',
  'description',
  'enrolment_key as enrolmentKey',
  'created_at as createdAt',
  'updated_at as updatedAt',
] as const;

const GROUP_MEMBER_COLUMNS = [
  'id',
  'group_id as groupId',
  'user_id as userId',
  'time_added as timeAdded',
  'component',
  'item_id as itemId',
] as const;

const GROUPING_COLUMNS = [
  'id',
  'course_id as courseId',
  'name',
  'id_number as idNumber',
  'description',
  'created_at as createdAt',
  'updated_at as updatedAt',
] as const;

const COHORT_COLUMNS = [
  'id',
  'tenant_id as tenantId',
  'name',
  'id_number as idNumber',
  'description',
  'context_id as contextId',
  'component',
  'visible',
  'created_at as createdAt',
  'updated_at as updatedAt',
] as const;

const COHORT_MEMBER_COLUMNS = ['cohort_id as cohortId', 'user_id as userId', 'time_added as timeAdded'] as const;

function groupInsertValues(v: NewGroup) {
  return {
    ...(v.id !== undefined ? { id: v.id } : {}),
    course_id: v.courseId,
    name: v.name,
    ...(v.idNumber !== undefined ? { id_number: v.idNumber } : {}),
    ...(v.description !== undefined ? { description: v.description } : {}),
    ...(v.enrolmentKey !== undefined ? { enrolment_key: v.enrolmentKey } : {}),
  };
}

function groupUpdateValues(v: Partial<NewGroup>) {
  return {
    ...(v.courseId !== undefined ? { course_id: v.courseId } : {}),
    ...(v.name !== undefined ? { name: v.name } : {}),
    ...(v.idNumber !== undefined ? { id_number: v.idNumber } : {}),
    ...(v.description !== undefined ? { description: v.description } : {}),
    ...(v.enrolmentKey !== undefined ? { enrolment_key: v.enrolmentKey } : {}),
  };
}

function groupingInsertValues(v: NewGrouping) {
  return {
    ...(v.id !== undefined ? { id: v.id } : {}),
    course_id: v.courseId,
    name: v.name,
    ...(v.idNumber !== undefined ? { id_number: v.idNumber } : {}),
    ...(v.description !== undefined ? { description: v.description } : {}),
  };
}

function cohortInsertValues(v: NewCohort) {
  return {
    ...(v.id !== undefined ? { id: v.id } : {}),
    tenant_id: v.tenantId,
    name: v.name,
    ...(v.idNumber !== undefined ? { id_number: v.idNumber } : {}),
    ...(v.description !== undefined ? { description: v.description } : {}),
    context_id: v.contextId,
    ...(v.component !== undefined ? { component: v.component } : {}),
    ...(v.visible !== undefined ? { visible: v.visible } : {}),
  };
}

function cohortUpdateValues(v: Partial<NewCohort>) {
  return {
    ...(v.tenantId !== undefined ? { tenant_id: v.tenantId } : {}),
    ...(v.name !== undefined ? { name: v.name } : {}),
    ...(v.idNumber !== undefined ? { id_number: v.idNumber } : {}),
    ...(v.description !== undefined ? { description: v.description } : {}),
    ...(v.contextId !== undefined ? { context_id: v.contextId } : {}),
    ...(v.component !== undefined ? { component: v.component } : {}),
    ...(v.visible !== undefined ? { visible: v.visible } : {}),
  };
}

export class GroupsRepository {
  constructor(private readonly handle: CentreDb) {}

  async transaction<T>(fn: (tx: GroupsTx) => Promise<T>): Promise<T> {
    return this.handle.transaction().execute(fn);
  }

  async insertGroup(v: NewGroup): Promise<GroupRow> {
    return this.handle
      .insertInto('course_groups')
      .values(groupInsertValues(v))
      .returning(GROUP_COLUMNS)
      .executeTakeFirstOrThrow() as Promise<GroupRow>;
  }

  async getGroup(id: string): Promise<GroupRow | undefined> {
    return this.handle
      .selectFrom('course_groups')
      .select(GROUP_COLUMNS)
      .where('id', '=', id)
      .executeTakeFirst() as Promise<GroupRow | undefined>;
  }

  async listGroups(courseId: string): Promise<GroupRow[]> {
    return this.handle
      .selectFrom('course_groups')
      .select(GROUP_COLUMNS)
      .where('course_id', '=', courseId)
      .orderBy('id', 'asc')
      .execute() as Promise<GroupRow[]>;
  }

  async updateGroup(id: string, v: Partial<NewGroup>): Promise<void> {
    await this.handle
      .updateTable('course_groups')
      .set({ ...groupUpdateValues(v), updated_at: new Date() })
      .where('id', '=', id)
      .execute();
  }

  async deleteGroup(id: string): Promise<void> {
    await this.handle.deleteFrom('course_groups').where('id', '=', id).execute();
  }

  /** Thêm thành viên vào nhóm (trùng thì bỏ qua). */
  async addMember(groupId: string, userId: string, component = '', itemId: string | null = null): Promise<void> {
    await this.handle
      .insertInto('groups_members')
      .values({ group_id: groupId, user_id: userId, component, item_id: itemId })
      .onConflict((oc) => oc.doNothing())
      .execute();
  }

  async removeMember(groupId: string, userId: string): Promise<void> {
    await this.handle
      .deleteFrom('groups_members')
      .where('group_id', '=', groupId)
      .where('user_id', '=', userId)
      .execute();
  }

  async listMembers(groupId: string): Promise<GroupMemberRow[]> {
    return this.handle
      .selectFrom('groups_members')
      .select(GROUP_MEMBER_COLUMNS)
      .where('group_id', '=', groupId)
      .orderBy('id', 'asc')
      .execute() as Promise<GroupMemberRow[]>;
  }

  async listMembersOfGroups(groupIds: string[]): Promise<GroupMemberRow[]> {
    if (!groupIds.length) return [];
    return this.handle
      .selectFrom('groups_members')
      .select(GROUP_MEMBER_COLUMNS)
      .where('group_id', 'in', groupIds)
      .execute() as Promise<GroupMemberRow[]>;
  }

  async groupsOfUser(courseId: string, userId: string): Promise<GroupRow[]> {
    const memberRows = (await this.handle
      .selectFrom('groups_members')
      .select(GROUP_MEMBER_COLUMNS)
      .where('user_id', '=', userId)
      .execute()) as GroupMemberRow[];
    const ids = memberRows.map((m) => m.groupId);
    if (!ids.length) return [];
    return this.handle
      .selectFrom('course_groups')
      .select(GROUP_COLUMNS)
      .where('course_id', '=', courseId)
      .where('id', 'in', ids)
      .execute() as Promise<GroupRow[]>;
  }

  async isMember(groupId: string, userId: string): Promise<boolean> {
    const row = await this.handle
      .selectFrom('groups_members')
      .select(GROUP_MEMBER_COLUMNS)
      .where('group_id', '=', groupId)
      .where('user_id', '=', userId)
      .executeTakeFirst();
    return !!row;
  }

  async insertGrouping(v: NewGrouping): Promise<GroupingRow> {
    return this.handle
      .insertInto('groupings')
      .values(groupingInsertValues(v))
      .returning(GROUPING_COLUMNS)
      .executeTakeFirstOrThrow() as Promise<GroupingRow>;
  }

  async getGrouping(id: string): Promise<GroupingRow | undefined> {
    return this.handle
      .selectFrom('groupings')
      .select(GROUPING_COLUMNS)
      .where('id', '=', id)
      .executeTakeFirst() as Promise<GroupingRow | undefined>;
  }

  async listGroupings(courseId: string): Promise<GroupingRow[]> {
    return this.handle
      .selectFrom('groupings')
      .select(GROUPING_COLUMNS)
      .where('course_id', '=', courseId)
      .orderBy('id', 'asc')
      .execute() as Promise<GroupingRow[]>;
  }

  async deleteGrouping(id: string): Promise<void> {
    await this.handle.deleteFrom('groupings').where('id', '=', id).execute();
  }

  /** Đưa nhóm vào grouping (trùng thì bỏ qua). */
  async addGroupToGrouping(groupingId: string, groupId: string): Promise<void> {
    await this.handle
      .insertInto('groupings_groups')
      .values({ grouping_id: groupingId, group_id: groupId })
      .onConflict((oc) => oc.doNothing())
      .execute();
  }

  async removeGroupFromGrouping(groupingId: string, groupId: string): Promise<void> {
    await this.handle
      .deleteFrom('groupings_groups')
      .where('grouping_id', '=', groupingId)
      .where('group_id', '=', groupId)
      .execute();
  }

  async groupsInGrouping(groupingId: string): Promise<GroupRow[]> {
    const links = await this.handle
      .selectFrom('groupings_groups')
      .select(['grouping_id as groupingId', 'group_id as groupId', 'time_added as timeAdded'])
      .where('grouping_id', '=', groupingId)
      .execute();
    if (!links.length) return [];
    return this.handle
      .selectFrom('course_groups')
      .select(GROUP_COLUMNS)
      .where('id', 'in', links.map((l) => l.groupId))
      .execute() as Promise<GroupRow[]>;
  }

  async insertCohort(v: NewCohort, tx?: GroupsDb): Promise<CohortRow> {
    const db = tx ?? this.handle;
    return db
      .insertInto('cohort')
      .values(cohortInsertValues(v))
      .returning(COHORT_COLUMNS)
      .executeTakeFirstOrThrow() as Promise<CohortRow>;
  }

  async getCohort(id: string): Promise<CohortRow | undefined> {
    return this.handle
      .selectFrom('cohort')
      .select(COHORT_COLUMNS)
      .where('id', '=', id)
      .executeTakeFirst() as Promise<CohortRow | undefined>;
  }

  async listCohorts(): Promise<CohortRow[]> {
    return this.handle
      .selectFrom('cohort')
      .select(COHORT_COLUMNS)
      .orderBy('id', 'asc')
      .execute() as Promise<CohortRow[]>;
  }

  async updateCohort(id: string, v: Partial<NewCohort>, tx?: GroupsDb): Promise<void> {
    const db = tx ?? this.handle;
    await db
      .updateTable('cohort')
      .set({ ...cohortUpdateValues(v), updated_at: new Date() })
      .where('id', '=', id)
      .execute();
  }

  async deleteCohort(id: string): Promise<void> {
    await this.handle.deleteFrom('cohort').where('id', '=', id).execute();
  }

  /** Thêm thành viên cohort; trả `true` khi dòng thật sự được chèn (chưa là thành viên). */
  async addCohortMember(cohortId: string, userId: string): Promise<boolean> {
    const inserted = await this.handle
      .insertInto('cohort_members')
      .values({ cohort_id: cohortId, user_id: userId })
      .onConflict((oc) => oc.doNothing())
      .returning('cohort_id as cohortId')
      .execute();
    return inserted.length > 0;
  }

  /** Gỡ thành viên cohort; trả `true` khi có dòng bị xoá. */
  async removeCohortMember(cohortId: string, userId: string, db: GroupsDb = this.handle): Promise<boolean> {
    const deleted = await db
      .deleteFrom('cohort_members')
      .where('cohort_id', '=', cohortId)
      .where('user_id', '=', userId)
      .returning('cohort_id as cohortId')
      .execute();
    return deleted.length > 0;
  }

  async listCohortMembers(cohortId: string): Promise<CohortMemberRow[]> {
    return this.handle
      .selectFrom('cohort_members')
      .select(COHORT_MEMBER_COLUMNS)
      .where('cohort_id', '=', cohortId)
      .orderBy('time_added', 'asc')
      .execute() as Promise<CohortMemberRow[]>;
  }

  async isCohortMember(cohortId: string, userId: string): Promise<boolean> {
    const row = await this.handle
      .selectFrom('cohort_members')
      .select(COHORT_MEMBER_COLUMNS)
      .where('cohort_id', '=', cohortId)
      .where('user_id', '=', userId)
      .executeTakeFirst();
    return !!row;
  }
}
