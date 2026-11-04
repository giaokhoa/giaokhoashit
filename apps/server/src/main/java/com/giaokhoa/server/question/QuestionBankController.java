package com.giaokhoa.server.question;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/question-banks")
public class QuestionBankController {

	private final QuestionService service;

	public QuestionBankController(QuestionService service) {
		this.service = service;
	}

	@PostMapping
	public QuestionService.BankView createBank(@AuthenticationPrincipal Jwt jwt,
			@RequestBody QuestionService.CreateBankRequest request) {
		return this.service.createBank(jwt, request);
	}

	@GetMapping
	public List<QuestionService.BankView> listBanks() {
		return this.service.listBanks();
	}

}
