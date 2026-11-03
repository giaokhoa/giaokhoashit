import {
  CanActivate,
  ExecutionContext,
  ForbiddenException,
  Inject,
  Injectable,
  Optional,
  UnauthorizedException,
} from '@nestjs/common';
import type { AuthenticatedUser } from '../../common/current-user.decorator';
import { SpaceDatabaseService } from '../../db/routing/space-database.service';
import { PermissionEvaluator } from '../access/permission-evaluator.service';
import { extractSpaceSlugFromHost, resolveDedicatedSpaceBySlug } from './space-resolution';
import { TenantsRepository } from '@lms/db/repositories/tenants';
import { ControlPlaneRepository } from '@lms/db/repositories/control-plane';
import { tenantAls } from './tenant-als';
import { TenantsService } from './tenants.service';
import { TenantContextService } from './tenant-context.service';
import {
  createVerifiedDomainLookup,
  findVerifiedSpaceForHost,
  isHostVerifiedForSpace,
  type VerifiedDomainGuardLookup,
} from './domain-resolution';

/**
 * TenantGuard (TENANT-0, #168 — hồ sơ mục 4.1): chạy sau JwtAuthGuard,
 * phân giải tenant hiệu lực của request và nạp vào TenantContextService
 * (AsyncLocalStorage qua `enterWith`, nên mọi handler/interceptor chạy
 * sau trong cùng chuỗi request đều thấy). Thứ tự phân giải: claim `tid`
 * của token (đã kiểm chứng: tenant còn active; tenant school/center
 * đòi membership active) → tenant mặc định xác định của user → tenant-
 * zero cho request ẩn danh (không gian công cộng của Trang chính).
 *
 * `tid` trỏ vào tenant đã khoá → 403 `tenant.not_active`; membership đã
 * kết thúc mà token còn hạn → 401 `tenant.membership_inactive` để
 * client đăng nhập lại/chuyển tenant (site admin được rơi về tenant-
 * zero thay vì bị khoá ngoài khỏi hệ thống vận hành của chính mình).
 * `tid` đã khai mà không tra được dòng tenant nào → 401
 * `tenant.unknown`, fail-closed thay vì rơi lặng về tenant-zero
 * (Phần A, issue #587).
 *
 * Đường kho riêng (issue #584): request ở địa chỉ của một trung tâm ở
 * kho riêng KHÔNG đi qua cổng membership của kho chung nữa — tài
 * khoản của trung tâm đó không tồn tại ở kho chung nên cổng cũ sẽ
 * từ chối oan. Nhánh kho riêng phân giải tenant từ CHÍNH kho của
 * trung tâm (dòng tenant mang slug của địa chỉ): tenant đó phải
 * active; tenant school/center đòi membership active TRONG kho riêng
 * (thành viên nội bộ của trung tâm, không nối tới tài khoản toàn
 * cục); tenant kind `platform` (site chính ở kho riêng) mở như
 * tenant-zero của đường chung. Ngoại lệ duy nhất giữ luồng cũ là
 * phiên mang `tid` của tenant platform — đường vận hành platform,
 * để `SpaceContextGuard` miễn trừ sau đó như trước.
 */
@Injectable()
export class TenantGuard implements CanActivate {
  constructor(
    private readonly repo: TenantsRepository,
    private readonly tenants: TenantsService,
    private readonly evaluator: PermissionEvaluator,
    private readonly tenantContext: TenantContextService,
    private readonly spaceDb: SpaceDatabaseService,
    // Sổ domain đã xác minh (vòng sửa PR #25): tham số tuỳ chọn để
    // các test đơn vị cũ dựng guard không cần control plane vẫn chạy
    // đường slug cũ; khi có sổ, nhánh kho riêng dưới đây phải qua
    // chốt chặn domain trước khi chọn tenant (xem
    // `domain-resolution.ts`). TenantsModule đã import
    // ControlPlaneModule nên runtime Nest luôn cấp được provider này.
    @Optional()
    @Inject(ControlPlaneRepository)
    private readonly controlPlane?: Pick<
      ControlPlaneRepository,
      'findVerifiedDomainByName' | 'listVerifiedDomainNamesForSpace'
    >,
  ) {}

  /**
   * Cổng tra cứu domain đã xác minh của guard, hoặc `undefined` khi
   * control plane chưa được cấp/cấu hình — khi đó guard giữ đường
   * slug cũ như trước (control plane chưa cấu hình không phải lỗi
   * của request). Lỗi tra cứu ở đây được nuốt về `undefined` ở điểm
   * gọi, không ném ra ngoài.
   */
  private verifiedDomainLookup(): VerifiedDomainGuardLookup | undefined {
    if (!this.controlPlane) return undefined;
    return {
      findVerifiedDomain: createVerifiedDomainLookup(this.controlPlane),
      listVerifiedDomainNamesForSpace: (spaceId: string) =>
        this.controlPlane!.listVerifiedDomainNamesForSpace(spaceId),
    };
  }

  async canActivate(context: ExecutionContext): Promise<boolean> {
    // HOTFIX #196: guard là khâu THIẾT LẬP ngữ cảnh nên chính các tra cứu
    // của nó (tenant-zero, tenant của claim tid, membership) phải chạy
    // trong ngữ cảnh platform tường minh — ngoài ngữ cảnh, RLS
    // fail-closed giấu sạch bảng tenants/memberships, guard tưởng tenant
    // chưa gieo và cho request chạy KHÔNG ngữ cảnh (chỉ lộ khi app chạy
    // bằng role thường). Ngữ cảnh cho handler vẫn do
    // TenantContextInterceptor bọc lại từ `request.tenantId` như trước.
    return tenantAls.run({ platform: true }, () => this.resolveRequestTenant(context));
  }

  private async resolveRequestTenant(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<{
      user?: AuthenticatedUser;
      tenantId?: string;
      headers: Record<string, string | string[] | undefined>;
    }>();
    // Tenant-zero qua bản cache singleton của repository: danh tính
    // của nó cố định từ lúc bootstrap nên không tra lại xuống DB ở
    // mọi request (xem TenantsRepository.getPlatformTenant).
    const zero = await this.repo.getPlatformTenant();
    // Tenant-zero chưa được gieo (chỉ có thể xảy ra trước bootstrap seed
    // trong các test đơn vị không boot app đầy đủ): không gắn ngữ cảnh.
    if (!zero) return true;
    const user = request.user;
    // Nhánh kho riêng (issue #584): request đã xác thực ở địa chỉ
    // của trung tâm ở kho riêng thì tenant hiệu lực là dòng tenant
    // trong CHÍNH kho của trung tâm đó — không tra kho chung, không
    // đòi membership toàn cục. Phiên vận hành platform (không mang
    // claim `centre_id`, mang `tid` của tenant platform) là đường
    // vận hành platform nên rơi xuống luồng cũ bên dưới; điều kiện
    // thiếu claim là bắt buộc vì phiên của site chính mang cùng
    // `tid` nhưng mang `centre_id` của site chính và phải đi nhánh
    // kho riêng.
    if (user) {
      // Chốt chặn domain đã xác minh (vòng sửa PR #25, xem
      // `domain-resolution.ts`): Host khớp domain đã xác minh thì slug
      // của không gian đó thắng nhãn đầu của Host; Host còn lại đi
      // đường slug cũ, nhưng nếu không gian đích đã có domain xác
      // minh mà Host không nằm trong số đó thì bị từ chối ngay tại
      // đây — không được rơi xuống luồng kho chung/platform bên dưới.
      const hostHeader = request.headers?.host;
      const verifiedLookup = this.verifiedDomainLookup();
      let verifiedSpace: Awaited<ReturnType<typeof findVerifiedSpaceForHost>> | undefined;
      if (verifiedLookup) {
        try {
          verifiedSpace = await findVerifiedSpaceForHost(hostHeader, verifiedLookup.findVerifiedDomain);
        } catch {
          verifiedSpace = undefined;
        }
      }
      const effectiveSlug = verifiedSpace?.slug ?? extractSpaceSlugFromHost(
        Array.isArray(hostHeader) ? hostHeader[0] : hostHeader,
      );
      const space = effectiveSlug ? await resolveDedicatedSpaceBySlug(this.spaceDb, effectiveSlug) : null;
      const isPlatformSession = user.tenantId !== undefined && user.tenantId === zero.id && user.centreId === undefined;
      if (space && !isPlatformSession) {
        if (verifiedLookup) {
          let verified = true;
          try {
            verified = await isHostVerifiedForSpace(hostHeader, space.spaceId, verifiedLookup);
          } catch {
            verified = true;
          }
          if (!verified) {
            throw new UnauthorizedException({
              message: 'Host is not a verified domain of the centre at this address',
              code: 'space.domain_not_verified',
            });
          }
        }
        const spaceTenant = await this.spaceDb.withSpaceKysely(space.slug, () =>
          this.repo.getTenantBySlug(space.slug),
        );
        if (!spaceTenant) {
          throw new UnauthorizedException({
            message: 'Space tenant is not ready in its own store',
            code: 'space.tenant_missing',
          });
        }
        if (spaceTenant.status !== 'active') {
          throw new ForbiddenException({
            message: `Tenant ${spaceTenant.slug} is ${spaceTenant.status}`,
            code: 'tenant.not_active',
          });
        }
        if (spaceTenant.kind !== 'platform') {
          const membership = await this.spaceDb.withSpaceKysely(space.slug, () =>
            this.repo.getMembership(spaceTenant.id, user.id),
          );
          if (membership?.status !== 'active') {
            throw new UnauthorizedException({
              message: 'Membership in the active tenant is no longer active',
              code: 'tenant.membership_inactive',
            });
          }
        }
        this.tenantContext.enter({ tenantId: spaceTenant.id, userId: user.id });
        // Ghim cả lên request như luồng cũ: interceptor đọc lại từ
        // đây để bọc ALS quanh handler (kèm slug trung tâm do
        // SpaceContextGuard ghim sau đó).
        request.tenantId = spaceTenant.id;
        return true;
      }
    }
    let tenantId = zero.id;
    if (user) {
      if (user.tenantId) {
        const tenant = await this.repo.getTenant(user.tenantId);
        // Bất biến fail-closed (Phần A, issue #587): `tid` đã khai
        // trong phiên mà không tra được dòng tenant nào thì KHÔNG
        // được rơi lặng về tenant platform — phiên đó không gắn
        // được vào trung tâm nào nên bị từ chối rõ ràng ngay tại
        // đây, thay vì chạy tiếp dưới danh tính tenant platform.
        if (!tenant) {
          throw new UnauthorizedException({
            message: 'Tenant declared by the session could not be resolved',
            code: 'tenant.unknown',
          });
        }
        if (tenant.status !== 'active') {
          throw new ForbiddenException({
            message: `Tenant ${tenant.slug} is ${tenant.status}`,
            code: 'tenant.not_active',
          });
        }
        if (tenant.kind === 'platform') {
          tenantId = tenant.id;
        } else {
          const membership = await this.repo.getMembership(tenant.id, user.id);
          if (membership?.status === 'active') {
            tenantId = tenant.id;
          } else if (await this.evaluator.isSiteAdmin(user.id)) {
            tenantId = zero.id;
          } else {
            throw new UnauthorizedException({
              message: 'Membership in the active tenant is no longer active',
              code: 'tenant.membership_inactive',
            });
          }
        }
      } else {
        tenantId = (await this.tenants.resolveDefaultTenant(user.id)).id;
      }
    }
    this.tenantContext.enter({ tenantId, userId: user?.id });
    // Ghim cả lên request: `enterWith` ở guard không sống qua biên
    // guard→handler của Nest (đã kiểm chứng bằng spec #168: handler
    // thấy ALS rỗng), nên TenantContextInterceptor đọc lại từ đây và
    // bọc `run` quanh lúc handler được subscribe mới giữ được ngữ
    // cảnh cho toàn bộ chuỗi xử lý của request.
    request.tenantId = tenantId;
    return true;
  }
}
