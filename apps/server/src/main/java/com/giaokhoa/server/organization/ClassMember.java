package com.giaokhoa.server.organization;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "class_member")
public class ClassMember {

	@Id
	private UUID id;

	@TenantId
	@Column(name = "partition_key", nullable = false, updatable = false)
	private String partitionKey;

	@Column(name = "class_id", nullable = false, updatable = false)
	private UUID classId;

	@Column(name = "student_sub", nullable = false, updatable = false)
	private String studentSub;

	@Column(name = "enrolled_by_sub", nullable = false)
	private String enrolledBySub;

	// Huỷ ghi danh là đặt mốc này chứ không xoá dòng: ràng buộc duy nhất trên (class_id, partition_key,
	// student_sub) giữ mỗi cặp lớp–học viên đúng một dòng, và ghi danh lại làm sống lại chính dòng đó bằng cách
	// xoá mốc này. Ghi danh đang hiệu lực là dòng có removed_at null.
	@Column(name = "removed_at")
	private OffsetDateTime removedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
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

	public UUID getClassId() {
		return this.classId;
	}

	public void setClassId(UUID classId) {
		this.classId = classId;
	}

	public String getStudentSub() {
		return this.studentSub;
	}

	public void setStudentSub(String studentSub) {
		this.studentSub = studentSub;
	}

	public String getEnrolledBySub() {
		return this.enrolledBySub;
	}

	public void setEnrolledBySub(String enrolledBySub) {
		this.enrolledBySub = enrolledBySub;
	}

	public OffsetDateTime getRemovedAt() {
		return this.removedAt;
	}

	public void setRemovedAt(OffsetDateTime removedAt) {
		this.removedAt = removedAt;
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
