package com.giaokhoa.server.profile;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// Mọi endpoint ở đây chỉ thao tác trên hồ sơ của chính chủ token: `sub` lấy từ JWT đã được Resource Server
// kiểm chứng là khoá duy nhất để tra cứu, client không truyền và không giả mạo được danh tính của người khác.
@RestController
@RequestMapping("/api")
public class ProfileController {

	private final ProfileService profileService;

	public ProfileController(ProfileService profileService) {
		this.profileService = profileService;
	}

	public record UpdateProfileRequest(String displayName, String avatarUrl, Map<String, String> customFields) {
	}

	public record CreateDefinitionRequest(String fieldKey, String label) {
	}

	@GetMapping("/profile")
	ProfileService.ProfileView getProfile(@AuthenticationPrincipal Jwt jwt) {
		return this.profileService.getOrCreateProfile(jwt.getSubject());
	}

	@PutMapping("/profile")
	ProfileService.ProfileView updateProfile(@AuthenticationPrincipal Jwt jwt,
			@RequestBody UpdateProfileRequest request) {
		if (request.displayName() != null && request.displayName().length() > 200) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "displayName must be at most 200 characters");
		}
		if (request.avatarUrl() != null && request.avatarUrl().length() > 1000) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "avatarUrl must be at most 1000 characters");
		}
		return this.profileService.updateProfile(jwt.getSubject(), request.displayName(), request.avatarUrl(),
				request.customFields());
	}

	@GetMapping("/custom-field-definitions")
	List<ProfileService.DefinitionView> listDefinitions() {
		return this.profileService.listDefinitions();
	}

	@PostMapping("/custom-field-definitions")
	@ResponseStatus(HttpStatus.CREATED)
	ProfileService.DefinitionView createDefinition(@RequestBody CreateDefinitionRequest request) {
		return this.profileService.createDefinition(request.fieldKey(), request.label());
	}

}
