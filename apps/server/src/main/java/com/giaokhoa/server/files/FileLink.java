package com.giaokhoa.server.files;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Liên kết tệp theo ngữ cảnh sử dụng: gắn một blob vào đúng một phiên bản tài nguyên, tại đúng một vị trí nội
 * dung (phần thân, lựa chọn, đáp án, lời giải hay tài liệu) và một khối nội dung khi tệp thuộc về một khối. Mỗi
 * bản sao và bản nhận có liên kết riêng của mình; biết định danh blob không tự tạo quyền — quyền đọc quyết qua
 * liên kết này tại thời điểm truy cập.
 */
@Entity
@Table(name = "file_link")
public class FileLink {

	public static final String POSITION_CONTENT = "content";

	public static final String POSITION_CHOICE = "choice";

	public static final String POSITION_ANSWER = "answer";

	public static final String POSITION_SOLUTION = "solution";

	public static final String POSITION_DOCUMENT = "document";

	@Id
	private UUID id;

	@Column(name = "resource_id", nullable = false)
	private UUID resourceId;

	@Column(name = "resource_version_id", nullable = false)
	private UUID resourceVersionId;

	@Column(name = "position", nullable = false)
	private String position;

	@Column(name = "block_id")
	private String blockId;

	@Column(name = "blob_id", nullable = false)
	private UUID blobId;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "session_id")
	private UUID sessionId;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "created_by_sub", nullable = false)
	private String createdBySub;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	/** Vị trí thuộc phần đáp án: đọc tệp ở các vị trí này phải kết hợp quyền xem đáp án. */
	public boolean answerArea() {
		return POSITION_ANSWER.equals(this.position) || POSITION_SOLUTION.equals(this.position);
	}

	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public UUID getResourceId() {
		return this.resourceId;
	}

	public void setResourceId(UUID resourceId) {
		this.resourceId = resourceId;
	}

	public UUID getResourceVersionId() {
		return this.resourceVersionId;
	}

	public void setResourceVersionId(UUID resourceVersionId) {
		this.resourceVersionId = resourceVersionId;
	}

	public String getPosition() {
		return this.position;
	}

	public void setPosition(String position) {
		this.position = position;
	}

	public String getBlockId() {
		return this.blockId;
	}

	public void setBlockId(String blockId) {
		this.blockId = blockId;
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

	public UUID getSessionId() {
		return this.sessionId;
	}

	public void setSessionId(UUID sessionId) {
		this.sessionId = sessionId;
	}

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public String getCreatedBySub() {
		return this.createdBySub;
	}

	public void setCreatedBySub(String createdBySub) {
		this.createdBySub = createdBySub;
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
