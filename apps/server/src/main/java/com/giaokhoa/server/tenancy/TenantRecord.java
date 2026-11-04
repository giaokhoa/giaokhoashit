package com.giaokhoa.server.tenancy;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * Entity khung để kiểm chứng discriminator và RLS của khung tenancy, chưa mang logic nghiệp vụ: mọi hàng thuộc đúng
 * một phân vùng qua {@code partition_key}, và {@code owner_sub} ghi người tạo hàng trong phân vùng đó.
 */
@Entity
@Table(name = "tenant_record")
public class TenantRecord {

	@Id
	private UUID id;

	// Hibernate tự đóng dấu bằng tenant id của session khi hàng được persist lần đầu; ứng dụng không tự đặt trường
	// này, và sửa phân vùng của một hàng có sẵn không phải là đường di chuyển dữ kiện giữa các phân vùng.
	@TenantId
	@Column(name = "partition_key", nullable = false)
	private String partitionKey;

	@Column(name = "owner_sub", nullable = false)
	private String ownerSub;

	@Column(name = "content")
	private String content;

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

	public String getOwnerSub() {
		return this.ownerSub;
	}

	public void setOwnerSub(String ownerSub) {
		this.ownerSub = ownerSub;
	}

	public String getContent() {
		return this.content;
	}

	public void setContent(String content) {
		this.content = content;
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
