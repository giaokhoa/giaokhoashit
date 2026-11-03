package com.giaokhoa.server.profile;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_custom_field_value")
public class UserCustomFieldValue {

	@EmbeddedId
	private Id id;

	@Column(name = "field_value", nullable = false)
	private String fieldValue;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	public Id getId() {
		return this.id;
	}

	public void setId(Id id) {
		this.id = id;
	}

	public String getFieldValue() {
		return this.fieldValue;
	}

	public void setFieldValue(String fieldValue) {
		this.fieldValue = fieldValue;
	}

	public OffsetDateTime getUpdatedAt() {
		return this.updatedAt;
	}

	public void setUpdatedAt(OffsetDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

	@Embeddable
	public static class Id implements Serializable {

		@Column(name = "user_id")
		private UUID userId;

		@Column(name = "definition_id")
		private UUID definitionId;

		public Id() {
		}

		public Id(UUID userId, UUID definitionId) {
			this.userId = userId;
			this.definitionId = definitionId;
		}

		public UUID getUserId() {
			return this.userId;
		}

		public UUID getDefinitionId() {
			return this.definitionId;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof Id that)) {
				return false;
			}
			return Objects.equals(this.userId, that.userId)
					&& Objects.equals(this.definitionId, that.definitionId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.userId, this.definitionId);
		}

	}

}
