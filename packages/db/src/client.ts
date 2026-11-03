/**
 * Khung Kysely của `@lms/db`: ba hàm dựng instance và các kiểu handle cho ba kho dữ liệu của hệ (kho trung tâm, kho
 * control plane, kho ngân hàng hệ thống).
 *
 * Mỗi kho có đúng một interface schema sinh bằng kysely-codegen từ cơ sở dữ liệu thật (thư mục `src/types/`, lệnh
 * `db:types`) và một cặp kiểu handle:
 *
 * - `XxxDb` — instance `Kysely<XxxDB>` dùng cho truy vấn thường.
 * - `XxxTx` — `Transaction<XxxDB>`, kiểu của handle được truyền vào callback của `db.transaction().execute(async (trx)
 *   => …)`; mọi repository nhận handle qua một trong hai kiểu này, không tự giữ kết nối riêng.
 *
 * Quy ước của package (chi tiết trong `README.md` và `AGENTS.md` của package): package KHÔNG tự mở pool và KHÔNG đọc
 * biến môi trường — tầng ứng dụng tạo `Pool` (đã gắn ngữ cảnh tenant, RLS…) rồi truyền vào factory. Vòng đời instance
 * đi theo vòng đời pool của ứng dụng: khi đóng ứng dụng, gọi `instance.destroy()` (hàm này đóng luôn pool đã truyền
 * vào, theo đúng hành vi của `PostgresDialect`).
 *
 * Kiểu của từng hàng suy bằng `Selectable`/`Insertable`/`Updateable` của Kysely trên interface schema, thay cho
 * `$inferSelect`/ `$inferInsert` của bộ máy cũ, ví dụ: `Selectable<CentreDB['tenants']>`.
 */
import { Kysely, PostgresDialect, type Transaction } from 'kysely';
import type { Pool } from 'pg';
import type { DB as CentreDB } from './types/centre-db';
import type { DB as ControlPlaneDB } from './types/control-plane-db';
import type { DB as SystemBankDB } from './types/system-bank-db';

export type { CentreDB, ControlPlaneDB, SystemBankDB };

export type CentreDb = Kysely<CentreDB>;

export type CentreTx = Transaction<CentreDB>;

export type ControlPlaneDb = Kysely<ControlPlaneDB>;

export type ControlPlaneTx = Transaction<ControlPlaneDB>;

export type SystemBankDb = Kysely<SystemBankDB>;

export type SystemBankTx = Transaction<SystemBankDB>;

/**
 * Dựng instance Kysely cho kho trung tâm trên pool do tầng ứng dụng cấp. Pool quyết định toàn bộ ngữ cảnh kết nối (GUC
 * tenant cho RLS, giới hạn kết nối…); factory này chỉ gắn dialect PostgreSQL.
 */
export function createCentreKysely(pool: Pool): CentreDb {
  return new Kysely<CentreDB>({ dialect: new PostgresDialect({ pool }) });
}

/**
 * Dựng instance Kysely cho kho control plane trên pool do tầng ứng dụng cấp (kho này không đi qua định tuyến tenant —
 * một pool thường).
 */
export function createControlPlaneKysely(pool: Pool): ControlPlaneDb {
  return new Kysely<ControlPlaneDB>({ dialect: new PostgresDialect({ pool }) });
}

export function createSystemBankKysely(pool: Pool): SystemBankDb {
  return new Kysely<SystemBankDB>({ dialect: new PostgresDialect({ pool }) });
}
