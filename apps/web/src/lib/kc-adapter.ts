/**
 * Instance adapter `keycloak-js` duy nhất của toàn app, ở tầng dùng chung.
 *
 * Vì sao module này tồn tại: adapter giữ token trong bộ nhớ của chính instance và quá trình khởi tạo xử lý tham
 * số callback trên URL, nên toàn app chỉ được có đúng một instance — hai instance là hai phiên độc lập, token của
 * instance này không nhìn thấy ở instance kia. Trước đây việc tạo instance nằm trong feature auth (trang tenant);
 * từ khi các màn hình nghiệp vụ của bản phát hành 07/11 (feature classroom) cũng cần access token để gọi
 * `apps/server`, điểm tạo instance đưa xuống tầng dùng chung này để mọi feature dùng lại đúng một instance, còn
 * vòng đời khởi tạo (check-sso im lặng, PKCE S256, scope `organization:*`) vẫn do feature auth sở hữu.
 *
 * Cấu hình là đúng ba giá trị tài liệu của adapter yêu cầu cho client công khai `lms-web` của realm `giaokhoa`
 * trên Keycloak dev — không thêm lớp biến môi trường.
 */
import Keycloak from 'keycloak-js';

/** Instance adapter dùng chung; chỉ tạo lười ở lần gọi phía trình duyệt đầu tiên. */
let adapter: Keycloak | null = null;

/**
 * Tạo (lần đầu) hoặc lấy instance adapter `keycloak-js` dùng chung của app. Module có thể được nạp trong bản
 * build SSR nhưng việc tạo instance chỉ xảy ra khi một hàm phía client thật sự gọi hàm này.
 *
 * @returns Instance adapter dùng chung.
 */
export function getKeycloakAdapter(): Keycloak {
  adapter ??= new Keycloak({
    url: 'https://auth.fuckr.net',
    realm: 'giaokhoa',
    clientId: 'lms-web',
  });
  return adapter;
}
