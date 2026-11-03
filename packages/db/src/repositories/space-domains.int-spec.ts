import { Pool } from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createControlPlaneKysely, type ControlPlaneDb } from '../client';
import { verifyStore } from '../migrate/verify';
import { buildTestDatabase, dropTestDatabase, withDatabase } from '../test-support/build-test-database';
import {
  ControlPlaneRepository,
  isSpaceDomainConflict,
  isSpacePrimaryDomainConflict,
  type NewSpaceRegistration,
} from './control-plane';

/**
 * Spec tích hợp của domain đã xác minh và issuer cố định trong sổ
 * control plane (issue #5).
 *
 * Database control plane dựng từ rỗng bằng chính bộ máy migration
 * của package (hai carrier đóng băng + các migration native, gồm
 * migration cộng bảng `space_domains` và cột `issuer_url` của
 * issue này) trên cụm PostgreSQL thật; mọi ca chạy qua repository
 * production — không mô phỏng handle.
 *
 * Các bất biến của hợp đồng được chốt tại đây:
 * - Domain là duy nhất toàn hệ: đăng ký trùng (kể cả chỉ khác chữ
 *   hoa, nhờ chuẩn hoá canonical ở repository) bị chặn, và domain
 *   của một không gian không bao giờ trỏ sang không gian khác.
 * - Mỗi không gian tối đa một domain chính; domain chính thứ hai
 *   bị chặn bởi chỉ số duy nhất riêng phần, nhận diện tách bạch
 *   với ca trùng domain.
 * - Chỉ domain `verified` mới có dòng tra cứu phân giải; domain
 *   `pending`/`disabled` vô hình với bộ phân giải. Alias và domain
 *   chính đã xác minh của cùng một không gian phân giải về cùng
 *   một issuer — issuer nằm trên dòng đăng ký của không gian.
 * - Hậu kiểm số đếm của kho sau migration đạt (7 bảng, 4 dòng
 *   nhật ký) — bằng chứng migration chạy đạt trên database thật.
 */

const adminUrl =
  process.env.LMS_DB_TEST_ADMIN_URL ??
  (process.env.DATABASE_URL ? withDatabase(process.env.DATABASE_URL, 'postgres') : undefined);

const TEST_DB = 'lms_db_issue5_domains';

const ISSUER_A = 'https://keycloak.example.test/realms/trung-tam-a';

let pool: Pool;
let db: ControlPlaneDb;
let repo: ControlPlaneRepository;
let spaceAId = '';
let spaceBId = '';

/** Bắt lỗi của một thao tác ghi để khẳng định trên chính object lỗi (mã/constraint mà Postgres ném ra). */
async function captureError(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
  } catch (err) {
    return err;
  }
  throw new Error('Thao tác được kỳ vọng ném lỗi nhưng đã chạy thành công');
}

/** Dữ kiện đăng ký không gian mẫu; slug truyền vào để các ca không giẫm nhau. */
function registration(slug: string, patch: Partial<NewSpaceRegistration> = {}): NewSpaceRegistration {
  return {
    slug,
    name: `Không gian ${slug}`,
    kind: 'center',
    status: 'active',
    databaseName: `db_${slug.replaceAll('-', '_')}`,
    storageMode: 'dedicated',
    connectionRoleRef: `role_${slug.replaceAll('-', '_')}`,
    secretRef: `env:SPACE_${slug.toUpperCase().replaceAll('-', '_')}_URL`,
    schemaVersion: 'v1',
    plan: null,
    ...patch,
  };
}

beforeAll(async () => {
  if (!adminUrl) {
    throw new Error('Thiếu kết nối quản trị cho spec domain/issuer: đặt LMS_DB_TEST_ADMIN_URL hoặc DATABASE_URL.');
  }
  const storeUrl = await buildTestDatabase({ adminUrl, database: TEST_DB, store: 'control-plane' });
  pool = new Pool({ connectionString: storeUrl });
  db = createControlPlaneKysely(pool);
  repo = new ControlPlaneRepository(db);

  const spaceA = await repo.insertSpaceIfAbsent(registration('issue5-trung-tam-a', { issuerUrl: ISSUER_A }));
  spaceAId = spaceA!.id;
  const spaceB = await repo.insertSpaceIfAbsent(registration('issue5-trung-tam-b'));
  spaceBId = spaceB!.id;
}, 120_000);

afterAll(async () => {
  if (db) await db.destroy().catch(() => undefined);
  if (pool) await pool.end().catch(() => undefined);
  if (adminUrl) await dropTestDatabase(adminUrl, TEST_DB).catch(() => undefined);
});

describe('sổ domain đã xác minh và issuer cố định', () => {
  it('hậu kiểm kho control plane sau migration đạt: 7 bảng và 4 dòng nhật ký', async () => {
    const result = await verifyStore(db, 'control-plane');
    expect(result.mismatches).toEqual([]);
    expect(result.ok).toBe(true);
    expect(result.tables).toBe(7);
    expect(result.journalRows).toBe(4);
  });

  it('dòng đăng ký mang issuer cố định khi được chốt vào sổ, và null khi realm chưa cấp phát', async () => {
    const a = await repo.findSpaceBySlug('issue5-trung-tam-a');
    expect(a?.issuerUrl).toBe(ISSUER_A);
    const b = await repo.findSpaceBySlug('issue5-trung-tam-b');
    expect(b?.issuerUrl).toBeNull();
    // Cập nhật issuer qua đường ghi của sổ (điểm cấp phát realm chốt vào).
    const patched = await repo.updateSpace(spaceBId, { issuerUrl: 'https://keycloak.example.test/realms/trung-tam-b' });
    expect(patched.issuerUrl).toBe('https://keycloak.example.test/realms/trung-tam-b');
  });

  it('domain ở trạng thái pending chưa phân giải được; chuyển verified thì domain chính và alias cùng trỏ về một issuer', async () => {
    const primary = await repo.insertDomain({ spaceId: spaceAId, domain: 'trung-tam-a.example.vn', kind: 'primary' });
    expect(primary.status).toBe('pending');
    expect(await repo.findVerifiedDomainByName('trung-tam-a.example.vn')).toBeUndefined();

    const verifiedAt = new Date();
    await repo.updateDomain(primary.id, { status: 'verified', verifiedAt });
    const alias = await repo.insertDomain({ spaceId: spaceAId, domain: 'a.example.vn', kind: 'alias', status: 'verified', verifiedAt });

    const viaPrimary = await repo.findVerifiedDomainByName('trung-tam-a.example.vn');
    expect(viaPrimary?.spaceId).toBe(spaceAId);
    expect(viaPrimary?.slug).toBe('issue5-trung-tam-a');
    expect(viaPrimary?.issuerUrl).toBe(ISSUER_A);
    expect(viaPrimary?.domainKind).toBe('primary');

    const viaAlias = await repo.findVerifiedDomainByName('a.example.vn');
    expect(viaAlias?.spaceId).toBe(spaceAId);
    expect(viaAlias?.issuerUrl).toBe(viaPrimary?.issuerUrl);
    expect(viaAlias?.domainKind).toBe('alias');
    expect(alias.verifiedAt?.toISOString()).toBe(verifiedAt.toISOString());
  });

  it('domain là duy nhất toàn hệ: đăng ký trùng cho không gian khác bị chặn, kể cả chỉ khác chữ hoa', async () => {
    const sameSpace = await captureError(repo.insertDomain({ spaceId: spaceAId, domain: 'trung-tam-a.example.vn' }));
    expect(isSpaceDomainConflict(sameSpace)).toBe(true);
    const otherSpace = await captureError(repo.insertDomain({ spaceId: spaceBId, domain: 'trung-tam-a.example.vn' }));
    expect(isSpaceDomainConflict(otherSpace)).toBe(true);
    // Dạng viết khác chữ hoa được chuẩn hoá trước khi ghi nên vẫn
    // va chạm đúng một dòng — không tạo được đăng ký song song.
    const caseVariant = await captureError(repo.insertDomain({ spaceId: spaceBId, domain: 'TRUNG-TAM-A.EXAMPLE.VN' }));
    expect(isSpaceDomainConflict(caseVariant)).toBe(true);
    // Domain vẫn thuộc về không gian A sau mọi lần thử chiếm.
    const owner = await repo.findDomainByName('trung-tam-a.example.vn');
    expect(owner?.spaceId).toBe(spaceAId);
  });

  it('mỗi không gian tối đa một domain chính: domain chính thứ hai bị chặn và nhận diện tách bạch với trùng domain', async () => {
    const err = await captureError(repo.insertDomain({ spaceId: spaceAId, domain: 'chinh-thu-hai.example.vn', kind: 'primary' }));
    expect(isSpacePrimaryDomainConflict(err)).toBe(true);
    expect(isSpaceDomainConflict(err)).toBe(false);
    // Không gian khác vẫn đăng ký được domain chính của riêng nó.
    const ownPrimary = await repo.insertDomain({ spaceId: spaceBId, domain: 'trung-tam-b.example.vn', kind: 'primary' });
    expect(ownPrimary.kind).toBe('primary');
  });

  it('tra cứu theo tên chuẩn hoá chữ hoa và dấu chấm cuối về đúng dòng đã đăng ký', async () => {
    const found = await repo.findDomainByName('TRUNG-TAM-A.EXAMPLE.VN.');
    expect(found?.spaceId).toBe(spaceAId);
    const listed = await repo.listDomainsForSpace(spaceAId);
    expect(listed.map((d) => d.domain)).toEqual(['a.example.vn', 'trung-tam-a.example.vn']);
  });

  it('domain bị tắt (disabled) thì không còn dòng tra cứu phân giải', async () => {
    const alias = await repo.findDomainByName('a.example.vn');
    expect(alias).toBeDefined();
    await repo.updateDomain(alias!.id, { status: 'disabled' });
    expect(await repo.findVerifiedDomainByName('a.example.vn')).toBeUndefined();
    // Domain chính không bị ảnh hưởng bởi việc tắt alias.
    expect((await repo.findVerifiedDomainByName('trung-tam-a.example.vn'))?.spaceId).toBe(spaceAId);
  });
});
