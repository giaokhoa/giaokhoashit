# Điểm nghiệm thu tenant (tenant-demo)

Ứng dụng demo rất nhỏ phục vụ việc nghiệm thu đầu-cuối của đăng nhập Keycloak với nhiều tenant: mở trang tại hostname
của một trung tâm, bấm "Đăng nhập", được chuyển sang Keycloak realm `giaokhoa` (client public `lms-web`), đăng nhập
xong quay lại và thấy thông tin của chính mình cùng tenant (Organization) mình thuộc về.

Trang sau đăng nhập đối chiếu tenant của trang đang mở (nhãn đầu của hostname) với claim `organization` của tài
khoản. Là thành viên của tenant đang mở thì trang hiển thị vai trò của người dùng trong tenant đó, dạng "Giáo
viên — thuộc trung-tam-2". Không phải thành viên thì trang khẳng định tài khoản không thuộc tenant đang mở và
không có quyền tại đó: quyền của tài khoản chỉ tồn tại trong organization mà nó là thành viên, theo đúng mô hình
Organizations của Keycloak.

Ứng dụng là một file `server.js` dùng thuần thư viện chuẩn của Node (http, fetch, crypto), tự cài đặt authorization
code flow với PKCE S256, `state` và `nonce` theo OpenID Connect Core/Discovery và RFC 7636, có kiểm chứng chữ ký ID
token qua JWKS của issuer. Không có dependency nào. Phiên đăng nhập chỉ ở bộ nhớ tiến trình; token không bao giờ
được hiển thị hay ghi log.

## Cách chạy

Trong stack: service `tenant-demo` của `compose.yaml` ở gốc repo build thư mục này và publish cổng host 8082.

Chạy tay bằng Docker:

```bash
docker build -t tenant-demo keycloak/tenant-demo
docker run --rm -p 8082:3000 tenant-demo
```

Chạy tay bằng Node (bản LTS hiện hành):

```bash
node keycloak/tenant-demo/server.js
```

## Biến môi trường

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `PORT` | `3000` | Cổng HTTP trong tiến trình/container. |
| `OIDC_ISSUER` | `https://auth.fuckr.net/realms/giaokhoa` | Issuer của realm; đặt để chạy cùng Keycloak local. |
| `TENANT_DEMO_ALLOWED_HOSTS` | `trung-tam-1.fuckr.net,trung-tam-2.fuckr.net` | Host được phép dựng `redirect_uri`. |
| `TENANT_DEMO_PUBLIC_SCHEME` | `https` | Scheme địa chỉ công khai; đặt `http` khi chạy local. |

Phía Keycloak, client `lms-web` cần có redirect URI và post-logout redirect URI cho từng hostname trong danh sách
cho phép, và client scope `organization` ở dạng Default để claim `organization` có mặt trong token/userinfo.
