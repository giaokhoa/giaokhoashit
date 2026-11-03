package com.giaokhoa.server.profile;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {

	private final AppUserRepository users;

	private final CustomFieldDefinitionRepository definitions;

	private final UserCustomFieldValueRepository values;

	public ProfileService(AppUserRepository users, CustomFieldDefinitionRepository definitions,
			UserCustomFieldValueRepository values) {
		this.users = users;
		this.definitions = definitions;
		this.values = values;
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
		AppUser user = findOrCreateUser(keycloakSub);
		if (displayName != null) {
			user.setDisplayName(displayName);
		}
		if (avatarUrl != null) {
			user.setAvatarUrl(avatarUrl);
		}
		user.setUpdatedAt(OffsetDateTime.now());
		this.users.save(user);
		if (customFields != null) {
			customFields.forEach((fieldKey, value) -> setCustomFieldValue(user.getId(), fieldKey, value));
		}
		return toView(user);
	}

	public List<DefinitionView> listDefinitions() {
		return this.definitions.findAllByOrderByFieldKeyAsc().stream()
				.map(definition -> new DefinitionView(definition.getId(), definition.getFieldKey(),
						definition.getLabel()))
				.toList();
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
		if (this.definitions.findByFieldKey(fieldKey).isPresent()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "custom field already exists: " + fieldKey);
		}
		CustomFieldDefinition definition = new CustomFieldDefinition();
		definition.setId(UUID.randomUUID());
		definition.setFieldKey(fieldKey);
		definition.setLabel(label);
		definition.setCreatedAt(OffsetDateTime.now());
		this.definitions.save(definition);
		return new DefinitionView(definition.getId(), definition.getFieldKey(), definition.getLabel());
	}

	private AppUser findOrCreateUser(String keycloakSub) {
		return this.users.findByKeycloakSub(keycloakSub).orElseGet(() -> {
			AppUser created = new AppUser();
			created.setId(UUID.randomUUID());
			created.setKeycloakSub(keycloakSub);
			OffsetDateTime now = OffsetDateTime.now();
			created.setCreatedAt(now);
			created.setUpdatedAt(now);
			try {
				return this.users.saveAndFlush(created);
			}
			catch (DataIntegrityViolationException ex) {
				// Hai request đầu tiên của cùng một `sub` có thể cùng thấy chưa có hồ sơ và cùng chèn; request
				// thua cuộc đua nhận lỗi trùng khoá unique và chỉ cần đọc lại bản ghi request thắng vừa tạo.
				return this.users.findByKeycloakSub(keycloakSub).orElseThrow(() -> ex);
			}
		});
	}

	private void setCustomFieldValue(UUID userId, String fieldKey, String value) {
		if (value == null || value.length() > 2000) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"custom field value must be at most 2000 characters: " + fieldKey);
		}
		CustomFieldDefinition definition = this.definitions.findByFieldKey(fieldKey)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"unknown custom field: " + fieldKey));
		UserCustomFieldValue.Id id = new UserCustomFieldValue.Id(userId, definition.getId());
		UserCustomFieldValue entry = this.values.findById(id).orElseGet(() -> {
			UserCustomFieldValue created = new UserCustomFieldValue();
			created.setId(id);
			return created;
		});
		entry.setFieldValue(value);
		entry.setUpdatedAt(OffsetDateTime.now());
		this.values.save(entry);
	}

	private ProfileView toView(AppUser user) {
		List<UserCustomFieldValue> entries = this.values.findByIdUserId(user.getId());
		Map<UUID, String> keyByDefinitionId = this.definitions
				.findAllById(entries.stream().map(entry -> entry.getId().getDefinitionId()).toList())
				.stream()
				.collect(Collectors.toMap(CustomFieldDefinition::getId, CustomFieldDefinition::getFieldKey));
		Map<String, String> customFields = new LinkedHashMap<>();
		entries.stream()
				.sorted((a, b) -> keyByDefinitionId.get(a.getId().getDefinitionId())
						.compareTo(keyByDefinitionId.get(b.getId().getDefinitionId())))
				.forEach(entry -> customFields.put(keyByDefinitionId.get(entry.getId().getDefinitionId()),
						entry.getFieldValue()));
		return new ProfileView(user.getId(), user.getKeycloakSub(), user.getDisplayName(), user.getAvatarUrl(),
				customFields, user.getCreatedAt(), user.getUpdatedAt());
	}

}
