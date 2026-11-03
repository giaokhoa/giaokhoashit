import type {
  AssignRole,
  AssignmentListResponse,
  CapabilityMatrixResponse,
  CreateRole,
  OverrideCapability,
  ReplaceRoleCapabilities,
  Role,
  UpdateRole,
  UserListStatus,
  UserWithAssignmentsListResponse,
} from '@lms/contracts';
import { apiJson } from '#lib/api';

/** Tạo vai trò mới (POST /access/roles) — vai mới bắt đầu với 0 ghi đè. */
export function createRole(draft: CreateRole) {
  return apiJson<Role>('/api/v1/access/roles', { method: 'POST', body: JSON.stringify(draft) });
}

/**
 * Cập nhật tên/mô tả của vai trò (PATCH /access/roles/:id) — `shortName`
 * và `archetype` không đổi được qua endpoint này.
 */
export function updateRole(input: { roleId: string; draft: UpdateRole }) {
  return apiJson<Role>(`/api/v1/access/roles/${input.roleId}`, {
    method: 'PATCH',
    body: JSON.stringify(input.draft),
  });
}

/**
 * Lưu ma trận thay toàn bộ cho một vai tại đúng một ngữ cảnh
 * (PUT /access/roles/:id/capabilities) — nguyên tử: sau khi áp dụng
 * thành công, tập ghi đè của vai tại ngữ cảnh đó bằng đúng tập gửi
 * lên; phần tử vắng mặt trở về kế thừa.
 *
 * @param input - Định danh vai trò, ngữ cảnh và tập ghi đè khác kế thừa.
 * @returns Các ghi đè đã lưu.
 */
export function replaceRoleCapabilities(input: { roleId: string; payload: ReplaceRoleCapabilities }) {
  return apiJson<{ capability: string; permission: number }[]>(`/api/v1/access/roles/${input.roleId}/capabilities`, {
    method: 'PUT',
    body: JSON.stringify(input.payload),
  });
}

/**
 * Xoá vai trò (DELETE /access/roles/:id) — backend từ chối vai dựng
 * sẵn (có `archetype`) và vai còn người giữ, với mã lỗi riêng cho từng
 * trường hợp; tầng hiển thị dịch mã lỗi thành thông báo cho người dùng.
 *
 * @param roleId - Định danh vai trò cần xoá.
 */
export function deleteRole(roleId: string) {
  return apiJson<{ deleted: boolean }>(`/api/v1/access/roles/${roleId}`, { method: 'DELETE' });
}

/**
 * Tham số liệt kê dòng gán vai của đợt 3 — mọi lọc, tìm và phân trang
 * của bảng đều đi qua các tham số server này, không lọc phía client
 * trên tập đã tải toàn bộ. Bộ lọc/lượt tìm rỗng được bỏ khỏi chuỗi
 * truy vấn để backend hiểu là không lọc theo tiêu chí đó.
 */
export interface ListAssignmentsParams {
  userId?: string;
  roleId?: string;
  contextId?: string;
  search?: string;
  page: number;
  pageSize: number;
}

export function listAssignments(params: ListAssignmentsParams) {
  const query = new URLSearchParams();
  if (params.userId) query.set('userId', params.userId);
  if (params.roleId) query.set('roleId', params.roleId);
  if (params.contextId) query.set('contextId', params.contextId);
  if (params.search) query.set('search', params.search);
  query.set('page', String(params.page));
  query.set('pageSize', String(params.pageSize));
  return apiJson<AssignmentListResponse>(`/api/v1/access/assignments?${query.toString()}`);
}

/** Cỡ trang của lượt quét toàn bộ tập người giữ vai — khớp trần phía server của danh sách gán vai. */
const ALL_ASSIGNMENTS_PAGE_SIZE = 100;

export async function fetchRoleHolderUserIds(roleId: string, contextId: string) {
  const userIds = new Set<string>();
  let page = 1;
  let total = Number.POSITIVE_INFINITY;
  let fetched = 0;
  while (fetched < total) {
    const result = await listAssignments({ roleId, contextId, page, pageSize: ALL_ASSIGNMENTS_PAGE_SIZE });
    for (const row of result.assignments) userIds.add(row.userId);
    fetched += result.assignments.length;
    total = result.total;
    if (result.assignments.length === 0) break;
    page += 1;
  }
  return userIds;
}

/**
 * Gán một vai trò cho một người dùng tại một ngữ cảnh
 * (POST /access/assignments) — backend giữ nguyên các chốt chặn: gán
 * trùng cùng nguồn là no-op lặng, gán vai giảng dạy trúng chốt gói số
 * chỗ hoặc chốt tách vai bị từ chối với mã lỗi riêng; tầng hiển thị
 * dịch mã lỗi thành lý do, không báo thành công giả.
 *
 * @param input - Bộ ba người dùng × vai trò × ngữ cảnh cần gán.
 */
export function createAssignment(input: AssignRole) {
  return apiJson('/api/v1/access/assignments', { method: 'POST', body: JSON.stringify(input) });
}

/**
 * Gỡ một dòng gán (DELETE /access/assignments/:id) — dòng do ghi danh
 * quản lý (`component = 'enrol'`) bị backend từ chối với mã
 * `access.assignment_enrol_managed`; tầng hiển thị phân biệt dòng này
 * ngay trên bảng và dịch mã lỗi khi gặp trường hợp đua dữ kiện.
 *
 * @param assignmentId - Định danh dòng gán cần gỡ.
 */
export function deleteAssignment(assignmentId: string) {
  return apiJson<{ deleted: boolean }>(`/api/v1/access/assignments/${assignmentId}`, { method: 'DELETE' });
}

/**
 * Đọc ma trận quyền toàn trang tại một ngữ cảnh
 * (GET /access/capability-matrix?contextId=…): mọi vai trò kèm số
 * người giữ phân biệt và đúng các ghi đè ĐÃ LƯU tại ngữ cảnh đó —
 * năng lực vắng mặt trong đáp ứng nghĩa là đang ở trạng thái kế thừa
 * tại ngữ cảnh này. Trang Quyền độc lập dùng đáp ứng này làm nền cho
 * bảng ma trận và thẻ đếm ô Cho phép.
 *
 * @param contextId - Định danh ngữ cảnh cần đọc ma trận.
 * @returns Đáp ứng ma trận của ngữ cảnh.
 */
export function fetchCapabilityMatrix(contextId: string) {
  const query = new URLSearchParams({ contextId });
  return apiJson<CapabilityMatrixResponse>(`/api/v1/access/capability-matrix?${query.toString()}`);
}

/**
 * Đặt ghi đè quyền cho đúng một ô (POST /access/overrides): thân gồm
 * vai trò + năng lực + ngữ cảnh + giá trị bốn trạng thái; đặt giá trị
 * kế thừa (0) thì backend xoá dòng ghi đè của ô. Trang Quyền độc lập
 * gọi hàm này cho từng lần đổi ô — không có đường lưu toàn trang.
 *
 * @param input - Bộ bốn vai trò × năng lực × ngữ cảnh × giá trị.
 */
export function setCapabilityOverride(input: OverrideCapability) {
  return apiJson<{ updated: boolean }>('/api/v1/access/overrides', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export interface ListUsersWithAssignmentsParams {
  search?: string;
  roleId?: string;
  status?: UserListStatus;
  page: number;
  pageSize: number;
}

/**
 * Đọc một trang người dùng kèm toàn bộ gán vai của từng người
 * (GET /access/users-with-assignments): một người dùng đúng một phần
 * tử, `total` đếm người dùng khớp (không đếm dòng gán), thứ tự ổn
 * định theo định danh người dùng tăng dần. Bảng người dùng kèm vai
 * trò trên trang vai trò đọc qua hàm này — không tự ghép danh sách
 * người dùng với danh sách gán ở client.
 *
 * @param params - Bộ lọc/tìm/phân trang của bảng.
 * @returns Trang người dùng kèm gán vai và tổng số người dùng khớp.
 */
export function listUsersWithAssignments(params: ListUsersWithAssignmentsParams) {
  const query = new URLSearchParams();
  if (params.search) query.set('search', params.search);
  if (params.roleId) query.set('roleId', params.roleId);
  if (params.status) query.set('status', params.status);
  query.set('page', String(params.page));
  query.set('pageSize', String(params.pageSize));
  return apiJson<UserWithAssignmentsListResponse>(
    `/api/v1/access/users-with-assignments?${query.toString()}`,
  );
}
