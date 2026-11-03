import type { Capability } from '@lms/contracts';

/**
 * Kiểu dữ liệu của hộp thoại ma trận vai trò thuộc feature admin.
 *
 * Ma trận chỉ trình bày các ghi đè đã lưu tại ngữ cảnh đang chọn, không
 * trình bày quyền hiệu lực sau kế thừa: một năng lực vắng mặt trong bản
 * nháp nghĩa là đang ở trạng thái kế thừa (INHERIT = 0) tại ngữ cảnh
 * đó, và khi lưu, endpoint thay toàn bộ chỉ nhận các ghi đè khác kế
 * thừa — phần tử vắng mặt trong tập gửi lên trở về kế thừa.
 */

export type RoleMatrixDraft = Record<string, number>;

export interface CapabilityGroup {
  resource: string;
  capabilities: Capability[];
}

export function resourceOfCapability(name: string): string {
  const index = name.indexOf(':');
  return index === -1 ? name : name.slice(0, index);
}

export function groupCapabilitiesByResource(capabilities: Capability[]): CapabilityGroup[] {
  const byResource = new Map<string, Capability[]>();
  for (const capability of capabilities) {
    const resource = resourceOfCapability(capability.name);
    const group = byResource.get(resource);
    if (group) group.push(capability);
    else byResource.set(resource, [capability]);
  }
  return [...byResource.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([resource, group]) => ({
      resource,
      capabilities: [...group].sort((left, right) => left.name.localeCompare(right.name)),
    }));
}

export function verbOfCapability(name: string): string {
  const index = name.indexOf(':');
  return index === -1 ? name : name.slice(index + 1);
}
