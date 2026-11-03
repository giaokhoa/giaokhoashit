import { describe, expect, it, vi } from 'vitest';
import {
  createVerifiedDomainLookup,
  effectiveRequestHost,
  findVerifiedSpaceForHost,
  isHostVerifiedForSpace,
  normalizeRequestHost,
  resolveSpaceByVerifiedDomain,
  type VerifiedDomainEntry,
  type VerifiedDomainLookup,
} from './domain-resolution';

/**
 * Ca đơn vị của bộ phân giải domain/issuer (issue #5): Host đã xác
 * minh là mắt xích đầu của chuỗi danh tính — các ca dưới đây khoá
 * các bất biến của hợp đồng: chỉ domain đã xác minh mới chọn được
 * không gian, alias và domain chính cho cùng một issuer, Host lạ /
 * hậu tố giả / header forwarded giả mạo không chọn được tenant hay
 * platform nào, và không tồn tại fallback.
 */

const ISSUER_A = 'https://keycloak.giaokhoa.vn/realms/trung-tam-a';
const ISSUER_PLATFORM = 'https://keycloak.giaokhoa.vn/realms/giaokhoa';

/** Mục tra cứu mẫu của trung tâm A: domain chính + một alias cùng trỏ về một issuer. */
function centreEntry(domain: string, domainKind: string): VerifiedDomainEntry {
  return {
    domain,
    domainKind,
    spaceId: 'space-a',
    slug: 'trung-tam-a',
    spaceKind: 'center',
    spaceStatus: 'active',
    issuerUrl: ISSUER_A,
  };
}

/** Cổng tra cứu giả: chỉ các domain trong bảng mới "đã xác minh", phần còn lại không tồn tại. */
function fakeLookup(entries: Record<string, VerifiedDomainEntry>): VerifiedDomainLookup & { calls: string[] } {
  const calls: string[] = [];
  const lookup = (async (domain: string) => {
    calls.push(domain);
    return entries[domain];
  }) as VerifiedDomainLookup & { calls: string[] };
  lookup.calls = calls;
  return lookup;
}

const CENTRE_DOMAINS: Record<string, VerifiedDomainEntry> = {
  'trung-tam-a.giaokhoa.vn': centreEntry('trung-tam-a.giaokhoa.vn', 'primary'),
  'a.giaokhoa.vn': centreEntry('a.giaokhoa.vn', 'alias'),
  'giaokhoa.vn': {
    domain: 'giaokhoa.vn',
    domainKind: 'primary',
    spaceId: 'space-platform',
    slug: 'trang-chinh',
    spaceKind: 'platform',
    spaceStatus: 'active',
    issuerUrl: ISSUER_PLATFORM,
  },
};

describe('Chuẩn hoá Host của request', () => {
  it('hạ chữ thường, bỏ cổng và bỏ dấu chấm cuối để về dạng canonical', () => {
    expect(normalizeRequestHost('Trung-Tam-A.GiaoKhoa.VN')).toBe('trung-tam-a.giaokhoa.vn');
    expect(normalizeRequestHost('trung-tam-a.giaokhoa.vn:8443')).toBe('trung-tam-a.giaokhoa.vn');
    expect(normalizeRequestHost('trung-tam-a.giaokhoa.vn.')).toBe('trung-tam-a.giaokhoa.vn');
    expect(normalizeRequestHost('  trung-tam-a.giaokhoa.vn  ')).toBe('trung-tam-a.giaokhoa.vn');
  });

  it('địa chỉ IP không bao giờ là domain đã xác minh', () => {
    expect(normalizeRequestHost('127.0.0.1')).toBeNull();
    expect(normalizeRequestHost('127.0.0.1:8080')).toBeNull();
    expect(normalizeRequestHost('192.168.1.10')).toBeNull();
    expect(normalizeRequestHost('[::1]:3000')).toBeNull();
    expect(normalizeRequestHost('::1')).toBeNull();
  });

  it('tên một nhãn (localhost, tên máy nội bộ) không chọn được tenant', () => {
    expect(normalizeRequestHost('localhost')).toBeNull();
    expect(normalizeRequestHost('localhost:3000')).toBeNull();
    expect(normalizeRequestHost('may-chu-noi-bo')).toBeNull();
  });

  it('nhãn sai hình dạng DNS thì không hợp lệ', () => {
    expect(normalizeRequestHost('-trung-tam-a.giaokhoa.vn')).toBeNull();
    expect(normalizeRequestHost('trung_tam_a.giaokhoa.vn')).toBeNull();
    expect(normalizeRequestHost('trung-tam-a..giaokhoa.vn')).toBeNull();
    expect(normalizeRequestHost('.giaokhoa.vn')).toBeNull();
  });

  it('host trống hoặc thiếu thì không hợp lệ', () => {
    expect(normalizeRequestHost(undefined)).toBeNull();
    expect(normalizeRequestHost(null)).toBeNull();
    expect(normalizeRequestHost('')).toBeNull();
    expect(normalizeRequestHost('   ')).toBeNull();
  });
});

describe('Host hiệu lực của request', () => {
  it('lấy hostname mà Express đã tính theo cấu hình proxy tin cậy', () => {
    expect(effectiveRequestHost({ hostname: 'trung-tam-a.giaokhoa.vn' })).toBe('trung-tam-a.giaokhoa.vn');
    expect(effectiveRequestHost({})).toBeNull();
    expect(effectiveRequestHost({ hostname: undefined })).toBeNull();
  });

  it('header X-Forwarded-Host thô do khách tự khai không bao giờ được đọc', () => {
    // Request giả mang cả hai: hostname (Express đã tính — chỉ hon
    // X-Forwarded-Host khi chuỗi proxy được tin) và header thô do
    // khách kết nối trực tiếp tự gắn. Bộ phân giải chỉ thấy
    // hostname; header thô không tồn tại trong hình dạng đầu vào.
    const req = {
      hostname: 'trung-tam-a.giaokhoa.vn',
      headers: { 'x-forwarded-host': 'giaokhoa.vn' },
    };
    expect(effectiveRequestHost(req)).toBe('trung-tam-a.giaokhoa.vn');
  });
});

describe('Phân giải domain đã xác minh thành không gian và issuer', () => {
  it('domain chính đã xác minh của trung tâm đang hoạt động phân giải về đúng issuer cố định của trung tâm', async () => {
    const result = await resolveSpaceByVerifiedDomain('trung-tam-a.giaokhoa.vn', fakeLookup(CENTRE_DOMAINS));
    expect(result).toEqual({
      kind: 'resolved',
      domain: 'trung-tam-a.giaokhoa.vn',
      domainKind: 'primary',
      spaceId: 'space-a',
      slug: 'trung-tam-a',
      spaceKind: 'center',
      issuerUrl: ISSUER_A,
    });
  });

  it('alias đã xác minh chọn cùng một issuer như domain chính của cùng trung tâm', async () => {
    const viaPrimary = await resolveSpaceByVerifiedDomain('trung-tam-a.giaokhoa.vn', fakeLookup(CENTRE_DOMAINS));
    const viaAlias = await resolveSpaceByVerifiedDomain('a.giaokhoa.vn', fakeLookup(CENTRE_DOMAINS));
    // Cùng spaceId và cùng issuer tuyệt đối ở cả hai đường — chỉ
    // khác loại domain đã khớp.
    expect(viaPrimary).toMatchObject({ kind: 'resolved', spaceId: 'space-a', issuerUrl: ISSUER_A, domainKind: 'primary' });
    expect(viaAlias).toMatchObject({ kind: 'resolved', spaceId: 'space-a', issuerUrl: ISSUER_A, domainKind: 'alias' });
  });

  it('domain của platform phân giải về realm platform, tách bạch với realm của trung tâm', async () => {
    const result = await resolveSpaceByVerifiedDomain('giaokhoa.vn', fakeLookup(CENTRE_DOMAINS));
    // Issuer khớp tuyệt đối với realm platform `giaokhoa` — khác
    // hẳn issuer của realm trung tâm (ISSUER_A) ở ca trên.
    expect(result).toMatchObject({ kind: 'resolved', spaceKind: 'platform', issuerUrl: ISSUER_PLATFORM });
  });

  it('Host chưa đăng ký bị từ chối là unknown-host — không fallback về platform hay kho chung', async () => {
    const result = await resolveSpaceByVerifiedDomain('trung-tam-b.giaokhoa.vn', fakeLookup(CENTRE_DOMAINS));
    expect(result).toEqual({ kind: 'rejected', reason: 'unknown-host' });
  });

  it('hậu tố giả và tiền tố giả không khớp domain đã đăng ký (chỉ khớp tuyệt đối)', async () => {
    const lookup = fakeLookup(CENTRE_DOMAINS);
    // Domain thật làm hậu tố của một tên lạ thuộc kẻ khác kiểm soát.
    expect(await resolveSpaceByVerifiedDomain('trung-tam-a.giaokhoa.vn.evil.com', lookup))
      .toEqual({ kind: 'rejected', reason: 'unknown-host' });
    // Nhãn đầu gần giống slug của trung tâm nhưng là một domain khác hẳn.
    expect(await resolveSpaceByVerifiedDomain('evil-trung-tam-a.giaokhoa.vn', lookup))
      .toEqual({ kind: 'rejected', reason: 'unknown-host' });
    // Tên miền con sâu hơn dưới domain thật cũng không tự suy ra trung tâm.
    expect(await resolveSpaceByVerifiedDomain('lop-1.trung-tam-a.giaokhoa.vn', lookup))
      .toEqual({ kind: 'rejected', reason: 'unknown-host' });
  });

  it('domain chưa xác minh hoặc đã tắt không có dòng tra cứu nên bị từ chối như Host lạ', async () => {
    // Cổng tra cứu chỉ trả domain verified: pending/disabled vắng
    // mặt trong bảng tra cứu là đúng hợp đồng của cổng.
    const lookup = fakeLookup({ 'trung-tam-a.giaokhoa.vn': centreEntry('trung-tam-a.giaokhoa.vn', 'primary') });
    expect(await resolveSpaceByVerifiedDomain('pending.giaokhoa.vn', lookup))
      .toEqual({ kind: 'rejected', reason: 'unknown-host' });
    expect(await resolveSpaceByVerifiedDomain('disabled.giaokhoa.vn', lookup))
      .toEqual({ kind: 'rejected', reason: 'unknown-host' });
  });

  it('không gian chưa active thì từ chối dù domain đã xác minh', async () => {
    const entries = {
      'moi.giaokhoa.vn': { ...centreEntry('moi.giaokhoa.vn', 'primary'), spaceStatus: 'provisioning' },
      'ngung.giaokhoa.vn': { ...centreEntry('ngung.giaokhoa.vn', 'primary'), spaceStatus: 'suspended' },
    };
    expect(await resolveSpaceByVerifiedDomain('moi.giaokhoa.vn', fakeLookup(entries)))
      .toEqual({ kind: 'rejected', reason: 'space-not-active' });
    expect(await resolveSpaceByVerifiedDomain('ngung.giaokhoa.vn', fakeLookup(entries)))
      .toEqual({ kind: 'rejected', reason: 'space-not-active' });
  });

  it('không gian chưa có issuer cố định (realm chưa cấp phát) thì từ chối tường minh', async () => {
    const entries = {
      'chua-co-realm.giaokhoa.vn': { ...centreEntry('chua-co-realm.giaokhoa.vn', 'primary'), issuerUrl: null },
    };
    expect(await resolveSpaceByVerifiedDomain('chua-co-realm.giaokhoa.vn', fakeLookup(entries)))
      .toEqual({ kind: 'rejected', reason: 'issuer-not-provisioned' });
  });

  it('Host không hợp lệ bị từ chối trước khi xuống tầng tra cứu', async () => {
    const lookup = fakeLookup(CENTRE_DOMAINS);
    expect(await resolveSpaceByVerifiedDomain('127.0.0.1', lookup)).toEqual({ kind: 'rejected', reason: 'invalid-host' });
    expect(await resolveSpaceByVerifiedDomain('localhost', lookup)).toEqual({ kind: 'rejected', reason: 'invalid-host' });
    expect(await resolveSpaceByVerifiedDomain(undefined, lookup)).toEqual({ kind: 'rejected', reason: 'invalid-host' });
    expect(lookup.calls).toEqual([]);
  });

  it('Host viết khác chữ hoa hoặc kèm cổng vẫn phân giải về đúng domain canonical đã đăng ký', async () => {
    const lookup = fakeLookup(CENTRE_DOMAINS);
    const result = await resolveSpaceByVerifiedDomain('Trung-Tam-A.GiaoKhoa.VN:8443', lookup);
    expect(result.kind).toBe('resolved');
    expect(lookup.calls).toEqual(['trung-tam-a.giaokhoa.vn']);
  });
});

describe('Adapter tra cứu từ repository control plane', () => {
  it('ánh xạ dòng domain đã xác minh của repository sang mục tra cứu, giữ nguyên mọi trường phân giải cần', async () => {
    const repo = {
      findVerifiedDomainByName: vi.fn(async (domain: string) => ({
        domainId: 'domain-1',
        domain,
        domainKind: 'alias',
        spaceId: 'space-a',
        slug: 'trung-tam-a',
        spaceKind: 'center',
        spaceStatus: 'active',
        storageMode: 'dedicated',
        issuerUrl: ISSUER_A,
      })),
    };
    const lookup = createVerifiedDomainLookup(repo);
    await expect(lookup('a.giaokhoa.vn')).resolves.toEqual({
      domain: 'a.giaokhoa.vn',
      domainKind: 'alias',
      spaceId: 'space-a',
      slug: 'trung-tam-a',
      spaceKind: 'center',
      spaceStatus: 'active',
      issuerUrl: ISSUER_A,
    });
    expect(repo.findVerifiedDomainByName).toHaveBeenCalledWith('a.giaokhoa.vn');
  });

  it('repository không có dòng (domain chưa xác minh/không tồn tại) thì adapter trả undefined', async () => {
    const repo = { findVerifiedDomainByName: vi.fn(async () => undefined) };
    const lookup = createVerifiedDomainLookup(repo);
    await expect(lookup('la.giaokhoa.vn')).resolves.toBeUndefined();
  });
});

describe('Chốt chặn domain đã xác minh cho guard', () => {
  it('Host là domain đã xác minh thì trả đúng không gian của domain đó, kể cả khi nhãn đầu không phải slug', async () => {
    const lookup = fakeLookup(CENTRE_DOMAINS);
    const found = await findVerifiedSpaceForHost('a.giaokhoa.vn', lookup);
    expect(found?.slug).toBe('trung-tam-a');
    expect(found?.spaceId).toBe('space-a');
    await expect(findVerifiedSpaceForHost('trung-tam-a.evil.com', lookup)).resolves.toBeUndefined();
  });

  it('trung tâm đã có domain xác minh mà Host không nằm trong số đó thì chốt chặn trả false', async () => {
    const guardLookup = {
      findVerifiedDomain: fakeLookup(CENTRE_DOMAINS),
      listVerifiedDomainNamesForSpace: async () => ['trung-tam-a.giaokhoa.vn', 'a.giaokhoa.vn'],
    };
    await expect(isHostVerifiedForSpace('trung-tam-a.evil.com', 'space-a', guardLookup)).resolves.toBe(false);
    await expect(isHostVerifiedForSpace('a.giaokhoa.vn', 'space-a', guardLookup)).resolves.toBe(true);
  });

  it('trung tâm chưa có domain xác minh nào thì chốt chặn cho qua đường slug chuyển tiếp', async () => {
    const guardLookup = {
      findVerifiedDomain: fakeLookup(CENTRE_DOMAINS),
      listVerifiedDomainNamesForSpace: async () => [],
    };
    await expect(isHostVerifiedForSpace('trung-tam-a.test.local', 'space-a', guardLookup)).resolves.toBe(true);
  });
});
