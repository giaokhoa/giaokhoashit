import { data, redirect, useLoaderData } from 'react-router';
import { buttonVariants } from '@lms/ui/components/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@lms/ui/components/card';
import { PageShell } from '#components/page-shell';
import {
  isMemberOfTenant,
  roleLabelsOf,
  tenantAliasForHostname,
  tenantDisplayName,
  type TenantIdentity,
} from '../kc-tenant';
import { destroySessionCookie, fetchIdentity, readSession } from '../kc-oidc.server';

/**
 * Trang vào của hai tenant nghiệm thu Keycloak (issue #38) — route
 * standalone ngoài shell, đích đến của trang gốc `/` khi hostname thuộc
 * một trong hai tenant.
 *
 * Flow của loader: phân giải tenant từ hostname của request (hostname
 * ngoài hai tenant nghiệm thu thì quay về trang gốc) → đọc phiên phía
 * web (cookie `lms_kc_session`) → nếu có phiên, lấy danh tính qua
 * `GET /api/me` của `apps/server` bằng access token trong kho phiên.
 * Phiên còn mà server không còn nhận token (hết hạn ở phía Keycloak,
 * server khởi động lại) thì xoá cookie phiên và hiển thị trạng thái
 * chưa đăng nhập.
 *
 * Hiển thị theo tư cách đối với tenant của trang: claim `organization`
 * trong danh tính chứa tenant của trang → người dùng là thành viên, hiện
 * vai trò của họ trong tenant đó; không chứa → hiện khẳng định tài khoản
 * thuộc organization nào (hoặc không thuộc trung tâm nào) và không có
 * quyền tại tenant của trang, kèm thông tin cơ bản để đối chiếu. Token
 * thô không bao giờ tới được component này — loader chỉ truyền danh
 * tính đã ánh xạ từ claim.
 */

/** Dữ kiện loader của trang tenant: tenant của trang + danh tính (null khi chưa đăng nhập). */
interface TenantLoaderData {
  tenantAlias: string;
  identity: TenantIdentity | null;
}

/**
 * Loader của trang tenant: phân giải tenant theo hostname và nạp danh
 * tính của phiên hiện tại từ `apps/server`.
 *
 * @param request - Request đang phục vụ (nguồn hostname và cookie phiên).
 * @returns Tenant của trang kèm danh tính, hoặc null danh tính khi chưa
 *   đăng nhập; chuyển về `/` khi hostname không phải tenant nghiệm thu.
 */
export async function loader({ request }: { request: Request }) {
  const alias = tenantAliasForHostname(new URL(request.url).hostname);
  if (!alias) return redirect('/');
  const session = readSession(request);
  if (!session) return data({ tenantAlias: alias, identity: null } satisfies TenantLoaderData);
  const identity = await fetchIdentity(session.accessToken);
  if (!identity) {
    // Phiên phía web còn nhưng apps/server đã bác token: dọn cookie
    // phiên để trạng thái hiển thị khớp trạng thái thật.
    return data({ tenantAlias: alias, identity: null } satisfies TenantLoaderData, {
      headers: { 'Set-Cookie': destroySessionCookie(request) },
    });
  }
  return data({ tenantAlias: alias, identity } satisfies TenantLoaderData);
}

/**
 * Trang tenant: tên tenant, nút Đăng nhập khi chưa có phiên; danh tính
 * và khẳng định tư cách khi đã đăng nhập, kèm nút Đăng xuất.
 *
 * @returns Mặt trang tenant theo trạng thái phiên.
 */
export default function TenantPage() {
  const { tenantAlias, identity } = useLoaderData<typeof loader>();
  const displayName = tenantDisplayName(tenantAlias);
  return (
    <PageShell width="narrow" contentClassName="mx-auto w-full max-w-md">
      <Card>
        <CardHeader>
          <CardTitle>{displayName}</CardTitle>
          <CardDescription>
            Trang của tenant <strong>{tenantAlias}</strong> — đăng nhập bằng tài khoản Keycloak của realm giaokhoa.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {!identity && (
            <a href="/tenant/login" className={buttonVariants({ className: 'w-full' })}>
              Đăng nhập
            </a>
          )}
          {identity && <IdentitySection tenantAlias={tenantAlias} identity={identity} />}
          {identity && (
            <a href="/tenant/logout" className={buttonVariants({ variant: 'outline', className: 'w-full' })}>
              Đăng xuất
            </a>
          )}
        </CardContent>
      </Card>
    </PageShell>
  );
}

/**
 * Khối danh tính sau đăng nhập: thông tin cơ bản của tài khoản và khẳng
 * định tư cách đối với tenant của trang.
 *
 * Hai nhánh hiển thị là ranh giới quyền của lát nghiệm thu: thành viên
 * của tenant trang thì thấy vai trò của mình trong tenant đó; không
 * phải thành viên thì chỉ thấy khẳng định không thuộc/không có quyền —
 * tuyệt đối không gán vai trò của tenant trang cho tài khoản ngoài.
 *
 * @param props - Tenant của trang và danh tính từ `apps/server`.
 * @returns Khối thông tin danh tính + tư cách.
 */
function IdentitySection({ tenantAlias, identity }: { tenantAlias: string; identity: TenantIdentity }) {
  const member = isMemberOfTenant(identity, tenantAlias);
  const roles = roleLabelsOf(identity);
  return (
    <div className="space-y-3">
      <dl className="space-y-1 text-sm">
        <div>
          <dt className="font-medium">Họ tên</dt>
          <dd>{identity.name ?? '—'}</dd>
        </div>
        <div>
          <dt className="font-medium">Tên đăng nhập</dt>
          <dd>{identity.preferredUsername ?? '—'}</dd>
        </div>
        <div>
          <dt className="font-medium">Email</dt>
          <dd>{identity.email ?? '—'}</dd>
        </div>
        <div>
          <dt className="font-medium">Thuộc trung tâm</dt>
          <dd>{identity.organizations.length > 0 ? identity.organizations.join(', ') : 'Không thuộc trung tâm nào'}</dd>
        </div>
      </dl>
      {member ? (
        <p className="text-sm">
          Vai trò tại {tenantDisplayName(tenantAlias)}: <strong>{roles.length > 0 ? roles.join(', ') : '—'}</strong>
        </p>
      ) : (
        <p className="text-sm font-medium">
          Tài khoản thuộc{' '}
          {identity.organizations.length > 0 ? identity.organizations.join(', ') : 'không thuộc trung tâm nào'} — không
          thuộc {tenantAlias} và không có quyền tại {tenantAlias}.
        </p>
      )}
    </div>
  );
}

/**
 * Tiêu đề trang theo tenant đang mở.
 *
 * @returns Mảng meta của React Router.
 */
export function meta() {
  return [{ title: 'Trang tenant — LMS' }];
}
