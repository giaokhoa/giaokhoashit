package com.giaokhoa.server.exam;

/**
 * Các hành động trên đề và trên câu thành phần mà lát soạn đề áp dụng, lấy từ tập hành động lõi dùng chung. Các
 * hành động độc lập: được một hành động không suy ra được hành động khác, riêng {@code view_answer} không bao
 * giờ suy ra từ {@code edit_draft}.
 */
public enum ExamAction {

	VIEW_CONTENT("view_content"),

	VIEW_ANSWER("view_answer"),

	/** Với câu hỏi: quyền dùng-trong-đề trên đúng một phiên bản câu cụ thể. */
	USE("use"),

	EDIT_DRAFT("edit_draft"),

	PUBLISH("publish");

	private final String code;

	ExamAction(String code) {
		this.code = code;
	}

	public String code() {
		return this.code;
	}

}
