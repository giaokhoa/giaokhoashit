package com.giaokhoa.server.exam;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

/**
 * Một phiên bản của một đề: đơn vị bất biến mà mọi tham chiếu tới đề dùng tới. Trạng thái chỉ có {@code draft}
 * hoặc {@code published}; khi phát hành, {@code locked_at} và {@code content_hash} được ghi và không bao giờ mở
 * lại — sửa bản đã phát hành chỉ bằng phiên bản nháp mới. {@code lock_version} là cột khoá lạc quan chống hai
 * lượt sửa ghi đè nhau trên cùng một bản nháp.
 */
@Entity
@Table(name = "exam_version")
public class ExamVersion {

	@Id
	private UUID id;

	@Column(name = "exam_id", nullable = false)
	private UUID examId;

	// Hibernate tự đóng dấu bằng tenant id của session khi hàng được persist lần đầu; ứng dụng không tự đặt.
	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "version_number", nullable = false)
	private int versionNumber;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "content_hash")
	private String contentHash;

	@Version
	@Column(name = "lock_version", nullable = false)
	private long lockVersion;

	@Column(name = "created_by_sub", nullable = false)
	private String createdBySub;

	@Column(name = "published_at")
	private OffsetDateTime publishedAt;

	@Column(name = "locked_at")
	private OffsetDateTime lockedAt;

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

	public UUID getExamId() {
		return this.examId;
	}

	public void setExamId(UUID examId) {
		this.examId = examId;
	}

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public void setPartitionKey(String partitionKey) {
		this.partitionKey = partitionKey;
	}

	public int getVersionNumber() {
		return this.versionNumber;
	}

	public void setVersionNumber(int versionNumber) {
		this.versionNumber = versionNumber;
	}

	public String getStatus() {
		return this.status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getContentHash() {
		return this.contentHash;
	}

	public void setContentHash(String contentHash) {
		this.contentHash = contentHash;
	}

	public long getLockVersion() {
		return this.lockVersion;
	}

	public String getCreatedBySub() {
		return this.createdBySub;
	}

	public void setCreatedBySub(String createdBySub) {
		this.createdBySub = createdBySub;
	}

	public OffsetDateTime getPublishedAt() {
		return this.publishedAt;
	}

	public void setPublishedAt(OffsetDateTime publishedAt) {
		this.publishedAt = publishedAt;
	}

	public OffsetDateTime getLockedAt() {
		return this.lockedAt;
	}

	public void setLockedAt(OffsetDateTime lockedAt) {
		this.lockedAt = lockedAt;
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
