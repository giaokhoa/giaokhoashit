package com.giaokhoa.server.exam;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.giaokhoa.server.access.AccessResource;
import com.giaokhoa.server.access.AccessResourceRepository;
import com.giaokhoa.server.access.PartitionSessions;
import com.giaokhoa.server.access.ResourceVersion;
import com.giaokhoa.server.access.ResourceVersionRepository;
import com.giaokhoa.server.question.Question;
import com.giaokhoa.server.question.QuestionBank;
import com.giaokhoa.server.question.QuestionBankRepository;
import com.giaokhoa.server.question.QuestionRepository;
import com.giaokhoa.server.question.QuestionVersion;
import com.giaokhoa.server.question.QuestionVersionRepository;
import com.giaokhoa.server.tenancy.TenantContexts;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cài đặt thật của cổng đọc phiên bản câu: đọc các bảng nội dung của feat câu hỏi (hiện tại chỉ có workspace cá
 * nhân) và lắp nội dung theo đúng hình mà lát soạn đề tiêu thụ. Các bảng lõi của lõi quyền chưa có hàng cho câu
 * của feat này, trong khi dịch vụ quyết định của lõi chỉ định vị được tài nguyên đã có hàng và khoá ngoại của
 * {@code resource_dependency} yêu cầu phiên bản câu đã có hàng {@code resource_version} tương ứng — nên mỗi lần
 * một phiên bản câu được tiêu thụ, cài đặt này bảo đảm các hàng lõi tương ứng của ngân hàng, câu và phiên bản câu
 * tồn tại trong phân vùng của chủ sở hữu câu, tạo mới nếu chưa có và đồng bộ các trường gương nếu đã có. Việc
 * đăng ký chỉ mang sang các sự kiện định danh, chủ sở hữu và trạng thái phiên bản; nội dung câu vẫn chỉ nằm ở
 * bảng nội dung của feat câu hỏi.
 */
@Component
public class PersonalBankQuestionVersionSource implements QuestionVersionSource {

	private final QuestionVersionRepository versions;

	private final QuestionRepository questions;

	private final QuestionBankRepository banks;

	private final AccessResourceRepository resources;

	private final ResourceVersionRepository coreVersions;

	private final PartitionSessions sessions;

	private final ObjectMapper objectMapper;

	public PersonalBankQuestionVersionSource(QuestionVersionRepository versions, QuestionRepository questions,
			QuestionBankRepository banks, AccessResourceRepository resources,
			ResourceVersionRepository coreVersions, PartitionSessions sessions, ObjectMapper objectMapper) {
		this.versions = versions;
		this.questions = questions;
		this.banks = banks;
		this.resources = resources;
		this.coreVersions = coreVersions;
		this.sessions = sessions;
		this.objectMapper = objectMapper;
	}

	@Override
	public Optional<QuestionVersionInfo> find(UUID questionVersionId) {
		QuestionVersion version = this.versions.findById(questionVersionId).orElse(null);
		if (version == null) {
			return Optional.empty();
		}
		Question question = this.questions.findById(version.getQuestionId()).orElse(null);
		QuestionBank bank = (question != null) ? this.banks.findById(question.getBankId()).orElse(null) : null;
		if (question == null || bank == null) {
			return Optional.empty();
		}
		registerCoreRows(bank, question, version);
		return Optional.of(new QuestionVersionInfo(question.getId(), version.getId(), version.getQuestionType(),
				AccessResource.WORKSPACE_PERSONAL, version.getOwnerSub(), null, false, contentOf(version)));
	}

	// Nội dung lắp từ các cột nội dung của phiên bản: trắc nghiệm mang lựa chọn và đáp án, tự luận mang tiêu chí
	// chấm; phần đáp án chỉ vắng khi chính phiên bản chưa có dữ kiện đó, việc lược bỏ theo quyền là của ExamService.
	private JsonNode contentOf(QuestionVersion version) {
		ObjectNode content = this.objectMapper.createObjectNode();
		content.put("stem", version.getStem());
		if (QuestionVersion.TYPE_SINGLE_CHOICE.equals(version.getQuestionType())) {
			content.set("choices", choicesOf(version));
			if (version.getCorrectChoiceId() != null) {
				content.put("correctChoiceId", version.getCorrectChoiceId());
			}
			if (version.getSolution() != null) {
				content.put("solution", version.getSolution());
			}
		}
		else {
			if (version.getRubric() != null) {
				content.put("rubric", version.getRubric());
			}
			if (version.getSolution() != null) {
				content.put("solution", version.getSolution());
			}
		}
		return content;
	}

	private JsonNode choicesOf(QuestionVersion version) {
		if (version.getChoices() == null) {
			return this.objectMapper.createArrayNode();
		}
		try {
			return this.objectMapper.readTree(version.getChoices());
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Không đọc được danh sách lựa chọn đã lưu của phiên bản câu.", ex);
		}
	}

	private void registerCoreRows(QuestionBank bank, Question question, QuestionVersion version) {
		String partition = "user:" + question.getOwnerSub();
		// Dữ kiện phiên chỉ cần sub của người gọi thật cho biến RLS phía người gọi; các hàng lõi ghi vào phân vùng
		// của chủ sở hữu câu và được RLS của bảng lõi bảo vệ theo khoá phân vùng, không theo người gọi.
		String actorSub = TenantContexts.current() != null ? TenantContexts.current().sub()
				: question.getOwnerSub();
		this.sessions.inPartition(partition, actorSub, () -> {
			if (this.resources.findById(bank.getId()).isEmpty()) {
				AccessResource bankResource = new AccessResource();
				bankResource.setId(bank.getId());
				bankResource.setResourceType("question_bank");
				bankResource.setWorkspaceKind(AccessResource.WORKSPACE_PERSONAL);
				bankResource.setOwnerType(AccessResource.OWNER_USER);
				bankResource.setOwnerUserSub(bank.getOwnerSub());
				bankResource.setCustodyKind(AccessResource.CUSTODY_OWNED);
				bankResource.setCreatedBySub(bank.getOwnerSub());
				bankResource.setCreatedAt(bank.getCreatedAt());
				bankResource.setUpdatedAt(bank.getUpdatedAt());
				this.resources.save(bankResource);
			}
			if (this.resources.findById(question.getId()).isEmpty()) {
				AccessResource questionResource = new AccessResource();
				questionResource.setId(question.getId());
				questionResource.setResourceType("question");
				questionResource.setWorkspaceKind(AccessResource.WORKSPACE_PERSONAL);
				questionResource.setOwnerType(AccessResource.OWNER_USER);
				questionResource.setOwnerUserSub(question.getOwnerSub());
				questionResource.setCollectionId(bank.getId());
				questionResource.setCustodyKind(AccessResource.CUSTODY_OWNED);
				questionResource.setInheritedUsageLimits(question.getUsageLimits());
				questionResource.setSourceResourceId(question.getSourceQuestionId());
				questionResource.setSourceVersionId(question.getSourceVersionId());
				questionResource.setCreatedBySub(question.getOwnerSub());
				questionResource.setCreatedAt(question.getCreatedAt());
				questionResource.setUpdatedAt(question.getUpdatedAt());
				this.resources.save(questionResource);
			}
			ResourceVersion mirror = this.coreVersions.findById(version.getId()).orElse(null);
			if (mirror == null) {
				mirror = new ResourceVersion();
				mirror.setId(version.getId());
				mirror.setResourceId(question.getId());
				mirror.setVersionNumber(version.getVersionNumber());
				mirror.setCreatedBySub(version.getOwnerSub());
				mirror.setCreatedAt(version.getCreatedAt());
			}
			mirror.setStatus(version.getStatus());
			mirror.setContentHash(version.getContentHash());
			mirror.setLockedAt(version.getLockedAt());
			mirror.setPublishedAt(
					QuestionVersion.STATUS_PUBLISHED.equals(version.getStatus()) ? version.getLockedAt() : null);
			mirror.setUpdatedAt(version.getUpdatedAt());
			this.coreVersions.save(mirror);
		});
	}

}
