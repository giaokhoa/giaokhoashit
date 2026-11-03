import { z } from 'zod';
import { uuidIdSchema } from './ids.js';

/**
 * Hợp đồng API của CONTROL PLANE (issue #580, chương trình tenant #579):
 * sổ đăng ký không gian (trung tâm / Trang chính) mà platform dùng để
 * biết mỗi không gian đang sống ở kho dữ liệu nào, cộng registry phiên
 * bản schema của từng kho đã đăng ký.
 *
 * Ranh giới nghiệp vụ của tầng này:
 * - Sổ chỉ giữ bản ghi định tuyến tối thiểu (định danh địa chỉ, kho
 *   đích, vòng đời, gói dịch vụ) — nhận diện riêng của trung tâm (logo,
 *   thương hiệu) thuộc về dữ liệu của chính trung tâm, không ở đây.
 * - `plan` chỉ là MÃ GÓI DỊCH VỤ được lưu lại; nghiệp vụ billing
 *   (hoá đơn, thu tiền) không thuộc hợp đồng này.
 * - Mọi trường bí mật chỉ là THAM CHIẾU (`secretRef`, ví dụ
 *   `env:CONTROL_PLANE_DATABASE_URL`) — giá trị bí mật không bao giờ
 *   đi qua API hay nằm trong database.
 * - Mọi thao tác ghi yêu cầu capability quản trị platform hiện hữu
 *   (`tenant:manage` tại context hệ thống) — tầng này không dựng mô
 *   hình tài khoản quản trị riêng.
 * - Từ issue #5, sổ còn giữ hai dữ kiện của chuỗi danh tính chuẩn:
 *   issuer OIDC cố định của từng không gian (mỗi trung tâm một realm
 *   Keycloak; realm platform `giaokhoa` thuộc về không gian platform)
 *   và ánh xạ domain đã xác minh → không gian. Host của request chọn
 *   tenant/realm/issuer CHỈ qua ánh xạ này — domain chưa đăng ký
 *   hoặc chưa xác minh không chọn được tenant hay platform nào.
 */
export const SPACE_KINDS = ['center', 'platform'] as const;
export const SPACE_STATUSES = ['provisioning', 'active', 'suspended', 'closed'] as const;
export const SPACE_STORAGE_MODES = ['shared', 'dedicated'] as const;
export const SPACE_SCHEMA_RUN_STATUSES = ['running', 'success', 'failed'] as const;

/** Mẫu slug của không gian — cùng khuôn slug tenant hiện hành (định danh địa chỉ, chữ thường + gạch nối). */
const spaceSlugSchema = z.string().regex(/^[a-z0-9][a-z0-9-]{1,78}[a-z0-9]$/);

/**
 * Issuer OIDC của một realm Keycloak — URL gốc của realm, dạng
 * `https://<host>/realms/<tên realm>`. Issuer là định danh so khớp
 * TUYỆT ĐỐI khi kiểm token (claim `iss` phải bằng đúng chuỗi đã
 * đăng ký), nên hình dạng ở tầng hợp đồng là một URL http/https
 * hoàn chỉnh không chứa khoảng trắng; việc chuẩn hoá sâu hơn (dấu
 * gạch cuối, cổng) thuộc về nơi cấp phát realm khi chốt issuer vào
 * sổ, không suy diễn ở đây.
 */
export const issuerUrlSchema = z.string().min(1).max(500).regex(/^https?:\/\/\S+$/);

export const spaceRegistrationSchema = z.object({
  id: uuidIdSchema,
  slug: z.string(),
  name: z.string(),
  kind: z.enum(SPACE_KINDS),
  status: z.enum(SPACE_STATUSES),
  databaseName: z.string(),
  storageMode: z.enum(SPACE_STORAGE_MODES),
  connectionRoleRef: z.string(),
  secretRef: z.string().nullable(),
  schemaVersion: z.string().nullable(),
  plan: z.string().nullable(),
  /** Issuer OIDC cố định của không gian (issue #5); `null` khi realm chưa được cấp phát. */
  issuerUrl: issuerUrlSchema.nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type SpaceRegistration = z.infer<typeof spaceRegistrationSchema>;

/** Tạo dòng đăng ký không gian — chỉ quản trị platform. Mặc định vòng đời bắt đầu ở `provisioning`. */
export const createSpaceRegistrationSchema = z.strictObject({
  slug: spaceSlugSchema,
  name: z.string().min(1).max(200),
  kind: z.enum(SPACE_KINDS),
  status: z.enum(SPACE_STATUSES).optional(),
  databaseName: z.string().min(1).max(120),
  storageMode: z.enum(SPACE_STORAGE_MODES),
  connectionRoleRef: z.string().min(1).max(200),
  secretRef: z.string().max(200).optional(),
  schemaVersion: z.string().max(120).optional(),
  plan: z.string().max(80).optional(),
  issuerUrl: issuerUrlSchema.optional(),
});
export type CreateSpaceRegistration = z.infer<typeof createSpaceRegistrationSchema>;

/** Cập nhật dòng đăng ký: tên hiển thị trên sổ, kho đích, chế độ kho, tham chiếu kết nối/bí mật, phiên bản schema, gói. Slug và loại không gian là định danh — không sửa qua đường này. */
export const updateSpaceRegistrationSchema = z.strictObject({
  name: z.string().min(1).max(200).optional(),
  databaseName: z.string().min(1).max(120).optional(),
  storageMode: z.enum(SPACE_STORAGE_MODES).optional(),
  connectionRoleRef: z.string().min(1).max(200).optional(),
  secretRef: z.string().max(200).nullable().optional(),
  schemaVersion: z.string().max(120).nullable().optional(),
  plan: z.string().max(80).nullable().optional(),
  issuerUrl: issuerUrlSchema.nullable().optional(),
});
export type UpdateSpaceRegistration = z.infer<typeof updateSpaceRegistrationSchema>;

/** Đổi trạng thái vòng đời của không gian — thao tác riêng để lịch sử quản trị ghi đúng loại hành động. */
export const changeSpaceStatusSchema = z.strictObject({
  status: z.enum(SPACE_STATUSES),
});
export type ChangeSpaceStatus = z.infer<typeof changeSpaceStatusSchema>;

/** Một dòng lịch sử quản trị của sổ: ai đã làm gì, giá trị trước/sau ra sao. */
export const spaceRegistryHistoryEntrySchema = z.object({
  id: uuidIdSchema,
  spaceId: uuidIdSchema,
  actorId: uuidIdSchema,
  action: z.enum(['create', 'update', 'status_change']),
  before: spaceRegistrationSchema.omit({ createdAt: true, updatedAt: true }).nullable(),
  after: spaceRegistrationSchema.omit({ createdAt: true, updatedAt: true }),
  createdAt: z.string(),
});
export type SpaceRegistryHistoryEntry = z.infer<typeof spaceRegistryHistoryEntrySchema>;

/** Trạng thái schema của một kho đã đăng ký: phiên bản hiện tại + khung trạng thái sao lưu/khôi phục gần nhất. */
export const spaceSchemaRegistrySchema = z.object({
  spaceId: uuidIdSchema,
  currentVersion: z.string().nullable(),
  lastBackupStatus: z.string().nullable(),
  lastBackupAt: z.string().nullable(),
  lastRestoreStatus: z.string().nullable(),
  lastRestoreAt: z.string().nullable(),
  updatedAt: z.string(),
});
export type SpaceSchemaRegistry = z.infer<typeof spaceSchemaRegistrySchema>;

/**
 * Ghi trạng thái schema của một kho (khung trường): phiên bản hiện tại
 * và trạng thái sao lưu/khôi phục. Cơ chế tự động ghi bởi công cụ
 * fleet thuộc issue #586; ở đây dữ kiện được ghi qua API quản trị.
 * Mốc thời gian dùng `z.iso.datetime` theo chuẩn hợp đồng của repo.
 */
export const updateSpaceSchemaRegistrySchema = z.strictObject({
  currentVersion: z.string().max(120).nullable().optional(),
  lastBackupStatus: z.string().max(40).nullable().optional(),
  lastBackupAt: z.iso.datetime({ offset: true }).nullable().optional(),
  lastRestoreStatus: z.string().max(40).nullable().optional(),
  lastRestoreAt: z.iso.datetime({ offset: true }).nullable().optional(),
});
export type UpdateSpaceSchemaRegistry = z.infer<typeof updateSpaceSchemaRegistrySchema>;

/** Kết quả một bước của một lượt chạy migration — khung tối thiểu, công cụ ghi lượt (issue #586) được thêm trường riêng. */
export const spaceSchemaMigrationStepSchema = z.looseObject({
  name: z.string(),
  status: z.string(),
  detail: z.string().optional(),
});

/** Ghi một lượt chạy migration trên kho của một không gian. Lượt `success` có `toVersion` sẽ nâng phiên bản hiện tại của kho lên `toVersion`. */
export const recordSpaceSchemaMigrationRunSchema = z.strictObject({
  runId: z.string().min(1).max(120),
  fromVersion: z.string().max(120).nullable().optional(),
  toVersion: z.string().max(120).nullable().optional(),
  status: z.enum(SPACE_SCHEMA_RUN_STATUSES),
  steps: z.array(spaceSchemaMigrationStepSchema).default([]),
  finishedAt: z.iso.datetime({ offset: true }).nullable().optional(),
});
export type RecordSpaceSchemaMigrationRun = z.infer<typeof recordSpaceSchemaMigrationRunSchema>;

export const spaceSchemaMigrationRunSchema = z.object({
  id: uuidIdSchema,
  spaceId: uuidIdSchema,
  runId: z.string(),
  fromVersion: z.string().nullable(),
  toVersion: z.string().nullable(),
  status: z.enum(SPACE_SCHEMA_RUN_STATUSES),
  steps: z.array(spaceSchemaMigrationStepSchema),
  startedAt: z.string(),
  finishedAt: z.string().nullable(),
});
export type SpaceSchemaMigrationRun = z.infer<typeof spaceSchemaMigrationRunSchema>;

/* ---- Domain đã xác minh của không gian (issue #5) ----
 *
 * Ánh xạ domain → không gian là mắt xích đầu của chuỗi danh tính
 * chuẩn: Host đã xác minh → tenant → realm → issuer cố định. Các
 * bất biến nghiệp vụ mà hình dạng này mã hoá:
 * - Domain là duy nhất TOÀN HỆ: một domain không bao giờ thuộc hai
 *   không gian (chốt ở tầng dữ liệu bằng ràng buộc duy nhất; tầng
 *   hợp đồng chốt hình dạng canonical để hai cách viết của cùng một
 *   domain không lọt qua thành hai đăng ký khác nhau).
 * - Mỗi không gian có tối đa MỘT domain chính (`primary`); các
 *   domain phụ (`alias`) không giới hạn số lượng và phân giải về
 *   cùng issuer với domain chính, vì issuer nằm trên dòng đăng ký
 *   của không gian chứ không nằm trên dòng domain.
 * - Chỉ domain ở trạng thái `verified` mới được bộ phân giải chọn;
 *   `pending` là đã đăng ký nhưng chưa xác minh quyền sở hữu,
 *   `disabled` là đã tắt — cả hai đều không chọn được tenant/platform.
 */

export const SPACE_DOMAIN_KINDS = ['primary', 'alias'] as const;
export const SPACE_DOMAIN_STATUSES = ['pending', 'verified', 'disabled'] as const;

/**
 * Domain dạng canonical: chữ thường, ít nhất hai nhãn DNS phân cách
 * bằng dấu chấm, mỗi nhãn 1–63 ký tự chữ/số/gạch nối không bắt đầu
 * hay kết thúc bằng gạch nối, tổng không quá 253 ký tự. Dạng này cố
 * ý loại: chữ hoa (hai cách viết của cùng domain phải là một), cổng
 * (`host:port` không phải danh tính của domain), địa chỉ IP (bốn
 * nhãn số dạng IPv4 literal — các nhãn toàn số khớp khuôn nhãn DNS
 * nên phải chặn riêng bằng `refine`) và tên một nhãn (localhost và
 * các tên nội bộ không bao giờ là domain đã xác minh của một tenant).
 */
export const spaceDomainNameSchema = z
  .string()
  .regex(/^(?=.{4,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$/)
  .refine((value) => !/^\d{1,3}(?:\.\d{1,3}){3}$/.test(value), {
    message: 'Domain không được là địa chỉ IP',
  });

/** Một domain đã đăng ký của một không gian. */
export const spaceDomainSchema = z.object({
  id: uuidIdSchema,
  spaceId: uuidIdSchema,
  domain: spaceDomainNameSchema,
  kind: z.enum(SPACE_DOMAIN_KINDS),
  status: z.enum(SPACE_DOMAIN_STATUSES),
  /** Mốc xác minh quyền sở hữu gần nhất; `null` khi domain chưa từng được xác minh. */
  verifiedAt: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type SpaceDomain = z.infer<typeof spaceDomainSchema>;

/**
 * Đăng ký một domain cho một không gian — chỉ quản trị platform.
 * Domain luôn bắt đầu ở trạng thái `pending`: việc xác minh quyền
 * sở hữu là một bước cấp phát riêng, hợp đồng tạo không cho phép
 * khai sẵn `verified` để bỏ qua bước đó. `kind` vắng mặt = `alias`;
 * domain chính của không gian được chỉ định tường minh.
 */
export const createSpaceDomainSchema = z.strictObject({
  spaceId: uuidIdSchema,
  domain: spaceDomainNameSchema,
  kind: z.enum(SPACE_DOMAIN_KINDS).optional(),
});
export type CreateSpaceDomain = z.infer<typeof createSpaceDomainSchema>;

/**
 * Cập nhật một domain đã đăng ký: đổi loại (chính/phụ) hoặc đổi
 * trạng thái cấp phát. Chuyển sang `verified` là hành động xác minh
 * — mốc `verifiedAt` do tầng service đóng tại thời điểm chuyển,
 * client không tự khai mốc này.
 */
export const updateSpaceDomainSchema = z.strictObject({
  kind: z.enum(SPACE_DOMAIN_KINDS).optional(),
  status: z.enum(SPACE_DOMAIN_STATUSES).optional(),
});
export type UpdateSpaceDomain = z.infer<typeof updateSpaceDomainSchema>;
