package com.giaokhoa.server.math;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

// Một khối công thức trong một phiên bản câu hỏi. Dạng của khối là dữ kiện, không phải khác biệt ở giao diện:
// khối có-nguồn giữ nguồn LaTeX riêng tư ngay tại hàng này, khối chỉ-có-ảnh không có nguồn ở phía nhận và
// trường source luôn rỗng. Cờ nguồn gốc hệ thống do server quản lý; client không đặt được qua bất kỳ thao tác
// nào của lát này.
@Entity
@Table(name = "formula_block")
public class FormulaBlock {

	public static final String FORM_SOURCE_BACKED = "source-backed";

	public static final String FORM_RENDER_ONLY = "render-only";

	public static final String SECTION_STEM = "stem";

	public static final String SECTION_CHOICE = "choice";

	public static final String SECTION_SOLUTION = "solution";

	public static final String SECTION_RUBRIC = "rubric";

	@Id
	private UUID id;

	@Column(name = "question_version_id", nullable = false)
	private UUID questionVersionId;

	@Column(name = "owner_sub", nullable = false)
	private String ownerSub;

	@Column(nullable = false)
	private String section;

	@Column(name = "block_id", nullable = false)
	private String blockId;

	@Column(nullable = false)
	private int position;

	@Column(nullable = false)
	private String form;

	@Column(nullable = false)
	private boolean display;

	@Column(name = "system_origin", nullable = false)
	private boolean systemOrigin;

	@Column
	private String source;

	@Column
	private String description;

	// Revision của khối là đồng thời khoá lạc quan: Hibernate tăng ở mỗi lượt ghi có thay đổi, và bản ghi
	// render khoá theo đúng giá trị này nên một kết quả render chỉ gắn được khi revision còn khớp.
	@Version
	@Column(nullable = false)
	private int revision;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	// Phần đáp án và lời giải mang đáp án: đọc hay sửa nguồn của khối ở các phần này cần thêm view_answer
	// ngoài quyền nguồn tương ứng.
	public boolean answerBearing() {
		return SECTION_SOLUTION.equals(this.section) || SECTION_RUBRIC.equals(this.section);
	}

	public boolean sourceBacked() {
		return FORM_SOURCE_BACKED.equals(this.form);
	}

	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public UUID getQuestionVersionId() {
		return this.questionVersionId;
	}

	public void setQuestionVersionId(UUID questionVersionId) {
		this.questionVersionId = questionVersionId;
	}

	public String getOwnerSub() {
		return this.ownerSub;
	}

	public void setOwnerSub(String ownerSub) {
		this.ownerSub = ownerSub;
	}

	public String getSection() {
		return this.section;
	}

	public void setSection(String section) {
		this.section = section;
	}

	public String getBlockId() {
		return this.blockId;
	}

	public void setBlockId(String blockId) {
		this.blockId = blockId;
	}

	public int getPosition() {
		return this.position;
	}

	public void setPosition(int position) {
		this.position = position;
	}

	public String getForm() {
		return this.form;
	}

	public void setForm(String form) {
		this.form = form;
	}

	public boolean isDisplay() {
		return this.display;
	}

	public void setDisplay(boolean display) {
		this.display = display;
	}

	public boolean isSystemOrigin() {
		return this.systemOrigin;
	}

	public void setSystemOrigin(boolean systemOrigin) {
		this.systemOrigin = systemOrigin;
	}

	public String getSource() {
		return this.source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public String getDescription() {
		return this.description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public int getRevision() {
		return this.revision;
	}

	public OffsetDateTime getCreatedAt() {
		return this.createdAt;
	}

	public void setCreatedAt(OffsetDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public OffsetDateTime getUpdatedAt() {
		return this.updatedAt;
	}

	public void setUpdatedAt(OffsetDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

}
