package com.giaokhoa.server.organization;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "course")
public class Course {

	@Id
	private UUID id;

	@TenantId
	@Column(name = "partition_key", nullable = false, updatable = false)
	private String partitionKey;

	@Column(nullable = false)
	private String name;

	// Chỉ là dấu vết nguồn gốc của hàng; quyền trên khoá học không bao giờ suy ra từ trường này.
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

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
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
