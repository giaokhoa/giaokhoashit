package com.giaokhoa.server.question;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

@Service
public class QuestionService {

	public record BankView(UUID id, String resourceType, String workspaceKind, String ownerType, String ownerUserSub,
			String custodyKind, String name, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
	}

	public record CreateBankRequest(String name) {
	}

	public record ChoiceInput(String id, String text) {
	}

	public record VersionView(UUID versionId, int versionNumber, String status, OffsetDateTime lockedAt,
			String contentHash, int revision, String questionType, int contentContractVersion, String stem,
			List<ChoiceInput> choices,
			@JsonInclude(JsonInclude.Include.NON_NULL) String correctChoiceId,
			@JsonInclude(JsonInclude.Include.NON_NULL) String solution,
			@JsonInclude(JsonInclude.Include.NON_NULL) String rubric) {
	}

	public record QuestionView(UUID id, String resourceType, String workspaceKind, String ownerType,
			String ownerUserSub, String custodyKind, UUID bankId, UUID sourceQuestionId, UUID sourceVersionId,
			String usageLimits, List<VersionView> versions, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
	}

	public record CreateQuestionRequest(UUID bankId, String questionType, String stem, List<ChoiceInput> choices,
			String correctChoiceId, String solution, String rubric, String usageLimits) {
	}

	public record UpdateVersionRequest(Integer revision, String questionType, String stem, List<ChoiceInput> choices,
			String correctChoiceId, String solution, String rubric) {
	}

	public record NewVersionRequest(UUID fromVersionId) {
	}

	public record CopyRequest(UUID targetBankId) {
	}

	public record EntryView(UUID id, UUID bankId, UUID questionId, UUID questionVersionId, OffsetDateTime createdAt) {
	}

	public record AddEntryRequest(UUID questionVersionId) {
	}

	private final QuestionBankRepository banks;

	private final QuestionRepository questions;

	private final QuestionVersionRepository versions;

	private final BankEntryRepository entries;

	private final QuestionVersionBlockRepository blocks;

	private final QuestionRights rights;

	private final ObjectMapper objectMapper;

	public QuestionService(QuestionBankRepository banks, QuestionRepository questions,
			QuestionVersionRepository versions, BankEntryRepository entries, QuestionVersionBlockRepository blocks,
			QuestionRights rights, ObjectMapper objectMapper) {
		this.banks = banks;
		this.questions = questions;
		this.versions = versions;
		this.entries = entries;
		this.blocks = blocks;
		this.rights = rights;
		this.objectMapper = objectMapper;
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

	@Transactional
	public QuestionView createQuestion(Jwt jwt, CreateQuestionRequest request) {
		TenantContext context = requireContext();
		if (!this.rights.personalAuthor(jwt)) {
			throw QuestionException.forbidden();
		}
		if (request == null || request.bankId() == null) {
			throw QuestionException.validation("Cần chọn ngân hàng cho câu hỏi.");
		}
		QuestionBank bank = this.banks.findByIdAndOwnerSub(request.bankId(), context.sub())
			.orElseThrow(QuestionException::notFound);
		requireSupportedType(request.questionType());
		OffsetDateTime now = OffsetDateTime.now();
		Question question = new Question();
		question.setId(UUID.randomUUID());
		question.setOwnerSub(context.sub());
		question.setBankId(bank.getId());
		question.setUsageLimits(request.usageLimits());
		question.setCreatedAt(now);
		question.setUpdatedAt(now);
		this.questions.save(question);
		QuestionVersion version = newVersionEntity(question, 1, now);
		applyContent(version, request.questionType(), request.stem(), request.choices(), request.correctChoiceId(),
				request.solution(), request.rubric());
		this.versions.save(version);
		return questionView(context, question);
	}

	@Transactional(readOnly = true)
	public QuestionView getQuestion(UUID questionId) {
		TenantContext context = requireContext();
		Question question = this.questions.findById(questionId).orElseThrow(QuestionException::notFound);
		if (!this.rights.allowed(context, question, null, QuestionRights.VIEW_CONTENT)) {
			// Đường đọc một tài nguyên ngoài phạm vi của người gọi trả 404 như tài nguyên không tồn tại, để
			// đáp ứng không lộ sự tồn tại của nó; các thao tác ghi dùng 403 theo cùng hợp đồng lỗi.
			throw QuestionException.notFound();
		}
		return questionView(context, question);
	}

	@Transactional
	public VersionView updateVersion(UUID questionId, UUID versionId, UpdateVersionRequest request) {
		TenantContext context = requireContext();
		Question question = this.questions.findById(questionId).orElseThrow(QuestionException::notFound);
		QuestionVersion version = resolveVersion(question, versionId);
		if (!this.rights.allowed(context, question, version, QuestionRights.EDIT_DRAFT)) {
			throw QuestionException.forbidden();
		}
		if (version.locked()) {
			throw QuestionException.locked();
		}
		if (request == null || request.revision() == null || request.revision() != version.getRevision()) {
			throw QuestionException.versionConflict();
		}
		requireSupportedType(request.questionType());
		applyContent(version, request.questionType(), request.stem(), request.choices(), request.correctChoiceId(),
				request.solution(), request.rubric());
		version.setUpdatedAt(OffsetDateTime.now());
		// Ghi và flush ngay để số revision trong đáp ứng là số thật sau lượt ghi: Hibernate chỉ tăng cột
		// @Version lúc flush, nếu đọc entity trước flush thì đáp ứng mang revision cũ và client không có số
		// đúng cho lượt sửa tiếp theo. Flush tại đây cũng làm xung đột lạc quan thật sự nổ thành
		// ObjectOptimisticLockingFailureException ngay trong service thay vì lúc commit.
		return versionView(context, question, this.versions.saveAndFlush(version));
	}

	@Transactional
	public VersionView newVersion(UUID questionId, NewVersionRequest request) {
		TenantContext context = requireContext();
		Question question = this.questions.findById(questionId).orElseThrow(QuestionException::notFound);
		if (request == null || request.fromVersionId() == null) {
			throw QuestionException.validation("Cần chọn phiên bản nguồn để tạo phiên bản nháp mới.");
		}
		QuestionVersion source = resolveVersion(question, request.fromVersionId());
		if (!this.rights.allowed(context, question, source, QuestionRights.EDIT_DRAFT)) {
			throw QuestionException.forbidden();
		}
		if (!source.locked()) {
			throw QuestionException.stateConflict("Chỉ tạo được phiên bản nháp mới từ một phiên bản đã khoá.");
		}
		int nextNumber = this.versions.findFirstByQuestionIdOrderByVersionNumberDesc(questionId)
			.map((latest) -> latest.getVersionNumber() + 1)
			.orElse(1);
		QuestionVersion version = newVersionEntity(question, nextNumber, OffsetDateTime.now());
		applyContent(version, source.getQuestionType(), source.getStem(), parseChoices(source.getChoices()),
				source.getCorrectChoiceId(), source.getSolution(), source.getRubric());
		QuestionVersion saved = this.versions.save(version);
		carryBlocks(source, saved, true);
		return versionView(context, question, saved);
	}

	@Transactional
	public VersionView publish(UUID questionId, UUID versionId) {
		TenantContext context = requireContext();
		Question question = this.questions.findById(questionId).orElseThrow(QuestionException::notFound);
		QuestionVersion version = resolveVersion(question, versionId);
		if (!this.rights.allowed(context, question, version, QuestionRights.PUBLISH)) {
			throw QuestionException.forbidden();
		}
		if (version.locked()) {
			throw QuestionException.stateConflict("Phiên bản đã phát hành và đã khoá.");
		}
		validateForPublish(version);
		OffsetDateTime now = OffsetDateTime.now();
		version.setStatus(QuestionVersion.STATUS_PUBLISHED);
		version.setLockedAt(now);
		version.setUpdatedAt(now);
		version.setContentHash(contentHash(version));
		return versionView(context, question, this.versions.saveAndFlush(version));
	}

	// Sao chép luôn tạo một tài nguyên mới thuộc về người sao chép; đáp án, lời giải và tiêu chí chấm của
	// phiên bản nguồn chỉ đi theo khi người sao chép có view_answer trên đúng phiên bản nguồn đó, còn truy vết
	// nguồn và giới hạn sử dụng của nguồn thì luôn đi theo và ràng buộc cả bản sao.
	@Transactional
	public QuestionView copy(Jwt jwt, UUID questionId, UUID versionId, CopyRequest request) {
		TenantContext context = requireContext();
		Question source = this.questions.findById(questionId).orElseThrow(QuestionException::notFound);
		QuestionVersion sourceVersion = resolveVersion(source, versionId);
		if (!this.rights.allowed(context, source, sourceVersion, QuestionRights.COPY)) {
			throw QuestionException.forbidden();
		}
		// Sao chép cần đủ ba điều kiện của đặc tả mục 6 trước khi tạo gì: quyền tạo bản riêng và quyền đọc trên
		// phiên bản nguồn, cộng quyền tạo câu trong ngân hàng đích; thiếu một điều là 403, không tạo tài nguyên.
		if (!this.rights.allowed(context, source, sourceVersion, QuestionRights.VIEW_CONTENT)) {
			throw QuestionException.forbidden();
		}
		if (!this.rights.personalAuthor(jwt)) {
			throw QuestionException.forbidden();
		}
		UUID targetBankId = (request != null) ? request.targetBankId() : null;
		if (targetBankId == null && context.sub().equals(source.getOwnerSub())) {
			targetBankId = source.getBankId();
		}
		if (targetBankId == null) {
			throw QuestionException.validation("Cần chọn ngân hàng đích cho bản sao.");
		}
		QuestionBank target = this.banks.findByIdAndOwnerSub(targetBankId, context.sub())
			.orElseThrow(QuestionException::notFound);
		boolean includeAnswers = this.rights.allowed(context, source, sourceVersion, QuestionRights.VIEW_ANSWER);
		OffsetDateTime now = OffsetDateTime.now();
		Question copy = new Question();
		copy.setId(UUID.randomUUID());
		copy.setOwnerSub(context.sub());
		copy.setBankId(target.getId());
		copy.setSourceQuestionId(source.getId());
		copy.setSourceVersionId(sourceVersion.getId());
		copy.setUsageLimits(source.getUsageLimits());
		copy.setCreatedAt(now);
		copy.setUpdatedAt(now);
		this.questions.save(copy);
		QuestionVersion version = newVersionEntity(copy, 1, now);
		applyContent(version, sourceVersion.getQuestionType(), sourceVersion.getStem(),
				parseChoices(sourceVersion.getChoices()),
				includeAnswers ? sourceVersion.getCorrectChoiceId() : null,
				includeAnswers ? sourceVersion.getSolution() : null,
				includeAnswers ? sourceVersion.getRubric() : null);
		QuestionVersion savedCopy = this.versions.save(version);
		carryBlocks(sourceVersion, savedCopy, false);
		return questionView(context, copy);
	}

	@Transactional
	public EntryView addEntry(UUID bankId, AddEntryRequest request) {
		TenantContext context = requireContext();
		QuestionBank bank = this.banks.findByIdAndOwnerSub(bankId, context.sub())
			.orElseThrow(QuestionException::notFound);
		if (request == null || request.questionVersionId() == null) {
			throw QuestionException.validation("Cần chọn phiên bản cụ thể cho mục ngân hàng.");
		}
		QuestionVersion version = this.versions.findById(request.questionVersionId())
			.orElseThrow(QuestionException::notFound);
		Question question = this.questions.findById(version.getQuestionId())
			.orElseThrow(QuestionException::notFound);
		if (!this.rights.allowed(context, question, version, QuestionRights.VIEW_CONTENT)) {
			throw QuestionException.forbidden();
		}
		if (this.entries.findByBankIdAndQuestionVersionId(bank.getId(), version.getId()).isPresent()) {
			throw QuestionException.stateConflict("Phiên bản này đã có trong ngân hàng.");
		}
		BankEntry entry = new BankEntry();
		entry.setId(UUID.randomUUID());
		entry.setBankId(bank.getId());
		entry.setQuestionId(question.getId());
		entry.setQuestionVersionId(version.getId());
		entry.setOwnerSub(context.sub());
		entry.setCreatedAt(OffsetDateTime.now());
		BankEntry saved = this.entries.save(entry);
		return new EntryView(saved.getId(), saved.getBankId(), saved.getQuestionId(), saved.getQuestionVersionId(),
				saved.getCreatedAt());
	}

	@Transactional
	public void removeEntry(UUID bankId, UUID entryId) {
		TenantContext context = requireContext();
		QuestionBank bank = this.banks.findByIdAndOwnerSub(bankId, context.sub())
			.orElseThrow(QuestionException::notFound);
		BankEntry entry = this.entries.findByIdAndBankId(entryId, bank.getId())
			.orElseThrow(QuestionException::notFound);
		this.entries.delete(entry);
	}

	// Khối nội dung đi theo phiên bản khi tạo nháp mới hoặc sao chép. Tạo nháp mới của cùng một tài nguyên
	// không phải chuyển chủ nên nguồn hợp lệ của chủ sở hữu được giữ nguyên; sao chép sang chủ khác thì khối
	// công thức thành chỉ-có-ảnh — nguồn không tự chuyển theo bản sao, kể cả nguồn do chính giáo viên tự soạn.
	private void carryBlocks(QuestionVersion source, QuestionVersion target, boolean keepSource) {
		OffsetDateTime now = OffsetDateTime.now();
		for (QuestionVersionBlock sourceBlock : this.blocks.findByQuestionVersionId(source.getId())) {
			QuestionVersionBlock block = new QuestionVersionBlock();
			block.setId(com.giaokhoa.server.files.UuidV7.next());
			block.setQuestionVersionId(target.getId());
			block.setOwnerSub(target.getOwnerSub());
			block.setPart(sourceBlock.getPart());
			block.setChoiceId(sourceBlock.getChoiceId());
			block.setPosition(sourceBlock.getPosition());
			block.setKind(sourceBlock.getKind());
			block.setTextContent(sourceBlock.getTextContent());
			block.setDescription(sourceBlock.getDescription());
			block.setSystemOrigin(sourceBlock.isSystemOrigin());
			block.setImageBlobId(sourceBlock.getImageBlobId());
			block.setImageWidth(sourceBlock.getImageWidth());
			block.setImageHeight(sourceBlock.getImageHeight());
			block.setBaselineOffset(sourceBlock.getBaselineOffset());
			if (QuestionVersionBlock.KIND_FORMULA.equals(sourceBlock.getKind())) {
				if (keepSource && QuestionVersionBlock.FORM_SOURCE_BACKED.equals(sourceBlock.getFormulaForm())) {
					block.setFormulaForm(QuestionVersionBlock.FORM_SOURCE_BACKED);
					block.setMathSource(sourceBlock.getMathSource());
					block.setRenderState(sourceBlock.getRenderState());
					block.setSourceRevision(sourceBlock.getSourceRevision());
				}
				else {
					block.setFormulaForm(QuestionVersionBlock.FORM_RENDER_ONLY);
					block.setRenderState(
							sourceBlock.getImageBlobId() != null ? QuestionVersionBlock.RENDER_READY : null);
				}
			}
			block.setCreatedAt(now);
			block.setUpdatedAt(now);
			this.blocks.save(block);
		}
	}

	private QuestionVersion resolveVersion(Question question, UUID versionId) {
		QuestionVersion version = this.versions.findById(versionId).orElseThrow(QuestionException::notFound);
		if (!version.getQuestionId().equals(question.getId())) {
			// Quan hệ phiên bản–câu hỏi không khớp được tính là bị từ chối trên một tài nguyên tồn tại (403),
			// không phải không tồn tại, theo hợp đồng lỗi dùng chung.
			throw QuestionException.forbidden();
		}
		return version;
	}

	private QuestionVersion newVersionEntity(Question question, int versionNumber, OffsetDateTime now) {
		QuestionVersion version = new QuestionVersion();
		version.setId(UUID.randomUUID());
		version.setQuestionId(question.getId());
		version.setOwnerSub(question.getOwnerSub());
		version.setVersionNumber(versionNumber);
		version.setStatus(QuestionVersion.STATUS_DRAFT);
		version.setContentContractVersion(QuestionVersion.CONTENT_CONTRACT_VERSION);
		version.setCreatedAt(now);
		version.setUpdatedAt(now);
		return version;
	}

	private void applyContent(QuestionVersion version, String questionType, String stem, List<ChoiceInput> choices,
			String correctChoiceId, String solution, String rubric) {
		ExternalContentReferences.rejectIfPresent(stem);
		ExternalContentReferences.rejectIfPresent(solution);
		ExternalContentReferences.rejectIfPresent(rubric);
		if (choices != null) {
			for (ChoiceInput choice : choices) {
				ExternalContentReferences.rejectIfPresent(choice.text());
			}
		}
		version.setQuestionType(questionType);
		version.setStem(stem != null ? stem : "");
		version.setChoices(QuestionVersion.TYPE_SINGLE_CHOICE.equals(questionType) ? choicesJson(choices) : null);
		version.setCorrectChoiceId(correctChoiceId);
		version.setSolution(solution);
		version.setRubric(rubric);
		version.setContentHash(contentHash(version));
	}

	// Kiểm tra hợp lệ tối thiểu chạy trước khi phát hành: bản nháp được phép thiếu, nhưng một phiên bản đã
	// phát hành phải tự đủ để dùng và chấm. Lỗi ở bước này là 422 và phiên bản giữ nguyên trạng thái nháp.
	private void validateForPublish(QuestionVersion version) {
		if (version.getStem() == null || version.getStem().isBlank()) {
			throw QuestionException.validation("Thân câu không được để trống khi phát hành.");
		}
		if (QuestionVersion.TYPE_SINGLE_CHOICE.equals(version.getQuestionType())) {
			List<ChoiceInput> choices = parseChoices(version.getChoices());
			if (choices == null || choices.size() < 2) {
				throw QuestionException.validation("Câu trắc nghiệm cần ít nhất hai lựa chọn.");
			}
			long distinctIds = choices.stream().map(ChoiceInput::id).distinct().count();
			if (distinctIds != choices.size()
					|| choices.stream()
						.anyMatch((choice) -> choice.id() == null || choice.id().isBlank() || choice.text() == null
								|| choice.text().isBlank())) {
				throw QuestionException.validation("Mỗi lựa chọn cần một mã riêng và nội dung không trống.");
			}
			if (version.getCorrectChoiceId() == null
					|| choices.stream().noneMatch((choice) -> choice.id().equals(version.getCorrectChoiceId()))) {
				throw QuestionException.validation("Đáp án phải trỏ đúng một trong các lựa chọn.");
			}
		}
		else if (QuestionVersion.TYPE_ESSAY_TEXT.equals(version.getQuestionType())) {
			if (version.getRubric() == null || version.getRubric().isBlank()) {
				throw QuestionException.validation("Câu tự luận cần tiêu chí chấm khi phát hành.");
			}
		}
	}

	private void requireSupportedType(String questionType) {
		if (!QuestionVersion.TYPE_SINGLE_CHOICE.equals(questionType)
				&& !QuestionVersion.TYPE_ESSAY_TEXT.equals(questionType)) {
			throw QuestionException.validation("Loại câu hỏi không được hỗ trợ: chỉ gồm single_choice và essay_text.");
		}
	}

	private TenantContext requireContext() {
		TenantContext context = TenantContexts.current();
		if (context == null) {
			throw QuestionException.forbidden();
		}
		return context;
	}

	private QuestionView questionView(TenantContext context, Question question) {
		List<VersionView> versionViews = this.versions.findByQuestionIdOrderByVersionNumberAsc(question.getId())
			.stream()
			.map((version) -> versionView(context, question, version))
			.toList();
		return new QuestionView(question.getId(), "question", "personal", "user", question.getOwnerSub(), "personal",
				question.getBankId(), question.getSourceQuestionId(), question.getSourceVersionId(),
				question.getUsageLimits(), versionViews, question.getCreatedAt(), question.getUpdatedAt());
	}

	// Phần đáp án của một phiên bản chỉ được điền khi người gọi có view_answer trên đúng phiên bản đó; thiếu
	// quyền thì ba trường đó vắng hẳn trong dữ kiện trả về, không trả dạng che hay rỗng.
	private VersionView versionView(TenantContext context, Question question, QuestionVersion version) {
		boolean includeAnswers = this.rights.allowed(context, question, version, QuestionRights.VIEW_ANSWER);
		return new VersionView(version.getId(), version.getVersionNumber(), version.getStatus(),
				version.getLockedAt(), version.getContentHash(), version.getRevision(), version.getQuestionType(),
				version.getContentContractVersion(), version.getStem(), parseChoices(version.getChoices()),
				includeAnswers ? version.getCorrectChoiceId() : null,
				includeAnswers ? version.getSolution() : null, includeAnswers ? version.getRubric() : null);
	}

	private BankView bankView(QuestionBank bank) {
		return new BankView(bank.getId(), "question_bank", "personal", "user", bank.getOwnerSub(), "personal",
				bank.getName(), bank.getCreatedAt(), bank.getUpdatedAt());
	}

	private String choicesJson(List<ChoiceInput> choices) {
		if (choices == null) {
			return null;
		}
		try {
			return this.objectMapper.writeValueAsString(choices);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Không ghi được danh sách lựa chọn.", ex);
		}
	}

	private List<ChoiceInput> parseChoices(String json) {
		if (json == null) {
			return null;
		}
		try {
			return this.objectMapper.readValue(json, new TypeReference<List<ChoiceInput>>() {
			});
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Không đọc được danh sách lựa chọn đã lưu.", ex);
		}
	}

	// Hàm băm nội dung tính trên đúng các trường nội dung của phiên bản, tính lại ở mọi lượt ghi nên khi phiên
	// bản khoá thì giá trị này đứng yên cùng nội dung đã khoá.
	private String contentHash(QuestionVersion version) {
		String material = String.join("\n", nullToEmpty(version.getQuestionType()), nullToEmpty(version.getStem()),
				nullToEmpty(version.getChoices()), nullToEmpty(version.getCorrectChoiceId()),
				nullToEmpty(version.getSolution()), nullToEmpty(version.getRubric()));
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Thiếu thuật toán SHA-256.", ex);
		}
	}

	private String nullToEmpty(String value) {
		return value != null ? value : "";
	}

}
