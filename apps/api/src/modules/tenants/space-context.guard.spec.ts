import { ForbiddenException, UnauthorizedException, type ExecutionContext } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { describe, expect, it, vi } from 'vitest';
import { AUTHENTICATED_VIA_BETTER_AUTH } from '../../common/better-auth-session.guard';
import type { AuthenticatedUser } from '../../common/current-user.decorator';
import {
  SpaceRoutingError,
  type ResolvedSpaceConnection,
} from '../../db/routing/space-connection-resolver';
import type { SpaceDatabaseService } from '../../db/routing/space-database.service';
import type { PermissionEvaluator } from '../access/permission-evaluator.service';
import { SkipSpaceContext } from './skip-space-context.decorator';
import { SpaceContextGuard } from './space-context.guard';
import type { TenantsRepository } from '@lms/db/repositories/tenants';

@SkipSpaceContext()
class SkipSpaceContextHandler {}

/**
 * Ca đơn vị của guard gắn ngữ cảnh trung tâm (issue #582): khung
 * định tuyến, sổ tenant và bộ đánh giá quyền được thay bằng vật
 * giả — các ca khoá đúng các quyết của guard: địa chỉ không mang
 * trung tâm thì không chạm; trung tâm chưa `active` bị chặn ở
 * traffic nghiệp vụ nhưng đường vận hành platform đi qua; token
 * lệch trung tâm bị từ chối TRƯỚC bước chuẩn bị (không mở kết nối
 * nào tới kho của trung tâm tại địa chỉ); và bước chuẩn bị + ghim
 * slug chỉ chạy cho phiên hợp lệ.
 */

const DEDICATED_TARGET: ResolvedSpaceConnection = {
  slug: 'trung-tam-a',
  spaceId: 'id-dang-ky-a',
  status: 'active',
  mode: 'dedicated',
  databaseName: 'lms_centre_a',
  connectionRole: 'centre_a_app',
  connectionString: 'postgres://centre_a_app:pw@db.internal:5432/lms_centre_a',
  schemaVersion: 'v3',
};

const SHARED_TARGET: ResolvedSpaceConnection = {
  slug: 'truong-mac-dinh',
  spaceId: 'id-dang-ky-truong',
  status: 'active',
  mode: 'shared',
  databaseName: 'lms_shared',
  connectionRole: 'lms_app',
  schemaVersion: 'v1',
};

interface FakeRequest {
  user?: AuthenticatedUser;
  tenantId?: string;
  spaceSlug?: string;
  hostname?: string;
  headers: Record<string, string | undefined>;
  [AUTHENTICATED_VIA_BETTER_AUTH]?: boolean;
}

function makeGuard(options?: {
  target?: ResolvedSpaceConnection;
  resolveError?: Error;
  lookupError?: Error;
  listError?: Error;
  platformTenantId?: string;
  isSiteAdmin?: boolean;
  tenantById?: Record<string, { slug: string } | undefined>;
  /** Dòng domain đã xác minh thay thế mặc định; có thể đặt tường minh undefined để mô phỏng Host lạ. */
  verifiedDomainRow?: Record<string, unknown> | undefined;
  /** Danh sách domain đã xác minh của không gian đích. */
  verifiedDomainNames?: string[];
}) {
  const defaultDomain = {
    domainId: 'domain-a',
    domain: HOST_A,
    domainKind: 'primary',
    spaceId: DEDICATED_TARGET.spaceId,
    slug: DEDICATED_TARGET.slug,
    spaceKind: 'center',
    spaceStatus: 'active',
    storageMode: 'dedicated',
    issuerUrl: 'https://keycloak.example.test/realms/trung-tam-a',
  };
  const hasDomainOverride = options !== undefined
    && Object.prototype.hasOwnProperty.call(options, 'verifiedDomainRow');
  const domainRow = hasDomainOverride ? options.verifiedDomainRow : defaultDomain;
  const spaceDb = {
    resolveTarget: vi.fn(async () => {
      if (options?.resolveError) throw options.resolveError;
      return options?.target ?? DEDICATED_TARGET;
    }),
    getKyselyForSpace: vi.fn(async () => ({})),
    // Nhánh cookie đọc tenant hiệu lực qua kho riêng (Phần A,
    // issue #587): mock chạy thẳng callback như kho đích thật.
    withSpaceKysely: vi.fn(async (_slug: string, fn: () => Promise<unknown>) => fn()),
  };
  const repo = {
    getPlatformTenant: vi.fn(async () =>
      options?.platformTenantId ? { id: options.platformTenantId } : undefined,
    ),
    getTenant: vi.fn(async (id: string) => options?.tenantById?.[id]),
  };
  const evaluator = {
    isSiteAdmin: vi.fn(async () => options?.isSiteAdmin ?? false),
  };
  const controlPlane = {
    findVerifiedDomainByName: vi.fn(async (domain: string) => {
      if (options?.lookupError) throw options.lookupError;
      return domainRow && domainRow.domain === domain ? domainRow : undefined;
    }),
    listVerifiedDomainNamesForSpace: vi.fn(async (spaceId: string) => {
      if (options?.listError) throw options.listError;
      if (options?.verifiedDomainNames) return options.verifiedDomainNames;
      return domainRow && domainRow.spaceId === spaceId ? [String(domainRow.domain)] : [];
    }),
  };
  const guard = new SpaceContextGuard(
    spaceDb as unknown as SpaceDatabaseService,
    repo as unknown as TenantsRepository,
    evaluator as unknown as PermissionEvaluator,
    new Reflector(),
    controlPlane as never,
  );
  return { guard, spaceDb, repo, evaluator, controlPlane };
}

function httpContext(request: FakeRequest): ExecutionContext {
  request.hostname ??= request.headers.host;
  return {
    getType: () => 'http',
    getHandler: () => httpContextHandler,
    getClass: () => httpContextHandler,
    switchToHttp: () => ({ getRequest: () => request }),
  } as unknown as ExecutionContext;
}

function httpContextHandler() {}

const HOST_A = 'trung-tam-a.he-thong.example';

describe('Guard gắn ngữ cảnh trung tâm theo địa chỉ', () => {
  it('route đánh dấu tự gắn trung tâm thì cho qua ngay mà không phân giải hay ghim gì', async () => {
    const { guard, spaceDb } = makeGuard();
    const request: FakeRequest = { headers: { host: 'trung-tam-a.he-thong.example' } };
    const context = {
      getType: () => 'http',
      getHandler: () => SkipSpaceContextHandler,
      getClass: () => SkipSpaceContextHandler,
      switchToHttp: () => ({ getRequest: () => request }),
    } as unknown as ExecutionContext;
    expect(await guard.canActivate(context)).toBe(true);
    expect(spaceDb.resolveTarget).not.toHaveBeenCalled();
    expect(spaceDb.getKyselyForSpace).not.toHaveBeenCalled();
  });

  it('địa chỉ không mang tên miền con của trung tâm thì cho qua và không phân giải gì', async () => {
    const { guard, spaceDb } = makeGuard();
    const request: FakeRequest = { headers: { host: 'localhost' } };
    await expect(guard.canActivate(httpContext(request))).resolves.toBe(true);
    expect(spaceDb.resolveTarget).not.toHaveBeenCalled();
    expect(request.spaceSlug).toBeUndefined();
  });

  it('Host có nhãn slug nhưng không có domain verified bị từ chối, không suy slug để mở kho', async () => {
    const { guard, spaceDb } = makeGuard({ verifiedDomainRow: undefined });
    const request: FakeRequest = { headers: { host: 'slug-la.he-thong.example' } };
    const error = await guard.canActivate(httpContext(request)).catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(UnauthorizedException);
    expect((error as UnauthorizedException).getResponse()).toMatchObject({ code: 'space.domain_not_verified' });
    expect(spaceDb.resolveTarget).not.toHaveBeenCalled();
    expect(spaceDb.getKyselyForSpace).not.toHaveBeenCalled();
  });

  it('domain đã verified nhưng khung định tuyến chưa cấu hình thì lỗi được ném ra, không fallback', async () => {
    const failure = new SpaceRoutingError('space_routing.not_configured', 'not configured');
    const { guard, spaceDb } = makeGuard({ resolveError: failure });
    const request: FakeRequest = { headers: { host: HOST_A } };
    await expect(guard.canActivate(httpContext(request))).rejects.toBe(failure);
    expect(spaceDb.getKyselyForSpace).not.toHaveBeenCalled();
  });

  it('tầng định tuyến chưa cấu hình control plane nhưng phiên mang centre_id thì fail-closed thay vì cho qua', async () => {
    const failure = new SpaceRoutingError('space_routing.not_configured', 'not configured');
    const { guard, spaceDb } = makeGuard({ resolveError: failure });
    const request: FakeRequest = {
      headers: { host: HOST_A },
      tenantId: 'tenant-a',
      user: { id: 'u1', username: 'gv1', centreId: 'id-dang-ky-a' },
    };
    await expect(guard.canActivate(httpContext(request))).rejects.toBe(failure);
    expect(spaceDb.getKyselyForSpace).not.toHaveBeenCalled();
  });

  it('lỗi phân giải do cấu hình kho riêng lệch sổ thì ném ra nguyên trạng thay vì rơi về đường thường', async () => {
    const failure = new SpaceRoutingError('space_routing.role_mismatch', 'role mismatch');
    const { guard } = makeGuard({ resolveError: failure });
    const request: FakeRequest = { headers: { host: HOST_A } };
    await expect(guard.canActivate(httpContext(request))).rejects.toBe(failure);
  });

  it('trung tâm bị đình chỉ thì traffic nghiệp vụ bị từ chối 403 kèm mã space.not_active, không chuẩn bị kho', async () => {
    const { guard, spaceDb } = makeGuard({ target: { ...DEDICATED_TARGET, status: 'suspended' } });
    const request: FakeRequest = {
      headers: { host: HOST_A },
      tenantId: 'tenant-a',
      user: { id: 'u1', username: 'gv1', centreId: 'id-dang-ky-a' },
    };
    const err = await guard.canActivate(httpContext(request)).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ForbiddenException);
    expect((err as ForbiddenException).getResponse()).toMatchObject({ code: 'space.not_active' });
    expect(spaceDb.getKyselyForSpace).not.toHaveBeenCalled();
  });

  it('quản trị viên site chính đứng ở tenant platform thì đi qua cả khi trung tâm bị đình chỉ, không bị ghim vào kho của trung tâm', async () => {
    const { guard, spaceDb } = makeGuard({
      target: { ...DEDICATED_TARGET, status: 'suspended' },
      platformTenantId: 'tenant-zero',
      isSiteAdmin: true,
    });
    const request: FakeRequest = {
      headers: { host: HOST_A },
      tenantId: 'tenant-zero',
      user: { id: 'admin', username: 'admin' },
    };
    await expect(guard.canActivate(httpContext(request))).resolves.toBe(true);
    expect(spaceDb.getKyselyForSpace).not.toHaveBeenCalled();
    expect(request.spaceSlug).toBeUndefined();
  });

  it('token mang centre_id của trung tâm khác tới địa chỉ kho riêng thì bị từ chối 401 TRƯỚC bước chuẩn bị', async () => {
    const { guard, spaceDb } = makeGuard();
    const request: FakeRequest = {
      headers: { host: HOST_A },
      tenantId: 'tenant-b',
      user: { id: 'u2', username: 'gv2', centreId: 'id-dang-ky-b' },
    };
    const err = await guard.canActivate(httpContext(request)).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(UnauthorizedException);
    expect((err as UnauthorizedException).getResponse()).toMatchObject({ code: 'auth.centre_mismatch' });
    expect(spaceDb.getKyselyForSpace).not.toHaveBeenCalled();
  });

  it('token mang centre_id khớp trung tâm tại địa chỉ thì bước chuẩn bị chạy và slug được ghim lên request', async () => {
    const { guard, spaceDb } = makeGuard();
    const request: FakeRequest = {
      headers: { host: HOST_A },
      tenantId: 'tenant-a',
      user: { id: 'u1', username: 'gv1', centreId: 'id-dang-ky-a' },
    };
    await expect(guard.canActivate(httpContext(request))).resolves.toBe(true);
    expect(spaceDb.getKyselyForSpace).toHaveBeenCalledWith('trung-tam-a');
    expect(request.spaceSlug).toBe('trung-tam-a');
  });

  it('phiên cookie không mang centre_id thì đối chiếu bằng slug của tenant hiệu lực: khớp thì cho qua và chuẩn bị kho', async () => {
    const { guard, spaceDb } = makeGuard({ tenantById: { 'tenant-a': { slug: 'trung-tam-a' } } });
    const request: FakeRequest = {
      headers: { host: HOST_A },
      tenantId: 'tenant-a',
      user: { id: 'u1', username: 'gv1' },
      [AUTHENTICATED_VIA_BETTER_AUTH]: true,
    };
    await expect(guard.canActivate(httpContext(request))).resolves.toBe(true);
    // Lượt đọc tenant phải mang tenantId làm ngữ cảnh kho đích:
    // thiếu nó, RLS của bảng `tenants` giấu dòng và nhánh này
    // từ chối oan mọi phiên cookie hợp lệ (lỗi A2).
    expect(spaceDb.withSpaceKysely).toHaveBeenCalledWith(
      'trung-tam-a',
      expect.any(Function),
      { tenantId: 'tenant-a' },
    );
    expect(spaceDb.getKyselyForSpace).toHaveBeenCalledWith('trung-tam-a');
    expect(request.spaceSlug).toBe('trung-tam-a');
  });

  it('phiên cookie không mang centre_id và tenant hiệu lực thuộc trung tâm khác thì bị từ chối 401, không chuẩn bị kho', async () => {
    const { guard, spaceDb } = makeGuard({ tenantById: { 'tenant-b': { slug: 'trung-tam-b' } } });
    const request: FakeRequest = {
      headers: { host: HOST_A },
      tenantId: 'tenant-b',
      user: { id: 'u2', username: 'gv2' },
      [AUTHENTICATED_VIA_BETTER_AUTH]: true,
    };
    const err = await guard.canActivate(httpContext(request)).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(UnauthorizedException);
    expect((err as UnauthorizedException).getResponse()).toMatchObject({ code: 'auth.centre_mismatch' });
    expect(spaceDb.getKyselyForSpace).not.toHaveBeenCalled();
  });

  it('phiên JWT cũ không mang centre_id tại địa chỉ kho riêng thì bị từ chối 401 fail-closed, không cho qua theo slug khớp và không chuẩn bị kho', async () => {
    const { guard, spaceDb } = makeGuard({ tenantById: { 'tenant-a': { slug: 'trung-tam-a' } } });
    const request: FakeRequest = {
      headers: { host: HOST_A },
      tenantId: 'tenant-a',
      user: { id: 'u1', username: 'gv1' },
    };
    const err = await guard.canActivate(httpContext(request)).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(UnauthorizedException);
    expect((err as UnauthorizedException).getResponse()).toMatchObject({ code: 'auth.centre_mismatch' });
    expect(spaceDb.getKyselyForSpace).not.toHaveBeenCalled();
  });

  it('request ẩn danh tới địa chỉ kho riêng đang hoạt động vẫn được chuẩn bị kho để đường đăng nhập đọc đúng kho của trung tâm', async () => {
    const { guard, spaceDb } = makeGuard();
    const request: FakeRequest = { headers: { host: HOST_A }, tenantId: 'tenant-zero' };
    await expect(guard.canActivate(httpContext(request))).resolves.toBe(true);
    expect(spaceDb.getKyselyForSpace).toHaveBeenCalledWith('trung-tam-a');
    expect(request.spaceSlug).toBe('trung-tam-a');
  });

  it('domain verified của trung tâm kho chung chọn đúng không gian và chuẩn bị theo đường kho chung', async () => {
    const { guard, spaceDb } = makeGuard({
      target: SHARED_TARGET,
      verifiedDomainRow: {
        domainId: 'domain-shared',
        domain: 'truong-mac-dinh.he-thong.example',
        domainKind: 'primary',
        spaceId: SHARED_TARGET.spaceId,
        slug: SHARED_TARGET.slug,
        spaceKind: 'center',
        spaceStatus: 'active',
        storageMode: 'shared',
        issuerUrl: 'https://keycloak.example.test/realms/truong-mac-dinh',
      },
    });
    const request: FakeRequest = {
      headers: { host: 'truong-mac-dinh.he-thong.example' },
      tenantId: 'tenant-truong',
      user: { id: 'u3', username: 'gv3' },
    };
    await expect(guard.canActivate(httpContext(request))).resolves.toBe(true);
    expect(spaceDb.getKyselyForSpace).toHaveBeenCalledWith('truong-mac-dinh');
    expect(request.spaceSlug).toBe('truong-mac-dinh');
  });

  it('Host mượn nhãn slug của trung tâm đã có domain xác minh thì bị từ chối 401, không chuẩn bị kho và không rơi về kho chung', async () => {
    const { guard, spaceDb } = makeGuard({
      verifiedDomainNames: ['trung-tam-a.giaokhoa.vn'],
    });
    const request: FakeRequest = { headers: { host: 'trung-tam-a.evil.com' } };
    const err = await guard.canActivate(httpContext(request)).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(UnauthorizedException);
    expect((err as UnauthorizedException).getResponse()).toMatchObject({ code: 'space.domain_not_verified' });
    expect(spaceDb.getKyselyForSpace).not.toHaveBeenCalled();
    expect(request.spaceSlug).toBeUndefined();
  });

  it('domain verified thắng Host giả ở header thô khi Express trả Host hiệu lực từ proxy tin cậy', async () => {
    const { guard, spaceDb, controlPlane } = makeGuard({
      verifiedDomainRow: {
        domainId: 'domain-proxy',
        domain: 'truong-a.vn',
        domainKind: 'primary',
        spaceId: DEDICATED_TARGET.spaceId,
        slug: DEDICATED_TARGET.slug,
        spaceKind: 'center',
        spaceStatus: 'active',
        storageMode: 'dedicated',
        issuerUrl: 'https://keycloak.example.test/realms/trung-tam-a',
      },
      verifiedDomainNames: ['truong-a.vn'],
    });
    const request: FakeRequest = {
      hostname: 'truong-a.vn',
      headers: { host: 'trung-tam-a..attacker.example' },
    };
    await expect(guard.canActivate(httpContext(request))).resolves.toBe(true);
    expect(controlPlane.findVerifiedDomainByName).toHaveBeenCalledWith('truong-a.vn');
    expect(controlPlane.findVerifiedDomainByName).not.toHaveBeenCalledWith('trung-tam-a..attacker.example');
    expect(spaceDb.getKyselyForSpace).toHaveBeenCalledWith('trung-tam-a');
  });

  it('Host sai chuẩn có nhãn slug giả bị từ chối thay vì mở kho theo slug', async () => {
    const { guard, spaceDb } = makeGuard({ verifiedDomainRow: undefined });
    const request: FakeRequest = { headers: { host: 'trung-tam-a..attacker.example' } };
    const error = await guard.canActivate(httpContext(request)).catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(UnauthorizedException);
    expect((error as UnauthorizedException).getResponse()).toMatchObject({ code: 'space.domain_not_verified' });
    expect(spaceDb.resolveTarget).not.toHaveBeenCalled();
  });

  it('lỗi truy vấn domain trong control plane làm guard thất bại thay vì coi Host là chưa đăng ký', async () => {
    const failure = new Error('control plane lookup failed');
    const { guard, spaceDb } = makeGuard({ lookupError: failure });
    const request: FakeRequest = { headers: { host: HOST_A } };
    await expect(guard.canActivate(httpContext(request))).rejects.toBe(failure);
    expect(spaceDb.resolveTarget).not.toHaveBeenCalled();
  });

  it('domain verified có issuerUrl null bị từ chối theo kết quả issuer-not-provisioned', async () => {
    const { guard, spaceDb } = makeGuard({
      verifiedDomainRow: {
        domainId: 'domain-pending-issuer',
        domain: 'realm-chua-cap.vn',
        domainKind: 'primary',
        spaceId: DEDICATED_TARGET.spaceId,
        slug: DEDICATED_TARGET.slug,
        spaceKind: 'center',
        spaceStatus: 'active',
        storageMode: 'dedicated',
        issuerUrl: null,
      },
    });
    const request: FakeRequest = { headers: { host: 'realm-chua-cap.vn' } };
    const error = await guard.canActivate(httpContext(request)).catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(UnauthorizedException);
    expect((error as UnauthorizedException).getResponse()).toMatchObject({ code: 'space.issuer_not_provisioned' });
    expect(spaceDb.resolveTarget).not.toHaveBeenCalled();
  });

  it('lỗi liệt kê domain verified của không gian cũng làm guard thất bại', async () => {
    const failure = new Error('verified-domain listing failed');
    const { guard, spaceDb } = makeGuard({ listError: failure });
    const request: FakeRequest = { headers: { host: HOST_A } };
    await expect(guard.canActivate(httpContext(request))).rejects.toBe(failure);
    expect(spaceDb.resolveTarget).not.toHaveBeenCalled();
  });

  it('domain đã xác minh thắng nhãn đầu của Host: domain tuỳ chọn không chứa slug vẫn chọn đúng trung tâm và chuẩn bị kho của nó', async () => {
    const { guard, spaceDb } = makeGuard({
      verifiedDomainRow: {
        domainId: 'domain-1',
        domain: 'truong-a.vn',
        domainKind: 'primary',
        spaceId: 'id-dang-ky-a',
        slug: 'trung-tam-a',
        spaceKind: 'center',
        spaceStatus: 'active',
        storageMode: 'dedicated',
        issuerUrl: 'https://keycloak.example.test/realms/trung-tam-a',
      },
      verifiedDomainNames: ['truong-a.vn'],
    });
    const request: FakeRequest = { headers: { host: 'truong-a.vn' }, tenantId: 'tenant-zero' };
    await expect(guard.canActivate(httpContext(request))).resolves.toBe(true);
    expect(spaceDb.getKyselyForSpace).toHaveBeenCalledWith('trung-tam-a');
    expect(request.spaceSlug).toBe('trung-tam-a');
  });
});
