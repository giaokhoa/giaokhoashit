package com.giaokhoa.server.profile;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "custom_field_definition")
public class CustomFieldDefinition {

	@Id
	private UUID id;

	@Column(name = "field_key", nullable = false, unique = true)
	private String fieldKey;

	@Column(name = "label", nullable = false)
	private String label;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public String getFieldKey() {
		return this.fieldKey;
	}

	public void setFieldKey(String fieldKey) {
		this.fieldKey = fieldKey;
	}

	public String getLabel() {
		return this.label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public OffsetDateTime getCreatedAt() {
		return this.createdAt;
	}

	public void setCreatedAt(OffsetDateTime createdAt) {
		this.createdAt = createdAt;
	}

}
