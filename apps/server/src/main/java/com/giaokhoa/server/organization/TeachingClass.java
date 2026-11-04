package com.giaokhoa.server.organization;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "teaching_class")
public class TeachingClass {

	@Id
	private UUID id;

	@TenantId
	@Column(name = "partition_key", nullable = false, updatable = false)
	private String partitionKey;

	// Null nghĩa là lớp độc lập không thuộc khoá học nào; khoá ngoại ghép với partition_key ở tầng cơ sở dữ liệu
	// chặn việc một lớp trỏ sang khoá học của trung tâm khác.
	@Column(name = "course_id")
	private UUID courseId;

	@Column(nullable = false)
	private String name;

	// Giáo viên phụ trách hiện tại của lớp; thay người là ghi đè trường này và có hiệu lực ngay vì mọi phép kiểm
	// quyền dạy đều đọc giá trị hiện tại trong cơ sở dữ liệu, không đọc từ token.
	@Column(name = "teacher_sub")
	private String teacherSub;

	// Chỉ là dấu vết nguồn gốc của hàng; quyền trên lớp không bao giờ suy ra từ trường này.
	@Column(name = "created_by_sub", nullable = false, updatable = false)
	private String createdBySub;

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

	public UUID getCourseId() {
		return this.courseId;
	}

	public void setCourseId(UUID courseId) {
		this.courseId = courseId;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getTeacherSub() {
		return this.teacherSub;
	}

	public void setTeacherSub(String teacherSub) {
		this.teacherSub = teacherSub;
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
