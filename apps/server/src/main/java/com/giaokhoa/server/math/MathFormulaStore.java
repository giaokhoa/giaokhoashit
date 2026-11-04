package com.giaokhoa.server.math;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Tầng ghi dữ kiện của lát LaTeX, tách khỏi dịch vụ điều phối để bước render (gọi worker ngoài) không nằm
// trong bất kỳ giao dịch nào: mỗi phương thức ở đây là một giao dịch ngắn quanh một bước ghi, và việc gắn kết
// quả render luôn kiểm lại revision tại chính giao dịch gắn.
@Component
class MathFormulaStore {

	private final FormulaBlockRepository blocks;

	private final FormulaRenderRepository renders;

	MathFormulaStore(FormulaBlockRepository blocks, FormulaRenderRepository renders) {
		this.blocks = blocks;
		this.renders = renders;
	}

	@Transactional(readOnly = true)
	List<FormulaBlock> blocksOf(UUID versionId) {
		return this.blocks.findByQuestionVersionIdOrderByPositionAsc(versionId);
	}

	@Transactional(readOnly = true)
	Optional<FormulaBlock> findBlock(UUID versionId, String blockId) {
		return this.blocks.findByQuestionVersionIdAndBlockId(versionId, blockId);
	}

	@Transactional(readOnly = true)
	long countBlocks(UUID versionId) {
		return this.blocks.countByQuestionVersionId(versionId);
	}

	// Bản ghi render hiện hành của một khối: khối có-nguồn khoá theo revision hiện tại của chính khối; khối
	// chỉ-có-ảnh không có revision nguồn nên bản ghi sẵn sàng mới nhất là bản ghi hiện hành của nó.
	@Transactional(readOnly = true)
	Optional<FormulaRender> currentRender(FormulaBlock block) {
		if (block.sourceBacked()) {
			return this.renders.findByFormulaBlockIdAndSourceRevision(block.getId(), block.getRevision());
		}
		return this.renders.findByFormulaBlockIdOrderBySourceRevisionDesc(block.getId())
			.stream()
			.filter(FormulaRender::ready)
			.findFirst();
	}

	record SavedSource(FormulaBlock block, FormulaRender render) {
	}

	@Transactional
	SavedSource createSourceBlock(UUID versionId, String ownerSub, String section, String blockId, int position,
			String source, String description, boolean display) {
		FormulaBlock block = new FormulaBlock();
		block.setId(UuidV7.generate());
		block.setQuestionVersionId(versionId);
		block.setOwnerSub(ownerSub);
		block.setSection(section);
		block.setBlockId(blockId);
		block.setPosition(position);
		block.setForm(FormulaBlock.FORM_SOURCE_BACKED);
		block.setDisplay(display);
		block.setSystemOrigin(false);
		block.setSource(source);
		block.setDescription(description);
		OffsetDateTime now = OffsetDateTime.now();
		block.setCreatedAt(now);
		block.setUpdatedAt(now);
		FormulaBlock saved = this.blocks.saveAndFlush(block);
		return new SavedSource(saved, pendingRender(saved));
	}

	@Transactional
	SavedSource updateSource(UUID blockPk, String source, String description, boolean display) {
		FormulaBlock block = this.blocks.findById(blockPk).orElseThrow();
		block.setSource(source);
		block.setDescription(description);
		block.setDisplay(display);
		block.setUpdatedAt(OffsetDateTime.now());
		FormulaBlock saved = this.blocks.saveAndFlush(block);
		return new SavedSource(saved, pendingRender(saved));
	}

	// Tự soạn nguồn mới cho khối chỉ-có-ảnh: khối chuyển thành có-nguồn của chủ sở hữu hiện hành. Nguồn mới là
	// của chính người soạn nên cờ nguồn gốc hệ thống của khối được gỡ; thao tác này không đọc và không cần
	// bất kỳ nguồn cũ nào — khối chỉ-có-ảnh vốn không có nguồn để đọc.
	@Transactional
	SavedSource authorSource(UUID blockPk, String source, String description) {
		FormulaBlock block = this.blocks.findById(blockPk).orElseThrow();
		block.setForm(FormulaBlock.FORM_SOURCE_BACKED);
		block.setSystemOrigin(false);
		block.setSource(source);
		if (description != null) {
			block.setDescription(description);
		}
		block.setUpdatedAt(OffsetDateTime.now());
		FormulaBlock saved = this.blocks.saveAndFlush(block);
		return new SavedSource(saved, pendingRender(saved));
	}

	@Transactional
	FormulaRender reopenRender(UUID blockPk) {
		FormulaBlock block = this.blocks.findById(blockPk).orElseThrow();
		FormulaRender render = pendingRender(block);
		if (FormulaRender.STATUS_FAILED.equals(render.getStatus())) {
			render.setStatus(FormulaRender.STATUS_PENDING);
			render.setErrorCode(null);
			render.setUpdatedAt(OffsetDateTime.now());
			return this.renders.save(render);
		}
		return render;
	}

	// Gắn kết quả chỉ khi revision của khối vẫn khớp revision mà tác vụ render đã khoá: một tác vụ cũ hoàn
	// tất sau khi nguồn đã sửa tiếp không ghi đè được kết quả của revision mới, và bản ghi của nó được đóng
	// dấu lỗi STALE_REVISION để truy vết thay vì biến mất.
	@Transactional
	boolean attachResult(UUID renderId, MathRenderer.RenderedImage image) {
		FormulaRender render = this.renders.findById(renderId).orElseThrow();
		FormulaBlock block = this.blocks.findById(render.getFormulaBlockId()).orElseThrow();
		if (block.getRevision() != render.getSourceRevision()) {
			render.setStatus(FormulaRender.STATUS_FAILED);
			render.setErrorCode("STALE_REVISION");
			render.setUpdatedAt(OffsetDateTime.now());
			this.renders.save(render);
			return false;
		}
		render.setStatus(FormulaRender.STATUS_READY);
		render.setImagePng(image.png());
		render.setImageWidth(image.width());
		render.setImageHeight(image.height());
		render.setBaselineOffset(image.baselineOffset());
		render.setRendererVersion(image.rendererVersion());
		render.setRenderConfig(image.renderConfig());
		render.setErrorCode(null);
		render.setUpdatedAt(OffsetDateTime.now());
		this.renders.save(render);
		return true;
	}

	@Transactional
	void markFailed(UUID renderId, String errorCode) {
		this.renders.findById(renderId).ifPresent((render) -> {
			if (!render.ready()) {
				render.setStatus(FormulaRender.STATUS_FAILED);
				render.setErrorCode(errorCode);
				render.setUpdatedAt(OffsetDateTime.now());
				this.renders.save(render);
			}
		});
	}

	private FormulaRender pendingRender(FormulaBlock block) {
		return this.renders.findByFormulaBlockIdAndSourceRevision(block.getId(), block.getRevision())
			.orElseGet(() -> {
				FormulaRender render = new FormulaRender();
				render.setId(UuidV7.generate());
				render.setFormulaBlockId(block.getId());
				render.setSourceRevision(block.getRevision());
				render.setSourceHash(sourceHash(block.getSource()));
				render.setStatus(FormulaRender.STATUS_PENDING);
				render.setRendererVersion("pending");
				render.setRenderConfig("{}");
				OffsetDateTime now = OffsetDateTime.now();
				render.setCreatedAt(now);
				render.setUpdatedAt(now);
				return this.renders.save(render);
			});
	}

	static String sourceHash(String source) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest((source != null ? source : "").getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Thiếu thuật toán SHA-256.", ex);
		}
	}

}
