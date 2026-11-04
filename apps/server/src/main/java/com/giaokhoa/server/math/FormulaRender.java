package com.giaokhoa.server.math;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Bản ghi của một lần render: khoá theo khối và revision của nguồn, giữ hash của nguồn, toàn bộ cấu hình
// render và phiên bản bộ render đã sinh ảnh. Ảnh là một blob của nền tệp; bản ghi chỉ giữ định danh blob cùng
// số đo hiển thị. Một bản ghi đã sẵn sàng không bị sửa lại: revision mới của nguồn sinh bản ghi mới.
@Entity
@Table(name = "formula_render")
public class FormulaRender {

	public static final String STATUS_PENDING = "pending";

	public static final String STATUS_READY = "ready";

	public static final String STATUS_FAILED = "failed";

	@Id
	private UUID id;

	@Column(name = "formula_block_id", nullable = false)
	private UUID formulaBlockId;

	@Column(name = "source_revision", nullable = false)
	private int sourceRevision;

	@Column(name = "source_hash", nullable = false)
	private String sourceHash;

	@Column(nullable = false)
	private String status;

	@Column(name = "image_blob_id")
	private UUID imageBlobId;

	@Column(name = "image_width")
	private Integer imageWidth;

	@Column(name = "image_height")
	private Integer imageHeight;

	@Column(name = "baseline_offset")
	private Integer baselineOffset;

	@Column(name = "renderer_version", nullable = false)
	private String rendererVersion;

	@Column(name = "render_config", nullable = false)
	private String renderConfig;

	@Column(name = "error_code")
	private String errorCode;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	public boolean ready() {
		return STATUS_READY.equals(this.status);
	}

	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public UUID getFormulaBlockId() {
		return this.formulaBlockId;
	}

	public void setFormulaBlockId(UUID formulaBlockId) {
		this.formulaBlockId = formulaBlockId;
	}

	public int getSourceRevision() {
		return this.sourceRevision;
	}

	public void setSourceRevision(int sourceRevision) {
		this.sourceRevision = sourceRevision;
	}

	public String getSourceHash() {
		return this.sourceHash;
	}

	public void setSourceHash(String sourceHash) {
		this.sourceHash = sourceHash;
	}

	public String getStatus() {
		return this.status;
	}

	public void setStatus(String status) {
		this.status = status;
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

	public String getRendererVersion() {
		return this.rendererVersion;
	}

	public void setRendererVersion(String rendererVersion) {
		this.rendererVersion = rendererVersion;
	}

	public String getRenderConfig() {
		return this.renderConfig;
	}

	public void setRenderConfig(String renderConfig) {
		this.renderConfig = renderConfig;
	}

	public String getErrorCode() {
		return this.errorCode;
	}

	public void setErrorCode(String errorCode) {
		this.errorCode = errorCode;
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
