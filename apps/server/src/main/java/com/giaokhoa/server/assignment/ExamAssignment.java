package com.giaokhoa.server.assignment;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Một lần giao bài: ghim bất biến đúng một phiên bản đề đã phát hành (`examVersionId`) cho đúng một lớp, trong
 * phân vùng của Organization sở hữu lớp đích. Nội dung bài làm không nằm ở đây — nó đọc từ snapshot của phiên
 * bản đề đã ghim, nên sửa hay xoá ở nguồn không đổi được lần giao này.
 */
@Entity
@Table(name = "exam_assignment")
public class ExamAssignment {

	@Id
	private UUID id;

	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "class_id", nullable = false)
	private UUID classId;

	@Column(name = "exam_resource_id", nullable = false)
	private UUID examResourceId;

	@Column(name = "exam_version_id", nullable = false)
	private UUID examVersionId;

	@Column(name = "assigned_by_sub", nullable = false)
	private String assignedBySub;

	@Column(name = "opens_at", nullable = false)
	private OffsetDateTime opensAt;

	@Column(name = "due_at", nullable = false)
	private OffsetDateTime dueAt;

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

	public void setPartitionKey(String partitionKey) {
		this.partitionKey = partitionKey;
	}

	public UUID getClassId() {
		return this.classId;
	}

	public void setClassId(UUID classId) {
		this.classId = classId;
	}

	public UUID getExamResourceId() {
		return this.examResourceId;
	}

	public void setExamResourceId(UUID examResourceId) {
		this.examResourceId = examResourceId;
	}

	public UUID getExamVersionId() {
		return this.examVersionId;
	}

	public void setExamVersionId(UUID examVersionId) {
		this.examVersionId = examVersionId;
	}

	public String getAssignedBySub() {
		return this.assignedBySub;
	}

	public void setAssignedBySub(String assignedBySub) {
		this.assignedBySub = assignedBySub;
	}

	public OffsetDateTime getOpensAt() {
		return this.opensAt;
	}

	public void setOpensAt(OffsetDateTime opensAt) {
		this.opensAt = opensAt;
	}

	public OffsetDateTime getDueAt() {
		return this.dueAt;
	}

	public void setDueAt(OffsetDateTime dueAt) {
		this.dueAt = dueAt;
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
