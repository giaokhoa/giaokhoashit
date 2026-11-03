package com.giaokhoa.server.profile;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

	private final AppUserRepository users;

	private final AppUserCreator userCreator;

	public ProfileService(AppUserRepository users, AppUserCreator userCreator) {
		this.users = users;
		this.userCreator = userCreator;
	}

	public record ProfileView(UUID id, String keycloakSub, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
	}

	@Transactional
	public ProfileView getOrCreateProfile(String keycloakSub) {
		AppUser user = findOrCreateUser(keycloakSub);
		return new ProfileView(user.getId(), user.getKeycloakSub(), user.getCreatedAt(), user.getUpdatedAt());
	}

	private AppUser findOrCreateUser(String keycloakSub) {
		return this.users.findByKeycloakSub(keycloakSub).orElseGet(() -> {
			try {
				return this.userCreator.createUser(keycloakSub);
			}
			catch (DataIntegrityViolationException ex) {
				// Hai request đầu tiên của cùng một `sub` có thể cùng thấy chưa có bản ghi và cùng chèn; request
				// thua cuộc đua nhận lỗi trùng khoá unique và chỉ cần đọc lại bản ghi request thắng vừa tạo. Lần
				// chèn đã chạy ở transaction riêng nên transaction hiện tại không bị huỷ theo và đọc lại được.
				return this.users.findByKeycloakSub(keycloakSub).orElseThrow(() -> ex);
			}
		});
	}

}
