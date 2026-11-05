package com.giaokhoa.server.math;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.giaokhoa.server.question.Question;
import com.giaokhoa.server.question.QuestionException;
import com.giaokhoa.server.question.QuestionRepository;
import com.giaokhoa.server.question.QuestionRights;
import com.giaokhoa.server.question.QuestionVersion;
import com.giaokhoa.server.question.QuestionVersionRepository;
import com.giaokhoa.server.tenancy.TenantContext;
import com.giaokhoa.server.tenancy.TenantContexts;

// Nghiệp vụ của lát LaTeX trên nền câu hỏi: hai hợp đồng dữ liệu của khối công thức (soạn thảo và hiển thị),
// luồng render khóa theo revision, và cổng phát hành theo trạng thái render. Nguồn LaTeX chỉ rời server qua
// mặt soạn thảo cho người có read_math_source trên đúng khối; mặt hiển thị không có trường nguồn ở bất kỳ
// dạng nào và ảnh chỉ được phục vụ qua đường đọc có kiểm quyền tại thời điểm truy cập.
@Service
public class MathFormulaService {

	private static final Set<String> SECTIONS = Set.of(FormulaBlock.SECTION_STEM, FormulaBlock.SECTION_CHOICE,
			FormulaBlock.SECTION_SOLUTION, FormulaBlock.SECTION_RUBRIC);

	public record ImageView(String fileRef, int width, int height, int baselineOffset) {
	}

	public record DisplayBlockView(String blockId, String kind, String form, String renderState,
			@JsonInclude(JsonInclude.Include.NON_NULL) ImageView image, String description) {
	}

	public record DraftBlockView(String blockId, String kind, String form, String renderState,
			@JsonInclude(JsonInclude.Include.NON_NULL) String source,
			@JsonInclude(JsonInclude.Include.NON_NULL) ImageView image, String description, int revision,
			@JsonInclude(JsonInclude.Include.NON_NULL) String errorCode) {
	}

	// Các trường form, systemOrigin, sourceHash, imageBlobId và renderState trong yêu cầu là trường do server
	// quyết: client gửi lên là bị từ chối theo phụ lục hợp đồng, không âm thầm bỏ qua. Chúng được khai báo ở
	// đây chỉ để phát hiện việc gửi lên.
	public record PutSourceRequest(Integer revision, String section, String source, String description,
			Boolean display, String form, Boolean systemOrigin, String sourceHash, UUID imageBlobId,
			String renderState) {
	}

	public record AuthorSourceRequest(Integer revision, String source, String description, String form,
			Boolean systemOrigin, String sourceHash, UUID imageBlobId, String renderState) {
	}

	private final QuestionRepository questions;

	private final QuestionVersionRepository versions;

	private final QuestionRights rights;

	private final MathFormulaStore store;

	private final MathRenderer renderer;

	private final MathRenderProperties properties;

	public MathFormulaService(QuestionRepository questions, QuestionVersionRepository versions,
			QuestionRights rights, MathFormulaStore store, MathRenderer renderer,
			MathRenderProperties properties) {
		this.questions = questions;
		this.versions = versions;
		this.rights = rights;
		this.store = store;
		this.renderer = renderer;
		this.properties = properties;
	}

	public List<DisplayBlockView> listDisplay(UUID questionId, UUID versionId) {
		TenantContext context = requireContext();
		Question question = loadQuestion(questionId);
		QuestionVersion version = loadVersion(question, versionId);
		if (!this.rights.allowed(context, question, version, QuestionRights.VIEW_CONTENT)) {
			throw QuestionException.notFound();
		}
		boolean includeAnswers = this.rights.allowed(context, question, version, QuestionRights.VIEW_ANSWER);
		return this.store.blocksOf(versionId)
			.stream()
			.filter((block) -> includeAnswers || !block.answerBearing())
			.map(this::displayView)
			.toList();
	}

	public List<DraftBlockView> listDraft(UUID questionId, UUID versionId) {
		TenantContext context = requireContext();
		Question question = loadQuestion(questionId);
		QuestionVersion version = loadVersion(question, versionId);
		if (!this.rights.allowed(context, question, version, QuestionRights.EDIT_DRAFT)) {
			throw QuestionException.forbidden();
		}
		return this.store.blocksOf(versionId).stream().map((block) -> draftView(context, question, version, block))
			.toList();
	}

	public DraftBlockView putSource(UUID questionId, UUID versionId, String blockId, PutSourceRequest request) {
		TenantContext context = requireContext();
		if (request == null) {
			throw QuestionException.validation("Thiếu dữ kiện nguồn công thức.");
		}
		rejectServerManaged(request.form(), request.systemOrigin(), request.sourceHash(), request.imageBlobId(),
				request.renderState());
		Question question = loadQuestion(questionId);
		QuestionVersion version = loadVersion(question, versionId);
		requireDraftEditable(context, question, version);
		FormulaBlock existing = this.store.findBlock(versionId, blockId).orElse(null);
		String section = existing != null ? existing.getSection()
				: requireSection(request.section());
		requireSourceRights(context, question, version, section);
		validateSource(request.source());
		boolean display = request.display() != null ? request.display() : existing != null && existing.isDisplay();
		MathFormulaStore.SavedSource saved;
		if (existing == null) {
			if (this.store.countBlocks(versionId) >= this.properties.getMaxBlocksPerVersion()) {
				throw QuestionException.validation("Một phiên bản chỉ chứa tối đa "
						+ this.properties.getMaxBlocksPerVersion() + " khối công thức.");
			}
			int position = (int) this.store.countBlocks(versionId);
			saved = this.store.createSourceBlock(versionId, question.getOwnerSub(), section, blockId, position,
					request.source(), request.description(), display);
		}
		else {
			if (!existing.sourceBacked()) {
				throw QuestionException.stateConflict(
						"Khối chỉ-có-ảnh không sửa nguồn trực tiếp; hãy dùng thao tác tự soạn nguồn mới.");
			}
			if (request.revision() == null || request.revision() != existing.getRevision()) {
				throw QuestionException.versionConflict();
			}
			saved = this.store.updateSource(existing.getId(), request.source(),
					request.description() != null ? request.description() : existing.getDescription(), display);
		}
		renderAndAttach(saved, context.sub());
		return draftView(context, question, version, reload(saved.block()));
	}

	public DraftBlockView authorSource(UUID questionId, UUID versionId, String blockId,
			AuthorSourceRequest request) {
		TenantContext context = requireContext();
		if (request == null) {
			throw QuestionException.validation("Thiếu dữ kiện nguồn công thức.");
		}
		rejectServerManaged(request.form(), request.systemOrigin(), request.sourceHash(), request.imageBlobId(),
				request.renderState());
		Question question = loadQuestion(questionId);
		QuestionVersion version = loadVersion(question, versionId);
		requireDraftEditable(context, question, version);
		FormulaBlock block = this.store.findBlock(versionId, blockId).orElseThrow(QuestionException::notFound);
		if (block.sourceBacked()) {
			throw QuestionException.stateConflict("Khối đã có nguồn; hãy sửa nguồn bằng thao tác sửa trực tiếp.");
		}
		requireSourceRights(context, question, version, block.getSection());
		validateSource(request.source());
		if (request.revision() == null || request.revision() != block.getRevision()) {
			throw QuestionException.versionConflict();
		}
		MathFormulaStore.SavedSource saved = this.store.authorSource(block.getId(), request.source(),
				request.description());
		renderAndAttach(saved, context.sub());
		return draftView(context, question, version, reload(saved.block()));
	}

	public DraftBlockView retryRender(UUID questionId, UUID versionId, String blockId) {
		TenantContext context = requireContext();
		Question question = loadQuestion(questionId);
		QuestionVersion version = loadVersion(question, versionId);
		requireDraftEditable(context, question, version);
		FormulaBlock block = this.store.findBlock(versionId, blockId).orElseThrow(QuestionException::notFound);
		if (!block.sourceBacked()) {
			throw QuestionException.stateConflict("Khối chỉ-có-ảnh không có nguồn để render lại.");
		}
		FormulaRender render = this.store.reopenRender(block.getId());
		if (!render.ready()) {
			runRender(block, render, context.sub());
		}
		return draftView(context, question, version, reload(block));
	}

	public byte[] formulaImage(UUID questionId, UUID versionId, String blockId) {
		TenantContext context = requireContext();
		Question question = loadQuestion(questionId);
		QuestionVersion version = loadVersion(question, versionId);
		if (!this.rights.allowed(context, question, version, QuestionRights.VIEW_CONTENT)) {
			throw QuestionException.notFound();
		}
		FormulaBlock block = this.store.findBlock(versionId, blockId).orElseThrow(QuestionException::notFound);
		if (block.answerBearing()
				&& !this.rights.allowed(context, question, version, QuestionRights.VIEW_ANSWER)) {
			throw QuestionException.notFound();
		}
		FormulaRender render = this.store.currentRender(block)
			.filter(FormulaRender::ready)
			.orElseThrow(QuestionException::notFound);
		byte[] image = this.store.readImage(render, block, context.sub());
		if (image == null) {
			throw QuestionException.notFound();
		}
		return image;
	}

	// Cổng phát hành của lát này: mọi khối công thức của phiên bản phải có ảnh sẵn sàng khớp revision hiện
	// tại của khối; thiếu hoặc lệch thì phát hành bị chặn nhưng bản nháp vẫn lưu và sửa được bình thường.
	public void assertPublishable(QuestionVersion version) {
		for (FormulaBlock block : this.store.blocksOf(version.getId())) {
			boolean ready = this.store.currentRender(block).map(FormulaRender::ready).orElse(false);
			if (!ready) {
				throw QuestionException.validation(
						"Phiên bản còn khối công thức chưa render xong hoặc render lỗi nên chưa phát hành được.");
			}
		}
	}

	public record FormulaImageSnapshot(String fileRef, UUID imageBlobId, Integer width, Integer height,
			Integer baselineOffset) {
	}

	public record FormulaSnapshot(String blockId, String section, int position, String form, boolean display,
			boolean systemOrigin, boolean answerBearing, String description, FormulaImageSnapshot image) {
	}

	// Hình chiếu đóng băng của các khối công thức của một phiên bản, dùng cho bản sao và bản nhận (bản chụp
	// đề, bản nộp về trung tâm): mang dạng khối cùng tham chiếu ảnh render hiện hành, không mang nguồn ở
	// bất kỳ dạng nào — khối có-nguồn cũng chỉ đóng băng ở phần ảnh. Quyền trên phiên bản đã được phía gọi
	// quyết trước; hình chiếu này không tự mở thêm quyền đọc nào.
	public List<FormulaSnapshot> snapshotBlocks(UUID versionId) {
		return this.store.blocksOf(versionId).stream().map((block) -> {
			FormulaRender render = this.store.currentRender(block).orElse(null);
			FormulaImageSnapshot image = render != null && render.ready()
					? new FormulaImageSnapshot(render.getId().toString(), render.getImageBlobId(),
							render.getImageWidth(), render.getImageHeight(), render.getBaselineOffset())
					: null;
			return new FormulaSnapshot(block.getBlockId(), block.getSection(), block.getPosition(), block.getForm(),
					block.isDisplay(), block.isSystemOrigin(), block.answerBearing(), block.getDescription(), image);
		}).toList();
	}

	// Điểm nối cho thao tác sao chép câu hỏi: khối công thức và bản ghi render của chúng được mang sang
	// phiên bản của bản sao theo đúng quy tắc nguồn của lần sao chép đó (xem MathFormulaStore.copyBlocksTo).
	// Quyền sao chép đã được quyết ở dịch vụ câu hỏi trước khi gọi tới đây.
	public void carryBlocksForCopy(UUID sourceVersionId, UUID targetVersionId, String targetOwnerSub,
			boolean keepSource, String actorSub) {
		this.store.copyBlocksTo(sourceVersionId, targetVersionId, targetOwnerSub, keepSource, actorSub);
	}

	private void renderAndAttach(MathFormulaStore.SavedSource saved, String actorSub) {
		FormulaRender render = saved.render();
		if (render.ready()) {
			return;
		}
		runRender(saved.block(), render, actorSub);
	}

	private void runRender(FormulaBlock block, FormulaRender render, String actorSub) {
		try {
			MathRenderer.RenderedImage image = this.renderer.render(block.getSource(), block.isDisplay());
			this.store.attachResult(render.getId(), image, actorSub);
		}
		catch (MathRenderer.MathRenderException ex) {
			this.store.markFailed(render.getId(), ex.code());
		}
	}

	private FormulaBlock reload(FormulaBlock block) {
		return this.store.findBlock(block.getQuestionVersionId(), block.getBlockId()).orElse(block);
	}

	private DisplayBlockView displayView(FormulaBlock block) {
		FormulaRender render = this.store.currentRender(block).orElse(null);
		String state = render != null ? render.getStatus() : FormulaRender.STATUS_PENDING;
		ImageView image = render != null && render.ready()
				? new ImageView(render.getId().toString(), render.getImageWidth(), render.getImageHeight(),
						render.getBaselineOffset())
				: null;
		return new DisplayBlockView(block.getBlockId(), "formula", block.getForm(), state, image,
				block.getDescription());
	}

	private DraftBlockView draftView(TenantContext context, Question question, QuestionVersion version,
			FormulaBlock block) {
		FormulaRender render = this.store.currentRender(block).orElse(null);
		String state = render != null ? render.getStatus() : FormulaRender.STATUS_PENDING;
		ImageView image = render != null && render.ready()
				? new ImageView(render.getId().toString(), render.getImageWidth(), render.getImageHeight(),
						render.getBaselineOffset())
				: null;
		String source = canReadSource(context, question, version, block) ? block.getSource() : null;
		String errorCode = render != null && FormulaRender.STATUS_FAILED.equals(render.getStatus())
				? render.getErrorCode()
				: null;
		return new DraftBlockView(block.getBlockId(), "formula", block.getForm(), state, source, image,
				block.getDescription(), block.getRevision(), errorCode);
	}

	// Đọc nguồn cần đồng thời quyền trên chính khối: read_math_source, và với khối nằm trong đáp án hay lời
	// giải thì thêm view_answer — một quyền không bao giờ suy ra quyền còn lại.
	private boolean canReadSource(TenantContext context, Question question, QuestionVersion version,
			FormulaBlock block) {
		if (!block.sourceBacked()
				|| !this.rights.allowed(context, question, version, QuestionRights.READ_MATH_SOURCE)) {
			return false;
		}
		return !block.answerBearing()
				|| this.rights.allowed(context, question, version, QuestionRights.VIEW_ANSWER);
	}

	private void requireSourceRights(TenantContext context, Question question, QuestionVersion version,
			String section) {
		if (!this.rights.allowed(context, question, version, QuestionRights.EDIT_MATH_SOURCE)) {
			throw QuestionException.forbidden();
		}
		boolean answerBearing = FormulaBlock.SECTION_SOLUTION.equals(section)
				|| FormulaBlock.SECTION_RUBRIC.equals(section);
		if (answerBearing && !this.rights.allowed(context, question, version, QuestionRights.VIEW_ANSWER)) {
			throw QuestionException.forbidden();
		}
	}

	private void requireDraftEditable(TenantContext context, Question question, QuestionVersion version) {
		if (!this.rights.allowed(context, question, version, QuestionRights.EDIT_DRAFT)) {
			throw QuestionException.forbidden();
		}
		if (version.locked()) {
			throw QuestionException.locked();
		}
	}

	private void rejectServerManaged(String form, Boolean systemOrigin, String sourceHash, UUID imageBlobId,
			String renderState) {
		if (form != null || systemOrigin != null || sourceHash != null || imageBlobId != null
				|| renderState != null) {
			throw QuestionException.validation(
					"Yêu cầu chứa trường do server quyết định; các trường đó không được client gửi lên.");
		}
	}

	private String requireSection(String section) {
		if (section == null || !SECTIONS.contains(section)) {
			throw QuestionException.validation("Phần nội dung của khối công thức không hợp lệ.");
		}
		return section;
	}

	private void validateSource(String source) {
		if (source == null || source.isBlank()) {
			throw QuestionException.validation("Nguồn công thức không được để trống.");
		}
		if (source.length() > this.properties.getMaxSourceLength()) {
			throw QuestionException.validation("Nguồn công thức vượt quá " + this.properties.getMaxSourceLength()
					+ " ký tự cho phép của một khối.");
		}
	}

	private Question loadQuestion(UUID questionId) {
		return this.questions.findById(questionId).orElseThrow(QuestionException::notFound);
	}

	private QuestionVersion loadVersion(Question question, UUID versionId) {
		QuestionVersion version = this.versions.findById(versionId).orElseThrow(QuestionException::notFound);
		if (!version.getQuestionId().equals(question.getId())) {
			throw QuestionException.forbidden();
		}
		return version;
	}

	private TenantContext requireContext() {
		TenantContext context = TenantContexts.current();
		if (context == null) {
			throw QuestionException.forbidden();
		}
		return context;
	}

}
