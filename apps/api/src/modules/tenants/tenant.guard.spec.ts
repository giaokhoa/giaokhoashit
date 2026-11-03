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
  headers: Record<string, string | undefined>;
}

function httpContext(request: FakeRequest): ExecutionContext {
  return {
    getType: () => 'http',
    getHandler: () => httpContextHandler,
    getClass: () => httpContextHandler,
    switchToHttp: () => ({ getRequest: () => request }),
  } as unknown as ExecutionContext;
}

function httpContextHandler() {}

function makeGuard(options?: {
  tenantById?: Record<string, { id: string; slug: string; status: string; kind: string } | undefined>;
  membershipStatus?: string;
  /** Dòng domain đã xác minh mà sổ control plane trả cho Host của ca kiểm chứng (undefined = Host không phải domain đã xác minh). */
  verifiedDomainRow?: Record<string, unknown> | undefined;
  /** Danh sách domain đã xác minh của không gian đích (đặt để bật chốt chặn domain của guard). */
  verifiedDomainNames?: string[];
  /** Đích kho riêng mà khung định tuyến trả cho slug của ca kiểm chứng. */
  dedicatedTarget?: Record<string, unknown>;
  /** Dòng tenant trong kho riêng của ca kiểm chứng. */
  spaceTenant?: Record<string, unknown>;
}) {
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
  const controlPlane =
    options?.verifiedDomainRow !== undefined || options?.verifiedDomainNames !== undefined
      ? {
          findVerifiedDomainByName: vi.fn(async () => options?.verifiedDomainRow),
          listVerifiedDomainNamesForSpace: vi.fn(async () => options?.verifiedDomainNames ?? []),
        }
      : undefined;
  const guard = new TenantGuard(
    repo as unknown as TenantsRepository,
    tenants as unknown as TenantsService,
    evaluator as unknown as PermissionEvaluator,
    tenantContext as unknown as TenantContextService,
    spaceDb as unknown as SpaceDatabaseService,
    controlPlane as never,
  );
  return { guard, repo, tenantContext, spaceDb };
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
      headers: { host: 'he-thong.example' },
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
      headers: { host: 'he-thong.example' },
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
