package com.giaokhoa.server.profile;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserCreator {

	private final AppUserRepository users;

	public AppUserCreator(AppUserRepository users) {
		this.users = users;
	}

	// Lần chèn chạy ở transaction riêng (REQUIRES_NEW) của một bean riêng vì hai bẫy hạ tầng: propagation chỉ có hiệu
	// lực khi lời gọi đi qua proxy của Spring (tự gọi trong cùng lớp sẽ bị bỏ qua), và PostgreSQL huỷ toàn bộ
	// transaction chứa câu lệnh lỗi — transaction riêng bị huỷ vì trùng khoá không kéo theo transaction của request.
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public AppUser createUser(String keycloakSub) {
		AppUser created = new AppUser();
		created.setId(UUID.randomUUID());
		created.setKeycloakSub(keycloakSub);
		OffsetDateTime now = OffsetDateTime.now();
		created.setCreatedAt(now);
		created.setUpdatedAt(now);
		return this.users.saveAndFlush(created);
	}

}
