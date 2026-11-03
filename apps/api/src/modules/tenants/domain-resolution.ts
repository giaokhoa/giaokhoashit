import type { ControlPlaneRepository } from '@lms/db/repositories/control-plane';

/**
 * Bộ phân giải domain/issuer đã xác minh (issue #5) — mắt xích đầu
 * của chuỗi danh tính chuẩn trong `docs/keycloak-auth-contract.md`:
 *
 *   Host đã xác minh → tenant (không gian) → realm của tenant →
 *   issuer cố định đã đăng ký → (issuer, sub) → principal.
 *
 * Luồng xử lý của module:
 * 1. Host hiệu lực của request được lấy từ `req.hostname` của
 *    Express — giá trị Express tính theo cấu hình proxy tin cậy của
 *    ứng dụng (xem {@link effectiveRequestHost}); module KHÔNG bao
 *    giờ tự đọc header `X-Forwarded-Host` thô.
 * 2. Host được chuẩn hoá (hạ chữ thường, bỏ cổng, bỏ dấu chấm cuối)
 *    và kiểm hình dạng: IP, tên một nhãn (localhost…) và tên sai
 *    hình dạng DNS bị loại ngay, không xuống tới tầng tra cứu.
 * 3. Tra cứu khớp TUYỆT ĐỐI theo domain trong sổ control plane qua
 *    cổng {@link VerifiedDomainLookup}: chỉ domain ở trạng thái
 *    `verified` mới có dòng tra cứu. Không có phép khớp hậu tố,
 *    không suy slug từ nhãn đầu tiên, và KHÔNG có fallback — Host
 *    lạ không chọn được tenant nào, kể cả platform.
 * 4. Không gian phải đang `active` và đã có issuer cố định thì mới
 *    phân giải thành công; thiếu một trong hai điều kiện là từ
 *    chối có lý do tường minh, không âm thầm rơi về kho chung hay
 *    không gian platform.
 *
 * @remarks
 * Hai guard của request gọi resolver này trước khi chọn tenant hoặc
 * chuẩn bị kho. `space-resolution.ts` chỉ còn cung cấp hàm nhận diện
 * hình dạng subdomain để từ chối Host giả; không còn là nguồn chọn
 * slug hay đường fallback khi domain chưa verified.
 */

/**
 * Một domain đã xác minh kèm không gian sở hữu — hình chiếu tối
 * thiểu của dòng tra cứu ở sổ control plane mà bộ phân giải cần.
 * Alias và domain chính của cùng một không gian cho cùng một kết
 * quả ở các trường không gian/issuer, vì issuer nằm trên dòng đăng
 * ký của không gian.
 */
export interface VerifiedDomainEntry {
  /** Domain canonical đã khớp tuyệt đối với Host của request. */
  domain: string;
  /** primary | alias. */
  domainKind: string;
  /** Định danh của dòng đăng ký không gian (khoá chính của sổ). */
  spaceId: string;
  slug: string;
  /** center | platform. */
  spaceKind: string;
  /** provisioning | active | suspended | closed. */
  spaceStatus: string;
  /** Issuer OIDC cố định của không gian; `null` khi realm chưa được cấp phát. */
  issuerUrl: string | null;
}

/**
 * Cổng tra cứu domain đã xác minh mà bộ phân giải phụ thuộc — tầng
 * gọi cấp cách đọc thật (sổ control plane). Chỉ domain `verified`
 * mới có kết quả; mọi domain khác (chưa đăng ký, `pending`,
 * `disabled`) đều là `undefined`.
 */
export type VerifiedDomainLookup = (domain: string) => Promise<VerifiedDomainEntry | undefined>;

/** Lý do một Host bị từ chối phân giải — tập đóng để tầng gọi ánh xạ mã lỗi ổn định. */
export type DomainRejectionReason =
  /** Host trống, là IP, tên một nhãn hoặc sai hình dạng DNS. */
  | 'invalid-host'
  /** Host đúng hình dạng nhưng không khớp domain đã xác minh nào. */
  | 'unknown-host'
  /** Domain đã xác minh nhưng không gian chưa ở trạng thái `active`. */
  | 'space-not-active'
  /** Không gian chưa có issuer cố định trong sổ (realm chưa cấp phát). */
  | 'issuer-not-provisioned';

/** Kết quả phân giải domain/issuer: hoặc chọn được đúng một không gian + issuer, hoặc từ chối có lý do. */
export type DomainResolution =
  | {
      kind: 'resolved';
      domain: string;
      /** primary | alias — cả hai đều cho cùng issuer của không gian. */
      domainKind: string;
      spaceId: string;
      slug: string;
      /** center | platform. */
      spaceKind: string;
      /** Issuer cố định đã đăng ký — nguồn duy nhất để kiểm token của request thuộc Host này. */
      issuerUrl: string;
    }
  | { kind: 'rejected'; reason: DomainRejectionReason };

/** Mẫu một nhãn DNS hợp lệ (chữ thường sau khi chuẩn hoá). */
const DNS_LABEL_PATTERN = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
/** Mẫu IPv4 dạng thập phân — Host là IP thì không bao giờ là domain đã xác minh. */
const IPV4_PATTERN = /^\d{1,3}(?:\.\d{1,3}){3}$/;

/**
 * Chuẩn hoá Host của request về dạng canonical để tra cứu: cắt
 * khoảng trắng, hạ chữ thường, bỏ cổng (`:port`) và bỏ dấu chấm
 * cuối. Trả `null` khi Host không thể là một domain đã xác minh:
 * trống, là địa chỉ IP (v4 hay v6), là tên một nhãn (localhost và
 * các tên nội bộ), hoặc có nhãn sai hình dạng DNS — các Host này
 * bị từ chối trước khi xuống tầng tra cứu, không tốn một lượt đọc
 * sổ và không có đường vòng nào khác.
 *
 * @param host - Host thô (đã qua {@link effectiveRequestHost} khi
 *   lấy từ request thật).
 * @returns Domain canonical, hoặc `null` khi Host không hợp lệ.
 */
export function normalizeRequestHost(host: string | undefined | null): string | null {
  if (!host) return null;
  let candidate = host.trim().toLowerCase();
  if (!candidate) return null;
  // IPv6 (dạng ngoặc hoặc trần) chứa dấu hai chấm ở nhiều vị trí —
  // là IP, không phải domain; loại trước khi xử lý cổng để phép cắt
  // cổng không cắt nhầm vào địa chỉ.
  if (candidate.startsWith('[') || candidate.indexOf(':') !== candidate.lastIndexOf(':')) return null;
  const colonIndex = candidate.indexOf(':');
  if (colonIndex >= 0) candidate = candidate.slice(0, colonIndex);
  if (candidate.endsWith('.')) candidate = candidate.slice(0, -1);
  if (!candidate || candidate.length > 253) return null;
  if (IPV4_PATTERN.test(candidate)) return null;
  const labels = candidate.split('.');
  // Domain đã xác minh luôn có ít nhất hai nhãn: tên một nhãn
  // (localhost, tên máy nội bộ) không bao giờ chọn được tenant.
  if (labels.length < 2) return null;
  if (labels.some((label) => !DNS_LABEL_PATTERN.test(label))) return null;
  return candidate;
}

/**
 * Host hiệu lực của một request — ĐIỂM LẤY HOST DUY NHẤT cho bộ
 * phân giải: đọc `req.hostname`, giá trị Express tự tính từ header
 * `Host`, và CHỈ thay bằng `X-Forwarded-Host` khi chuỗi proxy đứng
 * trước ứng dụng được tin theo cấu hình `trust proxy` của ứng dụng
 * (đúng một chặng tin cậy — xem `common/client-ip.ts`). Nhờ vậy
 * header forwarded do một khách kết nối trực tiếp (hoặc đứng ngoài
 * chặng tin cậy) tự khai KHÔNG bao giờ tới được bộ phân giải: muốn
 * giả mạo Host để chọn tenant/platform, kẻ tấn công phải đứng ở
 * đúng chặng proxy tin cậy. Module cố ý không đọc
 * `req.headers['x-forwarded-host']` ở bất kỳ đâu.
 *
 * @param req - Request Express (chỉ cần trường `hostname`).
 * @returns Host hiệu lực, hoặc `null` khi request không mang Host.
 */
export function effectiveRequestHost(req: Pick<{ hostname?: string }, 'hostname'>): string | null {
  return req.hostname ?? null;
}

/**
 * Nhận diện các Host loopback chỉ phục vụ chạy ứng dụng cục bộ.
 * Chúng không chọn không gian theo domain và giữ đường phát triển
 * mặc định; mọi Host DNS thông thường vẫn phải có dòng verified
 * trong control plane trước khi được chọn tenant/platform.
 *
 * @param host - Host hiệu lực lấy từ Express.
 * @returns `true` chỉ cho localhost và loopback IPv4/IPv6.
 */
export function isLocalDevelopmentHost(host: string | undefined | null): boolean {
  if (!host) return false;
  const candidate = host.trim().toLowerCase();
  return candidate === 'localhost'
    || candidate === '127.0.0.1'
    || candidate === '::1'
    || candidate === '[::1]';
}

/**
 * Phân giải một Host thành không gian + issuer cố định, hoặc từ
 * chối có lý do. Phép tra cứu là khớp tuyệt đối trên domain
 * canonical: một Host như `tenant.example.com.evil.com` hay
 * `evil-tenant.example.com` chỉ khớp nếu CHÍNH nó đã được đăng ký
 * và xác minh — gần giống domain của tenant không tạo ra bất kỳ
 * quyền chọn tenant nào.
 *
 * @param host - Host hiệu lực của request (chưa chuẩn hoá cũng
 *   được — hàm tự chuẩn hoá trước khi tra).
 * @param lookup - Cổng tra cứu domain đã xác minh.
 * @returns Kết quả phân giải phân biệt theo `kind`.
 */
export async function resolveSpaceByVerifiedDomain(
  host: string | undefined | null,
  lookup: VerifiedDomainLookup,
): Promise<DomainResolution> {
  const domain = normalizeRequestHost(host);
  if (!domain) return { kind: 'rejected', reason: 'invalid-host' };
  const entry = await lookup(domain);
  if (!entry) return { kind: 'rejected', reason: 'unknown-host' };
  if (!entry.issuerUrl) return { kind: 'rejected', reason: 'issuer-not-provisioned' };
  if (entry.spaceStatus !== 'active') return { kind: 'rejected', reason: 'space-not-active' };
  return {
    kind: 'resolved',
    domain: entry.domain,
    domainKind: entry.domainKind,
    spaceId: entry.spaceId,
    slug: entry.slug,
    spaceKind: entry.spaceKind,
    issuerUrl: entry.issuerUrl,
  };
}

/**
 * Resolver domain là nguồn sự thật duy nhất để guard chọn không gian.
 * Guard không dùng slug trích từ Host để chọn tenant: nhãn đầu chỉ
 * được dùng để nhận diện Host có hình dạng subdomain cần từ chối
 * khi không khớp sổ. Host sai hình dạng, Host lạ, suffix giả, không
 * gian chưa có domain verified và không gian chưa cấp issuer đều
 * không được rơi về slug, tenant-zero hay kho chung.
 *
 * Resolver phải được gọi trước khi mở kho. Chỉ kết quả `resolved`
 * mới cung cấp slug và spaceId; mọi kết quả `rejected` là quyết định
 * từ chối có lý do. Lỗi đọc control plane được ném nguyên trạng để
 * guard fail-closed, tuyệt đối không chuyển thành "không có dữ liệu".
 */

/** Cổng tra cứu domain đã xác minh mà hai guard dùng, kèm danh sách domain của một không gian. */
export interface VerifiedDomainGuardLookup {
  /** Tra domain đã xác minh theo Host canonical (chỉ `verified` mới có kết quả). */
  findVerifiedDomain: VerifiedDomainLookup;
  /** Tên các domain đã xác minh của một không gian (rỗng khi chưa đăng ký domain nào). */
  listVerifiedDomainNamesForSpace: (spaceId: string) => Promise<string[]>;
}

/**
 * Tra không gian của một Host qua sổ domain đã xác minh.
 *
 * @param host - Host thô của request (kèm cổng cũng được — hàm tự chuẩn hoá).
 * @param lookup - Cổng tra cứu domain đã xác minh.
 * @returns Dòng domain đã xác minh kèm không gian sở hữu, hoặc `undefined` khi Host không phải domain đã xác minh.
 */
export async function findVerifiedSpaceForHost(
  host: string | string[] | undefined | null,
  lookup: VerifiedDomainLookup,
): Promise<VerifiedDomainEntry | undefined> {
  const header = Array.isArray(host) ? host[0] : host;
  const domain = normalizeRequestHost(header);
  if (!domain) return undefined;
  return lookup(domain);
}

/**
 * Chốt kiểm tra Host cuối cho guard: Host phải chuẩn hoá được và
 * phải nằm trong danh sách domain verified của chính không gian.
 * Danh sách rỗng không phải cửa sổ fallback — không gian chưa đăng
 * ký domain verified không được chọn bằng slug.
 *
 * @param host - Host thô của request.
 * @param spaceId - Định danh đăng ký của không gian đích.
 * @param lookup - Cổng tra cứu của guard.
 * @returns `true` chỉ khi Host chuẩn hoá được và nằm trong danh sách domain verified; `false` cho Host sai hình dạng hoặc không gian chưa có domain verified.
 */
export async function isHostVerifiedForSpace(
  host: string | string[] | undefined | null,
  spaceId: string,
  lookup: VerifiedDomainGuardLookup,
): Promise<boolean> {
  const header = Array.isArray(host) ? host[0] : host;
  const domain = normalizeRequestHost(header);
  if (!domain) return false;
  const verifiedDomains = await lookup.listVerifiedDomainNamesForSpace(spaceId);
  return verifiedDomains.includes(domain);
}

/**
 * Dựng cổng tra cứu thật từ repository của sổ control plane:
 * repository chỉ trả domain ở trạng thái `verified` kèm dòng đăng
 * ký của không gian, adapter này chỉ đổi hình dạng sang
 * {@link VerifiedDomainEntry} — không thêm luật nào.
 *
 * @param repo - Repository control plane của tầng ứng dụng.
 * @returns Cổng tra cứu cho {@link resolveSpaceByVerifiedDomain}.
 */
export function createVerifiedDomainLookup(
  repo: Pick<ControlPlaneRepository, 'findVerifiedDomainByName'>,
): VerifiedDomainLookup {
  return async (domain: string): Promise<VerifiedDomainEntry | undefined> => {
    const row = await repo.findVerifiedDomainByName(domain);
    if (!row) return undefined;
    return {
      domain: row.domain,
      domainKind: row.domainKind,
      spaceId: row.spaceId,
      slug: row.slug,
      spaceKind: row.spaceKind,
      spaceStatus: row.spaceStatus,
      issuerUrl: row.issuerUrl,
    };
  };
}
