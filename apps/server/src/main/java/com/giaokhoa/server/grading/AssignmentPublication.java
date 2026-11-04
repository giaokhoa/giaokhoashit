package com.giaokhoa.server.grading;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Dấu công bố duy nhất của một lần giao: một lần giao có tối đa một hàng. Lượt làm chấm xong sau dấu công bố tự
 * chuyển sang {@code PUBLISHED} ngay trong giao dịch chấm hoàn tất (công bố bổ sung), không tạo hàng thứ hai.
 */
@Entity
@Table(name = "assignment_publication")
public class AssignmentPublication {

	@Id
	@Column(name = "assignment_id")
	private UUID assignmentId;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "published_by_sub", nullable = false)
	private String publishedBySub;

	@Column(name = "published_at", nullable = false)
	private OffsetDateTime publishedAt;

	public UUID getAssignmentId() {
		return this.assignmentId;
	}

	public void setAssignmentId(UUID assignmentId) {
		this.assignmentId = assignmentId;
	}

	public String getPartitionKey() {
		return this.partitionKey;
	}

	public void setPartitionKey(String partitionKey) {
		this.partitionKey = partitionKey;
	}

	public String getPublishedBySub() {
		return this.publishedBySub;
	}

	public void setPublishedBySub(String publishedBySub) {
		this.publishedBySub = publishedBySub;
	}

	public OffsetDateTime getPublishedAt() {
		return this.publishedAt;
	}

	public void setPublishedAt(OffsetDateTime publishedAt) {
		this.publishedAt = publishedAt;
	}

}
