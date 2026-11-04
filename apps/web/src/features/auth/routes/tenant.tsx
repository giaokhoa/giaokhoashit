import { useEffect, useState } from 'react';
import { redirect, useLoaderData } from 'react-router';
import { Button } from '@lms/ui/components/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@lms/ui/components/card';
import { PageShell } from '#components/page-shell';
import {
  roleLabelsOf,
  tenantAliasForHostname,
  tenantDisplayName,
  tenantStanding,
  type TenantIdentity,
} from '../kc-tenant';
import {
  currentTenantIdentity,
  fetchServerIdentity,
  initializeKeycloak,
  loginToTenant,
  logoutFromTenant,
} from '../kc-client';

/**
 * Trang vào của hai tenant nghiệm thu Keycloak (issue #38) — route standalone ngoài shell, đích đến của trang gốc
 * `/` khi hostname thuộc một trong hai tenant.
 *
 * Flow của trang:
 *
 *   1. Loader chỉ phân giải tenant từ hostname của request; hostname ngoài hai tenant nghiệm thu thì quay về trang
 *      gốc. Loader không đọc cookie, không giữ phiên và không gọi Keycloak, vì adapter chính thức chạy ở trình duyệt.
 *   2. Điểm vào phía client đã khởi tạo `keycloak-js` trước router trên hostname tenant. Component dùng lại promise
 *      khởi tạo đó, đọc danh tính từ `tokenParsed`, rồi gọi `GET /api/me` của `apps/server` bằng Bearer token sau khi
 *      adapter làm mới token; đáp ứng của server có cùng hình dạng danh tính nên thay thế bản đọc từ token khi gọi
 *      thành công.
 *   3. Hiển thị theo claim `organization`: chứa tenant của trang thì là thành viên và hiện vai trò; không chứa thì
 *      là khách của tenant đó, kèm tenant mà tài khoản thật sự thuộc về nếu có. Đăng nhập không bị chặn bởi role hay
 *      membership — Keycloak chỉ xác thực danh tính, tư cách được suy ra sau khi token đã về trang.
 */

/** Dữ kiện loader của trang tenant: chỉ alias của tenant suy từ hostname. */
interface TenantLoaderData {
  tenantAlias: string;
}

/**
 * Loader của trang tenant: phân giải tenant theo hostname.
 *
 * @param request - Request đang phục vụ (nguồn hostname).
 * @returns Alias tenant của trang; chuyển về `/` khi hostname không phải tenant nghiệm thu.
 */
export function loader({ request }: { request: Request }): TenantLoaderData | Response {
  const alias = tenantAliasForHostname(new URL(request.url).hostname);
  if (!alias) return redirect('/');
  return { tenantAlias: alias };
}

/**
 * Trang tenant: tên tenant; trạng thái đang kiểm tra phiên trong lúc adapter hoàn tất; nút Đăng nhập khi chưa có
 * token; danh tính và tư cách thành viên/khách khi đã đăng nhập, kèm nút Đăng xuất qua adapter.
 *
 * @returns Mặt trang tenant theo trạng thái của adapter.
 */
export default function TenantPage() {
  const { tenantAlias } = useLoaderData<typeof loader>();
  const [identity, setIdentity] = useState<TenantIdentity | null>(null);
  const [ready, setReady] = useState(false);
  const displayName = tenantDisplayName(tenantAlias);

  useEffect(() => {
    let active = true;
    void (async () => {
      const authenticated = await initializeKeycloak();
      if (!active) return;
      setIdentity(authenticated ? currentTenantIdentity() : null);
      setReady(true);
      if (!authenticated) return;
      const serverIdentity = await fetchServerIdentity();
      if (active && serverIdentity) setIdentity(serverIdentity);
    })();
    return () => {
      active = false;
    };
  }, []);

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
          {!ready && <p className="text-sm text-muted-foreground">Đang kiểm tra phiên đăng nhập…</p>}
          {ready && !identity && (
            <Button type="button" className="w-full" onClick={() => void loginToTenant()}>
              Đăng nhập
            </Button>
          )}
          {identity && <IdentitySection tenantAlias={tenantAlias} identity={identity} />}
          {identity && (
            <Button type="button" variant="outline" className="w-full" onClick={() => void logoutFromTenant()}>
              Đăng xuất
            </Button>
          )}
        </CardContent>
      </Card>
    </PageShell>
  );
}

/**
 * Khối danh tính sau đăng nhập: thông tin cơ bản của tài khoản và tư cách của tài khoản đối với tenant của trang.
 *
 * Thành viên của tenant trang thì thấy vai trò của mình; tài khoản đã đăng nhập nhưng không thuộc tenant trang thì
 * thấy tư cách khách của tenant đó và trung tâm thật của tài khoản (nếu có), không bị gán vai trò của tenant trang.
 *
 * @param props - Tenant của trang và danh tính đã chuẩn hoá từ token/server.
 * @returns Khối thông tin danh tính và tư cách.
 */
function IdentitySection({ tenantAlias, identity }: { tenantAlias: string; identity: TenantIdentity }) {
  const standing = tenantStanding(identity, tenantAlias);
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
          <dd>
            {identity.organizations.length > 0
              ? identity.organizations.map(tenantDisplayName).join(', ')
              : 'Không thuộc trung tâm nào'}
          </dd>
        </div>
      </dl>
      {standing === 'member' ? (
        <>
          <p className="text-sm">
            Bạn là <strong>thành viên của {tenantDisplayName(tenantAlias)}</strong>.
          </p>
          <p className="text-sm">
            Vai trò tại {tenantDisplayName(tenantAlias)}: <strong>{roles.length > 0 ? roles.join(', ') : '—'}</strong>
          </p>
        </>
      ) : (
        <>
          <p className="text-sm">
            Bạn là <strong>khách của {tenantDisplayName(tenantAlias)}</strong>.
          </p>
          <p className="text-sm">
            {identity.organizations.length > 0
              ? `Tài khoản thuộc ${identity.organizations.map(tenantDisplayName).join(', ')}.`
              : 'Tài khoản chưa thuộc trung tâm nào.'}
          </p>
        </>
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
  return [{ title: 'Trang tenant — Giáo khoa' }];
}
