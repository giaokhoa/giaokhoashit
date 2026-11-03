import { redirect } from 'react-router';
import { Card, CardContent, CardHeader, CardTitle } from '@lms/ui/components/card';
import { PageShell } from '#components/page-shell';
import {
  clearPendingLoginCookie,
  createSessionCookie,
  exchangeCode,
  getOidcDiscovery,
  keycloakClientId,
  publicOrigin,
  readPendingLogin,
  validateIdToken,
} from '../kc-oidc.server';

/**
 * Callback OIDC của trang tenant (issue #38) — đích `redirect_uri` đã
 * đăng ký cho client `lms-web`.
 *
 * Flow của loader: đọc `code`/`state` từ query và bộ ba chờ trong
 * cookie → state không khớp thì huỷ lượt (xoá cookie chờ, về trang
 * tenant ở trạng thái chưa đăng nhập) → đổi code lấy token ở kênh
 * server-to-server kèm PKCE verifier → kiểm các claim bắt buộc của ID
 * token (issuer, audience, hạn dùng, nonce) → lập phiên phía web
 * (token nằm ở kho phiên phía server, trình duyệt chỉ nhận mã phiên
 * trong cookie HttpOnly) → về trang tenant. Mọi lối ra đều xoá cookie
 * chờ để bộ ba không dùng lại được cho lượt khác.
 *
 * Loader luôn kết thúc bằng chuyển hướng nên component mặc định không
 * bao giờ hiện ở luồng thật; nó tồn tại vì quy định route module của
 * app (mọi module đăng ký phải có export mặc định).
 */

/**
 * Loader xử lý callback: kiểm state, đổi code, lập phiên, về trang tenant.
 *
 * @param request - Request callback mang `code` và `state` trên query.
 * @returns Chuyển hướng về `/tenant`; thành công thì kèm cookie phiên.
 */
export async function loader({ request }: { request: Request }) {
  const url = new URL(request.url);
  const clearPending = clearPendingLoginCookie();
  const fail = () => redirect('/tenant', { headers: { 'Set-Cookie': clearPending } });

  const code = url.searchParams.get('code');
  const state = url.searchParams.get('state');
  const pending = readPendingLogin(request);
  if (!code || !pending || state !== pending.state) return fail();

  let tokens;
  let issuer: string;
  try {
    issuer = (await getOidcDiscovery()).issuer;
    tokens = await exchangeCode({
      code,
      verifier: pending.verifier,
      redirectUri: `${publicOrigin(request)}/tenant/callback`,
    });
  } catch {
    return fail();
  }
  if (!validateIdToken(tokens.idToken, { issuer, clientId: keycloakClientId(), nonce: pending.nonce })) return fail();

  const headers = new Headers();
  headers.append('Set-Cookie', clearPending);
  headers.append('Set-Cookie', createSessionCookie(tokens));
  return redirect('/tenant', { headers });
}

/**
 * Mặt dự phòng của route callback — loader đã chuyển về trang tenant ở
 * mọi luồng thật.
 *
 * @returns Thông báo đang xử lý đăng nhập.
 */
export default function TenantCallbackPage() {
  return (
    <PageShell width="narrow" contentClassName="mx-auto w-full max-w-md">
      <Card>
        <CardHeader>
          <CardTitle>Đang xử lý đăng nhập…</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm">Nếu trang không tự chuyển, hãy quay lại trang tenant.</p>
        </CardContent>
      </Card>
    </PageShell>
  );
}
