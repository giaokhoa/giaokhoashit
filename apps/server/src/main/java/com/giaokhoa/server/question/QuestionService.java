package com.giaokhoa.server.question;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

@Service
public class QuestionService {

	public record BankView(UUID id, String resourceType, String workspaceKind, String ownerType, String ownerUserSub,
			String custodyKind, String name, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
	}

	public record CreateBankRequest(String name) {
	}

	private final QuestionBankRepository banks;

	private final QuestionRepository questions;

	private final QuestionVersionRepository versions;

	private final BankEntryRepository entries;

	private final QuestionRights rights;

	public QuestionService(QuestionBankRepository banks, QuestionRepository questions,
			QuestionVersionRepository versions, BankEntryRepository entries, QuestionRights rights) {
		this.banks = banks;
		this.questions = questions;
		this.versions = versions;
		this.entries = entries;
		this.rights = rights;
	}

	// Tài nguyên do giáo viên soạn mặc định thuộc workspace cá nhân của chính tài khoản đó: chủ sở hữu luôn lấy
	// từ ngữ cảnh đã xác thực, kể cả khi thao tác diễn ra tại trang của một trung tâm, và không nhận từ dữ kiện
	// client gửi.
	@Transactional
	public BankView createBank(Jwt jwt, CreateBankRequest request) {
		TenantContext context = requireContext();
		if (!this.rights.personalAuthor(jwt)) {
			throw QuestionException.forbidden();
		}
		if (request == null || request.name() == null || request.name().isBlank()) {
			throw QuestionException.validation("Tên ngân hàng không được để trống.");
		}
		OffsetDateTime now = OffsetDateTime.now();
		QuestionBank bank = new QuestionBank();
		bank.setId(UUID.randomUUID());
		bank.setOwnerSub(context.sub());
		bank.setName(request.name());
		bank.setCreatedAt(now);
		bank.setUpdatedAt(now);
		return bankView(this.banks.save(bank));
	}

	@Transactional(readOnly = true)
	public List<BankView> listBanks() {
		TenantContext context = requireContext();
		return this.banks.findByOwnerSubOrderByCreatedAtAsc(context.sub())
			.stream()
			.map(this::bankView)
			.toList();
	}

	private TenantContext requireContext() {
		TenantContext context = TenantContexts.current();
		if (context == null) {
			throw QuestionException.forbidden();
		}
		return context;
	}

	private BankView bankView(QuestionBank bank) {
		return new BankView(bank.getId(), "question_bank", "personal", "user", bank.getOwnerSub(), "personal",
				bank.getName(), bank.getCreatedAt(), bank.getUpdatedAt());
	}

}
