import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import type { CreateTenant, CreateUser } from '@lms/contracts';
import { queryKeys as sharedQueryKeys } from '#lib/query-keys';
import {
  addTenantMember,
  createTenant,
  createTenantUser,
  fetchCurrentTenant,
  fetchTenantMembers,
  fetchTenantUsers,
  fetchTenants,
  setTenantMemberStatus,
} from '../api/tenant-admin';
import type { TenantMemberStatus } from '../types/tenant-admin';
import { queryKeys } from './keys';

/**
 * Tầng dữ liệu của bề mặt quản trị tenant.
 *
 * Luồng đọc có hai chế độ được quyết định bởi trang gọi:
 * - Platform: `GET /api/v1/tenants` trả mọi tenant; trang chọn một tenant rồi đọc membership và người dùng của đúng
 *   tenant đó.
 * - Tenant: `GET /api/v1/tenants/current` chỉ trả tenant hiệu lực của phiên; trang dùng chính `id` trong đáp ứng này
 *   cho mọi lời gọi membership và người dùng, không tự suy tenant từ danh sách platform.
 *
 * Mọi thao tác ghi đều đi qua API tenant hiện có. Sau khi server xác nhận, tầng này làm mới gốc khoá `admin.tenants` để
 * danh sách tenant, membership và hồ sơ người dùng cùng về trạng thái mới; riêng đổi trạng thái membership còn làm mới
 * ảnh chụp quyền vì membership là một cổng trong quá trình phân giải quyền của chính người bị đổi trạng thái. Lời gọi
 * API thuần nằm ở tầng `api/` của feature; khoá truy vấn riêng của admin nằm ở `keys.ts` cùng tầng này.
 */

export function tenantsQueryOptions() {
  return queryOptions({
    queryKey: queryKeys.tenants,
    queryFn: () => fetchTenants(),
  });
}

export function currentTenantQueryOptions() {
  return queryOptions({
    queryKey: queryKeys.currentTenant,
    queryFn: () => fetchCurrentTenant(),
  });
}

export function tenantMembersQueryOptions(tenantId: string) {
  return queryOptions({
    queryKey: queryKeys.tenantMembers(tenantId),
    queryFn: () => fetchTenantMembers(tenantId),
    enabled: tenantId !== '',
  });
}

export function tenantUsersQueryOptions(tenantId: string) {
  return queryOptions({
    queryKey: queryKeys.tenantUsers(tenantId),
    queryFn: () => fetchTenantUsers(tenantId),
    enabled: tenantId !== '',
  });
}

export function useCreateTenant() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (draft: CreateTenant) => createTenant(draft),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.tenantsRoot }),
  });
}

export function useAddTenantMember() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { tenantId: string; userId: string }) => addTenantMember(input),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.tenantsRoot }),
  });
}

export function useCreateTenantUser() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { tenantId: string; draft: CreateUser }) => createTenantUser(input),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.tenantsRoot }),
  });
}

export function useSetTenantMemberStatus() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { tenantId: string; userId: string; status: TenantMemberStatus }) =>
      setTenantMemberStatus(input),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: queryKeys.tenantsRoot });
      void client.invalidateQueries({ queryKey: sharedQueryKeys.capabilities });
    },
  });
}
