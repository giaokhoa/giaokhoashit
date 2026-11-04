package com.giaokhoa.server.question;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Một khối trong dãy khối nội dung của một phiên bản câu hỏi: khối văn bản thường hoặc khối công thức có định
 * danh ổn định trong phiên bản. Khối công thức thuộc đúng một trong hai dạng ở mức dữ kiện — có-nguồn (nguồn
 * LaTeX lưu tách ở {@code mathSource}, chỉ mặt soạn thảo có quyền mới nhận) hoặc chỉ-có-ảnh (không có nguồn ở
 * phía nhận). Cờ nguồn gốc hệ thống, trạng thái render và định danh blob ảnh là trường do server quản lý.
 */
@Entity
@Table(name = "question_version_block")
public class QuestionVersionBlock {

	public static final String PART_STEM = "stem";

	public static final String PART_CHOICE = "choice";

	public static final String PART_SOLUTION = "solution";

	public static final String PART_RUBRIC = "rubric";

	public static final String KIND_TEXT = "text";

	public static final String KIND_FORMULA = "formula";

	public static final String FORM_SOURCE_BACKED = "source-backed";

	public static final String FORM_RENDER_ONLY = "render-only";

	public static final String RENDER_PENDING = "pending";

	public static final String RENDER_READY = "ready";

	public static final String RENDER_FAILED = "failed";

	@Id
	private UUID id;

	@Column(name = "question_version_id", nullable = false)
	private UUID questionVersionId;

	@Column(name = "owner_sub", nullable = false)
	private String ownerSub;

	@Column(name = "part", nullable = false)
	private String part;

	@Column(name = "choice_id")
	private String choiceId;

	@Column(name = "position", nullable = false)
	private int position;

	@Column(name = "kind", nullable = false)
	private String kind;

	@Column(name = "text_content")
	private String textContent;

	@Column(name = "formula_form")
	private String formulaForm;

	@Column(name = "math_source")
	private String mathSource;

	@Column(name = "system_origin", nullable = false)
	private boolean systemOrigin;

	@Column(name = "render_state")
	private String renderState;

	@Column(name = "image_blob_id")
	private UUID imageBlobId;

	@Column(name = "image_width")
	private Integer imageWidth;

	@Column(name = "image_height")
	private Integer imageHeight;

	@Column(name = "baseline_offset")
	private Integer baselineOffset;

	@Column(name = "description")
	private String description;

	@Column(name = "source_revision", nullable = false)
	private int sourceRevision;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	/** Phần thuộc đáp án: đọc nguồn của khối ở các phần này phải kết hợp quyền xem đáp án. */
	public boolean answerArea() {
		return PART_SOLUTION.equals(this.part) || PART_RUBRIC.equals(this.part);
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

	public String getPart() {
		return this.part;
	}

	public void setPart(String part) {
		this.part = part;
	}

	public String getChoiceId() {
		return this.choiceId;
	}

	public void setChoiceId(String choiceId) {
		this.choiceId = choiceId;
	}

	public int getPosition() {
		return this.position;
	}

	public void setPosition(int position) {
		this.position = position;
	}

	public String getKind() {
		return this.kind;
	}

	public void setKind(String kind) {
		this.kind = kind;
	}

	public String getTextContent() {
		return this.textContent;
	}

	public void setTextContent(String textContent) {
		this.textContent = textContent;
	}

	public String getFormulaForm() {
		return this.formulaForm;
	}

	public void setFormulaForm(String formulaForm) {
		this.formulaForm = formulaForm;
	}

	public String getMathSource() {
		return this.mathSource;
	}

	public void setMathSource(String mathSource) {
		this.mathSource = mathSource;
	}

	public boolean isSystemOrigin() {
		return this.systemOrigin;
	}

	public void setSystemOrigin(boolean systemOrigin) {
		this.systemOrigin = systemOrigin;
	}

	public String getRenderState() {
		return this.renderState;
	}

	public void setRenderState(String renderState) {
		this.renderState = renderState;
	}

	public UUID getImageBlobId() {
		return this.imageBlobId;
	}

	public void setImageBlobId(UUID imageBlobId) {
		this.imageBlobId = imageBlobId;
	}

	public Integer getImageWidth() {
		return this.imageWidth;
	}

	public void setImageWidth(Integer imageWidth) {
		this.imageWidth = imageWidth;
	}

	public Integer getImageHeight() {
		return this.imageHeight;
	}

	public void setImageHeight(Integer imageHeight) {
		this.imageHeight = imageHeight;
	}

	public Integer getBaselineOffset() {
		return this.baselineOffset;
	}

	public void setBaselineOffset(Integer baselineOffset) {
		this.baselineOffset = baselineOffset;
	}

	public String getDescription() {
		return this.description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public int getSourceRevision() {
		return this.sourceRevision;
	}

	public void setSourceRevision(int sourceRevision) {
		this.sourceRevision = sourceRevision;
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
