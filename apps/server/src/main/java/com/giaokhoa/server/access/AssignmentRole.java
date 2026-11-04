package com.giaokhoa.server.access;

import java.util.Optional;
import java.util.Set;

/**
 * Ánh xạ vai phân công thành tập hành động lõi được công nhận trong đúng phạm vi phân công (đúng Organization,
 * đúng bộ sưu tập, đúng loại tài nguyên). Không vai nào gồm {@code view_answer}.
 *
 * <p>
 * Không vai nào trong bảng này mang {@code use}: đó là hành động tiêu thụ mà feat sở hữu loại tài nguyên định
 * nghĩa phạm vi cụ thể, nên một phân công còn hiệu lực trên bộ sưu tập và loại tài nguyên tự nó đã là điều kiện
 * bao phủ cho {@code use} khi một nguồn quyền khác (chủ sở hữu, grant) cho phép hành động đó. Mọi hành động còn
 * lại đều phải khớp đúng ánh xạ trong bảng này.
 */
public enum AssignmentRole {

	BANK_MANAGER("bank-manager", Set.of(AccessAction.MANAGE_COLLECTION, AccessAction.VIEW_CONTENT)),
	BANK_EDITOR("bank-editor",
			Set.of(AccessAction.CREATE_IN_COLLECTION, AccessAction.VIEW_CONTENT, AccessAction.EDIT_DRAFT)),
	BANK_PUBLISHER("bank-publisher",
			Set.of(AccessAction.VIEW_CONTENT, AccessAction.REVIEW, AccessAction.PUBLISH)),
	RESOURCE_EDITOR("resource-editor", Set.of(AccessAction.VIEW_CONTENT, AccessAction.EDIT_DRAFT)),
	RESOURCE_REVIEWER("resource-reviewer", Set.of(AccessAction.VIEW_CONTENT, AccessAction.REVIEW)),
	RESOURCE_PUBLISHER("resource-publisher", Set.of(AccessAction.VIEW_CONTENT, AccessAction.PUBLISH));

	private final String code;

	private final Set<AccessAction> actions;

	AssignmentRole(String code, Set<AccessAction> actions) {
		this.code = code;
		this.actions = actions;
	}

	public String code() {
		return this.code;
	}

	public boolean covers(AccessAction action) {
		return action == AccessAction.USE || this.actions.contains(action);
	}

	public static Optional<AssignmentRole> fromCode(String code) {
		for (AssignmentRole role : values()) {
			if (role.code.equals(code)) {
				return Optional.of(role);
			}
		}
		return Optional.empty();
	}

}
