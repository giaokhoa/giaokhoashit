package com.giaokhoa.server.access;

import java.util.Optional;

/**
 * Tập hành động lõi dùng chung cho mọi loại tài nguyên. Các hành động độc lập ở mức quyết định: được một hành động
 * không suy ra được hành động khác, và riêng {@code view_answer} không bao giờ được suy ra từ {@code edit_draft}
 * hoặc {@code review}.
 */
public enum AccessAction {

	VIEW_CONTENT("view_content"),
	VIEW_ANSWER("view_answer"),
	USE("use"),
	COPY("copy"),
	SHARE("share"),
	SUBMIT("submit"),
	CREATE_IN_COLLECTION("create_in_collection"),
	EDIT_DRAFT("edit_draft"),
	REVIEW("review"),
	PUBLISH("publish"),
	MANAGE_COLLECTION("manage_collection"),
	MANAGE_ASSIGNMENT("manage_assignment"),
	READ_MATH_SOURCE("read_math_source"),
	EDIT_MATH_SOURCE("edit_math_source");

	private final String code;

	AccessAction(String code) {
		this.code = code;
	}

	public String code() {
		return this.code;
	}

	public static Optional<AccessAction> fromCode(String code) {
		for (AccessAction action : values()) {
			if (action.code.equals(code)) {
				return Optional.of(action);
			}
		}
		return Optional.empty();
	}

}
