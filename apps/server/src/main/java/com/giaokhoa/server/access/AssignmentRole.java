package com.giaokhoa.server.access;

import java.util.Optional;
import java.util.Set;

/**
 * Ánh xạ vai phân công thành tập hành động lõi được công nhận trong đúng phạm vi phân công (đúng Organization,
 * đúng bộ sưu tập, đúng loại tài nguyên), đúng bảng ở mục 7 của đặc tả lõi quyền. Không vai nào gồm
 * {@code view_answer} và không vai nào gồm {@code use}: nguồn phân công đứng một mình chỉ cấp đúng các hành
 * động trong bảng này.
 *
 * <p>
 * Riêng ở hai đường mà đặc tả dùng phân công làm điều kiện giao — grant cho Organization (bước 6 của thuật toán
 * quyết định ở mục 8) và điều kiện của bản được cấp phép — hành động do grant xác lập, còn phân công chỉ xác
 * nhận người gọi có vai trong đúng phạm vi tiếp nhận; ở hai đường đó một phân công còn hiệu lực được coi là bao
 * phủ cả {@code use}, vì phạm vi cụ thể của {@code use} do feat sở hữu loại tài nguyên định nghĩa trên nền
 * grant đã cấp. Xem {@link #coversAsGrantCondition(AccessAction)}.
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
		return this.actions.contains(action);
	}

	/**
	 * Điều kiện bao phủ khi phân công là vế giao của một grant: ngoài các hành động trong bảng ánh xạ, một phân
	 * công còn hiệu lực trong đúng phạm vi cũng bao phủ {@code use} — hành động này do grant xác lập, không do
	 * phân công tự cấp. Chỉ dùng ở đường grant cho Organization và điều kiện của bản được cấp phép.
	 */
	public boolean coversAsGrantCondition(AccessAction action) {
		return action == AccessAction.USE || covers(action);
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
