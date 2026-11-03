import type { UserListResponse, UserListSortBy, UserListStatus, UserProfile } from '@lms/contracts';
import { apiJson } from '#lib/api';
import type { NewUserDraft, UpdateUserDraft } from '../types/admin-users';

export function fetchAdminUsers(page: number, pageSize: number, search: string) {
  return fetchAdminUsersList({ page, pageSize, search });
}

/**
 * Tham số liệt kê người dùng đầy đủ theo hợp đồng truy vấn của đợt 1:
 * lọc trạng thái, sắp xếp và phân trang đều chạy phía server; tham số
 * tuỳ chọn vắng mặt được bỏ khỏi chuỗi truy vấn để server giữ hành vi
 * mặc định vốn có của nó (ẩn tài khoản đã xoá, thứ tự theo id).
 */
export interface ListAdminUsersParams {
  page: number;
  pageSize: number;
  search?: string;
  status?: UserListStatus;
  sortBy?: UserListSortBy;
  sortDirection?: 'asc' | 'desc';
}

export function fetchAdminUsersList(params: ListAdminUsersParams) {
  const query = new URLSearchParams();
  if (params.search) query.set('search', params.search);
  if (params.status) query.set('status', params.status);
  if (params.sortBy) query.set('sortBy', params.sortBy);
  if (params.sortBy && params.sortDirection) query.set('sortDirection', params.sortDirection);
  query.set('page', String(params.page));
  query.set('pageSize', String(params.pageSize));
  return apiJson<UserListResponse>(`/api/v1/users?${query.toString()}`);
}

/** Cỡ trang của lượt quét toàn bộ cho đường lọc theo vai trò — đúng trần server của hợp đồng danh sách. */
const ALL_USERS_PAGE_SIZE = 100;

/**
 * Đọc TOÀN BỘ người dùng khớp bộ lọc/sắp xếp (GET /users, quét lần
 * lượt các trang ở cỡ trần 100). Chỉ dùng cho đường lọc theo vai trò
 * ở ngữ cảnh hệ thống: hợp đồng danh sách không có tham số vai trò,
 * nên trang lấy đủ tập người dùng đã lọc/sắp xếp phía server rồi giao
 * với tập người giữ vai (cũng do server lọc qua endpoint gán vai) —
 * mọi phép lọc vẫn chạy phía server, phía client chỉ giao hai tập
 * định danh và cắt trang để hiển thị.
 *
 * @param params - Bộ lọc và sắp xếp (không gồm phân trang hiển thị).
 * @returns Toàn bộ người dùng khớp, giữ đúng thứ tự server đã sắp.
 */
export async function fetchAllAdminUsers(params: Omit<ListAdminUsersParams, 'page' | 'pageSize'>) {
  const users: UserProfile[] = [];
  let page = 1;
  let total = Number.POSITIVE_INFINITY;
  while (users.length < total) {
    const result = await fetchAdminUsersList({ ...params, page, pageSize: ALL_USERS_PAGE_SIZE });
    users.push(...result.users);
    total = result.total;
    if (result.users.length === 0) break;
    page += 1;
  }
  return { users, total };
}

export function fetchAdminUser(userId: string) {
  return apiJson<UserProfile>(`/api/v1/users/${userId}`);
}

export function toggleUserSuspended(input: { id: string; suspended: boolean }) {
  return apiJson(`/api/v1/users/${input.id}/${input.suspended ? 'suspend' : 'reactivate'}`, {
    method: 'POST',
  });
}

export function deleteAdminUser(id: string) {
  return apiJson(`/api/v1/users/${id}`, { method: 'DELETE' });
}

export function createAdminUser(draft: NewUserDraft) {
  return apiJson('/api/v1/users', { method: 'POST', body: JSON.stringify(draft) });
}

export function updateAdminUser(input: { userId: string; draft: UpdateUserDraft }) {
  return apiJson(`/api/v1/users/${input.userId}`, {
    method: 'PATCH',
    body: JSON.stringify(input.draft),
  });
}
