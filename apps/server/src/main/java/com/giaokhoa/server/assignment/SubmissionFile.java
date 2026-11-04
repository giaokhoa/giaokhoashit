package com.giaokhoa.server.assignment;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Một tệp bài làm gắn vào một lượt làm: liên kết tới blob của nền tệp qua phiên tải lên của chính học viên.
 * Trạng thái ghi tại bước chốt gắn là `processing` (phiên chưa chốt thành blob), `ready` (blob đã chốt trong
 * hạn của lượt làm) hoặc `quarantined`; một hàng đang xử lý được đối chiếu khi đọc và trạng thái ready đã
 * chốt được ghi lại xuống hàng đã lưu, còn trạng thái bị từ chối và cách ly chỉ suy ra khi đọc, nên hàng đã
 * ghi là bất biến sau khi lượt làm khoá như chính lượt làm.
 */
@Entity
@Table(name = "submission_file")
public class SubmissionFile {

	public static final String PROCESSING = "processing";

	public static final String READY = "ready";

	public static final String REJECTED = "rejected";

	public static final String QUARANTINED = "quarantined";

	@Id
	private UUID id;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "attempt_id", nullable = false)
	private UUID attemptId;

	@Column(name = "upload_session_id", nullable = false)
	private UUID uploadSessionId;

	@Column(name = "blob_id")
	private UUID blobId;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	// Định dạng và dung lượng chỉ biết chắc khi blob đã chốt: hàng gắn lúc phiên còn đang xử lý để trống hai
	// cột này và hình chiếu khi đọc điền từ dữ kiện của blob.
	@Column(name = "content_type")
	private String contentType;

	@Column(name = "size_bytes")
	private Long sizeBytes;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "attached_at")
	private OffsetDateTime attachedAt;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public UUID getAttemptId() {
		return this.attemptId;
	}

	public void setAttemptId(UUID attemptId) {
		this.attemptId = attemptId;
	}

	public UUID getUploadSessionId() {
		return this.uploadSessionId;
	}

	public void setUploadSessionId(UUID uploadSessionId) {
		this.uploadSessionId = uploadSessionId;
	}

	public UUID getBlobId() {
		return this.blobId;
	}

	public void setBlobId(UUID blobId) {
		this.blobId = blobId;
	}

	public String getDisplayName() {
		return this.displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getContentType() {
		return this.contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public Long getSizeBytes() {
		return this.sizeBytes;
	}

	public void setSizeBytes(Long sizeBytes) {
		this.sizeBytes = sizeBytes;
	}

	public String getStatus() {
		return this.status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public OffsetDateTime getAttachedAt() {
		return this.attachedAt;
	}

	public void setAttachedAt(OffsetDateTime attachedAt) {
		this.attachedAt = attachedAt;
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
