package com.giaokhoa.server.access;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Giới hạn sử dụng kế thừa từ nguồn, ghi trên tài nguyên ở dạng các chỉ thị cách nhau bằng dấu chấm phẩy:
 * {@code org:<organizationId>} — chỉ được dùng trong các Organization liệt kê; {@code action:<mã hành động>} —
 * chỉ các hành động liệt kê mới được phép trên tài nguyên; {@code reshare:<mã hành động>} — người có quyền trên
 * tài nguyên chỉ nhờ một grant được cấp lại đúng hành động đó. Các giới hạn này ràng buộc mọi quyết định trên tài
 * nguyên, kể cả quyết định cho chính chủ sở hữu, và được sao chép nguyên vẹn sang mọi bản sao của tài nguyên;
 * không thao tác nào trong lõi được nới lỏng hoặc xoá chúng.
 */
public record UsageLimits(Set<String> organizationIds, Set<String> actionCodes, Set<String> reshareActionCodes) {

	public static UsageLimits parse(String raw) {
		Set<String> organizations = new LinkedHashSet<>();
		Set<String> actions = new LinkedHashSet<>();
		Set<String> reshare = new LinkedHashSet<>();
		if (raw != null) {
			for (String token : raw.split("[;\\n]")) {
				String directive = token.trim();
				if (directive.startsWith("org:")) {
					organizations.add(directive.substring("org:".length()).trim());
				}
				else if (directive.startsWith("action:")) {
					actions.add(directive.substring("action:".length()).trim());
				}
				else if (directive.startsWith("reshare:")) {
					reshare.add(directive.substring("reshare:".length()).trim());
				}
			}
		}
		return new UsageLimits(organizations, actions, reshare);
	}

	public boolean restrictsOrganizations() {
		return !this.organizationIds.isEmpty();
	}

	public boolean restrictsActions() {
		return !this.actionCodes.isEmpty();
	}

}
