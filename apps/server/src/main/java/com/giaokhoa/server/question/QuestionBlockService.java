package com.giaokhoa.server.question;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.giaokhoa.server.files.UuidV7;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

/**
 * Hợp đồng nội dung theo khối của phiên bản câu hỏi, tách hai mặt rõ rệt: mặt hiển thị chỉ mang văn bản thường
 * cùng tham chiếu ảnh của khối công thức — không có trường nguồn ở bất kỳ dạng nào; mặt soạn thảo mang thêm
 * nguồn LaTeX của khối có-nguồn, và chỉ cho người có quyền đọc nguồn trên đúng phiên bản đó. Khối thuộc đáp án
 * hay lời giải bị loại khỏi cả hai mặt khi người gọi thiếu quyền xem đáp án, và đường ghi đòi thêm quyền sửa
 * nguồn cho thao tác nguồn cùng quyền xem đáp án cho khối thuộc đáp án hay lời giải. Các trường do server quản
 * lý mà client gửi lên (dạng khối, nguồn gốc hệ thống, định danh blob ảnh, trạng thái render) bị từ chối thay
 * vì âm thầm bỏ qua.
 */
@Service
public class QuestionBlockService {

	private static final Set<String> PARTS = Set.of(QuestionVersionBlock.PART_STEM, QuestionVersionBlock.PART_CHOICE,
			QuestionVersionBlock.PART_SOLUTION, QuestionVersionBlock.PART_RUBRIC);

	private static final int MAX_SOURCE_LENGTH = 2000;

	private static final int MAX_FORMULA_BLOCKS = 50;

	public record BlockInput(UUID blockId, String part, String choiceId, String kind, String text, String source,
			String description, String form, Boolean systemOrigin, UUID imageBlobId, String renderState) {
	}

	public record ReplaceBlocksRequest(Integer revision, List<BlockInput> blocks) {
	}

	public record ImageRef(UUID fileRef, @JsonInclude(JsonInclude.Include.NON_NULL) Integer width,
			@JsonInclude(JsonInclude.Include.NON_NULL) Integer height,
			@JsonInclude(JsonInclude.Include.NON_NULL) Integer baselineOffset) {
	}

	public record DisplayBlock(UUID blockId, String part, @JsonInclude(JsonInclude.Include.NON_NULL) String choiceId,
			String kind, @JsonInclude(JsonInclude.Include.NON_NULL) String text,
			@JsonInclude(JsonInclude.Include.NON_NULL) String form,
			@JsonInclude(JsonInclude.Include.NON_NULL) String renderState,
			@JsonInclude(JsonInclude.Include.NON_NULL) ImageRef image,
			@JsonInclude(JsonInclude.Include.NON_NULL) String description) {
	}

	public record DraftBlock(UUID blockId, String part, @JsonInclude(JsonInclude.Include.NON_NULL) String choiceId,
			String kind, @JsonInclude(JsonInclude.Include.NON_NULL) String text,
			@JsonInclude(JsonInclude.Include.NON_NULL) String form,
			@JsonInclude(JsonInclude.Include.NON_NULL) String renderState,
			@JsonInclude(JsonInclude.Include.NON_NULL) String source,
			@JsonInclude(JsonInclude.Include.NON_NULL) ImageRef image,
			@JsonInclude(JsonInclude.Include.NON_NULL) String description, int revision) {
	}

	public record DisplayBlocksView(UUID questionId, UUID versionId, List<DisplayBlock> blocks) {
	}

	public record DraftBlocksView(UUID questionId, UUID versionId, int revision, List<DraftBlock> blocks) {
	}

	private final QuestionRepository questions;

	private final QuestionVersionRepository versions;

	private final QuestionVersionBlockRepository blocks;

	private final QuestionRights rights;

	public QuestionBlockService(QuestionRepository questions, QuestionVersionRepository versions,
			QuestionVersionBlockRepository blocks, QuestionRights rights) {
		this.questions = questions;
		this.versions = versions;
		this.blocks = blocks;
		this.rights = rights;
	}

	@Transactional
	public DraftBlocksView replaceBlocks(UUID questionId, UUID versionId, ReplaceBlocksRequest request) {
		TenantContext context = requireContext();
		Question question = resolveQuestion(questionId);
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
		List<BlockInput> inputs = (request.blocks() != null) ? request.blocks() : List.of();
		List<QuestionVersionBlock> existing = this.blocks.findByQuestionVersionId(versionId);
		validateInputs(inputs, existing);
		boolean canViewAnswer = this.rights.allowed(context, question, version, QuestionRights.VIEW_ANSWER);
		boolean canEditSource = this.rights.allowed(context, question, version, QuestionRights.EDIT_MATH_SOURCE);
		Map<UUID, QuestionVersionBlock> existingById = new HashMap<>();
		for (QuestionVersionBlock row : existing) {
			existingById.put(row.getId(), row);
		}
		for (BlockInput input : inputs) {
			QuestionVersionBlock matched = (input.blockId() != null) ? existingById.get(input.blockId()) : null;
			boolean writesSource = QuestionVersionBlock.KIND_FORMULA.equals(input.kind()) && input.source() != null
					&& !input.source().isBlank();
			// Xoá nguồn cũng là thao tác trên nguồn: khối có-nguồn được giữ định danh nhưng đầu vào đổi sang
			// khối văn bản thì nguồn của nó mất đi, nên vẫn đòi quyền sửa nguồn như khi ghi nguồn mới.
			boolean dropsSource = !writesSource && matched != null
					&& QuestionVersionBlock.FORM_SOURCE_BACKED.equals(matched.getFormulaForm());
			if ((writesSource || dropsSource) && !canEditSource) {
				throw QuestionException.forbidden();
			}
			if (answerPart(input.part()) && !canViewAnswer) {
				throw QuestionException.forbidden();
			}
		}
		OffsetDateTime now = OffsetDateTime.now();
		Map<String, Integer> positions = new HashMap<>();
		Set<UUID> kept = new HashSet<>();
		for (BlockInput input : inputs) {
			QuestionVersionBlock block = (input.blockId() != null) ? existingById.get(input.blockId()) : null;
			if (block == null) {
				block = new QuestionVersionBlock();
				block.setId(UuidV7.next());
				block.setQuestionVersionId(versionId);
				block.setOwnerSub(question.getOwnerSub());
				block.setCreatedAt(now);
			}
			kept.add(block.getId());
			block.setPart(input.part());
			block.setChoiceId(input.choiceId());
			String group = input.part() + "|" + input.choiceId();
			block.setPosition(positions.merge(group, 1, Integer::sum) - 1);
			block.setKind(input.kind());
			block.setDescription(input.description());
			block.setUpdatedAt(now);
			if (QuestionVersionBlock.KIND_TEXT.equals(input.kind())) {
				block.setTextContent(input.text());
				block.setFormulaForm(null);
				block.setMathSource(null);
				block.setRenderState(null);
				block.setImageBlobId(null);
				block.setImageWidth(null);
				block.setImageHeight(null);
				block.setBaselineOffset(null);
			}
			else {
				block.setTextContent(null);
				// Đầu vào không kèm nguồn trên một khối đang có là lượt giữ nguyên khối đó: dạng khối,
				// nguồn, trạng thái render, ảnh và revision đều không đổi — sửa văn bản quanh công thức
				// không được đòi nguồn mới cho khối chỉ-có-ảnh và cũng không được làm mất nguồn của khối
				// có-nguồn. Nguồn chỉ được ghi khi đầu vào thật sự mang nguồn mới.
				if (input.source() != null && !input.source().isBlank()) {
					boolean sameSource = QuestionVersionBlock.FORM_SOURCE_BACKED.equals(block.getFormulaForm())
							&& input.source().equals(block.getMathSource());
					// Nguồn không đổi thì giữ nguyên revision, trạng thái render và ảnh đã có của khối: định
					// danh ổn định là để sửa văn bản quanh công thức mà không làm mất ảnh của nó; nguồn đổi
					// thì revision tăng và render chạy lại, kết quả render của revision cũ không ghi đè
					// được revision mới.
					if (!sameSource) {
						block.setFormulaForm(QuestionVersionBlock.FORM_SOURCE_BACKED);
						block.setMathSource(input.source());
						block.setRenderState(QuestionVersionBlock.RENDER_PENDING);
						block.setSourceRevision(block.getSourceRevision() + 1);
					}
				}
			}
			this.blocks.save(block);
		}
		for (QuestionVersionBlock row : existing) {
			// Khối thuộc đáp án mà người gọi không được xem thì cũng không bị xoá bởi lượt lưu của họ: phạm vi
			// ghi của một lượt thay khối chỉ phủ phần người gọi nhìn thấy và được quyền chạm tới.
			if (!kept.contains(row.getId()) && (canViewAnswer || !row.answerArea())) {
				this.blocks.delete(row);
			}
		}
		version.setUpdatedAt(now);
		QuestionVersion savedVersion = this.versions.saveAndFlush(version);
		return draftView(context, question, savedVersion, this.blocks.findByQuestionVersionId(versionId));
	}

	@Transactional(readOnly = true)
	public DisplayBlocksView displayBlocks(UUID questionId, UUID versionId) {
		TenantContext context = requireContext();
		Question question = resolveQuestion(questionId);
		QuestionVersion version = resolveVersion(question, versionId);
		if (!this.rights.allowed(context, question, version, QuestionRights.VIEW_CONTENT)) {
			throw QuestionException.notFound();
		}
		boolean canViewAnswer = this.rights.allowed(context, question, version, QuestionRights.VIEW_ANSWER);
		List<DisplayBlock> view = sorted(this.blocks.findByQuestionVersionId(versionId)).stream()
				.filter(block -> canViewAnswer || !block.answerArea())
				.map(QuestionBlockService::displayBlock).toList();
		return new DisplayBlocksView(questionId, versionId, view);
	}

	@Transactional(readOnly = true)
	public DraftBlocksView draftBlocks(UUID questionId, UUID versionId) {
		TenantContext context = requireContext();
		Question question = resolveQuestion(questionId);
		QuestionVersion version = resolveVersion(question, versionId);
		if (!this.rights.allowed(context, question, version, QuestionRights.EDIT_DRAFT)) {
			throw QuestionException.forbidden();
		}
		return draftView(context, question, version, this.blocks.findByQuestionVersionId(versionId));
	}

	private DraftBlocksView draftView(TenantContext context, Question question, QuestionVersion version,
			List<QuestionVersionBlock> rows) {
		boolean canReadSource = this.rights.allowed(context, question, version, QuestionRights.READ_MATH_SOURCE);
		boolean canViewAnswer = this.rights.allowed(context, question, version, QuestionRights.VIEW_ANSWER);
		List<DraftBlock> view = sorted(rows).stream().filter(block -> canViewAnswer || !block.answerArea())
				.map(block -> {
					String source = null;
					if (QuestionVersionBlock.FORM_SOURCE_BACKED.equals(block.getFormulaForm()) && canReadSource
							&& (!block.answerArea() || canViewAnswer)) {
						source = block.getMathSource();
					}
					return new DraftBlock(block.getId(), block.getPart(), block.getChoiceId(), block.getKind(),
							block.getTextContent(), block.getFormulaForm(), block.getRenderState(), source,
							imageRef(block), block.getDescription(), block.getSourceRevision());
				}).toList();
		return new DraftBlocksView(question.getId(), version.getId(), version.getRevision(), view);
	}

	// Mặt hiển thị không mang dạng khối: người xem không phân biệt được khối có-nguồn với khối chỉ-có-ảnh, cả
	// hai đều chỉ là ảnh — và không có trường nguồn ở bất kỳ dạng nào.
	private static DisplayBlock displayBlock(QuestionVersionBlock block) {
		return new DisplayBlock(block.getId(), block.getPart(), block.getChoiceId(), block.getKind(),
				block.getTextContent(), null, block.getRenderState(), imageRef(block), block.getDescription());
	}

	private static ImageRef imageRef(QuestionVersionBlock block) {
		if (block.getImageBlobId() == null) {
			return null;
		}
		return new ImageRef(block.getImageBlobId(), block.getImageWidth(), block.getImageHeight(),
				block.getBaselineOffset());
	}

	private static void validateInputs(List<BlockInput> inputs, List<QuestionVersionBlock> existing) {
		Map<UUID, QuestionVersionBlock> existingById = new HashMap<>();
		for (QuestionVersionBlock row : existing) {
			existingById.put(row.getId(), row);
		}
		Set<UUID> referencedIds = new HashSet<>();
		int formulas = 0;
		for (BlockInput input : inputs) {
			if (input == null || !PARTS.contains(input.part())) {
				throw QuestionException.validation("Phần nội dung của khối không hợp lệ");
			}
			// Định danh khối chỉ có nghĩa khi trỏ đúng một khối đang có của chính phiên bản này: nhận định
			// danh lạ hay lặp lại một định danh hai lần trong cùng lượt lưu đều làm định danh hết ổn định.
			if (input.blockId() != null
					&& (!existingById.containsKey(input.blockId()) || !referencedIds.add(input.blockId()))) {
				throw QuestionException
						.validation("Định danh khối không thuộc phiên bản này hoặc bị lặp trong cùng lượt lưu");
			}
			if (input.form() != null || input.systemOrigin() != null || input.imageBlobId() != null
					|| input.renderState() != null) {
				throw QuestionException.validation(
						"Dạng khối, nguồn gốc hệ thống, định danh blob ảnh và trạng thái render do server quyết, client không được gửi");
			}
			if (QuestionVersionBlock.KIND_TEXT.equals(input.kind())) {
				if (input.text() == null) {
					throw QuestionException.validation("Khối văn bản phải có nội dung văn bản");
				}
				ExternalContentReferences.rejectIfPresent(input.text());
			}
			else if (QuestionVersionBlock.KIND_FORMULA.equals(input.kind())) {
				formulas++;
				// Đầu vào công thức trỏ tới một khối công thức đang có mà không kèm nguồn là lượt giữ
				// nguyên khối đó (sửa văn bản quanh công thức theo M2), nên nguồn chỉ bắt buộc khi khối
				// thật sự được tạo mới hay được ghi nguồn mới.
				QuestionVersionBlock matched = input.blockId() != null ? existingById.get(input.blockId()) : null;
				boolean keepsExisting = matched != null && QuestionVersionBlock.KIND_FORMULA.equals(matched.getKind())
						&& (input.source() == null || input.source().isBlank());
				if (!keepsExisting) {
					if (input.source() == null || input.source().isBlank()) {
						throw QuestionException
								.validation("Khối công thức soạn qua hợp đồng này phải có nguồn LaTeX");
					}
					if (input.source().length() > MAX_SOURCE_LENGTH) {
						throw QuestionException.validation("Nguồn LaTeX của một khối vượt quá 2000 ký tự");
					}
				}
			}
			else {
				throw QuestionException.validation("Loại khối không hợp lệ: chỉ có khối văn bản và khối công thức");
			}
		}
		if (formulas > MAX_FORMULA_BLOCKS) {
			throw QuestionException.validation("Một phiên bản có tối đa 50 khối công thức");
		}
	}

	private static boolean answerPart(String part) {
		return QuestionVersionBlock.PART_SOLUTION.equals(part) || QuestionVersionBlock.PART_RUBRIC.equals(part);
	}

	private static List<QuestionVersionBlock> sorted(List<QuestionVersionBlock> rows) {
		return rows.stream().sorted(Comparator.comparingInt((QuestionVersionBlock block) -> partRank(block.getPart()))
				.thenComparing(block -> block.getChoiceId() == null ? "" : block.getChoiceId())
				.thenComparingInt(QuestionVersionBlock::getPosition)).toList();
	}

	private static int partRank(String part) {
		return switch (part) {
			case QuestionVersionBlock.PART_STEM -> 0;
			case QuestionVersionBlock.PART_CHOICE -> 1;
			case QuestionVersionBlock.PART_SOLUTION -> 2;
			default -> 3;
		};
	}

	private Question resolveQuestion(UUID questionId) {
		return this.questions.findById(questionId).orElseThrow(QuestionException::notFound);
	}

	private QuestionVersion resolveVersion(Question question, UUID versionId) {
		QuestionVersion version = this.versions.findById(versionId).orElseThrow(QuestionException::notFound);
		if (!version.getQuestionId().equals(question.getId())) {
			throw QuestionException.forbidden();
		}
		return version;
	}

	private static TenantContext requireContext() {
		TenantContext context = TenantContexts.current();
		if (context == null) {
			throw QuestionException.forbidden();
		}
		return context;
	}

}
