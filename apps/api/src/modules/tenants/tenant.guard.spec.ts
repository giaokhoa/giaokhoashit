import { UnauthorizedException, type ExecutionContext } from '@nestjs/common';
import { describe, expect, it, vi } from 'vitest';
import type { AuthenticatedUser } from '../../common/current-user.decorator';
import type { SpaceDatabaseService } from '../../db/routing/space-database.service';
import type { PermissionEvaluator } from '../access/permission-evaluator.service';
import { TenantGuard } from './tenant.guard';
import type { TenantContextService } from './tenant-context.service';
import type { TenantsRepository } from '@lms/db/repositories/tenants';
import type { TenantsService } from './tenants.service';

interface FakeRequest {
  user?: AuthenticatedUser;
  tenantId?: string;
  hostname?: string;
  headers: Record<string, string | undefined>;
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

function makeGuard(options?: {
  tenantById?: Record<string, { id: string; slug: string; status: string; kind: string } | undefined>;
  membershipStatus?: string;
  lookupError?: Error;
  listError?: Error;
  /** Dòng domain verified thay thế mặc định; có thể đặt tường minh undefined để mô phỏng Host lạ. */
  verifiedDomainRow?: Record<string, unknown> | undefined;
  /** Danh sách domain verified của không gian đích. */
  verifiedDomainNames?: string[];
  /** Đích kho riêng mà khung định tuyến trả cho slug của ca kiểm chứng. */
  dedicatedTarget?: Record<string, unknown>;
  /** Dòng tenant trong kho riêng của ca kiểm chứng. */
  spaceTenant?: Record<string, unknown>;
}) {
  const defaultDomain = {
    domainId: 'domain-a',
    domain: HOST_A,
    domainKind: 'primary',
    spaceId: 'id-dang-ky-a',
    slug: 'trung-tam-a',
    spaceKind: 'center',
    spaceStatus: 'active',
    issuerUrl: 'https://keycloak.example.test/realms/trung-tam-a',
  };
  const hasDomainOverride = options !== undefined
    && Object.prototype.hasOwnProperty.call(options, 'verifiedDomainRow');
  const domainRow = hasDomainOverride ? options.verifiedDomainRow : defaultDomain;
  const repo = {
    getPlatformTenant: vi.fn(async () => ({ id: 'tenant-zero' })),
    getTenant: vi.fn(async (id: string) => options?.tenantById?.[id]),
    getTenantBySlug: vi.fn(async () => options?.spaceTenant),
    getMembership: vi.fn(async () => ({ status: options?.membershipStatus ?? 'active' })),
  };
  const tenants = {
    resolveDefaultTenant: vi.fn(async () => ({ id: 'tenant-default' })),
  };
  const evaluator = {
    isSiteAdmin: vi.fn(async () => false),
  };
  const tenantContext = {
    enter: vi.fn(),
  };
  const spaceDb = {
    resolveTarget: vi.fn(async () => {
      if (options?.dedicatedTarget) return options.dedicatedTarget;
      throw new Error('must not resolve a space for a plain host');
    }),
    withSpaceKysely: vi.fn(async (_slug: string, fn: () => Promise<unknown>) => fn()),
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
  const guard = new TenantGuard(
    repo as unknown as TenantsRepository,
    tenants as unknown as TenantsService,
    evaluator as unknown as PermissionEvaluator,
    tenantContext as unknown as TenantContextService,
    spaceDb as unknown as SpaceDatabaseService,
    controlPlane as never,
  );
  return { guard, repo, tenantContext, spaceDb, controlPlane };
}

/**
 * Ca đơn vị của nhánh cũ trong TenantGuard (Phần A, issue #587):
 * `tid` đã khai trong phiên mà không tra được dòng tenant nào
 * phải fail-closed rõ ràng, không rơi lặng về tenant platform.
 */
describe('Guard phân giải tenant hiệu lực', () => {
  it('tid đã khai mà không tra được tenant thì bị từ chối 401 mã tenant.unknown, không ghim tenant platform', async () => {
    const { guard, tenantContext } = makeGuard({ tenantById: {} });
    const request: FakeRequest = {
      headers: { host: 'localhost' },
      user: { id: 'u1', username: 'gv1', tenantId: 'tenant-da-xoa' },
    };
    const err = await guard.canActivate(httpContext(request)).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(UnauthorizedException);
    expect((err as UnauthorizedException).getResponse()).toMatchObject({ code: 'tenant.unknown' });
    expect(request.tenantId).toBeUndefined();
    expect(tenantContext.enter).not.toHaveBeenCalled();
  });

  it('tid tra được tenant school đang active và membership đang active thì ghim đúng tenant đó', async () => {
    const { guard, tenantContext } = makeGuard({
      tenantById: { 'tenant-a': { id: 'tenant-a', slug: 'trung-tam-a', status: 'active', kind: 'school' } },
      membershipStatus: 'active',
    });
    const request: FakeRequest = {
      headers: { host: 'localhost' },
      user: { id: 'u1', username: 'gv1', tenantId: 'tenant-a' },
    };
    await expect(guard.canActivate(httpContext(request))).resolves.toBe(true);
    expect(request.tenantId).toBe('tenant-a');
    expect(tenantContext.enter).toHaveBeenCalledWith({ tenantId: 'tenant-a', userId: 'u1' });
  });

  it('Host mượn nhãn slug của trung tâm kho riêng đã có domain xác minh thì bị từ chối 401, không rơi về tenant-zero', async () => {
    const { guard, tenantContext } = makeGuard({
      verifiedDomainNames: ['trung-tam-a.giaokhoa.vn'],
      dedicatedTarget: {
        slug: 'trung-tam-a',
        spaceId: 'id-dang-ky-a',
        status: 'active',
        mode: 'dedicated',
        databaseName: 'lms_centre_a',
        connectionRole: 'centre_a_app',
        connectionString: 'postgres://centre_a_app:pw@db.internal:5432/lms_centre_a',
        schemaVersion: 'v3',
      },
    });
    const request: FakeRequest = {
      headers: { host: 'trung-tam-a.evil.com' },
      user: { id: 'u1', username: 'gv1' },
    };
    const err = await guard.canActivate(httpContext(request)).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(UnauthorizedException);
    expect((err as UnauthorizedException).getResponse()).toMatchObject({ code: 'space.domain_not_verified' });
    expect(request.tenantId).toBeUndefined();
    expect(tenantContext.enter).not.toHaveBeenCalled();
  });

  it('Host giả ở header thô không ghi đè Host hiệu lực do proxy tin cậy cung cấp', async () => {
    const { guard, tenantContext, controlPlane } = makeGuard({
      verifiedDomainRow: {
        domainId: 'domain-proxy',
        domain: 'truong-a.vn',
        domainKind: 'primary',
        spaceId: 'id-dang-ky-a',
        slug: 'trung-tam-a',
        spaceKind: 'center',
        spaceStatus: 'active',
        issuerUrl: 'https://keycloak.example.test/realms/trung-tam-a',
      },
      verifiedDomainNames: ['truong-a.vn'],
      dedicatedTarget: {
        slug: 'trung-tam-a',
        spaceId: 'id-dang-ky-a',
        status: 'active',
        mode: 'dedicated',
        databaseName: 'lms_centre_a',
        connectionRole: 'centre_a_app',
        connectionString: 'postgres://centre_a_app:pw@db.internal:5432/lms_centre_a',
        schemaVersion: 'v3',
      },
      spaceTenant: { id: 'tenant-a', slug: 'trung-tam-a', status: 'active', kind: 'school' },
    });
    const request: FakeRequest = {
      hostname: 'truong-a.vn',
      headers: { host: 'trung-tam-a..attacker.example' },
      user: { id: 'u1', username: 'gv1', centreId: 'id-dang-ky-a' },
    };
    await expect(guard.canActivate(httpContext(request))).resolves.toBe(true);
    expect(controlPlane.findVerifiedDomainByName).toHaveBeenCalledWith('truong-a.vn');
    expect(controlPlane.findVerifiedDomainByName).not.toHaveBeenCalledWith('trung-tam-a..attacker.example');
    expect(tenantContext.enter).toHaveBeenCalledWith({ tenantId: 'tenant-a', userId: 'u1' });
  });

  it('Host có nhãn slug nhưng suffix giả không được chọn tenant-zero', async () => {
    const { guard, tenantContext, spaceDb } = makeGuard({ verifiedDomainRow: undefined });
    const request: FakeRequest = {
      headers: { host: 'trung-tam-a..attacker.example' },
      user: { id: 'u1', username: 'gv1' },
    };
    const error = await guard.canActivate(httpContext(request)).catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(UnauthorizedException);
    expect((error as UnauthorizedException).getResponse()).toMatchObject({ code: 'space.domain_not_verified' });
    expect(tenantContext.enter).not.toHaveBeenCalled();
    expect(spaceDb.resolveTarget).not.toHaveBeenCalled();
  });

  it('lỗi tra cứu domain control plane làm guard từ chối thay vì rơi về tenant-zero', async () => {
    const failure = new Error('control plane lookup failed');
    const { guard, tenantContext } = makeGuard({ lookupError: failure });
    const request: FakeRequest = {
      headers: { host: HOST_A },
      user: { id: 'u1', username: 'gv1' },
    };
    await expect(guard.canActivate(httpContext(request))).rejects.toBe(failure);
    expect(tenantContext.enter).not.toHaveBeenCalled();
  });

  it('domain verified có issuerUrl null bị từ chối thay vì fallback tenant-zero', async () => {
    const { guard, tenantContext, spaceDb } = makeGuard({
      verifiedDomainRow: {
        domainId: 'domain-pending-issuer',
        domain: 'realm-chua-cap.vn',
        domainKind: 'primary',
        spaceId: 'id-dang-ky-a',
        slug: 'trung-tam-a',
        spaceKind: 'center',
        spaceStatus: 'active',
        issuerUrl: null,
      },
    });
    const request: FakeRequest = {
      headers: { host: 'realm-chua-cap.vn' },
      user: { id: 'u1', username: 'gv1' },
    };
    const error = await guard.canActivate(httpContext(request)).catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(UnauthorizedException);
    expect((error as UnauthorizedException).getResponse()).toMatchObject({ code: 'space.issuer_not_provisioned' });
    expect(tenantContext.enter).not.toHaveBeenCalled();
    expect(spaceDb.resolveTarget).not.toHaveBeenCalled();
  });

  it('lỗi liệt kê domain verified cũng làm guard từ chối', async () => {
    const failure = new Error('verified-domain listing failed');
    const { guard, tenantContext } = makeGuard({ listError: failure });
    const request: FakeRequest = {
      headers: { host: HOST_A },
      user: { id: 'u1', username: 'gv1' },
    };
    await expect(guard.canActivate(httpContext(request))).rejects.toBe(failure);
    expect(tenantContext.enter).not.toHaveBeenCalled();
  });

  it('domain đã xác minh thắng nhãn đầu của Host: domain tuỳ chọn không chứa slug vẫn chọn đúng tenant kho riêng', async () => {
    const { guard, tenantContext } = makeGuard({
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
      dedicatedTarget: {
        slug: 'trung-tam-a',
        spaceId: 'id-dang-ky-a',
        status: 'active',
        mode: 'dedicated',
        databaseName: 'lms_centre_a',
        connectionRole: 'centre_a_app',
        connectionString: 'postgres://centre_a_app:pw@db.internal:5432/lms_centre_a',
        schemaVersion: 'v3',
      },
      spaceTenant: { id: 'tenant-a', slug: 'trung-tam-a', status: 'active', kind: 'school' },
    });
    const request: FakeRequest = {
      headers: { host: 'truong-a.vn' },
      user: { id: 'u1', username: 'gv1' },
    };
    await expect(guard.canActivate(httpContext(request))).resolves.toBe(true);
    expect(request.tenantId).toBe('tenant-a');
    expect(tenantContext.enter).toHaveBeenCalledWith({ tenantId: 'tenant-a', userId: 'u1' });
  });
});
