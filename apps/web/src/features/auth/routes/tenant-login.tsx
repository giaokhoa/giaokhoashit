import { redirect } from 'react-router';
import { Card, CardContent, CardHeader, CardTitle } from '@lms/ui/components/card';
import { PageShell } from '#components/page-shell';
import { tenantAliasForHostname } from '../kc-tenant';
import {
  createPendingLogin,
  getOidcDiscovery,
  keycloakClientId,
  pendingLoginCookie,
  publicOrigin,
} from '../kc-oidc.server';

/**
 * Điểm bắt đầu đăng nhập Keycloak của trang tenant (issue #38).
 *
 * Flow của loader: kiểm hostname thuộc hai tenant nghiệm thu (ngoài ra
 * quay về trang gốc) → sinh bộ ba PKCE/state/nonce và gói vào cookie
 * HttpOnly ngắn hạn → chuyển hướng trình duyệt tới authorization
 * endpoint của issuer với `response_type=code`,
 * `scope=openid organization` và challenge S256. Scope `organization`
 * xin tường minh vì claim `organization` là dữ kiện tư cách của trang
 * tenant; gán scope này làm client scope mặc định không đủ để Keycloak
 * đưa claim vào token.
 *
 * `redirect_uri` dựng từ origin công khai của chính request (host của
 * tenant đang mở) nên cùng một bản build phục vụ cả hai tenant.
 *
 * Loader luôn kết thúc bằng chuyển hướng nên component mặc định không
 * bao giờ hiện ở luồng thật; nó tồn tại vì quy định route module của
 * app (mọi module đăng ký phải có export mặc định) và là mặt dự phòng
 * khi điều hướng phía client chạm vào route này.
 */

/**
 * Loader bắt đầu đăng nhập: sinh tham số chống giả mạo và chuyển hướng
 * sang trang đăng nhập của Keycloak.
 *
 * @param request - Request đang phục vụ (nguồn hostname và origin).
 * @returns Đáp ứng chuyển hướng tới authorization endpoint, kèm cookie
 *   chờ callback; hoặc chuyển về `/` khi hostname không phải tenant.
 */
export async function loader({ request }: { request: Request }) {
  const url = new URL(request.url);
  if (!tenantAliasForHostname(url.hostname)) return redirect('/');
  const pending = createPendingLogin();
  const discovery = await getOidcDiscovery();
  const authorize = new URL(discovery.authorizationEndpoint);
  authorize.searchParams.set('client_id', keycloakClientId());
  authorize.searchParams.set('redirect_uri', `${publicOrigin(request)}/tenant/callback`);
  authorize.searchParams.set('response_type', 'code');
  authorize.searchParams.set('scope', 'openid organization');
  authorize.searchParams.set('code_challenge', pending.challenge);
  authorize.searchParams.set('code_challenge_method', 'S256');
  authorize.searchParams.set('state', pending.state);
  authorize.searchParams.set('nonce', pending.nonce);
  return redirect(authorize.toString(), {
    headers: { 'Set-Cookie': pendingLoginCookie(pending) },
  });
}

/**
 * Mặt dự phòng của route bắt đầu đăng nhập — loader đã chuyển hướng
 * sang Keycloak ở mọi luồng thật.
 *
 * @returns Thông báo đang chuyển hướng.
 */
export default function TenantLoginPage() {
  return (
    <PageShell width="narrow" contentClassName="mx-auto w-full max-w-md">
      <Card>
        <CardHeader>
          <CardTitle>Đang chuyển tới trang đăng nhập…</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm">Nếu trang không tự chuyển, hãy quay lại trang tenant và bấm Đăng nhập lại.</p>
        </CardContent>
      </Card>
    </PageShell>
  );
}
