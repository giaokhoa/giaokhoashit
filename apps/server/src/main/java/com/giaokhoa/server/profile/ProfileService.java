package com.giaokhoa.server.profile;

import static com.giaokhoa.server.jooq.Tables.APP_USER;
import static com.giaokhoa.server.jooq.Tables.CUSTOM_FIELD_DEFINITION;
import static com.giaokhoa.server.jooq.Tables.USER_CUSTOM_FIELD_VALUE;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.giaokhoa.server.jooq.tables.records.AppUserRecord;
import com.giaokhoa.server.jooq.tables.records.CustomFieldDefinitionRecord;
import org.jooq.DSLContext;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {

	private final DSLContext dsl;

	public ProfileService(DSLContext dsl) {
		this.dsl = dsl;
	}

	public record ProfileView(UUID id, String keycloakSub, String displayName, String avatarUrl,
			Map<String, String> customFields, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
	}

	public record DefinitionView(UUID id, String fieldKey, String label) {
	}

	@Transactional
	public ProfileView getOrCreateProfile(String keycloakSub) {
		return toView(findOrCreateUser(keycloakSub));
	}

	@Transactional
	public ProfileView updateProfile(String keycloakSub, String displayName, String avatarUrl,
			Map<String, String> customFields) {
		AppUserRecord user = findOrCreateUser(keycloakSub);
		if (displayName != null) {
			user.setDisplayName(displayName);
		}
		if (avatarUrl != null) {
			user.setAvatarUrl(avatarUrl);
		}
		user.setUpdatedAt(OffsetDateTime.now());
		user.store();
		if (customFields != null) {
			customFields.forEach((fieldKey, value) -> setCustomFieldValue(user.getId(), fieldKey, value));
		}
		return toView(user);
	}

	public List<DefinitionView> listDefinitions() {
		return this.dsl.selectFrom(CUSTOM_FIELD_DEFINITION)
				.orderBy(CUSTOM_FIELD_DEFINITION.FIELD_KEY)
				.fetch(record -> new DefinitionView(record.getId(), record.getFieldKey(), record.getLabel()));
	}

	@Transactional
	public DefinitionView createDefinition(String fieldKey, String label) {
		if (fieldKey == null || !fieldKey.matches("[a-z][a-z0-9_]{0,63}")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"fieldKey must match [a-z][a-z0-9_]{0,63}");
		}
		if (label == null || label.isBlank() || label.length() > 200) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label must be 1-200 characters");
		}
		boolean exists = this.dsl.fetchExists(CUSTOM_FIELD_DEFINITION,
				CUSTOM_FIELD_DEFINITION.FIELD_KEY.eq(fieldKey));
		if (exists) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "custom field already exists: " + fieldKey);
		}
		CustomFieldDefinitionRecord record = this.dsl.newRecord(CUSTOM_FIELD_DEFINITION);
		record.setId(UUID.randomUUID());
		record.setFieldKey(fieldKey);
		record.setLabel(label);
		record.store();
		return new DefinitionView(record.getId(), record.getFieldKey(), record.getLabel());
	}

	private AppUserRecord findOrCreateUser(String keycloakSub) {
		AppUserRecord user = this.dsl.selectFrom(APP_USER)
				.where(APP_USER.KEYCLOAK_SUB.eq(keycloakSub))
				.fetchOne();
		if (user != null) {
			return user;
		}
		try {
			AppUserRecord created = this.dsl.newRecord(APP_USER);
			created.setId(UUID.randomUUID());
			created.setKeycloakSub(keycloakSub);
			created.store();
			return created;
		}
		catch (DataAccessException ex) {
			// Hai request đầu tiên của cùng một `sub` có thể cùng thấy chưa có hồ sơ và cùng chèn; request thua
			// cuộc đua nhận lỗi trùng khoá unique và chỉ cần đọc lại bản ghi mà request thắng vừa tạo.
			return this.dsl.selectFrom(APP_USER).where(APP_USER.KEYCLOAK_SUB.eq(keycloakSub)).fetchOne();
		}
	}

	private void setCustomFieldValue(UUID userId, String fieldKey, String value) {
		if (value == null || value.length() > 2000) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"custom field value must be at most 2000 characters: " + fieldKey);
		}
		UUID definitionId = this.dsl.select(CUSTOM_FIELD_DEFINITION.ID)
				.from(CUSTOM_FIELD_DEFINITION)
				.where(CUSTOM_FIELD_DEFINITION.FIELD_KEY.eq(fieldKey))
				.fetchOne(CUSTOM_FIELD_DEFINITION.ID);
		if (definitionId == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown custom field: " + fieldKey);
		}
		int updated = this.dsl.update(USER_CUSTOM_FIELD_VALUE)
				.set(USER_CUSTOM_FIELD_VALUE.FIELD_VALUE, value)
				.set(USER_CUSTOM_FIELD_VALUE.UPDATED_AT, OffsetDateTime.now())
				.where(USER_CUSTOM_FIELD_VALUE.USER_ID.eq(userId)
						.and(USER_CUSTOM_FIELD_VALUE.DEFINITION_ID.eq(definitionId)))
				.execute();
		if (updated == 0) {
			this.dsl.insertInto(USER_CUSTOM_FIELD_VALUE)
					.set(USER_CUSTOM_FIELD_VALUE.USER_ID, userId)
					.set(USER_CUSTOM_FIELD_VALUE.DEFINITION_ID, definitionId)
					.set(USER_CUSTOM_FIELD_VALUE.FIELD_VALUE, value)
					.execute();
		}
	}

	private ProfileView toView(AppUserRecord user) {
		Map<String, String> values = new LinkedHashMap<>();
		this.dsl.select(CUSTOM_FIELD_DEFINITION.FIELD_KEY, USER_CUSTOM_FIELD_VALUE.FIELD_VALUE)
				.from(USER_CUSTOM_FIELD_VALUE)
				.join(CUSTOM_FIELD_DEFINITION)
				.on(USER_CUSTOM_FIELD_VALUE.DEFINITION_ID.eq(CUSTOM_FIELD_DEFINITION.ID))
				.where(USER_CUSTOM_FIELD_VALUE.USER_ID.eq(user.getId()))
				.orderBy(CUSTOM_FIELD_DEFINITION.FIELD_KEY)
				.forEach(row -> values.put(row.get(CUSTOM_FIELD_DEFINITION.FIELD_KEY),
						row.get(USER_CUSTOM_FIELD_VALUE.FIELD_VALUE)));
		return new ProfileView(user.getId(), user.getKeycloakSub(), user.getDisplayName(), user.getAvatarUrl(),
				values, user.getCreatedAt(), user.getUpdatedAt());
	}

}
