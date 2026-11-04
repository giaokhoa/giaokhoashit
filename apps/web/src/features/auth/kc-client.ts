/**
 * Adapter Keycloak phía trình duyệt cho trang tenant (issue #38).
 *
 * File này là điểm duy nhất của feature auth sở hữu instance `keycloak-js`. Flow tổng thể:
 *
 *   1. Điểm vào phía client khởi tạo adapter trước router trên hai hostname tenant; các lần gọi sau dùng lại đúng
 *      một promise khởi tạo. Adapter chạy `check-sso` im lặng, dùng authorization code flow mặc định của nó cùng
 *      PKCE S256 và xin scope `organization:*` để token mang mọi Organization mà người dùng là thành viên, kèm id
 *      và vai theo từng Organization (adapter luôn tự thêm `openid`).
 *   2. Sau khởi tạo, token nằm trong bộ nhớ do adapter quản; component đọc danh tính đã ánh xạ từ `tokenParsed`,
 *      không đọc cookie phiên và không tự giải mã chuỗi JWT.
 *   3. Khi cần gọi `apps/server`, hàm lấy danh tính phía server làm mới token bằng `updateToken(30)` trước rồi mới
 *      gửi access token ở header `Authorization: Bearer`.
 *   4. Đăng nhập và đăng xuất đều quay về chính `/tenant` của hostname hiện tại để adapter xử lý callback ở cùng
 *      trang và trang tự chia lại hiển thị theo claim mới.
 *
 * Adapter chỉ được tạo lười khi một hàm phía client thật sự chạy: module có thể được nạp trong bản build SSR,
 * nhưng việc chạm vào `window` và khởi tạo Keycloak không bao giờ xảy ra phía server.
 */
import Keycloak from 'keycloak-js';
import { identityFromTokenClaims, type TenantIdentity } from './kc-tenant';

/** Instance adapter duy nhất của trang tenant; chỉ tồn tại sau lần gọi phía trình duyệt đầu tiên. */
let keycloak: Keycloak | null = null;

/** Promise khởi tạo dùng chung để điểm vào và component không khởi tạo adapter hai lần. */
let initialization: Promise<boolean> | null = null;

/**
 * Tạo hoặc lấy adapter với cấu hình client công khai `lms-web` của realm `giaokhoa` trên Keycloak dev — đúng ba
 * giá trị cấu hình mà tài liệu của adapter yêu cầu, không thêm lớp biến môi trường cho lát nghiệm thu này.
 *
 * @returns Instance adapter dùng chung của feature auth.
 */
function getKeycloak(): Keycloak {
  keycloak ??= new Keycloak({
    url: 'https://auth.fuckr.net',
    realm: 'giaokhoa',
    clientId: 'lms-web',
  });
  return keycloak;
}

/**
 * Địa chỉ quay về sau đăng nhập/đăng xuất: đúng trang tenant trên origin hiện tại, để cùng một bản build phục vụ cả
 * hai hostname tenant và adapter nhận callback tại route đang hiển thị.
 *
 * @returns URL tuyệt đối của `/tenant` trên origin hiện tại.
 */
function tenantRedirectUri(): string {
  return `${window.location.origin}/tenant`;
}

/**
 * Khởi tạo adapter một lần bằng `check-sso` im lặng và PKCE S256. Lỗi khởi tạo được quy về trạng thái chưa đăng
 * nhập để trang tenant vẫn hiển thị được nút Đăng nhập thay vì làm sập toàn bộ cây React.
 *
 * @returns `true` khi Keycloak xác nhận người dùng đã đăng nhập; `false` khi chưa đăng nhập hoặc khởi tạo lỗi.
 */
export function initializeKeycloak(): Promise<boolean> {
  initialization ??= (async () => {
    try {
      return await getKeycloak().init({
        onLoad: 'check-sso',
        silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
        pkceMethod: 'S256',
        scope: 'organization:*',
      });
    } catch (error) {
      console.error('Failed to initialize Keycloak adapter', error);
      return false;
    }
  })();
  return initialization;
}

/**
 * Đọc danh tính hiện tại từ `tokenParsed` của adapter.
 *
 * @returns Danh tính đã chuẩn hoá khi adapter đã xác thực và token có `sub`; ngược lại `null`.
 */
export function currentTenantIdentity(): TenantIdentity | null {
  const adapter = getKeycloak();
  return adapter.authenticated ? identityFromTokenClaims(adapter.tokenParsed) : null;
}

/**
 * Bắt đầu đăng nhập bằng adapter. Không điều kiện role hay membership nào được kiểm tra ở bước này: Keycloak chỉ
 * xác thực danh tính, còn việc chia hiển thị diễn ra sau khi trang nhận lại token.
 *
 * @returns Promise của adapter; ở luồng thật trình duyệt sẽ chuyển sang trang đăng nhập của Keycloak.
 */
export function loginToTenant(): Promise<void> {
  return getKeycloak().login({ redirectUri: tenantRedirectUri() });
}

/**
 * Đăng xuất qua adapter để phiên SSO của Keycloak kết thúc cùng phiên của ứng dụng.
 *
 * @returns Promise của adapter; ở luồng thật trình duyệt sẽ đi qua end-session endpoint rồi quay về `/tenant`.
 */
export function logoutFromTenant(): Promise<void> {
  return getKeycloak().logout({ redirectUri: tenantRedirectUri() });
}

/**
 * Lấy danh tính qua `GET /api/me` của `apps/server` bằng access token hiện tại. Token được làm mới trước khi gọi
 * khi còn dưới 30 giây hiệu lực; server vẫn là nơi kiểm chứng JWT ở lời gọi này. Lỗi mạng hoặc đáp ứng không thành
 * công trả `null` để component giữ danh tính đã đọc trực tiếp từ token.
 *
 * @returns Danh tính do server ánh xạ từ JWT, hoặc `null` khi chưa đăng nhập/không gọi được server.
 */
export async function fetchServerIdentity(): Promise<TenantIdentity | null> {
  const adapter = getKeycloak();
  if (!adapter.authenticated) return null;
  try {
    await adapter.updateToken(30);
    if (!adapter.token) return null;
    const response = await fetch('/api/me', {
      headers: { Authorization: `Bearer ${adapter.token}` },
    });
    if (!response.ok) return null;
    return (await response.json()) as TenantIdentity;
  } catch {
    return null;
  }
}
