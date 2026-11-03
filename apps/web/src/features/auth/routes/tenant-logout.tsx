import { redirect } from 'react-router';
import { Card, CardContent, CardHeader, CardTitle } from '@lms/ui/components/card';
import { PageShell } from '#components/page-shell';
import { destroySessionCookie, getOidcDiscovery, keycloakClientId, publicOrigin, readSession } from '../kc-oidc.server';

/**
 * Đăng xuất của trang tenant (issue #38).
 *
 * Flow của loader: huỷ phiên phía web (xoá khỏi kho phiên và xoá
 * cookie) rồi chuyển trình duyệt qua end-session endpoint của
 * Keycloak với `id_token_hint` của phiên vừa huỷ và
 * `post_logout_redirect_uri` về trang tenant, để phiên SSO của
 * Keycloak cũng kết thúc — sau đăng xuất, quay lại trang tenant là
 * trạng thái chưa đăng nhập thật, không bị SSO đăng nhập lại lặng lẽ.
 * Chưa có phiên (hoặc issuer không công bố end-session endpoint) thì
 * chỉ dọn phía web rồi về trang tenant.
 *
 * Loader luôn kết thúc bằng chuyển hướng nên component mặc định không
 * bao giờ hiện ở luồng thật; nó tồn tại vì quy định route module của
 * app (mọi module đăng ký phải có export mặc định).
 */

/**
 * Loader đăng xuất: dọn phiên phía web và kết thúc phiên Keycloak.
 *
 * @param request - Request đang phục vụ (nguồn cookie phiên và origin).
 * @returns Chuyển hướng tới end-session endpoint của Keycloak, hoặc về
 *   `/tenant` khi không có phiên để kết thúc ở Keycloak.
 */
export async function loader({ request }: { request: Request }) {
  const session = readSession(request);
  const clearCookie = destroySessionCookie(request);
  if (!session) return redirect('/tenant', { headers: { 'Set-Cookie': clearCookie } });

  let endSessionEndpoint: string | null = null;
  try {
    endSessionEndpoint = (await getOidcDiscovery()).endSessionEndpoint;
  } catch {
    endSessionEndpoint = null;
  }
  if (!endSessionEndpoint) return redirect('/tenant', { headers: { 'Set-Cookie': clearCookie } });

  const endSession = new URL(endSessionEndpoint);
  endSession.searchParams.set('id_token_hint', session.idToken);
  endSession.searchParams.set('post_logout_redirect_uri', `${publicOrigin(request)}/tenant`);
  endSession.searchParams.set('client_id', keycloakClientId());
  return redirect(endSession.toString(), { headers: { 'Set-Cookie': clearCookie } });
}

/**
 * Mặt dự phòng của route đăng xuất — loader đã chuyển hướng ở mọi
 * luồng thật.
 *
 * @returns Thông báo đang đăng xuất.
 */
export default function TenantLogoutPage() {
  return (
    <PageShell width="narrow" contentClassName="mx-auto w-full max-w-md">
      <Card>
        <CardHeader>
          <CardTitle>Đang đăng xuất…</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm">Nếu trang không tự chuyển, hãy quay lại trang tenant.</p>
        </CardContent>
      </Card>
    </PageShell>
  );
}
