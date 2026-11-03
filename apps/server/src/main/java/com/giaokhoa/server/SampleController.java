package com.giaokhoa.server;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Endpoint mẫu duy nhất của lát cắt dựng khung: chứng minh request mang JWT hợp lệ
// của realm đi qua được cổng kiểm chứng và danh tính trong token tới được tầng xử lý.
// Lát nghiệp vụ đầu tiên chuyển sang sẽ thay endpoint này.
@RestController
@RequestMapping("/api")
public class SampleController {

	@GetMapping("/ping")
	Map<String, String> ping(@AuthenticationPrincipal Jwt jwt) {
		return Map.of("status", "ok", "subject", jwt.getSubject());
	}

}
