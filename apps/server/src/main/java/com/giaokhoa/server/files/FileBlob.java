package com.giaokhoa.server.files;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Một blob đã chốt: dãy byte bất biến trong kho lưu trữ riêng tư, kèm loại nội dung đã kiểm chứng, kích thước,
 * checksum và trạng thái xử lý. Blob không mang quyền của riêng phiên bản nào — quyền truy cập quyết qua liên
 * kết tệp theo ngữ cảnh tại thời điểm đọc. Người tải lên chỉ là thông tin truy vết.
 */
@Entity
@Table(name = "file_blob")
public class FileBlob {

	public static final String STATUS_READY = "READY";

	public static final String STATUS_QUARANTINED = "QUARANTINED";

	@Id
	private UUID id;

	@Column(name = "storage_key", nullable = false, unique = true)
	private String storageKey;

	@Column(name = "content_type", nullable = false)
	private String contentType;

	@Column(name = "size_bytes", nullable = false)
	private long sizeBytes;

	@Column(name = "checksum_sha256", nullable = false)
	private String checksumSha256;

	@Column(name = "width")
	private Integer width;

	@Column(name = "height")
	private Integer height;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "uploaded_by_sub", nullable = false)
	private String uploadedBySub;

	@Column(name = "gc_marked_at")
	private OffsetDateTime gcMarkedAt;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

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

	public String getStorageKey() {
		return this.storageKey;
	}

	public void setStorageKey(String storageKey) {
		this.storageKey = storageKey;
	}

	public String getContentType() {
		return this.contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public long getSizeBytes() {
		return this.sizeBytes;
	}

	public void setSizeBytes(long sizeBytes) {
		this.sizeBytes = sizeBytes;
	}

	public String getChecksumSha256() {
		return this.checksumSha256;
	}

	public void setChecksumSha256(String checksumSha256) {
		this.checksumSha256 = checksumSha256;
	}

	public Integer getWidth() {
		return this.width;
	}

	public void setWidth(Integer width) {
		this.width = width;
	}

	public Integer getHeight() {
		return this.height;
	}

	public void setHeight(Integer height) {
		this.height = height;
	}

	public String getStatus() {
		return this.status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getUploadedBySub() {
		return this.uploadedBySub;
	}

	public void setUploadedBySub(String uploadedBySub) {
		this.uploadedBySub = uploadedBySub;
	}

	public OffsetDateTime getGcMarkedAt() {
		return this.gcMarkedAt;
	}

	public void setGcMarkedAt(OffsetDateTime gcMarkedAt) {
		this.gcMarkedAt = gcMarkedAt;
	}

	public String getPartitionKey() {
		return this.partitionKey;
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
