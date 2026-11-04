package com.giaokhoa.server.question;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

	@PostMapping("/{bankId}/entries")
	public QuestionService.EntryView addEntry(@PathVariable UUID bankId,
			@RequestBody QuestionService.AddEntryRequest request) {
		return this.service.addEntry(bankId, request);
	}

	@DeleteMapping("/{bankId}/entries/{entryId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeEntry(@PathVariable UUID bankId, @PathVariable UUID entryId) {
		this.service.removeEntry(bankId, entryId);
	}

}
