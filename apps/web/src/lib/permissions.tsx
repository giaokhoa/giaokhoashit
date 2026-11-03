import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { Course, MyAccessView } from '@lms/contracts';
import { apiJson } from '#lib/api';
import { useAuth } from '#lib/auth';
import { queryKeys } from '#lib/query-keys';

export interface CourseAccess {
  course: Course;
  capabilities: string[];
}

const CONTEXT_LEVEL = { SYSTEM: 10, TENANT: 20, COURSECAT: 40, COURSE: 50 } as const;

const QUESTION_CAPABILITIES = [
  'question:useall',
  'question:add',
  'question:editall',
  'question:managecategory',
] as const;

export interface PermissionsState {
  ready: boolean;
  access: MyAccessView | null;
  capabilitiesError: Error | null;
  canViewUsers: boolean;
  canManageRoles: boolean;
  canAdminister: boolean;
  canViewPlatformTenants: boolean;
  canManagePlatformTenants: boolean;
  canViewTenantAdmin: boolean;
  canManageTenantAdmin: boolean;
  canManageGuardians: boolean;
  canUseQuestionBank: boolean;
  courses: CourseAccess[];
  teaching: CourseAccess[];
  learning: CourseAccess[];
  hasLearnerFootprint: boolean;
}

const PermissionsContext = createContext<PermissionsState | null>(null);

async function probe<T>(path: string): Promise<T | null> {
  try {
    return await apiJson<T>(path);
  } catch {
    return null;
  }
}

/**
 * Capability đã phân giải tại context hệ thống (luôn có trong ảnh chụp quyền). Export để loader SSR của dashboard (lát
 * SL-1, `routes/home-index.tsx`) phân loại khối quản trị bằng ĐÚNG cùng định nghĩa này thay vì chép lại.
 */
export function systemCapabilities(access: MyAccessView): string[] {
  return access.contexts.find((c) => c.contextLevel === CONTEXT_LEVEL.SYSTEM)?.capabilities ?? [];
}

/**
 * Phân giải capability hiệu lực của người dùng tại context của MỘT khoá học từ ảnh chụp quyền: ưu tiên gán vai trò ngay
 * tại context khoá (sâu nhất), sau đó tới gán tại context danh mục chứa khoá, cuối cùng là context hệ thống — đúng thứ
 * tự kế thừa mà PermissionEvaluator áp dụng khi kiểm quyền tại context khoá. Nhiều gán cùng cấp thì hợp nhất
 * capability. Export để loader SSR của dashboard (lát SL-1) prefetch dữ liệu điểm cho đúng tập khoá dạy/học mà provider
 * sẽ phân loại.
 */
export function courseCapabilities(access: MyAccessView, course: Course): string[] {
  const capsByContext = new Map(access.contexts.map((c) => [c.contextId, c.capabilities]));
  const levels = [
    { level: CONTEXT_LEVEL.COURSE, instanceId: course.id },
    { level: CONTEXT_LEVEL.COURSECAT, instanceId: course.category },
  ];
  for (const { level, instanceId } of levels) {
    const caps = new Set<string>();
    let matched = false;
    for (const assignment of access.assignments) {
      if (assignment.contextLevel === level && assignment.instanceId === instanceId) {
        matched = true;
        for (const cap of capsByContext.get(assignment.contextId) ?? []) caps.add(cap);
      }
    }
    if (matched) return [...caps];
  }
  return systemCapabilities(access);
}

/**
 * Capability hiệu lực tại đúng một tenant.
 *
 * Luồng suy luận bám ảnh chụp quyền của API: capability tại context hệ thống luôn được cộng vào vì quyền platform kế
 * thừa xuống context tenant; phần riêng của tenant chỉ lấy từ các gán vai có context cấp tenant và `instanceId` bằng
 * đúng tenant đang xét, sau đó tra capability đã phân giải của chính context đó. Hàm không dùng membership để suy quyền
 * quản trị — thuộc về tenant và quản lý tenant là hai quan hệ khác nhau, API vẫn thực thi lại ở từng endpoint.
 */
export function tenantCapabilities(access: MyAccessView | null, tenantId: string): string[] {
  if (!access) return [];
  const caps = new Set(systemCapabilities(access));
  const capsByContext = new Map(access.contexts.map((c) => [c.contextId, c.capabilities]));
  for (const assignment of access.assignments) {
    if (assignment.contextLevel !== CONTEXT_LEVEL.TENANT || assignment.instanceId !== tenantId) continue;
    for (const cap of capsByContext.get(assignment.contextId) ?? []) caps.add(cap);
  }
  return [...caps];
}

/**
 * Người dùng có một capability tenant tại ít nhất một tenant hay không. Hàm chỉ phục vụ việc mở điều hướng: trang đích
 * vẫn phải xét tenant hiệu lực hoặc tenant được chọn bằng `tenantCapabilities` và vẫn chịu cổng 403 của API.
 */
function hasTenantCapabilitySomewhere(access: MyAccessView, capability: string): boolean {
  if (access.isSiteAdmin || systemCapabilities(access).includes(capability)) return true;
  const capsByContext = new Map(access.contexts.map((c) => [c.contextId, c.capabilities]));
  return access.assignments.some(
    (assignment) =>
      assignment.contextLevel === CONTEXT_LEVEL.TENANT &&
      (capsByContext.get(assignment.contextId) ?? []).includes(capability),
  );
}

function isCourseStaff(access: MyAccessView | null, courseAccess: CourseAccess): boolean {
  if (!access) return false;
  return access.isSiteAdmin || courseAccess.capabilities.includes('grade:viewall');
}

/**
 * Người dùng có gán vai trò tại chính context của khoá này hay không — đây là toàn bộ định nghĩa “thuộc khoá” đã chốt ở
 * #33 (ghi danh đang hiệu lực ∪ gán vai tại context khoá) sau khi đối chiếu phía API: nửa “ghi danh” của hợp nhất LUÔN
 * hiện diện trong ảnh chụp dưới dạng gán vai, vì tầng ghi danh của API vật chất hoá mọi ghi danh thành một gán vai tại
 * context khoá — instance ghi danh luôn chốt một vai (mặc định vai học viên), ghi danh thì cấp vai đó, rút ghi danh thì
 * gỡ đúng vai đó, đình chỉ thì giữ vai (khớp hành vi cũ: học viên bị đình chỉ vẫn ở nhóm learning qua nhánh gán vai).
 * Đây cũng là cùng định nghĩa mà roster tin nhắn dùng phía API (listMembershipCourseIds chỉ tính gán vai ở context có
 * contextLevel = COURSE, instanceId = id của khoá). Nhờ vậy phân loại không còn cần probe `enrolments/me` cho từng khoá
 * nữa (F-02, rà soát #133): ảnh chụp quyền đã mang sẵn mọi gán vai của người dùng (MyAccessView.assignments). Giáo viên
 * phụ trách khoá qua gán vai (đúng thiết kế seed: editingteacher tại context khoá, không qua ghi danh) nhờ đó thấy khoá
 * ở nhóm giảng dạy thay vì rơi vào cả hai danh sách đều rỗng.
 *
 * Export để loader SSR của dashboard (lát SL-1) dùng chung định nghĩa.
 */
export function hasCourseAssignment(access: MyAccessView | null, course: Course): boolean {
  if (!access) return false;
  return access.assignments.some(
    (a) => a.contextLevel === CONTEXT_LEVEL.COURSE && a.instanceId === course.id,
  );
}

export function PermissionsProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const enabled = !!user;

  // Query capabilities KHÔNG nuốt lỗi vào null như probe thường lệ: lỗi phải nổi lên query error state để provider phơi
  // ra qua `capabilitiesError` (F-15) — bề mặt vẫn suy biến mềm vì access được đọc từ data (undefined khi lỗi → null).
  const capabilitiesQuery = useQuery({
    queryKey: queryKeys.capabilities,
    queryFn: () => apiJson<MyAccessView>('/api/v1/users/me/capabilities'),
    enabled,
    staleTime: 60_000,
  });
  const coursesQuery = useQuery({
    queryKey: queryKeys.courses,
    queryFn: () => probe<Course[]>('/api/v1/courses'),
    enabled,
  });
  const courseList = useMemo(() => coursesQuery.data ?? [], [coursesQuery.data]);
  const accessSnapshot = capabilitiesQuery.data ?? null;

  const value = useMemo<PermissionsState>(() => {
    if (!user) {
      return {
        ready: true,
        access: null,
        capabilitiesError: null,
        canViewUsers: false,
        canManageRoles: false,
        canAdminister: false,
        canViewPlatformTenants: false,
        canManagePlatformTenants: false,
        canViewTenantAdmin: false,
        canManageTenantAdmin: false,
        canManageGuardians: false,
        canUseQuestionBank: false,
        courses: [],
        teaching: [],
        learning: [],
        hasLearnerFootprint: false,
      };
    }
    const access = accessSnapshot;
    const courses: CourseAccess[] = courseList.map((course) => ({
      course,
      capabilities: access ? courseCapabilities(access, course) : [],
    }));
    const sysCaps = access ? systemCapabilities(access) : [];
    const canViewUsers = (access?.isSiteAdmin ?? false) || sysCaps.includes('user:viewdetails');
    const canManageRoles = (access?.isSiteAdmin ?? false) || sysCaps.includes('role:manage');
    const canViewPlatformTenants =
      (access?.isSiteAdmin ?? false) || sysCaps.includes('tenant:viewall') || sysCaps.includes('tenant:manage');
    const canManagePlatformTenants = (access?.isSiteAdmin ?? false) || sysCaps.includes('tenant:manage');
    const canViewTenantAdmin =
      canViewPlatformTenants ||
      (access
        ? hasTenantCapabilitySomewhere(access, 'tenant:viewall') || hasTenantCapabilitySomewhere(access, 'tenant:manage')
        : false);
    const canManageTenantAdmin =
      canManagePlatformTenants || (access ? hasTenantCapabilitySomewhere(access, 'tenant:manage') : false);
    const teaching = courses.filter(
      (c) => hasCourseAssignment(access, c.course) && isCourseStaff(access, c),
    );
    const learning = courses.filter(
      (c) => hasCourseAssignment(access, c.course) && !isCourseStaff(access, c),
    );
    return {
      ready: !capabilitiesQuery.isPending && !coursesQuery.isPending,
      access,
      capabilitiesError: capabilitiesQuery.error ?? null,
      canViewUsers,
      canManageRoles,
      // Cổng mở UI quản trị KHÔNG được tính user:viewdetails: đó là capability nền mà API phân giải cho mọi người dùng
      // đã xác thực ở context hệ thống (để xem hồ sơ lẫn nhau), nên nếu tính cả nó thì mọi học viên đều thấy khối quản
      // trị rồi bị 403 ở các endpoint con. Chỉ các capability quản trị thật (quản vai trò, cấu hình site) hoặc cờ site
      // admin mới mở được khu vực này — API vẫn là nơi thực thi quyền thật ở từng endpoint.
      canAdminister: (access?.isSiteAdmin ?? false) || canManageRoles || sysCaps.includes('site:config'),
      canViewPlatformTenants,
      canManagePlatformTenants,
      canViewTenantAdmin,
      canManageTenantAdmin,
      canManageGuardians:
        (access?.isSiteAdmin ?? false) ||
        (access?.contexts.some((c) => c.capabilities.includes('guardian:manage')) ?? false),
      canUseQuestionBank:
        (access?.isSiteAdmin ?? false) ||
        (access?.contexts.some((c) =>
          c.capabilities.some((cap) => (QUESTION_CAPABILITIES as readonly string[]).includes(cap)),
        ) ??
          false),
      courses,
      teaching,
      learning,
      hasLearnerFootprint: learning.length > 0 || (access?.hasLearnerArtifacts ?? false),
    };
  }, [
    user,
    accessSnapshot,
    capabilitiesQuery.isPending,
    capabilitiesQuery.error,
    coursesQuery.isPending,
    courseList,
  ]);

  return <PermissionsContext.Provider value={value}>{children}</PermissionsContext.Provider>;
}

export function usePermissions(): PermissionsState {
  const ctx = useContext(PermissionsContext);
  if (!ctx) throw new Error('usePermissions must be used inside PermissionsProvider');
  return ctx;
}
