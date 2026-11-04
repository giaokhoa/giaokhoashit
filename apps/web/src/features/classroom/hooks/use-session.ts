/**
 * Hook phiên của feature classroom (issue #102): danh tính, tư cách tại trang và vai hiển thị của người dùng.
 *
 * Flow: điểm vào của app đã khởi tạo adapter `keycloak-js` dùng chung trên các hostname trung tâm trước khi
 * hydrate (cùng tiền điều kiện với trang tenant), nên hook chỉ đọc trạng thái đã xác thực của adapter — không tự
 * khởi tạo lần hai, vì adapter chỉ được khởi tạo đúng một lần. Khi đã xác thực, danh tính lấy từ `GET /api/me`
 * của `apps/server` (nguồn sự thật phía server), còn vai tại trang đọc từ claim của token: vai theo Organization
 * nằm ở `organization[<alias>].realm_access.roles`, vai realm chỉ dùng để nhận diện `platform-admin`. Vai ở đây
 * chỉ để dẫn luồng hiển thị; server vẫn là nơi quyết mọi quyền và các màn hình luôn xử lý đáp ứng từ chối.
 *
 * Trên hostname không phải trang trung tâm (trang nền tảng), alias rỗng: các màn hình vẫn mở ở ngữ cảnh cá nhân
 * và server tự quyết định phần dữ kiện trung tâm nào bị từ chối.
 */
import { useEffect, useState } from 'react';
import type { KeycloakTokenParsed } from 'keycloak-js';
import { getKeycloakAdapter } from '#lib/kc-adapter';
import { tenantAliasForHostname } from '#lib/kc-tenant-hosts';
import { fetchMe } from '../api/client';
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { classroomKeys } from '../queries/keys';
import type { MeView } from '../types/server';

/** Nhãn hiển thị của các vai đã biết; vai mặc định của Keycloak không có nhãn nên tự bị lọc khỏi phần hiển thị. */
const ROLE_LABELS: Record<string, string> = {
  'tenant-admin': 'Quản trị trung tâm',
  'tenant-teacher': 'Giáo viên',
  'tenant-member': 'Học sinh',
  'platform-admin': 'Quản trị nền tảng',
};

/**
 * Rút vai tại trang hiện tại từ claim của token.
 *
 * Vai theo Organization chỉ đọc ở `organization[<alias>].realm_access.roles` khi claim ở dạng map; claim ở dạng
 * mảng alias không mang vai nên phần vai theo Organization rỗng. Vai realm ở tầng trên cùng không bao giờ được
 * dùng làm vai của trung tâm — chỉ `platform-admin` được cộng thêm vì đó là vai toàn nền tảng.
 *
 * @param tokenParsed - Claim đã giải mã của access token, hoặc `undefined` khi chưa đăng nhập.
 * @param alias - Alias của Organization của trang, hoặc `null` ở trang nền tảng.
 * @param realmRoles - Vai realm do `GET /api/me` trả.
 * @returns Danh sách mã vai tại trang, đã khử trùng lặp.
 */
export function pageRolesOf(
  tokenParsed: KeycloakTokenParsed | undefined,
  alias: string | null,
  realmRoles: string[],
): string[] {
  const roles = new Set<string>();
  if (alias && tokenParsed) {
    const claim: unknown = tokenParsed['organization'];
    if (claim !== null && typeof claim === 'object' && !Array.isArray(claim)) {
      const entry: unknown = (claim as Record<string, unknown>)[alias];
      if (entry !== null && typeof entry === 'object') {
        const realmAccess: unknown = (entry as Record<string, unknown>)['realm_access'];
        if (realmAccess !== null && typeof realmAccess === 'object') {
          const orgRoles: unknown = (realmAccess as Record<string, unknown>)['roles'];
          if (Array.isArray(orgRoles)) {
            for (const role of orgRoles) {
              if (typeof role === 'string') roles.add(role);
            }
          }
        }
      }
    }
  }
  if (realmRoles.includes('platform-admin')) {
    roles.add('platform-admin');
  }
  return [...roles];
}

/**
 * Ánh xạ mã vai sang nhãn hiển thị tiếng Việt, bỏ qua các vai không có nhãn (vai mặc định của Keycloak).
 *
 * @param roles - Danh sách mã vai tại trang.
 * @returns Danh sách nhãn hiển thị tương ứng.
 */
export function roleLabelsOf(roles: string[]): string[] {
  return roles.map((role) => ROLE_LABELS[role]).filter((label): label is string => label !== undefined);
}

/** Trạng thái phiên mà các màn hình của feature dùng để dẫn luồng. */
export type ClassroomSession =
  | { phase: 'loading' }
  | { phase: 'anonymous' }
  | { phase: 'error'; error: Error }
  | {
      phase: 'ready';
      /** Danh tính theo `GET /api/me`. */
      me: MeView;
      /** Alias Organization của trang, `null` ở trang nền tảng. */
      alias: string | null;
      /** Mã vai tại trang (xem `pageRolesOf`). */
      roles: string[];
      /** Có vai quản trị trung tâm tại trang hay không. */
      isCenterAdmin: boolean;
      /** Có vai giáo viên tại trang hay không. */
      isTeacher: boolean;
      /** Có vai học sinh (thành viên) tại trang hay không. */
      isStudent: boolean;
    };

/**
 * Đọc phiên hiện tại cho các màn hình của feature.
 *
 * Side-effect: phát sinh đúng một truy vấn `GET /api/me` (dùng chung cache theo `classroomKeys.me`) khi adapter
 * đã xác thực; không điều hướng và không tự khởi tạo adapter.
 *
 * @returns Trạng thái phiên theo kiểu `ClassroomSession`; `anonymous` khi adapter chưa xác thực.
 */
export function useClassroomSession(): ClassroomSession {
  const [authenticated, setAuthenticated] = useState<boolean | null>(null);
  useEffect(() => {
    setAuthenticated(getKeycloakAdapter().authenticated === true);
  }, []);
  const meQuery: UseQueryResult<MeView> = useQuery({
    queryKey: classroomKeys.me,
    queryFn: fetchMe,
    enabled: authenticated === true,
  });

  if (authenticated === null) return { phase: 'loading' };
  if (!authenticated) return { phase: 'anonymous' };
  if (meQuery.isPending) return { phase: 'loading' };
  if (meQuery.isError) return { phase: 'error', error: meQuery.error };
  const me = meQuery.data;
  const alias = tenantAliasForHostname(window.location.hostname);
  const roles = pageRolesOf(getKeycloakAdapter().tokenParsed, alias, me.realmRoles);
  return {
    phase: 'ready',
    me,
    alias,
    roles,
    isCenterAdmin: roles.includes('tenant-admin'),
    isTeacher: roles.includes('tenant-teacher'),
    isStudent: roles.includes('tenant-member'),
  };
}

/**
 * Đưa người dùng về luồng đăng nhập của Keycloak, giữ nguyên trang đang đứng làm đích quay lại.
 *
 * Side-effect: chuyển hướng toàn trang sang máy chủ Keycloak qua adapter.
 */
export function requestLogin(): void {
  void getKeycloakAdapter().login({ redirectUri: window.location.href });
}
