import { startTransition, StrictMode } from 'react';
import { hydrateRoot } from 'react-dom/client';
import { HydratedRouter } from 'react-router/dom';
import { tenantAliasForHostname } from '#lib/kc-tenant-hosts';

/**
 * Điểm vào phía trình duyệt (React Router framework mode): giữ StrictMode như bản thư viện trước đây để hành vi
 * render hai lần ở môi trường dev bắt lỗi sớm, bọc trong startTransition như mẫu chính thức.
 *
 * Riêng hai hostname tenant của issue #38, adapter Keycloak phải được khởi tạo trước router vì quá trình khởi tạo
 * có thể xử lý và làm sạch tham số callback trên URL. Adapter chỉ nạp động trên các hostname này nên các mặt còn
 * lại của app không trả chi phí hay đổi hành vi vì lát tenant.
 *
 * @returns Promise hoàn tất sau khi cây React đã được hydrate.
 */
async function bootstrap(): Promise<void> {
  if (tenantAliasForHostname(window.location.hostname)) {
    const { initializeKeycloak } = await import('#features/auth/kc-client');
    await initializeKeycloak();
  }
  startTransition(() => {
    hydrateRoot(
      document,
      <StrictMode>
        <HydratedRouter />
      </StrictMode>,
    );
  });
}

void bootstrap();
