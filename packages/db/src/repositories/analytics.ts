/**
 * Repository của cụm ANALYTICS trên Kysely — chuyển đổi bộ máy cũ → Kysely thuộc nhóm G8 của chiến dịch chuyển tầng dữ
 * liệu (issue #648).
 *
 * Vai trò: mọi truy cập tới ba bảng analytics_models, analytics_predictions và insights của kho trung tâm tập trung ở
 * đây. Repository không chứa luật nghiệp vụ — đồng bộ model provider, tính điểm và gác quyền nằm ở tầng service phía
 * ứng dụng; repository chỉ nhận handle do tầng ứng dụng truyền vào.
 *
 * Khuôn hình dạng dữ liệu ở biên repository (theo khuôn pilot kho control plane):
 * - Bên trong repository, truy vấn viết trên interface schema sinh bằng codegen: tên bảng/cột giữ nguyên snake_case của
 *   database.
 * - Bên ngoài, repository nhận/trả các hình dạng dòng camelCase như phía tiêu thụ đang dùng: cột được chọn bằng danh
 *   sách tường minh có bí danh (`model_id as modelId`), giá trị ghi được ánh xạ từng trường từ camelCase sang
 *   snake_case ở các hàm `*InsertValues` trong file này.
 * - Giá trị `undefined` bị LOẠI khỏi values trước khi gọi builder (luật T-4 của chiến dịch); mọi giá trị where là tham
 *   số bắt buộc, không bao giờ `undefined`.
 * - Cột jsonb (`indicators`, `data`) khi ghi đi qua `jsonbValue`: serialize tường minh + ép kiểu `::jsonb`, vì một MẢNG
 *   ở cấp cao nhất bị driver `pg` hiểu là mảng PostgreSQL thay vì JSON.
 * - Repository không import từ `apps/*`, không đọc ALS/ngữ cảnh ứng dụng, không tự mở pool, không đọc biến môi trường.
 */
import { sql, type InsertObject, type RawBuilder, type UpdateObject } from 'kysely';
import type { CentreDb, CentreTx, CentreDB } from '../client';
import type { JsonValue } from '../types/centre-db';

export type AnalyticsDb = CentreDb | CentreTx;

export interface ModelRow {
  id: string;
  name: string;
  target: string;
  /** Danh sách tên indicator của model (jsonb). */
  indicators: unknown;
  enabled: boolean;
  createdAt: Date;
}

export interface PredictionRow {
  id: string;
  modelId: string;
  userId: string;
  contextId: string;
  courseId: string | null;
  score: number;
  data: unknown | null;
  createdAt: Date;
}

export interface InsightRow {
  id: string;
  modelId: string;
  predictionId: string | null;
  userId: string;
  recipientId: string;
  contextId: string;
  courseId: string | null;
  heading: string;
  data: unknown | null;
  createdAt: Date;
}

export interface NewModel {
  id?: string;
  name: string;
  target: string;
  indicators?: unknown;
  enabled?: boolean;
  createdAt?: Date;
}

export interface NewPrediction {
  id?: string;
  modelId: string;
  userId: string;
  contextId: string;
  courseId?: string | null;
  score?: number;
  data?: unknown | null;
  createdAt?: Date;
}

export interface NewInsight {
  id?: string;
  modelId: string;
  predictionId?: string | null;
  userId: string;
  recipientId: string;
  contextId: string;
  courseId?: string | null;
  heading?: string;
  data?: unknown | null;
  createdAt?: Date;
}

/**
 * Bọc một giá trị jsonb thành biểu thức tham số JSON tường minh (serialize + `::jsonb`) — xem lý do ở phần đầu file.
 */
function jsonbValue(value: unknown): RawBuilder<JsonValue> {
  return sql<JsonValue>`${JSON.stringify(value)}::jsonb`;
}

const MODEL_COLUMNS = [
  'id',
  'name',
  'target',
  'indicators',
  'enabled',
  'created_at as createdAt',
] as const;

const PREDICTION_COLUMNS = [
  'id',
  'model_id as modelId',
  'user_id as userId',
  'context_id as contextId',
  'course_id as courseId',
  'score',
  'data',
  'created_at as createdAt',
] as const;

const INSIGHT_COLUMNS = [
  'id',
  'model_id as modelId',
  'prediction_id as predictionId',
  'user_id as userId',
  'recipient_id as recipientId',
  'context_id as contextId',
  'course_id as courseId',
  'heading',
  'data',
  'created_at as createdAt',
] as const;

function modelInsertValues(values: NewModel): InsertObject<CentreDB, 'analytics_models'> {
  const row: InsertObject<CentreDB, 'analytics_models'> = {
    name: values.name,
    target: values.target,
  };
  if (values.id !== undefined) row.id = values.id;
  if (values.indicators !== undefined) row.indicators = jsonbValue(values.indicators);
  if (values.enabled !== undefined) row.enabled = values.enabled;
  if (values.createdAt !== undefined) row.created_at = values.createdAt;
  return row;
}

function predictionInsertValues(values: NewPrediction): InsertObject<CentreDB, 'analytics_predictions'> {
  const row: InsertObject<CentreDB, 'analytics_predictions'> = {
    model_id: values.modelId,
    user_id: values.userId,
    context_id: values.contextId,
  };
  if (values.id !== undefined) row.id = values.id;
  if (values.courseId !== undefined) row.course_id = values.courseId;
  if (values.score !== undefined) row.score = values.score;
  if (values.data !== undefined) row.data = jsonbValue(values.data);
  if (values.createdAt !== undefined) row.created_at = values.createdAt;
  return row;
}

function insightInsertValues(values: NewInsight): InsertObject<CentreDB, 'insights'> {
  const row: InsertObject<CentreDB, 'insights'> = {
    model_id: values.modelId,
    user_id: values.userId,
    recipient_id: values.recipientId,
    context_id: values.contextId,
  };
  if (values.id !== undefined) row.id = values.id;
  if (values.predictionId !== undefined) row.prediction_id = values.predictionId;
  if (values.courseId !== undefined) row.course_id = values.courseId;
  if (values.heading !== undefined) row.heading = values.heading;
  if (values.data !== undefined) row.data = jsonbValue(values.data);
  if (values.createdAt !== undefined) row.created_at = values.createdAt;
  return row;
}

export class AnalyticsRepository {
  constructor(private readonly handle: CentreDb) {}

  get dbHandle(): CentreDb {
    return this.handle;
  }

  /**
   * Ghi một mô hình, trùng tên thì cập nhật target/indicators/enabled theo dữ kiện mới (upsert theo cột unique `name`).
   */
  async upsertModel(v: NewModel, db: AnalyticsDb = this.handle): Promise<ModelRow> {
    // Nhánh cập nhật khi trùng tên: target luôn lấy theo dữ kiện mới, enabled mặc định true khi dữ kiện không nêu,
    // indicators chỉ ghi khi dữ kiện có mang (loại `undefined` tường minh theo luật T-4).
    const conflictSet: UpdateObject<CentreDB, 'analytics_models'> = {
      target: v.target,
      enabled: v.enabled ?? true,
    };
    if (v.indicators !== undefined) conflictSet.indicators = jsonbValue(v.indicators);
    const rows = await db
      .insertInto('analytics_models')
      .values(modelInsertValues(v))
      .onConflict((oc) => oc.column('name').doUpdateSet(conflictSet))
      .returning(MODEL_COLUMNS)
      .execute();
    return rows[0]!;
  }

  async getModelByName(name: string, db: AnalyticsDb = this.handle): Promise<ModelRow | undefined> {
    return db
      .selectFrom('analytics_models')
      .select(MODEL_COLUMNS)
      .where('name', '=', name)
      .executeTakeFirst();
  }

  async getModel(id: string, db: AnalyticsDb = this.handle): Promise<ModelRow | undefined> {
    return db
      .selectFrom('analytics_models')
      .select(MODEL_COLUMNS)
      .where('id', '=', id)
      .executeTakeFirst();
  }

  async listModels(db: AnalyticsDb = this.handle): Promise<ModelRow[]> {
    return db.selectFrom('analytics_models').select(MODEL_COLUMNS).execute();
  }

  async insertPrediction(v: NewPrediction, db: AnalyticsDb = this.handle): Promise<PredictionRow> {
    const rows = await db
      .insertInto('analytics_predictions')
      .values(predictionInsertValues(v))
      .returning(PREDICTION_COLUMNS)
      .execute();
    return rows[0]!;
  }

  async listPredictions(db: AnalyticsDb = this.handle): Promise<PredictionRow[]> {
    return db.selectFrom('analytics_predictions').select(PREDICTION_COLUMNS).execute();
  }

  async insertInsight(v: NewInsight, db: AnalyticsDb = this.handle): Promise<InsightRow> {
    const rows = await db
      .insertInto('insights')
      .values(insightInsertValues(v))
      .returning(INSIGHT_COLUMNS)
      .execute();
    return rows[0]!;
  }

  async listInsights(db: AnalyticsDb = this.handle): Promise<InsightRow[]> {
    return db.selectFrom('insights').select(INSIGHT_COLUMNS).execute();
  }

  async listInsightsForRecipient(recipientId: string, db: AnalyticsDb = this.handle): Promise<InsightRow[]> {
    return db
      .selectFrom('insights')
      .select(INSIGHT_COLUMNS)
      .where('recipient_id', '=', recipientId)
      .execute();
  }
}
