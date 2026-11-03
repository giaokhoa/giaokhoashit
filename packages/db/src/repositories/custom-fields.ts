/**
 * Repository của cụm CUSTOM FIELDS trên Kysely — chuyển đổi bộ máy cũ → Kysely thuộc nhóm G8 của chiến dịch chuyển tầng
 * dữ liệu (issue #648).
 *
 * Vai trò: mọi truy cập tới ba bảng custom_field_category, custom_field_field và custom_field_data của kho trung tâm
 * tập trung ở đây. Repository không chứa luật nghiệp vụ — hình dạng field theo component/area và lắp giá trị cho
 * instance nằm ở tầng service phía ứng dụng.
 *
 * Lưu ý giữ nguyên hành vi: hai đường xoá nhiều bảng (`deleteCategory`, `deleteField`) chạy TUẦN TỰ từng lệnh xoá như
 * bản bộ máy cũ — repository gốc không bọc transaction, và bản chuyển đổi cũng KHÔNG tự ý bọc transaction mới (đổi tính
 * nguyên tử là đổi nghiệp vụ, ngoài phạm vi chuyển ORM). Thứ tự xoá giữ đúng: dữ liệu của từng field → các field →
 * category.
 *
 * Khuôn hình dạng ở biên: tên cột snake_case của schema codegen ở bên trong, hình dạng camelCase ở bên ngoài qua danh
 * sách cột có bí danh tường minh và các hàm ánh xạ `*InsertValues` loại `undefined` từng trường (luật T-4). Cột jsonb
 * `config_data` khi ghi đi qua `jsonbValue` (serialize tường minh + `::jsonb`).
 */
import { sql, type Insertable, type InsertObject, type RawBuilder } from 'kysely';
import type { CentreDb, CentreTx, CentreDB } from '../client';
import type { JsonValue } from '../types/centre-db';

export type CustomFieldsDb = CentreDb | CentreTx;

export interface CategoryRow {
  id: string;
  name: string;
  component: string;
  area: string;
  itemId: string | null;
  sortOrder: number;
  createdAt: Date;
  updatedAt: Date;
}

export interface FieldRow {
  id: string;
  categoryId: string;
  shortName: string;
  name: string;
  type: string;
  configData: unknown;
  sortOrder: number;
  createdAt: Date;
  updatedAt: Date;
}

export interface DataRow {
  id: string;
  fieldId: string;
  instanceId: string;
  value: string | null;
  valueFormat: number;
  createdAt: Date;
  updatedAt: Date;
}

export interface NewCategory {
  id?: string;
  name: string;
  component: string;
  area: string;
  itemId?: string | null;
  sortOrder?: number;
  createdAt?: Date;
  updatedAt?: Date;
}

export interface NewField {
  id?: string;
  categoryId: string;
  shortName: string;
  name: string;
  type: string;
  configData?: unknown;
  sortOrder?: number;
  createdAt?: Date;
  updatedAt?: Date;
}

/**
 * Bọc một giá trị jsonb thành biểu thức tham số JSON tường minh (serialize + `::jsonb`) — xem lý do ở phần đầu file.
 */
function jsonbValue(value: unknown): RawBuilder<JsonValue> {
  return sql<JsonValue>`${JSON.stringify(value)}::jsonb`;
}

const CATEGORY_COLUMNS = [
  'id',
  'name',
  'component',
  'area',
  'item_id as itemId',
  'sort_order as sortOrder',
  'created_at as createdAt',
  'updated_at as updatedAt',
] as const;

const FIELD_COLUMNS = [
  'id',
  'category_id as categoryId',
  'short_name as shortName',
  'name',
  'type',
  'config_data as configData',
  'sort_order as sortOrder',
  'created_at as createdAt',
  'updated_at as updatedAt',
] as const;

const DATA_COLUMNS = [
  'id',
  'field_id as fieldId',
  'instance_id as instanceId',
  'value',
  'value_format as valueFormat',
  'created_at as createdAt',
  'updated_at as updatedAt',
] as const;

function categoryInsertValues(values: NewCategory): Insertable<CentreDB['custom_field_category']> {
  const row: Insertable<CentreDB['custom_field_category']> = {
    name: values.name,
    component: values.component,
    area: values.area,
  };
  if (values.id !== undefined) row.id = values.id;
  if (values.itemId !== undefined) row.item_id = values.itemId;
  if (values.sortOrder !== undefined) row.sort_order = values.sortOrder;
  if (values.createdAt !== undefined) row.created_at = values.createdAt;
  if (values.updatedAt !== undefined) row.updated_at = values.updatedAt;
  return row;
}

function fieldInsertValues(values: NewField): InsertObject<CentreDB, 'custom_field_field'> {
  const row: InsertObject<CentreDB, 'custom_field_field'> = {
    category_id: values.categoryId,
    short_name: values.shortName,
    name: values.name,
    type: values.type,
  };
  if (values.id !== undefined) row.id = values.id;
  if (values.configData !== undefined) row.config_data = jsonbValue(values.configData);
  if (values.sortOrder !== undefined) row.sort_order = values.sortOrder;
  if (values.createdAt !== undefined) row.created_at = values.createdAt;
  if (values.updatedAt !== undefined) row.updated_at = values.updatedAt;
  return row;
}

export class CustomFieldsRepository {
  constructor(private readonly handle: CentreDb) {}

  async insertCategory(v: NewCategory, db: CustomFieldsDb = this.handle): Promise<CategoryRow> {
    const rows = await db
      .insertInto('custom_field_category')
      .values(categoryInsertValues(v))
      .returning(CATEGORY_COLUMNS)
      .execute();
    return rows[0]!;
  }

  async listCategories(component: string, area: string, db: CustomFieldsDb = this.handle): Promise<CategoryRow[]> {
    return db
      .selectFrom('custom_field_category')
      .select(CATEGORY_COLUMNS)
      .where('component', '=', component)
      .where('area', '=', area)
      .orderBy('sort_order')
      .execute();
  }

  async getCategory(id: string, db: CustomFieldsDb = this.handle): Promise<CategoryRow | undefined> {
    return db
      .selectFrom('custom_field_category')
      .select(CATEGORY_COLUMNS)
      .where('id', '=', id)
      .executeTakeFirst();
  }

  /**
   * Xoá category: xoá dữ liệu của từng field trước, rồi các field, rồi chính category — tuần tự, không transaction (xem
   * đầu file).
   */
  async deleteCategory(id: string, db: CustomFieldsDb = this.handle): Promise<void> {
    const fields = await this.listFieldsByCategory(id, db);
    for (const f of fields) {
      await db.deleteFrom('custom_field_data').where('field_id', '=', f.id).execute();
    }
    await db.deleteFrom('custom_field_field').where('category_id', '=', id).execute();
    await db.deleteFrom('custom_field_category').where('id', '=', id).execute();
  }

  async insertField(v: NewField, db: CustomFieldsDb = this.handle): Promise<FieldRow> {
    const rows = await db
      .insertInto('custom_field_field')
      .values(fieldInsertValues(v))
      .returning(FIELD_COLUMNS)
      .execute();
    return rows[0]!;
  }

  async getField(id: string, db: CustomFieldsDb = this.handle): Promise<FieldRow | undefined> {
    return db
      .selectFrom('custom_field_field')
      .select(FIELD_COLUMNS)
      .where('id', '=', id)
      .executeTakeFirst();
  }

  async listFieldsByCategory(categoryId: string, db: CustomFieldsDb = this.handle): Promise<FieldRow[]> {
    return db
      .selectFrom('custom_field_field')
      .select(FIELD_COLUMNS)
      .where('category_id', '=', categoryId)
      .orderBy('sort_order')
      .execute();
  }

  /**
   * Xoá field: xoá dữ liệu của field trước, rồi chính field — tuần tự, không transaction (xem đầu file).
   */
  async deleteField(id: string, db: CustomFieldsDb = this.handle): Promise<void> {
    await db.deleteFrom('custom_field_data').where('field_id', '=', id).execute();
    await db.deleteFrom('custom_field_field').where('id', '=', id).execute();
  }

  async upsertData(fieldId: string, instanceId: string, value: string | null, db: CustomFieldsDb = this.handle): Promise<void> {
    await db
      .insertInto('custom_field_data')
      .values({ field_id: fieldId, instance_id: instanceId, value })
      .onConflict((oc) => oc
        .columns(['field_id', 'instance_id'])
        .doUpdateSet({ value, updated_at: new Date() }))
      .execute();
  }

  async getDataForInstance(fieldIds: string[], instanceId: string, db: CustomFieldsDb = this.handle): Promise<DataRow[]> {
    if (fieldIds.length === 0) return [];
    return db
      .selectFrom('custom_field_data')
      .select(DATA_COLUMNS)
      .where('instance_id', '=', instanceId)
      .execute();
  }

  /**
   * Dữ liệu của NHIỀU instance trong một truy vấn (DL-07, issue #489): bản theo lô của `getDataForInstance` — cùng điều
   * kiện lọc hiệu dụng của đường lẻ (mọi dòng dữ liệu của các instance; tập field do tầng service đối chiếu khi lắp giá
   * trị).
   */
  async getDataForInstances(fieldIds: string[], instanceIds: string[], db: CustomFieldsDb = this.handle): Promise<DataRow[]> {
    if (fieldIds.length === 0 || instanceIds.length === 0) return [];
    return db
      .selectFrom('custom_field_data')
      .select(DATA_COLUMNS)
      .where('instance_id', 'in', instanceIds)
      .execute();
  }
}
