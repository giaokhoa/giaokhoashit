package com.giaokhoa.server.profile;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Đường đọc hồ sơ duy nhất của lát khung này: `sub` lấy từ JWT đã được Resource Server kiểm chứng là khoá
// duy nhất để tra cứu, client không truyền và không giả mạo được danh tính của người khác.
@RestController
@RequestMapping("/api")
public class ProfileController {

	private final ProfileService profileService;

	public ProfileController(ProfileService profileService) {
		this.profileService = profileService;
	}

	@GetMapping("/profile")
	ProfileService.ProfileView getProfile(@AuthenticationPrincipal Jwt jwt) {
		return this.profileService.getOrCreateProfile(jwt.getSubject());
	}

}
